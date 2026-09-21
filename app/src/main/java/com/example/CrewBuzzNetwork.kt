package com.example

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URL
import fi.iki.elonen.NanoHTTPD

data class TableCallEvent(val tableId: String, val request: String)
data class CrewBuzzDeviceEvent(val tableId: String, val deviceId: String, val ip: String)

object CrewBuzzNetwork {
    private const val HTTP_PORT = 8080
    private const val DISCOVERY_PORT = 4001
    private const val DEVICE_HTTP_PORT = 80
    private const val DEVICE_ID = "CREWBUZZ-01"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _tableCalls = MutableSharedFlow<TableCallEvent>(extraBufferCapacity = 32)
    val tableCalls = _tableCalls.asSharedFlow()
    private val _devices = MutableSharedFlow<CrewBuzzDeviceEvent>(extraBufferCapacity = 32)
    val devices = _devices.asSharedFlow()

    @Volatile private var started = false
    @Volatile private var discoverySocket: DatagramSocket? = null
    @Volatile private var wifiNetwork: Network? = null
    @Volatile private var appContext: Context? = null
    private var httpServer: NanoHTTPD? = null

    fun start(context: Context) {
        if (started) return
        started = true
        appContext = context.applicationContext

        try {
            httpServer = object : NanoHTTPD(HTTP_PORT) {
                override fun serve(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
                    if (session.method == NanoHTTPD.Method.POST && session.uri == "/request") {
                        return try {
                            val files = HashMap<String, String>()
                            session.parseBody(files)
                            val body = files["postData"] ?: ""
                            val table = Regex("""["']table_id["']\s*:\s*["']([^"']+)["']""").find(body)?.groupValues?.get(1)
                            val request = Regex("""["']request["']\s*:\s*["']([^"']+)["']""").find(body)?.groupValues?.get(1) ?: "WAITER"
                            if (table.isNullOrBlank()) {
                                newFixedLengthResponse(NanoHTTPD.Response.Status.BAD_REQUEST, "text/plain", "Missing table_id")
                            } else {
                                _tableCalls.tryEmit(TableCallEvent(table, request))
                                newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/json", """{"ok":true}""")
                            }
                        } catch (e: Exception) {
                            newFixedLengthResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR, "text/plain", e.message ?: "error")
                        }
                    }
                    return newFixedLengthResponse(NanoHTTPD.Response.Status.NOT_FOUND, "text/plain", "Not found")
                }
            }.also { it.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) }

            val network = getLocalWifiNetwork(context)
            wifiNetwork = network
            discoverySocket = DatagramSocket(DISCOVERY_PORT).apply {
                soTimeout = 1000
                reuseAddress = true
                broadcast = true
                network?.let { it.bindSocket(this) }
            }
        } catch (_: Exception) {
            httpServer?.stop()
            httpServer = null
            discoverySocket?.close()
            discoverySocket = null
            wifiNetwork = null
            appContext = null
            started = false
            return
        }

        scope.launch { discoveryLoop() }
        scope.launch { broadcastDeviceDiscovery() }
    }

    // Kept for the existing UI button: Devices page calls scanNow() with no argument.
    fun scanNow() {
        val context = appContext ?: return
        scanNow(context)
    }

    fun scanNow(context: Context) {
        if (!started) return
        scope.launch {
            val network = getLocalWifiNetwork(context) ?: wifiNetwork
            wifiNetwork = network
            val localIp = localIpv4(context, network)
            val prefix = localIp.substringBeforeLast('.', "")
            if (prefix.isBlank()) return@launch

            val socket = discoverySocket
            if (socket != null && !socket.isClosed) {
                sendDiscoveryBroadcast(socket)
                (1..254).forEach { host ->
                    sendDiscoveryTo(socket, "$prefix.$host")
                }
            }

            scanLocalHttp(prefix, network)
        }
    }

    private suspend fun broadcastDeviceDiscovery() {
        while (started) {
            discoverySocket?.let { sendDiscoveryBroadcast(it) }
            delay(5000)
        }
    }

    private fun sendDiscoveryTo(socket: DatagramSocket, targetIp: String) {
        try {
            val bytes = "CREWBUZZ_DEVICE_DISCOVER".toByteArray()
            synchronized(socket) {
                if (!socket.isClosed) {
                    socket.send(DatagramPacket(bytes, bytes.size, InetAddress.getByName(targetIp), DISCOVERY_PORT))
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun sendDiscoveryBroadcast(socket: DatagramSocket) {
        try {
            val bytes = "CREWBUZZ_DEVICE_DISCOVER".toByteArray()
            synchronized(socket) {
                if (!socket.isClosed) {
                    socket.broadcast = true
                    socket.send(DatagramPacket(bytes, bytes.size, InetAddress.getByName("255.255.255.255"), DISCOVERY_PORT))
                }
            }
        } catch (_: Exception) {
        }
    }

    private suspend fun scanLocalHttp(prefix: String, network: Network?) {
        val semaphore = Semaphore(32)
        val jobs = (1..254).map { host ->
            scope.async {
                semaphore.withPermit {
                    probeHttpDevice("$prefix.$host", network)
                }
            }
        }
        jobs.awaitAll()
    }

    private fun probeHttpDevice(ip: String, network: Network?) {
        var connection: HttpURLConnection? = null
        try {
            val url = URL("http://$ip:$DEVICE_HTTP_PORT/crewbuzz")
            val opened = if (network != null) network.openConnection(url) else url.openConnection()
            connection = opened as HttpURLConnection
            connection.connectTimeout = 500
            connection.readTimeout = 500
            connection.requestMethod = "GET"
            connection.useCaches = false

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                val type = Regex("""\"type\"\s*:\s*\"([^\"]+)\"""").find(body)?.groupValues?.get(1)
                val table = Regex("""\"table_id\"\s*:\s*\"([^\"]+)\"""").find(body)?.groupValues?.get(1)
                val device = Regex("""\"device_id\"\s*:\s*\"([^\"]+)\"""").find(body)?.groupValues?.get(1)
                if (type == "CREWBUZZ_DEVICE" && !table.isNullOrBlank() && !device.isNullOrBlank()) {
                    _devices.tryEmit(CrewBuzzDeviceEvent(table, device, ip))
                }
            }
        } catch (_: Exception) {
        } finally {
            connection?.disconnect()
        }
    }

    private fun discoveryLoop() {
        val socket = discoverySocket ?: return
        val buffer = ByteArray(512)

        try {
            while (started) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    val message = String(packet.data, 0, packet.length).trim()

                    if (message == "CREWBUZZ_DISCOVER") {
                        val response = "CREWBUZZ|${localIpv4()}|$HTTP_PORT|$DEVICE_ID"
                        val bytes = response.toByteArray()
                        synchronized(socket) {
                            socket.send(DatagramPacket(bytes, bytes.size, packet.address, packet.port))
                        }
                    } else if (message.startsWith("CREWBUZZ_DEVICE|")) {
                        val parts = message.split("|")
                        if (parts.size >= 4) {
                            _devices.tryEmit(CrewBuzzDeviceEvent(parts[1], parts[2], parts[3]))
                        }
                    }
                } catch (_: SocketTimeoutException) {
                } catch (_: SocketException) {
                    break
                } catch (_: Exception) {
                }
            }
        } finally {
            discoverySocket = null
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun getLocalWifiNetwork(context: Context): Network? {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val active = cm.activeNetwork ?: return null
            val caps = cm.getNetworkCapabilities(active) ?: return null
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) active else null
        } catch (_: Exception) {
            null
        }
    }

    private fun localIpv4(context: Context, network: Network?): String {
        try {
            if (network != null) {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val props = cm.getLinkProperties(network)
                props?.linkAddresses?.forEach { link ->
                    val address = link.address
                    if (address is Inet4Address && !address.isLoopbackAddress && address.isSiteLocalAddress) {
                        return address.hostAddress ?: "0.0.0.0"
                    }
                }
            }
        } catch (_: Exception) {
        }

        return try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (!networkInterface.isUp || networkInterface.isLoopback || networkInterface.isVirtual) continue
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (address is Inet4Address && !address.isLoopbackAddress && address.isSiteLocalAddress) {
                        return address.hostAddress ?: "0.0.0.0"
                    }
                }
            }
            "0.0.0.0"
        } catch (_: Exception) {
            "0.0.0.0"
        }
    }

    private fun localIpv4(): String = try {
        val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
        while (interfaces.hasMoreElements()) {
            val networkInterface = interfaces.nextElement()
            if (!networkInterface.isUp || networkInterface.isLoopback || networkInterface.isVirtual) continue
            val addresses = networkInterface.inetAddresses
            while (addresses.hasMoreElements()) {
                val address = addresses.nextElement()
                if (address is Inet4Address && !address.isLoopbackAddress && address.isSiteLocalAddress) {
                    return address.hostAddress ?: "0.0.0.0"
                }
            }
        }
        "0.0.0.0"
    } catch (_: Exception) {
        "0.0.0.0"
    }
}
