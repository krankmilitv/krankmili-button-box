package com.krankmili.buttonbox.data

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

/**
 * Spoken feedback ("Sesli navigasyon aktif edildi", alerts, road/fuel/damage answers) through the tablet's TTS engine.
 * Mode: female / male / off. The engine decides which Turkish voices exist; with only one voice the male setting lowers the pitch.
 * Output goes to the alarm stream because VoiceControl mutes the media stream while it listens (to hide the recognizer beep).
 */
object Speaker {
    var mode by mutableStateOf("female")
        private set

    private var tts: TextToSpeech? = null
    private var ready = false
    private var prefs: android.content.SharedPreferences? = null
    private val pending = ArrayDeque<String>()
    @Volatile private var speaking = false
    @Volatile private var quietUntil = 0L
    private var lastAlertAt = 0L

    val label: String get() = when (mode) { "male" -> "Erkek ses"; "off" -> "Ses kapalı"; else -> "Kadın ses" }

    fun init(ctx: Context, p: android.content.SharedPreferences) {
        if (tts != null) return
        prefs = p
        mode = p.getString("tts_mode", "female") ?: "female"
        tts = TextToSpeech(ctx.applicationContext) { status ->
            val t = tts
            if (status != TextToSpeech.SUCCESS || t == null) return@TextToSpeech
            t.language = Locale("tr", "TR")
            t.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) { speaking = true }
                override fun onDone(utteranceId: String?) { speaking = false; quietUntil = System.currentTimeMillis() + 900 }
                @Deprecated("required override") override fun onError(utteranceId: String?) { speaking = false; quietUntil = System.currentTimeMillis() + 900 }
            })
            ready = true
            applyVoice()
            while (pending.isNotEmpty()) speakNow(pending.removeFirst())
        }
    }

    /** True while speaking and shortly after, so the recognizer does not hear (and obey) the tablet itself. */
    fun busy() = speaking || System.currentTimeMillis() < quietUntil

    fun cycleMode() {
        mode = when (mode) { "female" -> "male"; "male" -> "off"; else -> "female" }
        prefs?.edit()?.putString("tts_mode", mode)?.apply()
        if (mode == "off") tts?.stop() else {
            applyVoice()
            say(if (mode == "male") "Erkek ses seçildi" else "Kadın ses seçildi")
        }
    }

    private fun applyVoice() {
        val t = tts ?: return
        if (!ready) return
        val tr = try { t.voices?.filter { it.locale.language == "tr" && !it.isNetworkConnectionRequired }?.sortedBy { it.name } } catch (_: Exception) { null }
            ?: emptyList()
        val all = if (tr.isNotEmpty()) tr else try { t.voices?.filter { it.locale.language == "tr" }?.sortedBy { it.name } } catch (_: Exception) { null } ?: emptyList()
        if (all.isNotEmpty()) {
            val v = if (mode == "male" && all.size > 1) all.last() else all.first()
            try { t.voice = v } catch (_: Exception) {}
        }
        val single = all.size <= 1
        t.setPitch(if (mode == "male") (if (single) 0.75f else 0.9f) else 1.05f)
        t.setSpeechRate(1.0f)
    }

    /** Speaks now (replacing anything queued). */
    fun say(text: String) {
        if (mode == "off" || text.isBlank()) return
        if (!ready) { pending.addLast(text); return }
        speakNow(text)
    }

    /** For automatic alerts: skips if something was just said, so alerts never pile up. */
    fun alert(text: String) {
        val now = System.currentTimeMillis()
        if (mode == "off" || now - lastAlertAt < 6000 || busy()) return
        lastAlertAt = now
        say(text)
    }

    private fun speakNow(text: String) {
        val t = tts ?: return
        t.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), "k${System.nanoTime()}")
    }
}

/** Watches the truck status and speaks warnings once per threshold crossing (fuel, damage, arrival). */
class TruckAlerts {
    private var init = false
    private var fuel15 = false
    private var fuel7 = false
    private val bands = IntArray(4)
    private var near5 = false

    fun check(t: TruckStatus) {
        if (!t.gameRunning) { init = false; return }
        val wear = floatArrayOf(t.wearEngine, t.wearTransmission, t.wearChassis, t.wearWheels)
        val names = arrayOf("Motor", "Şanzıman", "Şasi", "Tekerlekler")
        val f = if (t.fuelCapacity > 0) t.fuelLiters / t.fuelCapacity else 1f
        val band = { w: Float -> if (w >= 0.75f) 3 else if (w >= 0.5f) 2 else if (w >= 0.25f) 1 else 0 }
        if (!init) {
            // First status after connecting: remember the current state without announcing it.
            init = true
            fuel15 = f < 0.15f; fuel7 = f < 0.07f
            for (i in 0..3) bands[i] = band(wear[i])
            near5 = t.routeKm in 0.1f..5f
            return
        }
        if (f > 0.30f) { fuel15 = false; fuel7 = false }
        if (f < 0.07f && !fuel7) { fuel7 = true; fuel15 = true; Speaker.alert("Dikkat, yakıt kritik seviyede.") }
        else if (f < 0.15f && !fuel15) { fuel15 = true; Speaker.alert("Yakıt azaldı. Yaklaşık ${t.rangeKm.toInt()} kilometre menzil kaldı.") }
        for (i in 0..3) {
            val b = band(wear[i])
            if (b > bands[i]) Speaker.alert("${names[i]} hasarı yüzde ${(wear[i] * 100).toInt()} oldu.")
            bands[i] = b
        }
        if (t.cargoLoaded && t.routeKm in 0.1f..5f && !near5) { near5 = true; Speaker.alert("Varışa yaklaşıyorsun, yaklaşık ${t.routeKm.toInt()} kilometre kaldı.") }
        if (t.routeKm > 10f) near5 = false
    }
}

/** Questions answered locally from the live truck status ("yol durumu", "yakıt durumu", …). */
object InfoCommands {
    val phrases: Map<String, List<String>> = mapOf(
        "info:road" to listOf("yol durumu", "yol durumunu söyle", "yol bilgisi", "ne kadar kaldı", "kaç kilometre kaldı", "varışa ne kadar kaldı", "rota durumu"),
        "info:fuel" to listOf("yakıt durumu", "yakıt durumunu söyle", "yakıt ne kadar", "yakıt ne durumda", "yakıt kaldı mı"),
        "info:damage" to listOf("hasar durumu", "hasar durumunu söyle", "hasar var mı", "araç durumu", "kamyon durumu"),
        "info:cargo" to listOf("yük bilgisi", "yük bilgisini söyle", "ne taşıyorum", "yük ne", "yük durumu"),
        "info:speed" to listOf("hızım ne", "hız kaç", "kaç kilometre hızla gidiyorum"),
        "info:weather" to listOf("hava durumu", "hava durumunu söyle", "hava nasıl"),
    )

    fun answer(id: String, t: TruckStatus?): String {
        if (id == "info:weather") return "Oyundan hava durumu bilgisi alınamıyor."
        if (t == null || !t.gameRunning) return "Oyun bağlı değil, bilgi alınamıyor."
        return when (id) {
            "info:road" ->
                if (t.destination.isBlank() || t.routeKm <= 0f) "Aktif bir rota yok. Hızın ${t.speedKmh.toInt()} kilometre."
                else "${t.destination} varış noktasına ${t.routeKm.toInt()} kilometre kaldı. Hızın ${t.speedKmh.toInt()} kilometre."
            "info:fuel" -> {
                val pct = if (t.fuelCapacity > 0) (t.fuelLiters / t.fuelCapacity * 100).toInt() else 0
                "Yakıt yüzde $pct, yaklaşık ${t.rangeKm.toInt()} kilometre menzil var."
            }
            "info:damage" -> {
                val e = (t.wearEngine * 100).toInt(); val g = (t.wearTransmission * 100).toInt()
                val c = (t.wearChassis * 100).toInt(); val w = (t.wearWheels * 100).toInt()
                if (e + g + c + w == 0) "Araçta hasar yok."
                else "Hasar: motor yüzde $e, şanzıman yüzde $g, şasi yüzde $c, tekerlekler yüzde $w."
            }
            "info:cargo" ->
                if (!t.cargoLoaded || t.cargo.isBlank()) "Şu an yük taşımıyorsun."
                else "${t.cargo}, ${"%.1f".format(Locale("tr", "TR"), t.cargoKg / 1000f)} ton taşıyorsun."
            "info:speed" -> "Hızın ${t.speedKmh.toInt()} kilometre."
            else -> ""
        }
    }
}
