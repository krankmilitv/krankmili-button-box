package com.krankmili.buttonbox.data

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale
import kotlin.math.abs

/** Matches what the recognizer heard against the `voice` phrases of the buttons. */
object VoiceMatcher {
    sealed interface Result {
        data class Matched(val ids: List<String>, val phrase: String) : Result
        data object WakeOnly : Result
        data class NoMatch(val heard: String) : Result
        data object Ignored : Result
    }

    // Turkish letters folded to ASCII so "camı aç" and "cami ac" compare equal.
    fun norm(s: String): String {
        val sb = StringBuilder()
        for (ch in s.lowercase(Locale("tr"))) {
            sb.append(
                when (ch) {
                    'ç' -> 'c'; 'ğ' -> 'g'; 'ı' -> 'i'; 'ö' -> 'o'; 'ş' -> 's'; 'ü' -> 'u'; 'â' -> 'a'
                    else -> if (ch.isLetterOrDigit()) ch else ' '
                },
            )
        }
        return sb.toString().trim().replace(Regex("\\s+"), " ")
    }

    private fun editDistance(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) {
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            prev = cur
        }
        return prev[b.length]
    }

    // "hey krank" is optional now; recognizers spell it in many ways, so it is only stripped when present.
    private val wake = Regex("^(?:hey|hay|ay|ey)\\s*(?:krank|crank|kronk|krenk|kreyk|kranc|krunk|kraynk|kreng|cronk|kranch)\\s*(.*)$")

    /**
     * phrases: normalized phrase -> ids of the buttons it presses (several buttons may share a phrase).
     * Without a wake word, ordinary talk must not press buttons: a phrase only counts when it is (nearly) the whole
     * utterance — the heard text may have at most 2 extra words ("lütfen sol camı aç").
     */
    fun match(alternatives: List<String>, phrases: Map<String, List<String>>, armed: Boolean): Result {
        var wakeSeen = false
        var firstHeard = ""
        for (alt in alternatives) {
            val n = norm(alt)
            if (n.isEmpty()) continue
            val m = wake.find(n)
            val cmd = if (m != null) { wakeSeen = true; m.groupValues[1].trim() } else n
            if (firstHeard.isEmpty()) firstHeard = cmd
            if (cmd.isEmpty()) continue
            phrases[cmd]?.let { return Result.Matched(it, cmd) }
            // Recognizers drop or add a letter ("sağ cam aç" / "sağ camı aç"): accept a tiny edit distance.
            val near = phrases.keys.filter { it.length >= 8 && abs(it.length - cmd.length) <= 2 && editDistance(it, cmd) <= (if (it.length >= 16) 2 else 1) }
            if (near.size == 1) return Result.Matched(phrases.getValue(near[0]), near[0])
            val words = cmd.split(' ').size
            val hit = phrases.keys
                .filter { it.length >= 4 && cmd.contains(it) && words <= it.split(' ').size + 2 }
                .maxByOrNull { it.length }
            if (hit != null) return Result.Matched(phrases.getValue(hit), hit)
        }
        return when {
            wakeSeen && firstHeard.isEmpty() -> Result.WakeOnly
            wakeSeen -> Result.NoMatch(firstHeard)
            else -> Result.Ignored
        }
    }
}

/**
 * Continuous listening with Android's SpeechRecognizer (restarted after every utterance).
 * Must be used from the main thread. The system beeps on some devices; the music stream is muted
 * while listening to hide it (and restored on stop).
 */
class VoiceControl(
    private val ctx: Context,
    private val onHeard: (List<String>) -> Unit,
    private val onState: (String) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var recognizer: SpeechRecognizer? = null
    private var active = false
    private var muted = false
    // "Prefer offline" makes some engines (Xiaomi/Google) answer LANGUAGE_UNAVAILABLE when no offline pack is installed;
    // in that case fall back to the online recognizer instead of giving up.
    private var preferOffline = true

    fun start() {
        if (active) return
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
            onState("ses tanıma yok")
            return
        }
        active = true
        preferOffline = true
        muteBeep(true)
        onState("dinliyor")
        listen()
    }

    fun stop() {
        active = false
        handler.removeCallbacksAndMessages(null)
        recognizer?.destroy()
        recognizer = null
        muteBeep(false)
        onState("")
    }

    private fun muteBeep(on: Boolean) {
        if (on == muted) return
        try {
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, if (on) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE, 0)
            muted = on
        } catch (_: Exception) {
        }
    }

    private fun restart(delayMs: Long) {
        if (!active) return
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ listen() }, delayMs)
    }

    private fun listen() {
        if (!active) return
        recognizer?.destroy()
        val r = SpeechRecognizer.createSpeechRecognizer(ctx)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                if (list.isNotEmpty()) onHeard(list)
                restart(250)
            }

            override fun onError(error: Int) {
                when (error) {
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> { stop(); onState("mikrofon izni yok") }
                    12, 13 -> {   // LANGUAGE_NOT_SUPPORTED / UNAVAILABLE
                        if (preferOffline) { preferOffline = false; onState("çevrimiçi dinliyor"); restart(300) }
                        else { stop(); onState("Türkçe ses tanıma yok (hata $error)") }
                    }
                    SpeechRecognizer.ERROR_NO_MATCH -> { onState("anlaşılmadı"); restart(200) }
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> { onState("dinliyor"); restart(200) }
                    else -> { onState("hata $error"); restart(1500) }
                }
            }

            override fun onReadyForSpeech(params: Bundle?) { onState("dinliyor") }
            override fun onBeginningOfSpeech() { onState("ses alıyor…") }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "tr-TR")
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOffline)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1000L)
        }
        try {
            r.startListening(i)
        } catch (_: Exception) {
            restart(1500)
        }
    }
}
