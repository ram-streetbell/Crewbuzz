package com.example

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.HttpURLConnection
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import fi.iki.elonen.NanoHTTPD

data class TableCallEvent(val tableId: String, val request: String)
data class CrewBuzzDeviceEvent(val tableId: String, val deviceId: String, val ip: String)

object CrewBuzzNetwork {
    private const val HTTP_PORT = 8080
    private const val DISCOVERY_PORT = 4001
    private const val DEVICE_ID = "CREWBUZZ-01"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _tableCalls = MutableSharedFlow<TableCallEvent>(extraBufferCapacity = 32)
    val tableCalls = _tableCalls.asSharedFlow()
    private val _devices = MutableSharedFlow<CrewBuzzDeviceEvent>(extraBufferCapacity = 32)
    val devices = _devices.asSharedFlow()

    @Volatile private var started = false
    @Volatile private var discoverySocket: DatagramSocket? = null
    private var httpServer: NanoHTTPD? = null

    fun start(context: Context) {
        if (started) return
        started = true

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
                            if (table.isNullOrBlank()) newFixedLengthResponse(NanoHTTPD.Response.Status.BAD_REQUEST, "text/plain", "Missing table_id")
                            else {
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
        } catch (_: Exception) {
            started = false
            return
        }

        scope.launch { discoveryLoop() }
        scope.launch { broadcastDeviceDiscovery() }
    }

    fun scanNow() {
        if (!started) return
        scope.launch {
            val socket = discoverySocket ?: return@launch
            val localIp = localIpv4()
            val prefix = localIp.substringBeforeLast('.', "")
            if (prefix.isBlank()) return@launch

            // UDP discovery, using the same bound port that receives replies.
            for (host in 1..254) {
                sendDiscoveryTo(socket, "$prefix.$host")
                if (host % 16 == 0) delay(10)
            }
            sendDiscoveryBroadcast(socket)

            // Reliable fallback: query the ESP's tiny HTTP identification endpoint.
            val found = ConcurrentHashMap.newKeySet<String>()
            val jobs = (1..254).map { host ->
                scope.launch {
                    val ip = "$prefix.$host"
                    if (found.contains(ip)) return@launch
                    val event = probeHttpDevice(ip)
                    if (event != null && found.add(ip)) _devices.tryEmit(event)
                }
            }
            jobs.forEach { it.join() }
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
                if (!socket.isClosed) socket.send(DatagramPacket(bytes, bytes.size, InetAddress.getByName(targetIp), DISCOVERY_PORT))
            }
        } catch (_: Exception) {}
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

    private fun probeHttpDevice(ip: String): CrewBuzzDeviceEvent? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL("http://$ip/crewbuzz").openConnection() as HttpURLConnection).apply {
                connectTimeout = 250
                readTimeout = 250
                requestMethod = "GET"
                useCaches = false
            }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val type = Regex("""\"type\"\s*:\s*\"([^\"]+)\"""").find(body)?.groupValues?.get(1)
            val table = Regex("""\"table_id\"\s*:\s*\"([^\"]+)\"""").find(body)?.groupValues?.get(1)
            val device = Regex("""\"device_id\"\s*:\s*\"([^\"]+)\"""").find(body)?.groupValues?.get(1)
            if (type == "CREWBUZZ_DEVICE" && !table.isNullOrBlank() && !device.isNullOrBlank()) CrewBuzzDeviceEvent(table, device, ip) else null
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun discoveryLoop() {
        val socket = try {
            DatagramSocket(DISCOVERY_PORT).apply {
                soTimeout = 1000
                reuseAddress = true
                broadcast = true
            }
        } catch (_: Exception) {
            started = false
            return
        }
        discoverySocket = socket
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
                        synchronized(socket) { socket.send(DatagramPacket(bytes, bytes.size, packet.address, packet.port)) }
                    } else if (message.startsWith("CREWBUZZ_DEVICE|")) {
                        val parts = message.split("|")
                        if (parts.size >= 4) _devices.tryEmit(CrewBuzzDeviceEvent(parts[1], parts[2], parts[3]))
                    }
                } catch (_: SocketTimeoutException) {}
                catch (_: SocketException) { break }
                catch (_: Exception) {}
            }
        } finally {
            discoverySocket = null
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun localIpv4(): String {
        return try {
            val socket = java.net.DatagramSocket()
            socket.connect(InetAddress.getByName("8.8.8.8"), 80)
            val ip = socket.localAddress.hostAddress ?: "0.0.0.0"
            socket.close()
            ip
        } catch (_: Exception) { "0.0.0.0" }
    }
}
