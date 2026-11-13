package uk.noammm.kav.ui

import uk.noammm.kav.data.Moovit

/** Kav Beta experiment: draw live buses where they should be now, not where they last reported. */
const val FLAG_BUS_ESTIMATE = "bus_estimate"

/**
 * A bus's position reaches Kav about 40 s after the bus sent it (median on the first logged trips;
 * 1 in 10 over 75 s), by which time a moving bus is a block or more further on. This moves it
 * forward along its route by the time since its sample, at the pace Moovit itself expects: the
 * distance to the next stop of yours it hasn't reached, over Moovit's predicted time there. It never
 * passes that stop and gives up on samples over 3 minutes old.
 *
 * Kav Beta logs how far the reported and the estimated positions turn out to be from the bus's
 * next sample ("bus_est"), so the two can be compared before this ships.
 */
object BusEstimate {
    private const val MAX_AGE_S = 180L
    private const val MAX_SPEED = 17.0 // m/s, about 60 km/h
    private const val OFF_ROUTE_M = 60.0

    val on: Boolean get() = uk.noammm.kav.Beta.flag(FLAG_BUS_ESTIMATE)

    /** Where the bus was at its sample, how fast Moovit expects it to go, and how far it can go. */
    class Track(
        val shapeId: Int,
        val along: Double,
        val speed: Double,
        val limit: Double,
        val sampleUtc: Long,
        val lat: Double,
        val lon: Double,
    ) {
        fun at(shape: List<Pair<Double, Double>>, t: Long): Pair<Double, Double>? {
            val dt = t - sampleUtc
            if (dt <= 0 || dt > MAX_AGE_S) return null
            return pointAlong(shape, along + (speed * dt).coerceAtMost(limit))
        }
    }

    fun track(a: Moovit.Arrival, r: Moovit.Resolved): Track? {
        if (!a.realBus || a.sampleUtc <= 0) return null
        val shape = r.shapes[a.tripShapeId]?.takeIf { it.size >= 2 } ?: return null
        if (distanceToPath(a.lat, a.lon, shape) > OFF_ROUTE_M) return null
        val along = alongPath(a.lat, a.lon, shape)
        // Moovit's predictions for this same trip at the stops it hasn't reached yet.
        val (stopAt, eta) = r.live.values.asSequence()
            .filter { it.tripId == a.tripId && !it.rtDropped && it.rtUtc > a.sampleUtc }
            .mapNotNull { x -> r.stop(x.stopId)?.point?.let { (la, lo) -> alongPath(la, lo, shape) to x.rtUtc } }
            .filter { it.first > along + 30 }
            .minByOrNull { it.first } ?: return null
        val speed = ((stopAt - along) / (eta - a.sampleUtc)).coerceIn(0.0, MAX_SPEED)
        return Track(a.tripShapeId, along, speed, (stopAt - along - 15).coerceAtLeast(0.0), a.sampleUtc, a.lat, a.lon)
    }

    /** [a] moved to where it should be at [now], or [a] itself when there's nothing to go on. */
    fun now(a: Moovit.Arrival, r: Moovit.Resolved, now: Long): Moovit.Arrival {
        if (!on) return a
        val shape = r.shapes[a.tripShapeId] ?: return a
        val p = track(a, r)?.at(shape, now) ?: return a
        return a.movedTo(p.first, p.second)
    }

    // Kav Beta: the last track of each bus, to check against its next sample.
    private val last = HashMap<String, Track>()

    internal fun logAccuracy(r: Moovit.Resolved) {
        if (!uk.noammm.kav.Beta.on) return
        val seen = HashSet<String>()
        for (a in r.live.values) {
            if (!a.realBus || a.vehicleId.isBlank() || !seen.add(a.vehicleId)) continue
            val prev = last[a.vehicleId]
            if (prev != null && a.sampleUtc > prev.sampleUtc) {
                val shape = r.shapes[prev.shapeId]
                val est = shape?.let { prev.at(it, a.sampleUtc) }
                uk.noammm.kav.Beta.log(
                    "bus_est", "veh" to a.vehicleId, "dt" to a.sampleUtc - prev.sampleUtc,
                    "speed" to "%.1f".format(prev.speed),
                    "rawErr" to metres(prev.lat, prev.lon, a.lat, a.lon).toInt(),
                    "estErr" to est?.let { metres(it.first, it.second, a.lat, a.lon).toInt() },
                )
            }
            track(a, r)?.let { last[a.vehicleId] = it } ?: last.remove(a.vehicleId)
        }
        if (last.size > 200) last.keys.retainAll(seen)
    }
}
