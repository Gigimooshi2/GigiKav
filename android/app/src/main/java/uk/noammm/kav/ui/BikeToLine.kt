package uk.noammm.kav.ui

import uk.noammm.kav.data.Moovit
import uk.noammm.kav.data.MoovitSession
import uk.noammm.kav.data.Net

/** Kav Beta: results also offer "cycle to the nearest stop of a line that gets you there, then ride". */
const val FLAG_BIKE_TO_LINE = "bike_to_line"

/**
 * Two sources of lines that get you there:
 *  - Kav's own timetable (the Ministry of Transport's): every line, trains included, that stops within
 *    [DEST_M] of where you're going, and its stop nearest to you before that.
 *  - Moovit, for lines that timetable doesn't have, such as the cities' weekend Na'im lines: the
 *    lines serving the stops near your destination right now, and the point of each line's route
 *    nearest to you before them (boarding at the regular stop there).
 * A boarding stop must be further than a walk ([MIN_M]) and within a ride ([MAX_M]). Times come from
 * Moovit, planned from that stop, leaving when you'd get there by bike.
 */
internal object BikeToLine {
    private const val DEST_M = 500.0
    private const val MIN_M = 500.0
    private const val MAX_M = 4000.0
    // City bike or scooter ~15 km/h; streets add about a third to the straight line; a minute to park.
    private const val BIKE_MS = 4.2
    private const val DETOUR = 1.3
    private const val PARK_S = 60L
    private const val PLAN_TOP = 5
    private const val KEEP = 3
    private const val DEST_STOPS = 4
    private const val MOOVIT_LINES = 10

    /** Where to get on: [line] is the timetable's number ("#n" for a train route), or Moovit's [lineId]. */
    class Candidate(
        val line: String,
        val lineId: Int,
        val point: Pair<Double, Double>,
        val name: String,
        val metres: Double,
    )

    /** From the timetable: the nearest boarding stop of each line that reaches [to]. */
    fun candidates(net: Net, from: Pair<Double, Double>, to: Pair<Double, Double>): List<Candidate> {
        val nS = net.nStops
        val nearDest = BooleanArray(nS)
        val fromDist = DoubleArray(nS) { -1.0 }
        for (s in 0 until nS) {
            if (metres(net.lat[s], net.lon[s], to.first, to.second) <= DEST_M) nearDest[s] = true
            val d = metres(net.lat[s], net.lon[s], from.first, from.second)
            if (d <= MAX_M) fromDist[s] = d
        }
        val best = HashMap<String, Pair<Int, Double>>()
        val seen = HashSet<Long>()
        for (t in 0 until net.tripRoute.size) {
            val a = net.tripStart[t]; val z = net.tripStart[t + 1]
            if (z - a < 2) continue
            // Trips of a line mostly share their stops; look at each pattern once.
            val key = (net.tripRoute[t].toLong() shl 34) xor (net.stStop[a].toLong() shl 17) xor
                net.stStop[z - 1].toLong() xor ((z - a).toLong() shl 54)
            if (!seen.add(key)) continue
            var board = -1; var boardD = Double.MAX_VALUE
            for (i in a until z) {
                val s = net.stStop[i]
                if (nearDest[s]) {
                    if (board >= 0) {
                        // Trains have no number in the timetable (Moovit shows the train's own number):
                        // each train route stands for itself.
                        val r = net.tripRoute[t]
                        val line = net.rShort.getOrElse(r) { "" }.ifBlank { "#$r" }
                        val cur = best[line]
                        if (cur == null || boardD < cur.second) best[line] = board to boardD
                    }
                    break
                }
                val d = fromDist[s]
                if (d >= 0 && d < boardD) { board = s; boardD = d }
            }
        }
        return best.map { (line, b) ->
            Candidate(line, -1, net.lat[b.first] to net.lon[b.first], net.name.getOrElse(b.first) { "" }, b.second)
        }
    }

    /** From Moovit: lines serving the stops near [to] now (Na'im included), boarded nearest to [from]. */
    suspend fun moovitCandidates(s: MoovitSession, net: Net, from: Pair<Double, Double>, to: Pair<Double, Double>): List<Candidate> {
        val near = (0 until net.nStops)
            .map { it to metres(net.lat[it], net.lon[it], to.first, to.second) }
            .filter { it.second <= DEST_M }.sortedBy { it.second }.take(DEST_STOPS)
        val ids = near.mapNotNull { (stop, _) -> StopPhotos.idOf(net, stop)?.let { it to stop } }
        if (ids.isEmpty()) return emptyList()
        val arrivals = runCatching { Moovit.stopArrivals(s, ids.map { it.first }).first.values }.getOrDefault(emptyList())
        val destPoint = ids.associate { (id, stop) -> id to (net.lat[stop] to net.lon[stop]) }
        val out = ArrayList<Candidate>()
        for (a in arrivals.filter { it.tripShapeId > 0 }.distinctBy { it.tripShapeId }.take(MOOVIT_LINES)) {
            val shape = Moovit.cachedShape(a.tripShapeId).ifEmpty {
                runCatching { Moovit.tripShape(s, a.tripShapeId) }.getOrDefault(emptyList())
            }
            val at = destPoint[a.stopId] ?: continue
            if (shape.size < 2) continue
            // The route up to the stop near your destination; its point nearest you.
            val upTo = shape.indices.minBy { metres(shape[it].first, shape[it].second, at.first, at.second) }
            if (upTo < 1) continue
            val i = (0..upTo).minBy { metres(shape[it].first, shape[it].second, from.first, from.second) }
            val p = shape[i]
            val d = metres(p.first, p.second, from.first, from.second)
            if (d > MAX_M) continue
            // Board at the regular stop there (Na'im uses them), else at the route itself.
            val stop = (0 until net.nStops).minByOrNull { metres(net.lat[it], net.lon[it], p.first, p.second) }
                ?.takeIf { metres(net.lat[it], net.lon[it], p.first, p.second) <= 120 }
            val point = stop?.let { net.lat[it] to net.lon[it] } ?: p
            out.add(Candidate("", a.lineId, point, stop?.let { net.name.getOrElse(it) { "" } }.orEmpty(),
                metres(point.first, point.second, from.first, from.second)))
        }
        return out
    }

    /** Bike-then-ride itineraries worth showing next to [normal], best first. */
    suspend fun options(
        s: MoovitSession,
        net: Net,
        from: Pair<Double, Double>,
        to: Pair<Double, Double>,
        startSec: Long,
        routeTypes: List<Int>,
        normal: List<Moovit.Itinerary>,
    ): List<Moovit.Itinerary> {
        val fromMoovit = runCatching { moovitCandidates(s, net, from, to) }.getOrElse {
            uk.noammm.kav.Beta.log("bike_line", "moovitError" to (it.message ?: it.javaClass.simpleName)); emptyList()
        }
        // Within a walk, the normal results already have it. One per spot: Moovit's plan from a stop
        // already weighs every line there.
        val all = (candidates(net, from, to) + fromMoovit).filter { it.metres > MIN_M }.sortedBy { it.metres }
        val found = ArrayList<Candidate>()
        for (c in all) {
            if (found.none { metres(it.point.first, it.point.second, c.point.first, c.point.second) < 150 }) found.add(c)
            if (found.size >= PLAN_TOP) break
        }
        val bestNormal = normal.minOfOrNull { it.arr } ?: Long.MAX_VALUE
        val out = ArrayList<Moovit.Itinerary>()
        for (c in found) {
            val bikeM = c.metres * DETOUR
            val reach = startSec + (bikeM / BIKE_MS).toLong()
            val plan = runCatching {
                Moovit.planItineraries(s, c.point, to, (reach + PARK_S) * 1000, Moovit.TIME_DEPARTURE, routeTypes).itineraries
            }.getOrElse {
                uk.noammm.kav.Beta.log("bike_line", "stop" to c.name, "error" to (it.message ?: it.javaClass.simpleName))
                emptyList()
            }
            // From there: the line itself if Moovit offers it, else its quickest way on.
            val pick = plan.filter { it.rides.isNotEmpty() }.let { list ->
                list.filter { t ->
                    val first = t.rides.first()
                    (c.lineId > 0 && c.lineId in first.lineChoices) || (c.line.isNotBlank() && !c.line.startsWith("#") && first.shortName == c.line)
                }.minByOrNull { it.arr } ?: list.minByOrNull { it.arr }
            }
            uk.noammm.kav.Beta.log(
                "bike_line", "line" to c.line.takeUnless { it.isBlank() || it.startsWith("#") }, "lineId" to c.lineId.takeIf { it > 0 },
                "src" to if (c.lineId > 0) "moovit" else "timetable", "stop" to c.name, "metres" to c.metres.toInt(),
                "found" to (pick != null), "first" to pick?.rides?.firstOrNull()?.shortName, "arr" to pick?.arr,
                "vsBest" to pick?.arr?.let { if (bestNormal == Long.MAX_VALUE) null else it - bestNormal },
            )
            if (pick == null) continue
            val bike = Moovit.Leg(Moovit.LegKind.BIKE, dep = startSec, arr = reach, meters = bikeM.toInt(), shape = listOf(from, c.point))
            out.add(Moovit.Itinerary(
                guid = "bike-${c.point.first}-${c.point.second}", group = 0, legs = listOf(bike) + pick.legs,
                dep = startSec, arr = pick.arr, fare = pick.fare, currency = pick.currency,
            ))
        }
        // One per first line, and only ones that could make sense (not far behind the best normal route).
        return out.filter { bestNormal == Long.MAX_VALUE || it.arr <= bestNormal + 15 * 60 }
            .distinctBy { t -> t.rides.firstOrNull()?.lineId }
            .sortedBy { it.arr }
            .take(KEEP)
    }
}
