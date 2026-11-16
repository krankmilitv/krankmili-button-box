package com.krankmili.buttonbox.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException

data class FoundServer(val host: String, val port: Int, val name: String, val tokenRequired: Boolean)

/** Finds the PC server on the local network via UDP broadcast (server listens on UDP 48765). */
object Discovery {
    private const val PORT = 48765
    /** Phone's own IPv4 addresses from the last search, shown to the user when nothing is found. */
    @Volatile var localIps: List<String> = emptyList()

    private val QUERY = "KRANKMILI_BB_DISCOVER".toByteArray()

    suspend fun find(attempts: Int = 3, waitMs: Int = 1200): FoundServer? =
        withContext(Dispatchers.IO) {
            try {
                DatagramSocket().use { sock ->
                    sock.broadcast = true
                    sock.soTimeout = waitMs
                    val targets = discoveryTargets()
                    localIps = localIpv4()
                    val buf = ByteArray(512)
                    repeat(attempts) {
                        // Many phones/routers drop limited broadcast, so also probe every host unicast.
                        for (t in targets) {
                            try { sock.send(DatagramPacket(QUERY, QUERY.size, t, PORT)) } catch (_: java.io.IOException) {}
                        }
                        val deadline = System.currentTimeMillis() + waitMs
                        while (System.currentTimeMillis() < deadline) {
                            val pkt = DatagramPacket(buf, buf.size)
                            try {
                                sock.receive(pkt)
                            } catch (e: SocketTimeoutException) {
                                break
                            }
                            val json = try {
                                JSONObject(String(pkt.data, 0, pkt.length, Charsets.UTF_8))
                            } catch (e: Exception) {
                                continue
                            }
                            if (json.optString("app") != "krankmili-buttonbox") continue
                            return@withContext FoundServer(
                                host = pkt.address.hostAddress ?: continue,
                                port = json.optInt("port", 5555),
                                name = json.optString("name"),
                                tokenRequired = json.optBoolean("token", false),
                            )
                        }
                    }
                }
                null
            } catch (e: java.io.IOException) {
                null
            }
        }

    private fun discoveryTargets(): List<InetAddress> {
        val out = linkedSetOf<InetAddress>(InetAddress.getByName("255.255.255.255"))
        try {
            for (nif in NetworkInterface.getNetworkInterfaces().toList()) {
                if (!nif.isUp || nif.isLoopback) continue
                for (ia in nif.interfaceAddresses) {
                    val addr = ia.address as? Inet4Address ?: continue
                    ia.broadcast?.let { out += it }
                    val b = addr.address
                    for (i in 1..254) {
                        if (i == (b[3].toInt() and 0xFF)) continue
                        out += InetAddress.getByAddress(byteArrayOf(b[0], b[1], b[2], i.toByte()))
                    }
                }
            }
        } catch (_: Exception) {
        }
        return out.toList()
    }

    private fun localIpv4(): List<String> = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { n -> n.interfaceAddresses.mapNotNull { (it.address as? Inet4Address)?.hostAddress } }
    } catch (_: Exception) {
        emptyList()
    }
}
