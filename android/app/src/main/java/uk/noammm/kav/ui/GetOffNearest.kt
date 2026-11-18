package uk.noammm.kav.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uk.noammm.kav.ActiveJourney
import uk.noammm.kav.data.Moovit

/** Kav Beta: on a bus, "get off where it passes closest to where you're going, then walk or bike". */
const val FLAG_GET_OFF_NEAREST = "get_off_nearest"

/**
 * Looks along the rest of the bus you're on for the stop physically closest to your destination,
 * and works out when you'd get there walking or cycling from it. Taking it replaces the rest of the
 * trip (any transfers) with this bus to that stop, then a walk or a ride.
 */
internal object GetOffNearest {
    // Walking ~4.5 km/h, cycling (city bike or scooter, with lights and crossings) ~15 km/h.
    private const val WALK_MS = 1.25
    private const val BIKE_MS = 4.2
    // A straight line understates the way there; streets add about a third.
    private const val DETOUR = 1.3

    val on: Boolean get() = uk.noammm.kav.Beta.flag(FLAG_GET_OFF_NEAREST)

    class Pick(
        val stop: Moovit.StopInfo,
        /** Stops past the one you were going to get off at (negative: before it). */
        val vsPlanned: Int,
        val busAt: Long,
        val path: List<Pair<Double, Double>>,
        val metres: Int,
        val walkSec: Long,
        val walkReal: Boolean,
        val planned: Long,
        /** True when the bus time there is Moovit's live prediction, false when Kav estimated it. */
        val busLive: Boolean = false,
        val ride: Moovit.Leg,
        val rideStops: List<Int>,
        val rideShape: List<Pair<Double, Double>>,
        val dest: Pair<Double, Double>,
        val stops: Map<Int, Moovit.StopInfo>,
    ) {
        val bikeSec get() = (metres / BIKE_MS).toLong()
        val walkArr get() = busAt + walkSec
        val bikeArr get() = busAt + bikeSec
        val alreadyPlanned get() = vsPlanned == 0
    }

    suspend fun find(j: ActiveJourney, steps: List<Step>, index: Int, fix: Fix?, now: Long): Pick? = withContext(Dispatchers.IO) {
        val step = steps.getOrNull(index) as? Step.Ride ?: return@withContext null
        val ride = boardingChoice(step.ride, step.wait, j.chosen[step.legIndex] ?: 0).first
        val r = j.resolved
        val dest = j.trip.legs.lastOrNull { it.shape.isNotEmpty() }?.shape?.lastOrNull() ?: return@withContext null
        val s = Online.open(fix?.let { it.lat to it.lon })
        val bus = myBus(ride, fix, now, r) ?: r.live[Moovit.ArrivalKey(ride.toStop, ride.tripId)] ?: r.arrival(ride)
        val pattern = bus?.patternId?.takeIf { it > 0 }?.let { Moovit.tripPattern(s, it) }.orEmpty()
            .ifEmpty { ride.stops }
        if (pattern.size < 2) return@withContext null

        // Stops still ahead: from the bus's next stop (or after where you boarded) to the end of the line.
        val boardIdx = pattern.indexOf(ride.fromStop).coerceAtLeast(0)
        val nextIdx = bus?.nextStopIndex?.takeIf { it in pattern.indices && it > boardIdx } ?: (boardIdx + 1)
        val ahead = pattern.drop(nextIdx).distinct()
        if (ahead.isEmpty()) return@withContext null
        val known = HashMap(r.stops)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(8)
        try {
            ahead.filter { it !in known }.map { id -> id to pool.submit<Moovit.StopInfo?> { Moovit.stopInfo(s, id) } }
                .forEach { (id, f) -> runCatching { f.get() }.getOrNull()?.let { known[id] = it } }
        } finally { pool.shutdown() }
        val best = ahead.mapNotNull { id -> known[id]?.takeIf { it.point != null } }
            .minByOrNull { st -> metres(st.lat, st.lon, dest.first, dest.second) } ?: return@withContext null
        val bestIdx = pattern.indexOf(best.id)
        val plannedIdx = pattern.indexOf(ride.toStop).takeIf { it >= 0 } ?: (bestIdx)

        // When the bus gets there: Moovit's prediction if it's a stop Kav asks about, else scaled
        // from the prediction at your planned stop by distance along the route (or stop count).
        val plannedEta = rideEta(ride, fix, now, r) ?: ride.arr
        val route = bus?.tripShapeId?.takeIf { it > 0 }?.let { id ->
            r.shapes[id] ?: Moovit.cachedShape(id).ifEmpty { runCatching { Moovit.tripShape(s, id) }.getOrDefault(emptyList()) }
        }.orEmpty()
        // Kav only follows your boarding and get-off stops live, so ask Moovit about this one too.
        val tripId = bus?.tripId ?: ride.tripId
        val liveThere = r.live[Moovit.ArrivalKey(best.id, tripId)]
            ?: runCatching { Moovit.stopArrivals(s, listOf(best.id)).first[Moovit.ArrivalKey(best.id, tripId)] }.getOrNull()
        val predicted = liveThere?.takeIf { !it.rtDropped }?.rtUtc?.takeIf { it > now }
        val busAt = predicted
            ?: if (route.size >= 2 && best.point != null) {
                val me = fix?.takeIf { it.isFresh(now) }?.let { alongPath(it.lat, it.lon, route) }
                    ?: bus?.takeIf { it.hasLocation }?.let { alongPath(it.lat, it.lon, route) } ?: 0.0
                val toPlanned = r.stop(ride.toStop)?.point?.let { alongPath(it.first, it.second, route) } ?: pathLength(route)
                val toBest = alongPath(best.lat, best.lon, route)
                val k = if (toPlanned - me > 50) (toBest - me) / (toPlanned - me) else 1.0
                now + ((plannedEta - now).coerceAtLeast(60) * k.coerceAtLeast(0.0)).toLong()
            } else {
                val k = (bestIdx - nextIdx + 1).toDouble() / (plannedIdx - nextIdx + 1).coerceAtLeast(1)
                now + ((plannedEta - now).coerceAtLeast(60) * k).toLong()
            }

        // The way on from that stop: Moovit's walking route if it gives one, else a straight line.
        val from = best.point!!
        val walk = runCatching {
            Moovit.planItineraries(s, from, dest, busAt * 1000, Moovit.TIME_DEPARTURE).itineraries
                .firstOrNull { t -> t.legs.all { it.kind == Moovit.LegKind.WALK } && t.legs.any { it.shape.size >= 2 } }
        }.getOrNull()
        val straight = metres(from.first, from.second, dest.first, dest.second)
        val path = walk?.legs?.flatMap { it.shape }?.takeIf { it.size >= 2 } ?: listOf(from, dest)
        val m = walk?.legs?.sumOf { it.meters }?.takeIf { it > 0 } ?: (straight * DETOUR).toInt()
        val walkSec = walk?.let { (it.arr - it.dep).takeIf { d -> d > 0 } } ?: (m / WALK_MS).toLong()

        val rideStops = run {
            val a = pattern.indexOf(ride.fromStop)
            if (a in 0..bestIdx) pattern.subList(a, bestIdx + 1) else ride.stops.takeWhile { it != best.id } + best.id
        }
        val rideShape = if (route.size >= 2) {
            val board = r.stop(ride.fromStop)?.point ?: ride.shape.firstOrNull()
            val tail = board?.let { splitPath(route, it.first, it.second).second } ?: route
            splitPath(tail, best.lat, best.lon).first
        } else ride.shape
        val planned = liveArrival(steps, index, j, fix, now).first
        Pick(best, bestIdx - plannedIdx, busAt, path, m, walkSec, walk != null, planned, predicted != null,
            ride, rideStops, rideShape, dest, known)
    }

    /** The trip from here on: this bus to the stop, then walking ([bike] false) or cycling. */
    fun trip(p: Pick, bike: Boolean): Pair<Moovit.Itinerary, (Moovit.Resolved) -> Moovit.Resolved> {
        val ride = p.ride.copy(
            toStop = p.stop.id, arr = p.busAt, stops = p.rideStops,
            shape = p.rideShape.ifEmpty { p.ride.shape }, alternatives = emptyList(), nextDeps = emptyList(),
        )
        val end = if (bike) p.bikeArr else p.walkArr
        val last = Moovit.Leg(
            if (bike) Moovit.LegKind.BIKE else Moovit.LegKind.WALK,
            dep = p.busAt, arr = end, fromStop = p.stop.id, meters = p.metres, shape = p.path,
        )
        val t = Moovit.Itinerary(
            guid = "nearest-${p.stop.id}-${if (bike) "bike" else "walk"}", group = 0,
            legs = listOf(ride, last), dep = ride.dep, arr = end,
        )
        return t to { r ->
            Moovit.Resolved(r.lines, r.stops + p.stops, r.routeTypes, r.live, r.shapes, r.pollSecs, r.patterns)
        }
    }
}

/** Kav Beta: the ride card's "get off closest to where you're going" button and what it found. */
@androidx.compose.runtime.Composable
internal fun GetOffNearestPanel(j: ActiveJourney, steps: List<Step>, index: Int, fix: Fix?, now: Long) {
    var pick by androidx.compose.runtime.remember(j.trip) { androidx.compose.runtime.mutableStateOf<GetOffNearest.Pick?>(null) }
    var busy by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var failed by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val hm = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
    fun at(t: Long) = hm.format(java.util.Date(t * 1000))
    fun mins(sec: Long) = ((sec + 59) / 60).coerceAtLeast(1)

    @androidx.compose.runtime.Composable
    fun Pill(label: String, strong: Boolean, onClick: () -> Unit) = androidx.compose.material3.Text(
        label, fontSize = 13.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
        color = if (strong) K.onAccent else K.text,
        modifier = androidx.compose.ui.Modifier
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(999.dp))
            .background(if (strong) K.accent else K.surface4)
            .clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )

    androidx.compose.foundation.layout.Column(
        androidx.compose.ui.Modifier.padding(top = K.gap2),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(K.gap2),
    ) {
        val p = pick
        if (p == null) {
            Pill(
                if (busy) T("Looking along the route…", "מחפשים לאורך המסלול…")
                else T("Get off closest to where you're going", "לרדת הכי קרוב ליעד"),
                strong = false,
            ) {
                if (busy) return@Pill
                busy = true; failed = false
                scope.launch {
                    pick = try { GetOffNearest.find(j, steps, index, fix, System.currentTimeMillis() / 1000) }
                        catch (e: kotlinx.coroutines.CancellationException) { throw e }
                        catch (e: Exception) { android.util.Log.w("KavNearest", "find failed", e); null }
                    busy = false
                    failed = pick == null
                    uk.noammm.kav.Beta.log("nearest_find", "found" to (pick != null), "stop" to pick?.stop?.id,
                        "vsPlanned" to pick?.vsPlanned, "metres" to pick?.metres, "walkReal" to pick?.walkReal,
                        "busAt" to pick?.busAt, "busLive" to pick?.busLive, "walkArr" to pick?.walkArr, "bikeArr" to pick?.bikeArr, "planned" to pick?.planned)
                }
            }
            if (failed) androidx.compose.material3.Text(
                T("Couldn't work out the stops ahead.", "לא הצלחנו לחשב את התחנות שבהמשך."), fontSize = 12.sp, color = K.dim,
            )
            return@Column
        }
        val where = when {
            p.vsPlanned == 0 -> T("Your stop is already the closest one.", "התחנה שלכם כבר הכי קרובה.")
            p.vsPlanned > 0 -> T("${p.stop.name}: ${p.vsPlanned} stops after yours", "${p.stop.name}: ${p.vsPlanned} תחנות אחרי שלכם")
            else -> T("${p.stop.name}: ${-p.vsPlanned} stops before yours", "${p.stop.name}: ${-p.vsPlanned} תחנות לפני שלכם")
        }
        androidx.compose.material3.Text(where, fontSize = 14.sp, color = K.text)
        androidx.compose.material3.Text(
            T("Bus there " + (if (p.busLive) "" else "~") + "${at(p.busAt)}, then ${p.metres} m" + (if (p.walkReal) "" else " (roughly)") + ":\n" +
                "walk ~${mins(p.walkSec)} min, arrive ${at(p.walkArr)} · bike ~${mins(p.bikeSec)} min, arrive ${at(p.bikeArr)}\n" +
                "Your plan: arrive ${at(p.planned)}",
                "האוטובוס שם " + (if (p.busLive) "" else "~") + "${at(p.busAt)}, ואז ${p.metres} מ׳" + (if (p.walkReal) "" else " (בערך)") + ":\n" +
                "הליכה ~${mins(p.walkSec)} דק׳, הגעה ${at(p.walkArr)} · אופניים ~${mins(p.bikeSec)} דק׳, הגעה ${at(p.bikeArr)}\n" +
                "התוכנית שלכם: הגעה ${at(p.planned)}"),
            fontSize = 12.sp, color = K.dim, lineHeight = 17.sp,
        )
        androidx.compose.foundation.layout.Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(K.gap2)) {
            fun take(bike: Boolean) {
                val (trip, resolve) = GetOffNearest.trip(p, bike)
                uk.noammm.kav.Beta.log("nearest_switch", "stop" to p.stop.id, "bike" to bike, "arr" to trip.arr, "planned" to p.planned)
                uk.noammm.kav.Reroute.switchTo = uk.noammm.kav.Reroute.Offer(
                    trip, resolve(j.resolved), p.planned - trip.arr, urgent = false,
                    headline = if (bike) T("Bike from ${p.stop.name}", "אופניים מ${p.stop.name}") else T("Walk from ${p.stop.name}", "הליכה מ${p.stop.name}"),
                    detail = "", sig = trip.guid, toLabel = j.toLabel,
                )
            }
            Pill(T("Walk from there", "ללכת משם"), strong = true) { take(bike = false) }
            Pill(T("Bike from there", "אופניים משם"), strong = true) { take(bike = true) }
            Pill(T("Never mind", "לא משנה"), strong = false) { pick = null }
        }
    }
}
