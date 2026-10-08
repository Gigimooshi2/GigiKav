package uk.noammm.kav

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import uk.noammm.kav.data.Moovit
import uk.noammm.kav.ui.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil

/**
 * Smart reroute: while a trip runs, keep asking Moovit for a better way to the destination.
 * Walking / waiting: once a minute from where you are.
 * On a bus: once per stop, planned from the bus's next stop at the time it gets there.
 * Offers only routes at least [MIN_GAIN] faster that you can actually catch.
 */
object Reroute {
    const val MIN_GAIN = 180L          // 3 min
    private const val CATCH_MARGIN = 60L // reach the stop at least 1 min before the bus
    private const val REOFFER_GAIN = 120L
    private const val COOLDOWN = 240L
    private const val NOTIFY_ID = 4711
    private const val CH_URGENT = "reroute_urgent"
    private const val CH_QUIET = "reroute_quiet"
    const val ACTION_SWITCH = "uk.noammm.kav.REROUTE_SWITCH"

    class Offer(
        val trip: Moovit.Itinerary,
        val resolved: Moovit.Resolved,
        val gain: Long,
        val urgent: Boolean,
        val headline: String,
        val detail: String,
        val sig: String,
        /** Set when this offer goes to one of the other destinations; null = the trip's own. */
        val toLabel: String? = null,
    )

    var enabled by mutableStateOf(false)
    /** Bumped on any change to an alternatives list, so the menu and badge redraw. */
    var altsVersion by mutableStateOf(0)
    /** Set while the place search is open to add an alternative for a trip to this destination. */
    var addingFor by mutableStateOf<Pair<Double, Double>?>(null)

    /** The saved place a trip's destination is (within 150 m), if any. Only used to key its list. */
    fun savedPlaceAt(ctx: Context, dest: Pair<Double, Double>?) = dest?.let { d ->
        Prefs.favourites(ctx).filter { it.place != null }
            .minByOrNull { metres(it.place!!.lat, it.place.lon, d.first, d.second) }
            ?.takeIf { metres(it.place!!.lat, it.place.lon, d.first, d.second) <= 150 }
    }

    private fun keyFor(ctx: Context, dest: Pair<Double, Double>?) =
        savedPlaceAt(ctx, dest)?.let { "fav_${it.id}" } ?: "general"

    /**
     * Places to also consider on a trip to [dest]: any places at all (addresses, stops, businesses),
     * kept in their own list. A trip to a saved destination (Home, Work…) has its own list, reused on
     * every trip there; any other destination shares the general list.
     */
    fun altsFor(ctx: Context, dest: Pair<Double, Double>?): List<Moovit.Place> = Prefs.rerouteAltPlaces(ctx, keyFor(ctx, dest))

    fun addAlt(ctx: Context, dest: Pair<Double, Double>?, p: Moovit.Place) {
        val key = keyFor(ctx, dest)
        val list = Prefs.rerouteAltPlaces(ctx, key)
        if (list.any { metres(it.lat, it.lon, p.lat, p.lon) < 50 }) return
        Prefs.setRerouteAltPlaces(ctx, key, list + p)
        altsVersion++
    }

    fun removeAlt(ctx: Context, dest: Pair<Double, Double>?, p: Moovit.Place) {
        val key = keyFor(ctx, dest)
        Prefs.setRerouteAltPlaces(ctx, key, Prefs.rerouteAltPlaces(ctx, key).filterNot { it.lat == p.lat && it.lon == p.lon })
        altsVersion++
    }

    var offer by mutableStateOf<Offer?>(null)
    /** Set when the user accepts; DirectionsOnline swaps the running trip. */
    var switchTo by mutableStateOf<Offer?>(null)

    private val dismissed = HashMap<String, Long>()
    private var lastSwitchAt = 0L
    private var notifiedSig: String? = null

    fun load(ctx: Context) { enabled = Prefs.reroute(ctx) }

    fun toggle(ctx: Context) {
        enabled = !enabled
        Prefs.setReroute(ctx, enabled)
        if (!enabled) clear(ctx)
    }

    fun clear(ctx: Context) {
        offer = null
        notifiedSig = null
        NotificationManagerCompat.from(ctx).cancel(NOTIFY_ID)
    }

    fun dismiss(ctx: Context) {
        offer?.let { dismissed[it.sig] = it.trip.arr }
        clear(ctx)
    }

    fun accept(ctx: Context) {
        val o = offer ?: return
        switchTo = o
        lastSwitchAt = System.currentTimeMillis() / 1000
        dismissed.clear()
        clear(ctx)
    }

    suspend fun run(ctx: Context, model: KavModel) {
        var lastPlanAt = 0L
        var lastRideStop = -1
        val stopCache = HashMap<Int, Moovit.StopInfo?>()
        val nextOnRide = HashMap<Long, Int>()
        var stepsFor: Moovit.Itinerary? = null
        var steps: List<Step> = emptyList()
        while (true) {
            delay(10_000)
            val j = model.activeJourney ?: return
            if (j.trip !== stepsFor) { stepsFor = j.trip; steps = buildSteps(j.trip, j.fromLabel, j.toLabel) }
            if (steps.isEmpty()) continue
            val now = System.currentTimeMillis() / 1000
            if (now - lastSwitchAt < COOLDOWN) continue
            // Drop an offer whose first bus has already gone.
            offer?.let { o -> if ((o.trip.rides.firstOrNull()?.dep ?: Long.MAX_VALUE) < now) clear(ctx) }
            val dest = j.trip.legs.lastOrNull { it.shape.isNotEmpty() }?.shape?.lastOrNull() ?: continue
            val idx = model.journeyStep.coerceIn(0, steps.lastIndex)
            val step = steps[idx]
            val fix = model.fix?.takeIf { it.isFresh(now) }

            var origin: Pair<Double, Double>? = null
            var at = now
            var riding: Moovit.Leg? = null
            try {
                when (step) {
                    is Step.Start, is Step.Walk, is Step.Wait -> {
                        if (now - lastPlanAt < 60) continue
                        origin = fix?.let { it.lat to it.lon } ?: model.here
                    }
                    is Step.Ride -> {
                        val ride = boardingChoice(step.ride, step.wait, j.chosen[step.legIndex] ?: 0).first
                        val ids = ride.stops
                        if (ids.size < 3) continue          // no stop between here and your planned one
                        val n = ids.size
                        val s = Online.open(fix?.let { it.lat to it.lon } ?: model.here)
                        val len = pathLength(ride.shape)
                        val aboard = fix != null && len > 0 && fix.aboard(ride.shape)
                        val fixAlong = if (aboard && fix != null) alongPath(fix.lat, fix.lon, ride.shape) else 0.0
                        val frac = if (ride.arr > ride.dep) ((now - ride.dep).toDouble() / (ride.arr - ride.dep)).coerceIn(0.0, 1.0) else 0.0
                        var k = maxOf(nextOnRide[ride.tripId] ?: 1, ceil(frac * (n - 1)).toInt().coerceAtLeast(1))
                        var point: Pair<Double, Double>? = null
                        var stopAlong = 0.0
                        for (tries in 0 until 6) {
                            if (k > n - 2) break
                            val id = ids[k]
                            val info = stopCache[id] ?: withContext(Dispatchers.IO) { Moovit.stopInfo(s, id) }
                                .also { stopCache[id] = it }
                            point = info?.point
                            val pt = point
                            stopAlong = if (pt != null) alongPath(pt.first, pt.second, ride.shape) else 0.0
                            if (aboard && pt != null && stopAlong < fixAlong - 30) k++ else break
                        }
                        val stopPoint = point
                        if (k > n - 2 || stopPoint == null) continue
                        nextOnRide[ride.tripId] = k
                        if (k == lastRideStop && now - lastPlanAt < 150) continue
                        lastRideStop = k
                        origin = stopPoint
                        at = if (aboard) now + ((stopAlong - fixAlong) / len * (ride.arr - ride.dep)).toLong().coerceAtLeast(20)
                            else maxOf(now + 20, ride.dep + (ride.arr - ride.dep) * k / (n - 1))
                        riding = ride
                    }
                    else -> continue
                }
                val from = origin ?: continue
                lastPlanAt = now
                val found = search(ctx, j, steps, idx, from, dest, at, riding)
                if (found == null) {
                    if (offer != null) clear(ctx)
                } else {
                    offer = found
                    notify(ctx, found)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("KavReroute", "check failed", e)
            }
        }
    }

    private suspend fun search(
        ctx: Context, j: ActiveJourney, steps: List<Step>, idx: Int,
        from: Pair<Double, Double>, dest: Pair<Double, Double>, at: Long, riding: Moovit.Leg?,
    ): Offer? {
        val filters = Prefs.filters(ctx)
        val s = Online.open(from)
        fun plan(to: Pair<Double, Double>) = Moovit.planItineraries(
            s, from, to, at * 1000, Moovit.TIME_DEPARTURE,
            routeTypes = routeTypesFor(filters), skipTaxi = ResultFilter.TAXI !in filters,
        ).laidOut()
        // Where you could go: the trip's destination, plus the places on its "also consider" list
        // (skipping ones that are really the same spot).
        val others = altsFor(ctx, dest).filter { metres(it.lat, it.lon, dest.first, dest.second) > 150 }.take(3)
        val list = withContext(Dispatchers.IO) { plan(dest) }
        // What's still ahead on the current plan, as line sets (a ride step carries all its options).
        val remaining = steps.drop(idx).filterIsInstance<Step.Ride>().map { it.ride.lineChoices.toSet() }
        fun same(it: Moovit.Itinerary) = it.rides.size == remaining.size &&
            it.rides.zip(remaining).all { (leg, want) -> leg.lineChoices.any { c -> c in want } }
        val baseline = list.filter(::same).minOfOrNull { it.arr } ?: j.trip.arr
        val pool = ArrayList<Pair<String?, Moovit.Itinerary>>()
        list.filter { !same(it) }.forEach { pool.add(null to it) }
        for (p in others) {
            runCatching { withContext(Dispatchers.IO) { plan(p.lat to p.lon) } }
                .onFailure { android.util.Log.w("KavReroute", "plan to ${p.name} failed", it) }
                .getOrNull()?.forEach { pool.add(p.name to it) }
        }
        val modes = j.trip.legs.map { it.kind }.toSet() + Moovit.LegKind.WALK + Moovit.LegKind.WAIT
        val needsRide = j.trip.legs.any { it.kind == Moovit.LegKind.RIDE }
        fun catchable(it: Moovit.Itinerary): Boolean {
            val first = it.legs.indexOfFirst { l -> l.kind == Moovit.LegKind.RIDE }
            if (first < 0) return true
            val ride = it.legs[first]
            if (riding != null && ride.tripId == riding.tripId) return true      // just staying on
            val reachStop = if (first == 0) at else it.legs[first - 1].arr.takeIf { a -> a > 0 } ?: at
            return ride.dep - reachStop >= CATCH_MARGIN
        }
        fun sig(to: String?, c: Moovit.Itinerary) = sigOf(c) + (to?.let { "@$it" } ?: "")
        val (bestTo, best) = pool.asSequence()
            .filter { (_, c) -> c.arr > 0 && c.arr <= baseline - MIN_GAIN && catchable(c) }
            .filter { (_, c) -> c.legs.none { l -> l.kind == Moovit.LegKind.OTHER } || ResultFilter.SHARED in filters }
            // Stay in the trip's own modes: a bus trip only ever gets bus (and walking) alternatives,
            // never a bike, taxi or scooter, and never a walk-only route.
            .filter { (_, c) -> c.legs.all { it.kind in modes } && (!needsRide || c.legs.any { it.kind == Moovit.LegKind.RIDE }) }
            .filter { (to, c) -> dismissed[sig(to, c)]?.let { c.arr <= it - REOFFER_GAIN } ?: true }
            .minByOrNull { (_, c) -> c.arr } ?: return null

        val r = withContext(Dispatchers.IO) { Moovit.hydrate(s, listOf(best)) }
        fun name(l: Moovit.Leg) = l.shortName.ifBlank { r.line(l.lineId)?.number.orEmpty() }.ifBlank { T("bus", "אוטובוס") }
        val rides = best.rides
        val first = rides.firstOrNull()
        val now = System.currentTimeMillis() / 1000
        val gain = baseline - best.arr
        val gainMin = (gain + 30) / 60
        val stayingOn = riding != null && first?.tripId == riding.tripId
        val urgent = when {
            riding != null -> !stayingOn
            first != null -> first.dep - now <= 240
            else -> false
        }
        val headline = when {
            riding != null && !stayingOn && first != null ->
                T("Get off at the next stop, take ${name(first)}", "רדו בתחנה הבאה, קחו את ${name(first)}")
            stayingOn && rides.size > 1 ->
                T("Stay on, then take ${name(rides[1])}", "הישארו באוטובוס, ואז ${name(rides[1])}")
            first != null -> T("Take ${name(first)} instead", "קחו את ${name(first)} במקום")
            else -> T("Walk instead", "עדיף ללכת")
        }.let { h -> if (bestTo != null) T("Go to $bestTo: ", "סעו ל$bestTo: ") + h.replaceFirstChar { it.lowercase() } else h }
        val hm = SimpleDateFormat("HH:mm", Locale.US)
        val detail = T("Faster: arrive ${hm.format(Date(best.arr * 1000))} (−$gainMin min)",
            "מהיר יותר: הגעה ב-${hm.format(Date(best.arr * 1000))} (−$gainMin דק׳)")
        return Offer(best, r, gain, urgent, headline, detail, sig(bestTo, best), bestTo)
    }

    private fun sigOf(t: Moovit.Itinerary) = t.rides.joinToString(">") { it.lineId.toString() }

    @android.annotation.SuppressLint("MissingPermission")
    private fun notify(ctx: Context, o: Offer) {
        val nm = NotificationManagerCompat.from(ctx)
        nm.createNotificationChannel(
            NotificationChannelCompat.Builder(CH_URGENT, NotificationManagerCompat.IMPORTANCE_HIGH)
                .setName(T("Faster route: act now", "מסלול מהיר יותר: עכשיו")).setVibrationEnabled(true).build(),
        )
        nm.createNotificationChannel(
            NotificationChannelCompat.Builder(CH_QUIET, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(T("Faster route", "מסלול מהיר יותר")).build(),
        )
        val fresh = o.sig != notifiedSig
        if (fresh) nm.cancel(NOTIFY_ID)
        notifiedSig = o.sig
        val open = PendingIntent.getActivity(
            ctx, 4712, Intent(ctx, MainActivity::class.java).setAction(PendingLink.ACTION_OPEN_TRIP)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val switch = PendingIntent.getActivity(
            ctx, 4713, Intent(ctx, MainActivity::class.java).setAction(ACTION_SWITCH)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val ignore = PendingIntent.getBroadcast(
            ctx, 4714, Intent(ctx, RerouteReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(ctx, if (o.urgent) CH_URGENT else CH_QUIET)
            .setSmallIcon(R.drawable.ic_trip_notice)
            .setContentTitle(o.headline)
            .setContentText(o.detail)
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setPriority(if (o.urgent) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_LOW)
            .addAction(0, T("Switch", "החלפה"), switch)
            .addAction(0, T("Ignore", "התעלמות"), ignore)
            .build()
        runCatching { nm.notify(NOTIFY_ID, n) }
    }
}

class RerouteReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) = Reroute.dismiss(ctx)
}
