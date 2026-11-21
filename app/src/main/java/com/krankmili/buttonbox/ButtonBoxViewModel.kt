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

    // The recognizer runs in VoiceService; heard text arrives here through the hub.
    private val heardHook: (List<String>) -> Unit = { onVoiceHeard(it) }
    private val stateHook: (String) -> Unit = { setVoiceState(it) }
    init {
        VoiceHub.onHeard = heardHook
        VoiceHub.onState = stateHook
    }

    private val voiceJobs = HashMap<String, kotlinx.coroutines.Job>()

    // ---- lit buttons: held keys, short pulses for taps, and the flashers (hazard / indicators).
    // The game does not tell us the flasher state, so it is tracked here: a press toggles it.
    private val holds = HashSet<String>()
    private val pulses = HashSet<String>()
    private val flashers = HashSet<String>()
    private val _lit = MutableStateFlow<Set<String>>(emptySet())
    val lit: StateFlow<Set<String>> = _lit
    private var indicatorJob: kotlinx.coroutines.Job? = null

    private fun publishLit() { synchronized(this) { _lit.value = (holds + pulses + flashers).toSet() } }

    private fun setHold(id: String, on: Boolean) { synchronized(this) { if (on) holds.add(id) else holds.remove(id) }; publishLit() }

    private fun pulse(id: String) {
        synchronized(this) { pulses.add(id) }; publishLit()
        scope.launch { kotlinx.coroutines.delay(500); synchronized(this@ButtonBoxViewModel) { pulses.remove(id) }; publishLit() }
    }

    private fun toggleFlasher(id: String) {
        synchronized(this) {
            when (id) {
                "hazard" -> if (!flashers.remove("hazard")) flashers.add("hazard")
                "indicator_l", "indicator_r" -> {
                    val other = if (id == "indicator_l") "indicator_r" else "indicator_l"
                    flashers.remove(other)
                    if (!flashers.remove(id)) flashers.add(id)
                }
            }
        }
        publishLit()
        // The game cancels an indicator after the turn; assume that within 20 s since it cannot be read.
        if (id.startsWith("indicator_")) {
            indicatorJob?.cancel()
            if (id in flashers) indicatorJob = scope.launch {
                kotlinx.coroutines.delay(20_000)
                synchronized(this@ButtonBoxViewModel) { flashers.remove("indicator_l"); flashers.remove("indicator_r") }
                publishLit()
            }
        }
    }

    private fun isFlasher(id: String) = id == "hazard" || id == "indicator_l" || id == "indicator_r"

    // Any interaction lights its button; taps pulse, holds stay lit while the key is down.
    private fun interact(id: String, hold: Boolean) {
        if (id == "engine_toggle") alerts.engineToggled(truck.value)
        if (isFlasher(id)) toggleFlasher(id) else if (!hold) pulse(id)
    }

    init {
        scope.launch { client.truck.collect { t -> if (t == null || !t.gameRunning) { synchronized(this@ButtonBoxViewModel) { flashers.clear() }; publishLit() } } }
    }

    val profiles: StateFlow<List<String>> get() = client.profiles
    val profile: StateFlow<String> get() = client.profile
    fun selectProfile(name: String) {
        client.selectProfile(name)
        com.krankmili.buttonbox.data.Speaker.say("Profil: $name")
    }

    private val alerts = com.krankmili.buttonbox.data.TruckAlerts()
    init {
        scope.launch { client.truck.collect { t -> if (t != null) alerts.check(t) } }
    }

    private fun speakFor(id: String) {
        val text = layout.value.asSequence().flatMap { it.buttons.asSequence() }.firstOrNull { it.id == id }?.speak.orEmpty()
        if (text.isNotEmpty()) com.krankmili.buttonbox.data.Speaker.say(text)
    }

    fun onVoiceHeard(alternatives: List<String>) {
        // Ignore what the microphone picked up while the tablet itself was speaking.
        if (com.krankmili.buttonbox.data.Speaker.busy()) return
        val buttons = layout.value.flatMap { it.buttons }
        val phrases = HashMap<String, MutableList<String>>()
        for ((iid, list) in com.krankmili.buttonbox.data.InfoCommands.phrases) for (v in list)
            phrases.getOrPut(com.krankmili.buttonbox.data.VoiceMatcher.norm(v)) { mutableListOf() }.add(iid)
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
                    if (id.startsWith("info:")) {
                        com.krankmili.buttonbox.data.Speaker.say(com.krankmili.buttonbox.data.InfoCommands.answer(id, truck.value))
                        continue
                    }
                    speakFor(id)
                    val hold = buttons.firstOrNull { it.id == id }?.mode == "hold"
                    // Windows need the key held for their whole travel so they open/close fully.
                    // "biraz" (a little) holds a window about 20% of its travel.
                    val holdMs = if (hold) (if (id.startsWith("window_") || id.startsWith("sunvisor_")) (if (r.phrase.contains("biraz")) 1000L else 5000L) else 1500L) else 80L
                    // A repeated command extends the running hold instead of releasing the key early;
                    // the opposite window direction is released first so both keys are never down together.
                    val opposite = when {
                        id.endsWith("_up") -> id.removeSuffix("_up") + "_down"
                        id.endsWith("_down") -> id.removeSuffix("_down") + "_up"
                        else -> null
                    }
                    if (opposite != null && (id.startsWith("window_") || id.startsWith("sunvisor_"))) voiceJobs.remove(opposite)?.let { it.cancel(); client.release(opposite) }
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
                    if (running == null) interact(id, hold)
                    voiceJobs[id] = scope.launch {
                        try {
                            if (running == null) client.press(id)
                            if (hold) setHold(id, true)
                            kotlinx.coroutines.delay(holdMs)
                        } finally {
                            if (voiceJobs[id] === kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]) {
                                voiceJobs.remove(id)
                                client.release(id)
                                setHold(id, false)
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
    fun press(id: String) {
        client.press(id); speakFor(id)
        val hold = layout.value.asSequence().flatMap { it.buttons.asSequence() }.firstOrNull { it.id == id }?.mode == "hold"
        interact(id, hold)
        if (hold) setHold(id, true)
    }
    fun release(id: String) { client.release(id); setHold(id, false) }

    override fun onCleared() {
        if (VoiceHub.onHeard === heardHook) VoiceHub.onHeard = null
        if (VoiceHub.onState === stateHook) VoiceHub.onState = null
        client.disconnect()
        scope.cancel()
    }
}
