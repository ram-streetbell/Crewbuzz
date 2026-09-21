package com.example

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.URL
import fi.iki.elonen.NanoHTTPD

data class TableCallEvent(val tableId: String, val request: String)
data class CrewBuzzDeviceEvent(val tableId: String, val deviceId: String, val ip: String)

object CrewBuzzNetwork {
    private const val HTTP_PORT = 8080
    private const val DISCOVERY_PORT = 4001
    private const val DEVICE_HTTP_PORT = 80
    private const val DEVICE_SCAN_TIMEOUT = 1200

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _tableCalls = MutableSharedFlow<TableCallEvent>(extraBufferCapacity = 64)
    val tableCalls = _tableCalls.asSharedFlow()
    private val _devices = MutableStateFlow<Map<String, CrewBuzzDeviceEvent>>(emptyMap())
    val devices = _devices.asStateFlow()

    @Volatile private var started = false
    @Volatile private var discoverySocket: DatagramSocket? = null
    @Volatile private var wifiNetwork: Network? = null
    @Volatile private var appContext: Context? = null
    @Volatile private var multicastLock: WifiManager.MulticastLock? = null
    private var httpServer: NanoHTTPD? = null

    fun start(context: Context) {
        if (started) return
        appContext = context.applicationContext
        try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wifi.createMulticastLock("CrewBuzzDiscovery").apply {
                setReferenceCounted(false)
                acquire()
            }
            wifiNetwork = getLocalWifiNetwork(context)

            httpServer = object : NanoHTTPD(HTTP_PORT) {
                override fun serve(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
                    return try {
                        when {
                            session.method == NanoHTTPD.Method.GET && session.uri == "/health" ->
                                newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "text/plain", "CREWBUZZ_OK")
                            session.method == NanoHTTPD.Method.POST && session.uri == "/request" -> {
                                val files = HashMap<String, String>()
                                session.parseBody(files)
                                val body = files["postData"] ?: ""
                                val table = Regex("""[\"']table_id[\"']\s*:\s*[\"']([^\"']+)[\"']""").find(body)?.groupValues?.get(1)
                                val request = Regex("""[\"']request[\"']\s*:\s*[\"']([^\"']+)[\"']""").find(body)?.groupValues?.get(1) ?: "WAITER"
                                if (table.isNullOrBlank()) {
                                    newFixedLengthResponse(NanoHTTPD.Response.Status.BAD_REQUEST, "text/plain", "Missing table_id")
                                } else {
                                    _tableCalls.tryEmit(TableCallEvent(table, request))
                                    newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/json", "{\"ok\":true}")
                                }
                            }
                            else -> newFixedLengthResponse(NanoHTTPD.Response.Status.NOT_FOUND, "text/plain", "Not found")
                        }
                    } catch (e: Exception) {
                        newFixedLengthResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR, "text/plain", e.message ?: "error")
                    }
                }
            }.also { it.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) }

            discoverySocket = DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                bind(java.net.InetSocketAddress(DISCOVERY_PORT))
                soTimeout = 800
                wifiNetwork?.let { it.bindSocket(this) }
            }
            started = true
        } catch (_: Exception) {
            httpServer?.stop()
            httpServer = null
            discoverySocket?.close()
            discoverySocket = null
            multicastLock?.release()
            multicastLock = null
            wifiNetwork = null
            appContext = null
            started = false
            return
        }
        scope.launch { discoveryLoop() }
        scope.launch { broadcastDeviceDiscovery() }
        scope.launch { delay(700); scanNow(context) }
    }

    fun scanNow() { appContext?.let { scanNow(it) } }

    fun scanNow(context: Context) {
        if (!started) return
        scope.launch {
            val network = getLocalWifiNetwork(context) ?: wifiNetwork
            wifiNetwork = network
            val localIp = localIpv4(context, network)
            val prefix = localIp.substringBeforeLast('.', "")
            if (prefix.isBlank() || prefix == "0.0.0") return@launch
            probeHttpDevice("$prefix.48", network)
            discoverySocket?.let { sendDiscoveryBroadcast(it) }
            scanLocalHttp(prefix, network)
        }
    }

    private suspend fun broadcastDeviceDiscovery() {
        while (started) {
            discoverySocket?.let { sendDiscoveryBroadcast(it) }
            delay(5000)
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
        } catch (_: Exception) {}
    }

    private suspend fun scanLocalHttp(prefix: String, network: Network?) {
        val semaphore = Semaphore(32)
        val jobs = (1..254).map { host ->
            scope.async { semaphore.withPermit { probeHttpDevice("$prefix.$host", network) } }
        }
        jobs.awaitAll()
    }

    private fun probeHttpDevice(ip: String, network: Network?) {
        var connection: HttpURLConnection? = null
        try {
            val url = URL("http://$ip:$DEVICE_HTTP_PORT/crewbuzz")
            connection = if (network != null) network.openConnection(url) as HttpURLConnection else url.openConnection() as HttpURLConnection
            connection.connectTimeout = DEVICE_SCAN_TIMEOUT
            connection.readTimeout = DEVICE_SCAN_TIMEOUT
            connection.requestMethod = "GET"
            connection.useCaches = false
            connection.setRequestProperty("Connection", "close")
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val type = jsonValue(body, "type")
            val table = jsonValue(body, "table_id")
            val device = jsonValue(body, "device_id")
            if (type == "CREWBUZZ_DEVICE" && !table.isNullOrBlank() && !device.isNullOrBlank()) {
                val event = CrewBuzzDeviceEvent(table, device, ip)
                _devices.value = _devices.value + (device to event)
                configureEsp(event, network ?: wifiNetwork)
            }
        } catch (_: Exception) {} finally { connection?.disconnect() }
    }

    private fun jsonValue(body: String, key: String): String? =
        Regex("""[\"']${Regex.escape(key)}[\"']\s*:\s*[\"']([^\"']*)[\"']""").find(body)?.groupValues?.get(1)

    private fun configureEsp(device: CrewBuzzDeviceEvent, network: Network?) {
        val context = appContext ?: return
        val tabletIp = localIpv4(context, network ?: wifiNetwork)
        if (tabletIp == "0.0.0.0") return
        var connection: HttpURLConnection? = null
        try {
            val url = URL("http://${device.ip}:80/configure?ip=$tabletIp&port=$HTTP_PORT")
            connection = if (network != null) network.openConnection(url) as HttpURLConnection else url.openConnection() as HttpURLConnection
            connection.connectTimeout = 1500
            connection.readTimeout = 1500
            connection.requestMethod = "GET"
            connection.useCaches = false
            connection.inputStream.bufferedReader().use { it.readText() }
        } catch (_: Exception) {} finally { connection?.disconnect() }
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
                    if (message.startsWith("CREWBUZZ_DEVICE|")) {
                        val parts = message.split("|")
                        if (parts.size >= 4) {
                            val event = CrewBuzzDeviceEvent(parts[1], parts[2], parts[3])
                            _devices.value = _devices.value + (event.deviceId to event)
                            scope.launch { configureEsp(event, wifiNetwork) }
                        }
                    }
                } catch (_: java.net.SocketTimeoutException) {} catch (_: java.net.SocketException) { break } catch (_: Exception) {}
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
        } catch (_: Exception) { null }
    }

    private fun localIpv4(context: Context, network: Network?): String {
        try {
            if (network != null) {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                cm.getLinkProperties(network)?.linkAddresses?.forEach { link ->
                    val address = link.address
                    if (address is Inet4Address && !address.isLoopbackAddress && address.isSiteLocalAddress) return address.hostAddress ?: "0.0.0.0"
                }
            }
        } catch (_: Exception) {}
        return localIpv4()
    }

    private fun localIpv4(): String {
        return try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val ni = interfaces.nextElement()
                if (!ni.isUp || ni.isLoopback || ni.isVirtual) continue
                val addresses = ni.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (address is Inet4Address && !address.isLoopbackAddress && address.isSiteLocalAddress) return address.hostAddress ?: "0.0.0.0"
                }
            }
            "0.0.0.0"
        } catch (_: Exception) { "0.0.0.0" }
    }
}
