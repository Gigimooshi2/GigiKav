package uk.noammm.kav.ui

import uk.noammm.kav.data.Moovit
import uk.noammm.kav.data.MoovitSession
import uk.noammm.kav.data.Net

/** Kav Beta: results also offer "cycle to the nearest stop of a line that gets you there, then ride". */
const val FLAG_BIKE_TO_LINE = "bike_to_line"

/**
 * Lines and stops come from Kav's own timetable (the bundled network): every line that stops within
 * [DEST_M] of where you're going, and for each, its stop nearest to you that comes before that on
 * the way there, further than a walk ([MIN_M]) but within a ride ([MAX_M]). Times come from Moovit:
 * planned from that stop, leaving when you'd get there by bike.
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

    class Candidate(val line: String, val stop: Int, val metres: Double)

    /** The nearest boarding stop of each line that reaches [to], closest to [from] first. */
    fun candidates(net: Net, from: Pair<Double, Double>, to: Pair<Double, Double>): List<Candidate> {
        val nS = net.nStops
        val nearDest = BooleanArray(nS)
        val fromDist = DoubleArray(nS) { -1.0 }
        for (s in 0 until nS) {
            if (metres(net.lat[s], net.lon[s], to.first, to.second) <= DEST_M) nearDest[s] = true
            val d = metres(net.lat[s], net.lon[s], from.first, from.second)
            if (d <= MAX_M) fromDist[s] = d
        }
        val best = HashMap<String, Candidate>()
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
                        if (cur == null || boardD < cur.metres) best[line] = Candidate(line, board, boardD)
                    }
                    break
                }
                val d = fromDist[s]
                if (d >= 0 && d < boardD) { board = s; boardD = d }
            }
        }
        // Within a walk, the normal results already have it. One per stop: Moovit's plan from a stop
        // already weighs every line there.
        return best.values.filter { it.metres > MIN_M }.sortedBy { it.metres }.distinctBy { it.stop }
    }

    /** Bike-then-ride itineraries worth showing next to [normal], best first. */
    fun options(
        s: MoovitSession,
        net: Net,
        from: Pair<Double, Double>,
        to: Pair<Double, Double>,
        startSec: Long,
        routeTypes: List<Int>,
        normal: List<Moovit.Itinerary>,
    ): List<Moovit.Itinerary> {
        val found = candidates(net, from, to).take(PLAN_TOP)
        val bestNormal = normal.minOfOrNull { it.arr } ?: Long.MAX_VALUE
        val out = ArrayList<Pair<Candidate, Moovit.Itinerary>>()
        for (c in found) {
            val stop = net.lat[c.stop] to net.lon[c.stop]
            val bikeM = c.metres * DETOUR
            val reach = startSec + (bikeM / BIKE_MS).toLong()
            val plan = runCatching {
                Moovit.planItineraries(s, stop, to, (reach + PARK_S) * 1000, Moovit.TIME_DEPARTURE, routeTypes)
                    .itineraries
            }.getOrElse {
                uk.noammm.kav.Beta.log("bike_line", "line" to c.line.takeUnless { it.startsWith("#") }, "error" to (it.message ?: it.javaClass.simpleName))
                emptyList()
            }
            // From that stop: the line itself if Moovit offers it, else its quickest way on.
            val pick = plan.filter { it.rides.isNotEmpty() }.let { list ->
                list.filter { t -> !c.line.startsWith("#") && t.rides.first().shortName == c.line }.minByOrNull { it.arr }
                    ?: list.minByOrNull { it.arr }
            }
            uk.noammm.kav.Beta.log(
                "bike_line", "line" to c.line.takeUnless { it.startsWith("#") }, "stop" to net.name.getOrElse(c.stop) { "" }, "metres" to c.metres.toInt(),
                "found" to (pick != null), "first" to pick?.rides?.firstOrNull()?.shortName, "arr" to pick?.arr,
                "vsBest" to pick?.arr?.let { if (bestNormal == Long.MAX_VALUE) null else it - bestNormal },
            )
            if (pick == null) continue
            val bike = Moovit.Leg(
                Moovit.LegKind.BIKE, dep = startSec, arr = reach, meters = bikeM.toInt(), shape = listOf(from, stop),
            )
            val legs = listOf(bike) + pick.legs
            out.add(c to Moovit.Itinerary(
                guid = "bike-${c.line}-${c.stop}", group = 0, legs = legs, dep = startSec, arr = pick.arr,
                fare = pick.fare, currency = pick.currency,
            ))
        }
        // One per first line, and only ones that could make sense (not far behind the best normal route).
        return out.map { it.second }
            .filter { bestNormal == Long.MAX_VALUE || it.arr <= bestNormal + 15 * 60 }
            .distinctBy { t -> t.rides.firstOrNull()?.let { it.lineId } }
            .sortedBy { it.arr }
            .take(KEEP)
    }
}
