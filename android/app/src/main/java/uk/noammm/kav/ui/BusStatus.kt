package uk.noammm.kav.ui

import androidx.compose.ui.graphics.Color
import uk.noammm.kav.data.Moovit
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Beta switch for the states below. Off: Kav shows buses the way it always has. */
const val FLAG_BUS_STATUS = "bus_status"

enum class BusState { LIVE, LIVE_AT_START, SCHEDULED, NOT_SEEN, CANCELLED }

/** [label] for the step card; [short] for the floating window and notification, where space is tight. */
class BusStatus(val state: BusState, val label: String, val short: String, val tint: Color)

private val hm get() = SimpleDateFormat("HH:mm", Locale.US)

/**
 * What we actually know about the bus you're waiting for. Moovit marks a trip that hasn't started
 * with a made-up bus parked at its first stop; that is the timetable, not a bus, so it is told apart
 * here from a real bus reporting its own GPS:
 *
 *  - LIVE           a real bus, on its way.
 *  - LIVE_AT_START  a real bus still at its first stop (green: the bus exists and is about to go).
 *  - SCHEDULED      only the timetable so far, and the trip isn't cancelled.
 *  - NOT_SEEN       the trip should already have left its first stop and no real bus has shown up.
 *  - CANCELLED      Moovit says this trip won't run here.
 */
fun busStatus(a: Moovit.Arrival?, now: Long): BusStatus? {
    if (a == null) return null
    val start = a.tripStartUtc
    fun due() = if (start > 0) hm.format(Date(start * 1000)) else null
    if (a.status == 3) return BusStatus(BusState.CANCELLED, T("Cancelled", "בוטל"), T("Cancelled", "בוטל"), K.critical)
    if (a.realBus) {
        val atStart = a.vehicleStatus == 3 || (a.nextStopIndex in 0..1 && a.toNextPct in 0..5)
        if (atStart) {
            val late = if (start > 0) ((now - start) / 60).toInt() else 0
            val tail = if (late >= 1) T(" · due out $late min ago", " · היה אמור לצאת לפני $late דק׳") else ""
            return BusStatus(BusState.LIVE_AT_START, T("Live · at first stop", "בזמן אמת · בתחנה הראשונה") + tail,
                T("Live at first stop", "בזמן אמת בתחנה הראשונה"), K.live)
        }
        val away = a.stopsAway
        val tail = if (away > 0) T(" · $away stops away", " · במרחק $away תחנות") else ""
        return BusStatus(BusState.LIVE, T("Live", "בזמן אמת") + tail, T("Live", "בזמן אמת"), K.live)
    }
    // No real bus yet: just the timetable (a parked placeholder, or no position at all).
    if (start > 0 && now > start + 60) {
        return BusStatus(BusState.NOT_SEEN, T("Not seen yet · should have left ${due()}", "עוד לא נראה · היה אמור לצאת ב-${due()}"),
            T("Not seen yet", "עוד לא נראה"), K.problem)
    }
    return BusStatus(
        BusState.SCHEDULED,
        T("Scheduled · not cancelled", "מתוכנן · לא בוטל") + (due()?.let { T(" · leaves first stop $it", " · יוצא מהתחנה הראשונה ב-$it") } ?: ""),
        T("Scheduled", "מתוכנן"), K.dim,
    )
}

/** Kav Beta log: Moovit's live record for every bus of the trip, and the state Kav made of it. */
internal fun logLive(journey: uk.noammm.kav.ActiveJourney, now: Long) {
    if (!uk.noammm.kav.Beta.on) return
    val r = journey.resolved
    val rides = journey.trip.legs.filter { it.kind == Moovit.LegKind.RIDE }.flatMap { it.options }
    for (leg in rides) for (stop in listOf(leg.fromStop, leg.toStop)) {
        val a = r.live[Moovit.ArrivalKey(stop, leg.tripId)] ?: continue
        val st = busStatus(a, now)
        uk.noammm.kav.Beta.log(
            "live", "line" to (leg.shortName.ifBlank { r.line(leg.lineId)?.number.orEmpty() }), "trip" to leg.tripId,
            "at" to if (stop == leg.fromStop) "board" else "alight", "stop" to stop,
            "veh" to a.vehicleId, "src" to a.locSource, "vs" to a.vehicleStatus, "lat" to a.lat, "lon" to a.lon,
            "sampleAge" to if (a.sampleUtc > 0) now - a.sampleUtc else null,
            "stopIdx" to a.stopIndex, "next" to a.nextStopIndex, "pct" to a.toNextPct,
            "sched" to a.staticUtc, "rt" to a.rtUtc, "start" to a.tripStartUtc, "cert" to a.certainty, "status" to a.status,
            "state" to st?.state?.name, "shown" to (uk.noammm.kav.Beta.flag(FLAG_BUS_STATUS) && st != null),
            "raw" to Moovit.rawArrivals[Moovit.ArrivalKey(stop, leg.tripId)],
        )
    }
}
