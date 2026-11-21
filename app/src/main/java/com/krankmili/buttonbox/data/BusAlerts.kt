package com.krankmili.buttonbox.data

/**
 * Spoken bus-job information (Coaches DLC): trip start, next stop and passengers, approaching a stop, trip end.
 * The stop and passenger numbers come from the game's "bus_job" configuration through krankmili-bus.dll.
 * Assumptions (verified only on a single-stage job): bus.stages.current is the leg being driven and the navigation
 * distance (routeKm) is the distance to that leg's destination stop.
 */
class BusAlerts {
    private var key = ""
    private var stage = -1
    private var near5 = false
    private var near1 = false
    private var startPending = false
    private var hadJob = false

    private fun times(s: BusStop): String {
        val a = clockText(s.departMin); val b = clockText(s.arriveMin)
        return if (a.isNotEmpty() && b.isNotEmpty()) "Kalkış $a, varış $b. " else ""
    }

    private fun route(b: BusJob): String =
        if (b.stops.size == 1) "${b.stops[0].from} çıkışlı, ${b.stops[0].to} durağına gidiyoruz."
        else "Duraklar: ${(listOf(b.stops[0].from) + b.stops.map { it.to }).joinToString(", ")}."

    /** Sentence block for the start briefing. */
    fun briefing(b: BusJob): String {
        val first = b.stops.first()
        return "Otobüs seferi. ${route(b)} ${first.boarding} yolcuyla yola çıkıyoruz. ${times(first)}" +
            (if (b.plannedKm > 0) "Yaklaşık ${b.plannedKm} kilometre. " else "") +
            nextStop(b)
    }

    private fun nextStop(b: BusJob): String {
        val s = b.now ?: return ""
        val leave = (if (s.leaving > 0) " ${s.leaving} yolcu inecek." else "") +
            (b.stops.getOrNull(b.current + 1)?.let { if (it.boarding > 0) " ${it.boarding} yolcu binecek." else "" } ?: "")
        return (if (b.isLast) "Son durak ${s.to}." else "Sıradaki durak ${s.to}.") + leave
    }

    /** Answer to "durak bilgisi" / "yolcu bilgisi". */
    fun describe(b: BusJob, routeKm: Float): String {
        val s = b.now ?: return "Otobüs seferi bilgisi yok."
        val km = if (routeKm >= 1f) " Yaklaşık ${routeKm.toInt()} kilometre kaldı." else ""
        val more = if (b.stops.size > 1) " Toplam ${b.stops.size} bölümlük sefer, şu an ${b.current + 1}. bölümdesin." else ""
        return nextStop(b) + km + " ${times(s)}Şu an araçta ${b.onBoard(b.current)} yolcu var.$more"
    }

    private fun endText(t: TruckStatus): String = when {
        t.busEvent.contains("cancel") -> "Otobüs seferi iptal edildi."
        t.busEvent.contains("complet") || t.busEvent.contains("deliver") ->
            "Sefer tamamlandı." + (if (t.busSatisfaction >= 0) " Yolcu memnuniyeti yüzde ${t.busSatisfaction}." else "")
        else -> "Otobüs seferi sona erdi."
    }

    /** Remember the current state so nothing is announced for it (first status, menus, parked). */
    fun sync(t: TruckStatus, started: Boolean = false) {
        val b = t.bus
        key = b?.key ?: ""
        stage = b?.current ?: -1
        near5 = t.routeKm in 0.1f..5f
        near1 = t.routeKm in 0.1f..1f
        hadJob = b != null
        startPending = b != null && !started
    }

    fun check(t: TruckStatus) {
        val b = t.bus
        if (b == null) {
            if (hadJob) {
                hadJob = false; key = ""
                if (Speaker.autoInfo) Speaker.inform(endText(t))
            }
            return
        }
        if (b.key != key) {
            key = b.key; stage = b.current; near5 = false; near1 = false; hadJob = true; startPending = true
        }
        if (startPending) {
            if (t.speedKmh > 5f) { startPending = false; if (Speaker.autoInfo) Speaker.inform("Sefer başladı. " + briefing(b)) }
            return
        }
        if (b.current != stage) {
            stage = b.current; near5 = false; near1 = false
            if (Speaker.autoInfo) Speaker.inform(nextStop(b))
            return
        }
        val km = t.routeKm
        if (km > 6f) near5 = false
        if (km > 1.5f) near1 = false
        val s = b.now ?: return
        if (km in 1f..5f && !near5) {
            near5 = true
            Speaker.alert("Durağa yaklaşıyoruz. ${s.to}, yaklaşık ${km.toInt()} kilometre.${if (s.leaving > 0) " ${s.leaving} yolcu inecek." else ""}${b.stops.getOrNull(b.current + 1)?.let { if (it.boarding > 0) " ${it.boarding} yolcu binecek." else "" } ?: ""}")
        } else if (km in 0.1f..1f && !near1) {
            near1 = true; near5 = true
            Speaker.alert("${s.to} durağına bir kilometreden az kaldı, yavaşla.")
        }
    }
}
