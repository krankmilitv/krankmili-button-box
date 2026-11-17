package com.krankmili.buttonbox

import androidx.lifecycle.ViewModel
import com.krankmili.buttonbox.data.BoxGroup
import com.krankmili.buttonbox.data.ButtonBoxClient
import com.krankmili.buttonbox.data.ConnectionState
import com.krankmili.buttonbox.data.TruckStatus
import com.krankmili.buttonbox.data.Discovery
import com.krankmili.buttonbox.data.FoundServer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow

class ButtonBoxViewModel : ViewModel() {

    // Own scope instead of viewModelScope: avoids depending on lifecycle-viewmodel-ktx.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = ButtonBoxClient(scope)

    val state: StateFlow<ConnectionState> = client.state
    val layout: StateFlow<List<BoxGroup>> = client.layout
    val truck: StateFlow<TruckStatus?> = client.truck

    private val _update = MutableStateFlow<com.krankmili.buttonbox.data.UpdateInfo?>(null)
    val update: StateFlow<com.krankmili.buttonbox.data.UpdateInfo?> = _update

    fun checkUpdate() { scope.launch { _update.value = com.krankmili.buttonbox.data.UpdateCheck.fetch() } }
    fun clearUpdate() { _update.value = null }

    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching
    private val _found = MutableStateFlow<FoundServer?>(null)
    val found: StateFlow<FoundServer?> = _found
    private val _searchFailed = MutableStateFlow(false)
    val searchFailed: StateFlow<Boolean> = _searchFailed

    fun discover() {
        if (_searching.value) return
        _searching.value = true
        _searchFailed.value = false
        scope.launch {
            val r = Discovery.find()
            _found.value = r
            _searchFailed.value = r == null
            _searching.value = false
        }
    }

    fun consumeFound() { _found.value = null }

    // ---- voice: "hey krank <command>" presses the matching buttons directly (no confirm dialog; that is for touch only)
    private val _voiceStatus = MutableStateFlow("")
    val voiceStatus: StateFlow<String> = _voiceStatus
    private var armedUntil = 0L

    fun setVoiceState(text: String) { _voiceStatus.value = text }

    private val voiceJobs = HashMap<String, kotlinx.coroutines.Job>()

    fun onVoiceHeard(alternatives: List<String>) {
        val buttons = layout.value.flatMap { it.buttons }
        val phrases = HashMap<String, MutableList<String>>()
        for (b in buttons) for (v in b.voice) {
            val ids = phrases.getOrPut(com.krankmili.buttonbox.data.VoiceMatcher.norm(v)) { mutableListOf() }
            if (b.id !in ids) ids.add(b.id)
        }
        val now = System.currentTimeMillis()
        when (val r = com.krankmili.buttonbox.data.VoiceMatcher.match(alternatives, phrases, now < armedUntil)) {
            is com.krankmili.buttonbox.data.VoiceMatcher.Result.Matched -> {
                armedUntil = 0
                _voiceStatus.value = "✓ ${r.phrase}"
                for (id in r.ids) {
                    val hold = buttons.firstOrNull { it.id == id }?.mode == "hold"
                    // Windows need the key held for their whole travel so they open/close fully.
                    // "biraz" (a little) holds a window about 20% of its travel.
                    val holdMs = if (hold) (if (id.startsWith("window_")) (if (r.phrase.contains("biraz")) 1000L else 5000L) else 1500L) else 80L
                    // A repeated command extends the running hold instead of releasing the key early;
                    // the opposite window direction is released first so both keys are never down together.
                    val opposite = when {
                        id.endsWith("_up") -> id.removeSuffix("_up") + "_down"
                        id.endsWith("_down") -> id.removeSuffix("_down") + "_up"
                        else -> null
                    }
                    if (opposite != null && id.startsWith("window_")) voiceJobs.remove(opposite)?.let { it.cancel(); client.release(opposite) }
                    val running = voiceJobs.remove(id)
                    running?.cancel()
                    // Voice answers the in-game confirmation itself (e.g. "servise git" -> Enter), no prompt on the tablet.
                    buttons.firstOrNull { it.id == id }?.prompt?.let { pr ->
                        scope.launch {
                            kotlinx.coroutines.delay(1500L)
                            client.press(pr.yesId)
                            kotlinx.coroutines.delay(80L)
                            client.release(pr.yesId)
                        }
                    }
                    voiceJobs[id] = scope.launch {
                        try {
                            if (running == null) client.press(id)
                            kotlinx.coroutines.delay(holdMs)
                        } finally {
                            if (voiceJobs[id] === kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]) {
                                voiceJobs.remove(id)
                                client.release(id)
                            }
                        }
                    }
                }
            }
            is com.krankmili.buttonbox.data.VoiceMatcher.Result.WakeOnly -> {
                armedUntil = now + 6000
                _voiceStatus.value = "söyle…"
            }
            is com.krankmili.buttonbox.data.VoiceMatcher.Result.NoMatch -> _voiceStatus.value = "? ${r.heard}"
            is com.krankmili.buttonbox.data.VoiceMatcher.Result.Ignored ->
                _voiceStatus.value = "duydum: ${alternatives.firstOrNull().orEmpty()}"
        }
    }

    fun connect(host: String, port: Int, token: String) = client.connect(host, port, token)
    fun disconnect() = client.disconnect()
    fun press(id: String) = client.press(id)
    fun release(id: String) = client.release(id)

    override fun onCleared() {
        client.disconnect()
        scope.cancel()
    }
}
