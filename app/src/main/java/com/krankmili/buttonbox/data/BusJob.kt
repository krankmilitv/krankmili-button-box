package com.krankmili.buttonbox.data

import org.json.JSONObject
import java.util.Locale

/** One leg of a Coaches DLC bus job: from a source stop to a destination stop. Times are minutes of the game day. */
data class BusStop(
    val from: String,
    val to: String,
    val departMin: Int,
    val arriveMin: Int,
    val boarding: Int,
    val leaving: Int,
)

/** Active bus job as reported by the PC plugin (attributes of the game's "bus_job" configuration). */
data class BusJob(
    val current: Int,
    val plannedKm: Int,
    val gameMin: Int,
    val stops: List<BusStop>,
) {
    val key: String get() = stops.joinToString(">") { it.from + "-" + it.to } + "@" + (stops.firstOrNull()?.departMin ?: 0)
    val now: BusStop? get() = stops.getOrNull(current.coerceIn(0, (stops.size - 1).coerceAtLeast(0)))
    val isLast: Boolean get() = current >= stops.size - 1
    /** Passengers on board while driving leg [i]: everyone who boarded up to it minus those who left before it. */
    fun onBoard(i: Int): Int = (0..i.coerceIn(0, stops.size - 1)).sumOf { stops[it].boarding } - (0 until i.coerceIn(0, stops.size - 1)).sumOf { stops[it].leaving }

    companion object {
        /** Reads {"gameTime":n,"job":{"source_stop.name[0]":"Prag",...}} sent by the server. */
        /** End-of-job event the plugin saw ("bus_job.cancelled", "bus_job.completed", …), or "" when none. */
        fun eventId(o: JSONObject?): String = o?.optJSONObject("event")?.optString("id").orEmpty()

        /** Passenger satisfaction in percent from the completion event, or -1 when it is not there. */
        fun satisfaction(o: JSONObject?): Int {
            val v = o?.optJSONObject("event")?.optJSONObject("a")?.optDouble("passenger.satisfaction", -1.0) ?: -1.0
            if (v < 0) return -1
            return (if (v <= 1.0) v * 100 else v).toInt().coerceIn(0, 100)
        }

        fun parse(o: JSONObject?): BusJob? {
            val job = o?.optJSONObject("job") ?: return null
            val count = job.optInt("bus.stages.count", 1).coerceIn(1, 32)
            val stops = List(count) { i ->
                BusStop(
                    from = job.optString("source_stop.name[$i]"),
                    to = job.optString("destination_stop.name[$i]"),
                    departMin = job.optInt("source_stop.departure_time.planned[$i]", -1),
                    arriveMin = job.optInt("destination_stop.arrival_time.planned[$i]", -1),
                    boarding = job.optInt("source_stop.entering_passengers.planned[$i]", 0),
                    leaving = job.optInt("destination_stop.leaving_passengers.planned[$i]", 0),
                )
            }.filter { it.to.isNotBlank() }
            if (stops.isEmpty()) return null
            return BusJob(
                current = job.optInt("bus.stages.current", 0),
                plannedKm = job.optInt("planned_distance.km", 0),
                gameMin = o.optInt("gameTime", -1),
                stops = stops,
            )
        }
    }
}

fun clockText(min: Int): String = if (min < 0) "" else "%02d:%02d".format(Locale.ROOT, (min / 60) % 24, min % 60)
