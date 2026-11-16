package com.krankmili.buttonbox.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket

/** Optional question shown on the tablet after the button is pressed; Yes/No press other buttons by id. */
data class BoxPrompt(val text: String, val yesId: String, val noId: String)

data class BoxButton(
    val id: String,
    val label: String,
    val mode: String,
    val icon: String = "",
    val sub: String = "",
    val span: Int = 1,
    val accent: Boolean = false,
    val prompt: BoxPrompt? = null,
)

data class BoxGroup(
    val title: String,
    val kind: String,      // "buttons" or "status"
    val page: Int,         // 1.. which page the panel is on (pages are switched with a selector)
    val column: Int,       // 1..4, which screen column the panel sits in
    val columns: Int,      // button grid width inside the panel
    val weight: Float,     // share of the column height
    val buttons: List<BoxButton>,
)

/** Live truck data pushed by the PC (null fields = not available). Fractions are 0..1. */
data class TruckStatus(
    val gameRunning: Boolean,
    val pluginFound: Boolean,
    val fuelLiters: Float,
    val fuelCapacity: Float,
    val rangeKm: Float,
    val wearEngine: Float,
    val wearTransmission: Float,
    val wearChassis: Float,
    val wearWheels: Float,
    val speedKmh: Float,
    val cargoLoaded: Boolean,
    val cargo: String,
    val cargoKg: Float,
    val destination: String,
    val routeKm: Float,
)

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data class Connecting(val note: String = "") : ConnectionState
    data object Connected : ConnectionState
}

/**
 * TCP client for the PC button box server. Protocol: docs/PROTOCOL.md
 * (newline-delimited JSON). Reconnects automatically until disconnect() is called.
 */
class ButtonBoxClient(private val scope: CoroutineScope) {

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _layout = MutableStateFlow<List<BoxGroup>>(emptyList())
    val layout: StateFlow<List<BoxGroup>> = _layout.asStateFlow()

    private val _truck = MutableStateFlow<TruckStatus?>(null)
    val truck: StateFlow<TruckStatus?> = _truck.asStateFlow()

    private var job: Job? = null

    @Volatile private var socket: Socket? = null
    @Volatile private var outgoing: Channel<String>? = null

    fun connect(host: String, port: Int, token: String) {
        disconnect()
        job = scope.launch(Dispatchers.IO) {
            while (isActive) {
                _state.value = ConnectionState.Connecting()
                val failure = try {
                    runSession(host, port, token)
                    "bağlantı koptu"
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // IOException (network) and JSONException (bad server data) both mean: retry.
                    e.message ?: "bağlantı hatası"
                }
                if (!isActive) break
                _state.value = ConnectionState.Connecting(failure)
                delay(RECONNECT_DELAY_MS)
            }
        }
    }

    fun disconnect() {
        job?.cancel()
        job = null
        // Closing the socket is what unblocks readLine() on the IO thread.
        try { socket?.close() } catch (_: IOException) {}
        outgoing?.close()
        socket = null
        outgoing = null
        _state.value = ConnectionState.Disconnected
        _truck.value = null
    }

    fun press(id: String) = send("press", id)
    fun release(id: String) = send("release", id)

    private fun send(type: String, id: String) {
        val line = JSONObject().put("t", type).put("id", id).toString()
        outgoing?.trySend(line)
    }

    private suspend fun runSession(host: String, port: Int, token: String) {
        val sock = Socket()
        socket = sock
        try {
            sock.tcpNoDelay = true
            sock.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            // Server pings back every ~2 s, so silence for this long means a dead link.
            sock.soTimeout = READ_TIMEOUT_MS

            val reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.UTF_8))
            val writer = OutputStreamWriter(sock.getOutputStream(), Charsets.UTF_8)
            val out = Channel<String>(Channel.UNLIMITED)
            outgoing = out

            writer.write(JSONObject().put("t", "hello").put("token", token).toString() + "\n")
            writer.flush()

            coroutineScope {
                launch(Dispatchers.IO) {
                    try {
                        for (line in out) {
                            writer.write(line + "\n")
                            writer.flush()
                        }
                    } catch (_: IOException) {
                        // reader loop reports the failure
                    }
                }
                launch {
                    while (isActive) {
                        delay(PING_INTERVAL_MS)
                        out.trySend("""{"t":"ping"}""")
                    }
                }

                try {
                    while (true) {
                        val line = reader.readLine() ?: throw IOException("sunucu bağlantıyı kapattı")
                        val msg = JSONObject(line)
                        when (msg.optString("t")) {
                            "layout" -> {
                                _layout.value = parseLayout(msg)
                                _state.value = ConnectionState.Connected
                            }
                            "status" -> _truck.value = TruckStatus(
                                gameRunning = msg.optBoolean("game"),
                                pluginFound = msg.optBoolean("plugin"),
                                fuelLiters = msg.optDouble("fuel", 0.0).toFloat(),
                                fuelCapacity = msg.optDouble("fuelCap", 0.0).toFloat(),
                                rangeKm = msg.optDouble("range", 0.0).toFloat(),
                                wearEngine = msg.optDouble("wearEngine", 0.0).toFloat(),
                                wearTransmission = msg.optDouble("wearTrans", 0.0).toFloat(),
                                wearChassis = msg.optDouble("wearChassis", 0.0).toFloat(),
                                wearWheels = msg.optDouble("wearWheels", 0.0).toFloat(),
                                speedKmh = msg.optDouble("speed", 0.0).toFloat(),
                                cargoLoaded = msg.optBoolean("cargoLoaded"),
                                cargo = msg.optString("cargo"),
                                cargoKg = msg.optDouble("cargoKg", 0.0).toFloat(),
                                destination = msg.optString("dest"),
                                routeKm = msg.optDouble("routeKm", 0.0).toFloat(),
                            )
                            "error" -> throw IOException(msg.optString("msg", "sunucu hatası"))
                            // "pong": nothing to do, receiving it resets the read timeout
                        }
                    }
                } finally {
                    out.close()
                    // ping loop never ends on its own; without this coroutineScope would hang
                    coroutineContext.cancelChildren()
                }
            }
        } finally {
            outgoing = null
            try { sock.close() } catch (_: IOException) {}
            if (socket === sock) socket = null
        }
    }

    private fun parseLayout(msg: JSONObject): List<BoxGroup> {
        val groups = msg.getJSONArray("groups")
        return List(groups.length()) { gi ->
            val g = groups.getJSONObject(gi)
            val buttons = g.optJSONArray("buttons")
            BoxGroup(
                title = g.optString("title"),
                kind = g.optString("kind", "buttons"),
                page = g.optInt("page", 1).coerceAtLeast(1),
                column = g.optInt("column", 1).coerceIn(1, 4),
                columns = g.optInt("columns", 2).coerceAtLeast(1),
                weight = g.optDouble("weight", 1.0).toFloat(),
                buttons = List(buttons?.length() ?: 0) { bi ->
                    val b = buttons!!.getJSONObject(bi)
                    BoxButton(
                        id = b.getString("id"),
                        label = b.optString("label", b.getString("id")),
                        mode = b.optString("mode", "tap"),
                        icon = b.optString("icon"),
                        sub = b.optString("sub"),
                        span = b.optInt("span", 1).coerceAtLeast(1),
                        accent = b.optBoolean("accent", false),
                        prompt = b.optJSONObject("prompt")?.let {
                            BoxPrompt(it.optString("text"), it.optString("yes"), it.optString("no"))
                        },
                    )
                },
            )
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 4000
        const val READ_TIMEOUT_MS = 6000
        const val PING_INTERVAL_MS = 2000L
        const val RECONNECT_DELAY_MS = 2000L
    }
}
