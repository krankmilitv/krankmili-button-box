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

    /** Automatic trip information (trip start, every 100 km, traffic, collision). Alerts (fuel, damage) follow the voice mode only. */
    var autoInfo by mutableStateOf(true)
        private set

    fun updateAutoInfo(on: Boolean) {
        autoInfo = on
        prefs?.edit()?.putBoolean("auto_info", on)?.apply()
    }

    val label: String get() = when (mode) { "male" -> "Erkek ses"; "off" -> "Ses kapalı"; else -> "Kadın ses" }

    fun init(ctx: Context, p: android.content.SharedPreferences) {
        if (tts != null) return
        prefs = p
        mode = p.getString("tts_mode", "female") ?: "female"
        autoInfo = p.getBoolean("auto_info", true)
        val google = "com.google.android.tts"
        val hasGoogle = try { ctx.packageManager.getPackageInfo(google, 0); true } catch (_: Exception) { false }
        val onInit = TextToSpeech.OnInitListener { status ->
            val t = tts
            if (status != TextToSpeech.SUCCESS || t == null) return@OnInitListener
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
        // Google's engine carries the same voices as Google Maps navigation; use it when installed.
        tts = if (hasGoogle) TextToSpeech(ctx.applicationContext, onInit, google) else TextToSpeech(ctx.applicationContext, onInit)
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
        val all = try {
            t.voices?.filter { it.locale.language == "tr" && !it.features.contains("notInstalled") }
                ?.sortedWith(compareByDescending<android.speech.tts.Voice> { it.quality }.thenBy { it.name })
        } catch (_: Exception) { null } ?: emptyList()
        if (all.isNotEmpty()) {
            val v = if (mode == "male" && all.size > 1) all[1] else all.first()
            try { t.voice = v } catch (_: Exception) {}
        }
        val single = all.size <= 1
        t.setPitch(if (mode == "male") (if (single) 0.8f else 0.92f) else 1.0f)
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

    /** For automatic trip information: queued after whatever is being said, never cuts it off. */
    fun inform(text: String) {
        if (mode == "off" || !autoInfo || text.isBlank()) return
        if (!ready) { pending.addLast(text); return }
        tts?.speak(text, TextToSpeech.QUEUE_ADD, Bundle(), "k${System.nanoTime()}")
    }

    private fun speakNow(text: String) {
        val t = tts ?: return
        t.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), "k${System.nanoTime()}")
    }
}

/** Watches the truck status and speaks warnings once per threshold crossing (fuel, damage, arrival). */
class TruckAlerts {
    private var init = false
    private var fuel30 = false
    private var fuel15 = false
    private var fuel7 = false
    private val bands = IntArray(4)
    private var near5 = false

    // Trip tracking: a new job (destination or cargo changes) announces itself once the route is known,
    // then every 100 km of route is reported. Speeds feed a rough ETA and a "traffic is slow" guess.
    private var tripKey = ""
    private var startPending = false
    private var briefPending = false   // once per connection: say cargo, destination and distance when the truck first moves
    private var lastMarkKm = 0f
    private var prevWear = 0f
    private var avgSpeed = 0f          // slow average of moving speed (km/h)
    private var fastSpeed = 0f         // fast average, drops quickly when the flow stops
    private var slowFlow = false
    private var lastTrafficAt = 0L

    private fun etaText(routeKm: Float): String {
        if (avgSpeed < 20f) return ""
        val minutes = (routeKm / avgSpeed * 60f).toInt()
        val h = minutes / 60; val m = minutes % 60
        return " Bu hızla yaklaşık " + (if (h > 0) "$h saat " else "") + "$m dakika sürer."
    }

    private fun trip(t: TruckStatus) {
        val moving = t.speedKmh > 20f
        if (moving) {
            avgSpeed = if (avgSpeed == 0f) t.speedKmh else avgSpeed + (t.speedKmh - avgSpeed) * 0.004f
            fastSpeed = if (fastSpeed == 0f) t.speedKmh else fastSpeed + (t.speedKmh - fastSpeed) * 0.05f
        } else if (t.speedKmh < 3f) {
            fastSpeed += (t.speedKmh - fastSpeed) * 0.02f   // standing still slowly pulls the fast average down
        }
        if (briefPending && t.speedKmh > 5f) {
            briefPending = false
            if (t.destination.isNotBlank() && t.routeKm > 1f && !startPending) {
                val ton = "%.1f".format(Locale("tr", "TR"), t.cargoKg / 1000f)
                val min = (t.routeKm / (if (avgSpeed >= 20f) avgSpeed else 60f) * 60f).toInt()
                val eta = (if (min / 60 > 0) "${min / 60} saat " else "") + "${min % 60} dakika"
                val cargo = if (t.cargoLoaded && t.cargo.isNotBlank()) "Yük ${t.cargo}, $ton ton. " else "Yük taşımıyorsun. "
                Speaker.inform("Araç çalıştı. $cargo Varış ${t.destination}, ${t.routeKm.toInt()} kilometre kaldı, yaklaşık $eta sürer.")
            }
        }
        val key = t.destination + "|" + t.cargo
        if (key != tripKey) {
            tripKey = key
            startPending = t.destination.isNotBlank() && t.cargoLoaded
        }
        if (startPending && t.routeKm > 1f) {
            startPending = false
            lastMarkKm = t.routeKm
            val ton = "%.1f".format(Locale("tr", "TR"), t.cargoKg / 1000f)
            val fuelNote = if (t.rangeKm in 1f..t.routeKm) " Dikkat, yakıt menzili rotaya yetmeyebilir, yolda yakıt almayı planla." else ""
            Speaker.inform("Sefer başladı. Hedefimiz ${t.destination}, ${t.routeKm.toInt()} kilometre. Yük ${t.cargo}, $ton ton. Yakıt menzili ${t.rangeKm.toInt()} kilometre.$fuelNote")
            return
        }
        if (t.routeKm > lastMarkKm + 20f) lastMarkKm = t.routeKm          // route got longer (detour): restart the count
        if (t.routeKm > 20f && lastMarkKm - t.routeKm >= 100f) {
            lastMarkKm = t.routeKm
            val pct = if (t.fuelCapacity > 0) (t.fuelLiters / t.fuelCapacity * 100).toInt() else 0
            val dmg = maxOf(t.wearEngine, t.wearTransmission, t.wearChassis, t.wearWheels)
            val dmgNote = if (dmg >= 0.05f) " En yüksek hasar yüzde ${(dmg * 100).toInt()}." else " Araçta önemli hasar yok."
            Speaker.inform("Yolun 100 kilometresi tamamlandı. ${t.destination} için ${t.routeKm.toInt()} kilometre kaldı.${etaText(t.routeKm)} Yakıt yüzde $pct. Yol akışı ${if (slowFlow) "yavaş" else "stabil"}.$dmgNote")
        }
        // Heuristic from the truck's own speed (the game exposes no traffic data): a sustained slowdown after fast driving.
        val now = System.currentTimeMillis()
        if (!slowFlow && avgSpeed > 55f && fastSpeed in 5f..28f && t.speedKmh > 3f && now - lastTrafficAt > 300_000L) {
            slowFlow = true; lastTrafficAt = now
            Speaker.inform("Trafik yoğun görünüyor, hızımız düştü.")
        } else if (slowFlow && fastSpeed > 50f) {
            slowFlow = false
            Speaker.inform("Trafik açıldı.")
        }
        // A sudden chassis/body damage jump is a collision.
        val w = maxOf(t.wearChassis, t.wearEngine, t.wearWheels)
        if (w - prevWear >= 0.02f) Speaker.alert("Çarpma algılandı. Hasar yüzde ${(w * 100).toInt()}.")
        prevWear = w
    }

    fun check(t: TruckStatus) {
        if (!t.gameRunning) { init = false; return }
        val wear = floatArrayOf(t.wearEngine, t.wearTransmission, t.wearChassis, t.wearWheels)
        val names = arrayOf("Motor", "Şanzıman", "Şasi", "Tekerlekler")
        val f = if (t.fuelCapacity > 0) t.fuelLiters / t.fuelCapacity else 1f
        val band = { w: Float -> if (w >= 0.75f) 3 else if (w >= 0.5f) 2 else if (w >= 0.25f) 1 else 0 }
        if (!init) {
            // First status after connecting: remember the current state without announcing it.
            init = true
            fuel30 = f < 0.30f; fuel15 = f < 0.15f; fuel7 = f < 0.07f
            for (i in 0..3) bands[i] = band(wear[i])
            near5 = t.routeKm in 0.1f..5f
            tripKey = t.destination + "|" + t.cargo
            lastMarkKm = t.routeKm
            briefPending = true
            prevWear = maxOf(t.wearChassis, t.wearEngine, t.wearWheels)
            return
        }
        if (Speaker.autoInfo) trip(t) else { tripKey = t.destination + "|" + t.cargo; prevWear = maxOf(t.wearChassis, t.wearEngine, t.wearWheels) }
        if (f > 0.35f) { fuel30 = false; fuel15 = false; fuel7 = false }
        if (f < 0.30f && !fuel30) { fuel30 = true; Speaker.alert("Yakıt yüzde 30'un altına düştü, benzin bitiyor. En yakın benzin istasyonuna git.") }
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
        "info:auto_off" to listOf("otomatik bilgiyi kapat", "otomatik bilgileri kapat", "otomatik bilgilendirmeyi kapat", "yol bilgisini kapat"),
        "info:auto_on" to listOf("otomatik bilgiyi aç", "otomatik bilgileri aç", "otomatik bilgilendirmeyi aç", "yol bilgisini aç"),
    )

    fun answer(id: String, t: TruckStatus?): String {
        if (id == "info:weather") return "Oyundan hava durumu bilgisi alınamıyor."
        if (id == "info:auto_off") { Speaker.updateAutoInfo(false); return "Otomatik bilgilendirme kapatıldı." }
        if (id == "info:auto_on") { Speaker.updateAutoInfo(true); return "Otomatik bilgilendirme açıldı." }
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
