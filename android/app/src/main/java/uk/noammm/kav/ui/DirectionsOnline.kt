@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package uk.noammm.kav.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.animation.togetherWith
import kotlinx.coroutines.withContext
import uk.noammm.kav.ActiveJourney
import uk.noammm.kav.hasLocationPermission
import uk.noammm.kav.requestLocationOnce
import kotlinx.coroutines.flow.first
import uk.noammm.kav.KavModel
import uk.noammm.kav.Prefs
import uk.noammm.kav.RecentTrip
import uk.noammm.kav.data.Moovit
import uk.noammm.kav.data.Moovit.Place
import uk.noammm.kav.data.MoovitLink

private data class OpenTrip(
    val trip: Moovit.Itinerary,
    val resolved: Moovit.Resolved,
    val fromLabel: String,
    val toLabel: String,
    val resume: Boolean = false,
    val backHome: Boolean = false,
)

private enum class Sort(val labelText: () -> String) {
    RECOMMENDED({ T("Recommended", "מומלץ") }),
    FASTEST({ T("Fastest", "המהיר ביותר") }),
    EARLIEST_DEPARTURE({ T("Departs first", "יציאה מוקדמת") }),
    EARLIEST_ARRIVAL({ T("Arrives first", "הגעה מוקדמת") }),
    LEAST_TRANSFERS({ T("Fewest transfers", "פחות החלפות") }),
    LEAST_WALKING({ T("Least walking", "פחות הליכה") }),
    CHEAPEST({ T("Cheapest", "הזול ביותר") }),
    LOWEST_CO2({ T("Lowest CO2", "פליטת CO2 נמוכה") }),
}

private const val TOO_CLOSE_M = 120.0
private const val TOO_CLOSE = "too-close"

private val HERE = listOf("Current location", "המיקום הנוכחי")

private fun hereName() = T(HERE[0], HERE[1])

private fun herePlace(at: Pair<Double, Double>) = Place(hereName(), "", at.first, at.second)

private fun isHere(p: Place?) = p != null && p.name in HERE

private fun endpointName(p: Place?) = if (isHere(p)) hereName() else p?.name

@Composable
fun DirectionsOnline(model: KavModel) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val here = model.here
    var fromPlace by remember { mutableStateOf<Place?>(null) }
    var toPlace by remember { mutableStateOf<Place?>(null) }
    var picking by remember { mutableStateOf<String?>(null) }
    var showResults by remember { mutableStateOf(false) }

    var plan by remember { mutableStateOf(Moovit.Plan()) }
    var raw by remember { mutableStateOf<List<Moovit.Itinerary>>(emptyList()) }
    var planning by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var resolved by remember { mutableStateOf(Moovit.Resolved()) }

    var open by remember { mutableStateOf<OpenTrip?>(null) }
    var sort by remember { mutableStateOf(Sort.RECOMMENDED) }
    var departAt by remember { mutableLongStateOf(0L) }
    var nudgedAt by remember { mutableLongStateOf(0L) }
    var shifting by remember { mutableStateOf(false) }
    var hereOrigin by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var locating by remember { mutableStateOf(false) }
    var linkNonce by remember { mutableIntStateOf(0) }
    var resumeSignal by remember { mutableIntStateOf(0) }
    val shiftScope = rememberCoroutineScope()
    var timeType by remember { mutableIntStateOf(Moovit.TIME_DEPARTURE) }
    var whenOpen by remember { mutableStateOf(false) }
    var orderOpen by remember { mutableStateOf(false) }
    var autoOpen by remember { mutableStateOf<RecentTrip?>(null) }
    var linkTrip by remember { mutableStateOf<List<MoovitLink.Ride>?>(null) }
    val filters = model.filters

    LaunchedEffect(model.returnHome) {
        if (model.returnHome) {
            open = null; picking = null; showResults = false; autoOpen = null
            model.returnHome = false
        }
    }

    LaunchedEffect(model.pendingFrom, model.pendingTo) {
        if (model.pendingFrom != null || model.pendingTo != null) showResults = true
        model.pendingFrom?.let { fromPlace = it; model.pendingFrom = null }
        model.pendingTo?.let { toPlace = it; model.pendingTo = null }
    }

    LaunchedEffect(model.pendingLink) {
        val link = model.pendingLink ?: return@LaunchedEffect
        model.pendingLink = null
        val toLat = link.toLat ?: return@LaunchedEffect
        val toLon = link.toLon ?: return@LaunchedEffect
        fun place(name: String?, lat: Double, lon: Double) = Moovit.Place(
            name ?: "%.5f, %.5f".format(java.util.Locale.US, lat, lon), "", lat, lon,
        )
        fromPlace = if (link.fromLat != null && link.fromLon != null) {
            place(link.fromName, link.fromLat, link.fromLon)
        } else null
        toPlace = place(link.toName, toLat, toLon)
        departAt = link.departMs
        timeType = Moovit.TIME_DEPARTURE
        open = null; autoOpen = null; picking = null
        linkTrip = link.rides.takeIf { it.isNotEmpty() && link.autoRun }
        // A link (widget, shared trip) is a new question: take a fresh position, not the last one.
        hereOrigin = null
        linkNonce++
        showResults = link.autoRun
    }

    // Starting from "Current location": if the last fix is over a minute old, wait (up to 6 s) for a
    // fresh one before planning, so a widget tap or a reopened app doesn't plan from where you were.
    LaunchedEffect(showResults, fromPlace == null, here == null, linkNonce) {
        if (!(showResults && fromPlace == null)) { hereOrigin = null; locating = false; return@LaunchedEffect }
        if (hereOrigin != null) return@LaunchedEffect
        fun stale(): Boolean {
            val f = model.fix ?: return true
            return System.currentTimeMillis() / 1000 - f.at > 60
        }
        if (stale()) {
            locating = true
            if (hasLocationPermission(ctx)) requestLocationOnce(ctx) { model.locate(it.first, it.second) }
            kotlinx.coroutines.withTimeoutOrNull(6000) { snapshotFlow { model.fix }.first { !stale() } }
            locating = false
        }
        hereOrigin = here
    }
    // Moved for real (not GPS jitter) while results are showing: re-plan from where you are now.
    LaunchedEffect(here) {
        val o = hereOrigin
        val h = here
        if (o != null && h != null && showResults && fromPlace == null && open == null &&
            metres(o.first, o.second, h.first, h.second) > 200
        ) hereOrigin = h
    }
    val fromLL = fromPlace?.let { it.lat to it.lon } ?: if (locating) null else hereOrigin ?: here
    val toLL = toPlace?.let { it.lat to it.lon }
    val fromIsHere = if (fromPlace == null) here != null else isHere(fromPlace)

    LaunchedEffect(showResults, fromLL, toLL, departAt, timeType, filters) {
        if (!showResults) { planning = false; return@LaunchedEffect }
        raw = emptyList(); resolved = Moovit.Resolved(); error = null
        if (fromLL == null || toLL == null) { planning = false; return@LaunchedEffect }
        if (metres(fromLL.first, fromLL.second, toLL.first, toLL.second) < TOO_CLOSE_M) {
            error = TOO_CLOSE; planning = false; return@LaunchedEffect
        }
        planning = true
        if (System.currentTimeMillis() - nudgedAt < 1_000L) delay(700) else if (departAt != 0L) delay(250)
        try {
            val s = Online.open(fromLL)
            val res = withContext(Dispatchers.IO) {
                Moovit.planItineraries(
                    s, fromLL, toLL, departAt, timeType,
                    routeTypes = routeTypesFor(filters), skipTaxi = ResultFilter.TAXI !in filters,
                )
            }
            plan = res
            raw = res.laidOut()
            linkTrip?.let { named ->
                if (raw.none { exactTrip(it, named) }) {
                    res.itineraries.firstOrNull { exactTrip(it, named) }
                        ?.let { raw = listOf(it) + raw }
                }
            }
            planning = false
            StopPhotos.prefetchIds(raw.flatMap { t -> t.rides.flatMap { r -> r.options.flatMap { listOf(it.fromStop, it.toStop) } } })
            resolved = withContext(Dispatchers.IO) { Moovit.hydrate(s, raw) }
            model.activeJourney?.takeIf { active -> raw.any { it === active.trip } }?.let {
                model.activeJourney = it.copy(resolved = Moovit.Resolved(
                    it.resolved.lines + resolved.lines, it.resolved.stops + resolved.stops,
                    it.resolved.routeTypes + resolved.routeTypes,
                    resolved.live + it.resolved.live, resolved.shapes + it.resolved.shapes,
                    it.resolved.pollSecs, it.resolved.patterns + resolved.patterns,
                ))
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Moovit.PlannerRefusal) {
            android.util.Log.i("KavPlan", "planner refused: ${e.code} ${e.title}")
            error = when (e.code) {
                Moovit.PLAN_TOO_CLOSE -> TOO_CLOSE
                Moovit.PLAN_NO_ROUTES -> T(
                    "Nothing is running for this trip at that time. Try another departure time.",
                    "אין קווים לנסיעה הזו בשעה הזו. נסו שעת יציאה אחרת.",
                )
                Moovit.PLAN_TOO_FAR -> T(
                    "These two places are too far apart to plan a trip between.",
                    "שני המקומות האלה רחוקים מכדי לתכנן נסיעה ביניהם.",
                )
                Moovit.PLAN_NO_COVERAGE -> T(
                    "Moovit has no timetable for this area.",
                    "ל-Moovit אין לוח זמנים לאזור הזה.",
                )
                else -> e.detail.ifBlank { e.title }.ifBlank {
                    T("Moovit would not plan this trip.", "Moovit לא תכנן את הנסיעה הזו.")
                }
            }
            planning = false
        } catch (e: Exception) {
            android.util.Log.e("KavPlan", "online plan failed", e)
            val reason = e.message ?: e.javaClass.simpleName
            error = if (uk.noammm.kav.hasNetwork(ctx)) T(
                "Moovit's planner did not answer: $reason",
                "התכנון של Moovit לא הגיב: $reason",
            ) else T(
                "No connection. Kav needs one to plan a trip.",
                "אין חיבור. Kav זקוק לחיבור כדי לתכנן נסיעה.",
            )
            planning = false
        }
    }

    LaunchedEffect(showResults, raw, resolved.pollSecs, open?.trip, model.activeJourney?.trip) {
        val active = model.activeJourney?.trip
        if (!showResults || raw.isEmpty() || (active != null && open?.trip === active)) return@LaunchedEffect
        val otherTrips = raw.filterNot { it === active }
        if (otherTrips.isEmpty()) return@LaunchedEffect
        while (true) {
            kotlinx.coroutines.delay(resolved.pollSecs.coerceIn(15, 120) * 1000L)
            val s = Online.session ?: break
            resolved = withContext(Dispatchers.IO) { Moovit.refreshLive(s, otherTrips, resolved) }
        }
    }

    val shown = remember(raw, sort, filters, resolved) {
        val kept = filterResults(raw, filters, resolved)
        when (sort) {
            Sort.RECOMMENDED -> kept
            Sort.FASTEST -> kept.sortedBy { it.durationMin }
            Sort.EARLIEST_DEPARTURE -> kept.sortedBy { it.dep }
            Sort.EARLIEST_ARRIVAL -> kept.sortedBy { it.arr }
            Sort.LEAST_TRANSFERS -> kept.sortedWith(compareBy({ it.transfers }, { it.durationMin }))
            Sort.LEAST_WALKING -> kept.sortedWith(
                compareBy({ i -> i.legs.filter { it.kind == Moovit.LegKind.WALK }.sumOf { it.minutes } }, { it.durationMin }),
            )
            Sort.CHEAPEST -> kept.sortedWith(compareBy({ if (it.fare < 0) Int.MAX_VALUE else it.fare }, { it.durationMin }))
            Sort.LOWEST_CO2 -> kept.sortedWith(compareBy({ if (it.co2g < 0) Int.MAX_VALUE else it.co2g }, { it.durationMin }))
        }
    }

    LaunchedEffect(autoOpen, showResults, planning, shown.firstOrNull(), error) {
        val taken = autoOpen ?: return@LaunchedEffect
        if (!showResults) { autoOpen = null; return@LaunchedEffect }
        if (planning) return@LaunchedEffect
        autoOpen = null
        if (error != null) return@LaunchedEffect
        val again = shown.firstOrNull { sameRoute(it, taken) }
            ?: shown.firstOrNull() ?: return@LaunchedEffect
        open = OpenTrip(
            again, resolved, fromPlace?.name ?: T("Current location", "המיקום הנוכחי"),
            toPlace?.name ?: T("Destination", "יעד"), backHome = true,
        )
    }

    LaunchedEffect(linkTrip, showResults, planning, raw, error) {
        val named = linkTrip ?: return@LaunchedEffect
        if (!showResults) { linkTrip = null; return@LaunchedEffect }
        if (planning) return@LaunchedEffect
        linkTrip = null
        if (error != null) return@LaunchedEffect
        val match = raw.firstOrNull { exactTrip(it, named) }
            ?: raw.firstOrNull { sameLines(it, named) }
            ?: return@LaunchedEffect
        open = OpenTrip(
            match, resolved, fromPlace?.name ?: T("Current location", "המיקום הנוכחי"),
            toPlace?.name ?: T("Destination", "יעד"),
        )
    }

    androidx.activity.compose.BackHandler(enabled = showResults && open == null && picking == null && autoOpen == null && linkTrip == null) {
        showResults = false
    }

    val under by underSearch(picking != null)

    // Trip notification tapped: open the running trip, not the home screen.
    LaunchedEffect(uk.noammm.kav.PendingLink.openTrip) {
        if (!uk.noammm.kav.PendingLink.openTrip) return@LaunchedEffect
        uk.noammm.kav.PendingLink.openTrip = false
        val journey = model.activeJourney ?: return@LaunchedEffect
        if (open?.trip === journey.trip) { resumeSignal++; return@LaunchedEffect }
        picking = null; autoOpen = null; linkTrip = null
        open = OpenTrip(journey.trip, journey.resolved, journey.fromLabel, journey.toLabel, resume = true)
    }

    // Accepted faster route: replace the running trip and keep navigating.
    LaunchedEffect(uk.noammm.kav.Reroute.switchTo) {
        val o = uk.noammm.kav.Reroute.switchTo ?: return@LaunchedEffect
        uk.noammm.kav.Reroute.switchTo = null
        val toLabel = model.activeJourney?.toLabel ?: open?.toLabel ?: T("Destination", "יעד")
        val fromLabel = T("Current location", "המיקום הנוכחי")
        model.journeyStep = 0
        model.activeJourney = ActiveJourney(o.trip, o.resolved, fromLabel, toLabel)
        picking = null; autoOpen = null; linkTrip = null
        showResults = true
        open = OpenTrip(o.trip, o.resolved, fromLabel, toLabel, resume = true)
    }

    // Same route, one departure earlier or later (like Moovit's single-route page).
    fun shiftOpen(dir: Int) {
        val cur = open ?: return
        val a = fromLL ?: return
        val b = toLL ?: return
        if (shifting) return
        shifting = true
        shiftScope.launch {
            try {
                val s = Online.open(a)
                val sig = cur.trip.rides.map { it.lineChoices.toSet() }
                fun same(it: Moovit.Itinerary) = it.rides.size == sig.size &&
                    it.rides.zip(sig).all { (leg, want) -> leg.lineChoices.any { c -> c in want } }
                fun plan(atSec: Long, type: Int) = Moovit.planItineraries(
                    s, a, b, atSec * 1000, type,
                    routeTypes = routeTypesFor(filters), skipTaxi = ResultFilter.TAXI !in filters,
                )
                val (res, pick) = withContext(Dispatchers.IO) {
                    if (dir > 0) {
                        val r = plan(cur.trip.dep + 60, Moovit.TIME_DEPARTURE)
                        r to r.laidOut().filter { same(it) && it.dep > cur.trip.dep }.minByOrNull { it.dep }
                    } else {
                        val r1 = plan(cur.trip.arr - 60, Moovit.TIME_ARRIVAL)
                        val p1 = r1.laidOut().filter { same(it) && it.dep < cur.trip.dep }.maxByOrNull { it.dep }
                        if (p1 != null) r1 to p1 else {
                            val r2 = plan(cur.trip.dep - 45 * 60, Moovit.TIME_DEPARTURE)
                            r2 to r2.laidOut().filter { same(it) && it.dep < cur.trip.dep }.maxByOrNull { it.dep }
                        }
                    }
                }
                if (pick == null) {
                    android.widget.Toast.makeText(
                        ctx,
                        if (dir > 0) T("No later departure for this route.", "אין יציאה מאוחרת יותר למסלול הזה.")
                        else T("No earlier departure for this route.", "אין יציאה מוקדמת יותר למסלול הזה."),
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                } else {
                    val rs = withContext(Dispatchers.IO) { Moovit.hydrate(s, listOf(pick)) }
                    if (open === cur) open = cur.copy(trip = pick, resolved = rs)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("KavShift", "shift failed", e)
                android.widget.Toast.makeText(ctx, T("Moovit did not answer.", "Moovit לא הגיב."), android.widget.Toast.LENGTH_SHORT).show()
            } finally {
                shifting = false
            }
        }
    }
    androidx.compose.animation.AnimatedContent(
        targetState = Triple(open, showResults, autoOpen != null || linkTrip != null),
        modifier = Modifier.fillMaxSize().graphicsLayer { alpha = under },
        transitionSpec = {
            if (initialState.first != null && targetState.first != null)
                androidx.compose.animation.fadeIn() togetherWith androidx.compose.animation.fadeOut()
            else if (targetState.first != null || (targetState.second && !initialState.second)) forward()
            else backward()
        },
        label = "directions",
    ) { (chosen, displayingResults, opening) ->
        if (chosen != null) {
            val active = model.activeJourney?.takeIf { it.trip === chosen.trip }
            val detailResolved = active?.resolved
                ?: if (!chosen.resume && raw.any { it === chosen.trip }) resolved else chosen.resolved
            TripDetailScreen(
                model,
                chosen.trip, detailResolved,
                fromLabel = chosen.fromLabel,
                toLabel = chosen.toLabel,
                onBack = { open = null; if (chosen.resume || chosen.backHome) showResults = false },
                startInNavigation = chosen.resume,
                onStart = {
                    if (active == null) {
                        model.journeyStep = 0
                        model.activeJourney = ActiveJourney(chosen.trip, detailResolved, chosen.fromLabel, chosen.toLabel)
                    }
                },
                onNavigating = { model.navigating = it },
                onShift = if (chosen.resume || active != null) null else { d -> shiftOpen(d) },
                shifting = shifting,
                active = active != null,
                resumeSignal = resumeSignal,
                onEnd = {
                    if (model.activeJourney?.trip === chosen.trip) model.activeJourney = null
                    toPlace?.let {
                        Prefs.rememberTrip(
                            ctx, fromPlace, it, System.currentTimeMillis(), chosen.trip,
                        )
                    }
                    open = null
                    showResults = false
                },
            )
            return@AnimatedContent
        }

    if (!displayingResults) {
        HomeScreen(
            model = model,
            recentTrips = Prefs.trips(ctx).take(if (model.activeJourney != null) 2 else 3),
            onSearch = { fromPlace = null; departAt = 0L; timeType = Moovit.TIME_DEPARTURE; picking = "to" },
            onFavourite = { p ->
                fromPlace = null; toPlace = p
                departAt = 0L; timeType = Moovit.TIME_DEPARTURE
                showResults = true
            },
            onSetFavourite = { f -> model.settingFavourite = f },
            onTrip = { t ->
                fromPlace = t.from; toPlace = t.to
                departAt = 0L; timeType = Moovit.TIME_DEPARTURE
                autoOpen = t; showResults = true
            },
            onResume = {
                model.activeJourney?.let { journey ->
                    open = OpenTrip(
                        journey.trip, journey.resolved, journey.fromLabel, journey.toLabel, resume = true,
                    )
                }
            },
        )
        return@AnimatedContent
    }

    if (opening) {
        LoadingScreen(T("Finding your route", "מוצאים לכם מסלול")) {
            autoOpen = null; linkTrip = null; showResults = false
        }
        return@AnimatedContent
    }

    val resultsState = androidx.compose.foundation.lazy.rememberLazyListState()
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            state = resultsState,
            contentPadding = PaddingValues(bottom = K.gap6 + LocalBottomBarInset.current),
            verticalArrangement = Arrangement.spacedBy(K.gap2),
        ) {
            item(key = "trip") {
                PlanHeader(
                    from = endpointName(fromPlace) ?: if (here != null) hereName() else T("Choose a start…", "בחרו נקודת התחלה…"),
                    to = endpointName(toPlace) ?: T("Where do you want to go?…", "לאן תרצו להגיע?…"),
                    fromIsHere = fromIsHere,
                    toIsHere = isHere(toPlace),
                    onFrom = { picking = "from" },
                    onTo = { picking = "to" },
                    onSwap = {
                        val a = fromPlace
                        val b = toPlace
                        // "Current location" always means where you are now, not where it was when picked.
                        fromPlace = if (b != null && isHere(b)) null else b
                        toPlace = if (a == null || isHere(a)) here?.let(::herePlace) else a
                        hereOrigin = here
                    },
                    onBack = { showResults = false },
                )
            }
            item(key = "when") {
                Column {
                    DepartRow(
                        whenLabel(departAt, timeType),
                        onWhen = { whenOpen = true },
                        order = sort.labelText(),
                        onOrder = { orderOpen = true },
                    )
                    PreciseLocationNudge()
                }
            }
            if (timeType != Moovit.TIME_LAST && fromLL != null && toLL != null && error != TOO_CLOSE) item(key = "shift") {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = K.gap3, vertical = K.gap1),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    fun nudge(delta: Long) {
                        val now = System.currentTimeMillis()
                        val base = if (departAt == 0L) now else departAt
                        departAt = (base + delta).let { if (kotlin.math.abs(it - now) < 60_000L) 0L else it }
                        if (departAt == 0L) timeType = Moovit.TIME_DEPARTURE
                        nudgedAt = System.currentTimeMillis()
                    }
                    ShiftButton(T("‹ Earlier", "› מוקדם יותר")) { nudge(-15 * 60_000L) }
                    Spacer(Modifier.weight(1f))
                    Text(
                        (if (planning || shown.isEmpty()) {
                            if (departAt == 0L) T("Now", "עכשיו")
                            else java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date(departAt))
                        } else shown.firstOrNull()?.let {
                            java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
                                .format(java.util.Date(it.dep * 1000))
                        }).orEmpty(),
                        fontSize = 12.sp, color = K.dim,
                    )
                    Spacer(Modifier.weight(1f))
                    ShiftButton(T("Later ›", "מאוחר יותר ‹")) { nudge(15 * 60_000L) }
                }
            }
            when {
                error == TOO_CLOSE -> item {
                    Note(
                        T(
                            "You're too close to your destination to plan a route.",
                            "אתם קרובים מדי ליעד כדי לתכנן מסלול.",
                        ),
                        Modifier.padding(K.gap4),
                    )
                }
                error != null -> item { Note(error.orEmpty(), Modifier.padding(K.gap4)) }
                toLL == null -> item {
                    Note(
                        if (here == null) T(
                            "Choose where you are starting from, and where you are going.",
                            "בחרו מהיכן אתם יוצאים ולאן אתם רוצים להגיע.",
                        )
                        else T("Where do you want to go?", "לאן תרצו להגיע?"),
                        Modifier.padding(K.gap4),
                    )
                }
                fromLL == null && locating -> item { Note(T("Finding where you are…", "מאתרים את המיקום שלכם…"), Modifier.padding(K.gap4)) }
                fromLL == null -> item { Note(T("Choose a start to find routes.", "בחרו נקודת התחלה כדי למצוא מסלולים."), Modifier.padding(K.gap4)) }
                planning -> item { LoadingBlock(T("Finding routes", "מחפשים מסלולים"), Modifier.fillParentMaxHeight(.6f)) }
                shown.isEmpty() -> item {
                    Note(
                        if (raw.isEmpty()) T("No routes found for this trip.", "לא נמצאו מסלולים לנסיעה הזו.")
                        else T("Every route found is switched off in your filters.", "כל המסלולים שנמצאו הוסתרו על ידי המסננים שלכם."),
                        Modifier.padding(K.gap4),
                    )
                }
                else -> {
                    items(shown.size) { i ->
                        Column(Modifier.padding(horizontal = K.gap3)) {
                            val heading = plan.heading(shown[i])
                            if (heading.isNotBlank() && (i == 0 || plan.heading(shown[i - 1]) != heading)) {
                                Text(
                                    heading, style = DisplayItalic, fontSize = 12.sp, color = K.dim,
                                    modifier = Modifier.padding(start = K.gap1, top = K.gap3, bottom = 2.dp),
                                )
                            }
                            Box(Modifier.popIn(i, raw to sort)) {
                                ItineraryCard(shown[i], resolved) {
                                    toPlace?.let { to -> Prefs.noteTripRoute(ctx, fromPlace, to, shown[i]) }
                                    open = OpenTrip(
                                        shown[i], resolved, fromPlace?.name ?: T("Current location", "המיקום הנוכחי"),
                                        toPlace?.name ?: T("Destination", "יעד"),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        ScrollEdge(resultsState.canScrollBackward)
    }


    }

    val settingFav = model.settingFavourite
    LaunchedEffect(settingFav) { if (settingFav != null) picking = "fav" }

    val rise = with(androidx.compose.ui.platform.LocalDensity.current) { 76.dp.roundToPx() }
    androidx.compose.animation.AnimatedContent(
        targetState = picking,
        modifier = Modifier.fillMaxSize(),
        transitionSpec = { if (targetState != null) searchIn(rise) else searchOut(rise) },
        label = "placePicker",
    ) { which ->
        if (which != null) {
        PlacePicker(
            title = if (which == "from") T("start…", "התחלה…") else T("destination…", "יעד…"),
            here = here,
            allowMyLocation = which != "fav",
            initialSetting = if (which == "fav") settingFav else null,
            onMyLocation = {
                if (which == "from") fromPlace = null
                else toPlace = here?.let(::herePlace)
                picking = null
                if (which != "from" || toPlace != null) showResults = true
                model.placeQuery = ""
            },
            onPick = { p ->
                if (which == "from") fromPlace = p else toPlace = p
                picking = null
                if (which != "from" || toPlace != null) showResults = true
                model.placeQuery = ""
            },
            onDismiss = { picking = null; model.settingFavourite = null },
            net = model.net,
            favourites = model.favourites,
            onSaveFavourites = { model.saveFavourites(ctx, it) },
            query = model.placeQuery,
            onQuery = { model.placeQuery = it },
            onLocate = { model.locate(it.first, it.second) },
        )
        }
    }

    if (orderOpen) {
        val orders = Sort.entries.filter { it != Sort.LOWEST_CO2 || Shown.co2 }
        ChoiceSheet(
            T("Order routes by", "סדר המסלולים"), orders.map { it.labelText() }, orders.indexOf(sort),
            onPick = { sort = orders[it]; orderOpen = false },
            onDismiss = { orderOpen = false },
        )
    }

    if (whenOpen) WhenSheet(
        departAt = departAt,
        timeType = timeType,
        onPick = { ms, type ->
            departAt = ms; timeType = type; whenOpen = false; showResults = true
        },
        onNow = { departAt = 0L; timeType = Moovit.TIME_DEPARTURE; whenOpen = false },
        onDismiss = { whenOpen = false },
    )
}

internal fun sameRoute(candidate: Moovit.Itinerary, taken: RecentTrip): Boolean {
    if (taken.group < 0) return false
    if (candidate.group != taken.group) return false
    val rides = candidate.rides
    if (rides.size != taken.lines.size) return false
    return taken.lines.indices.all { i -> taken.lines[i] in rides[i].lineChoices }
}

internal fun exactTrip(candidate: Moovit.Itinerary, named: List<MoovitLink.Ride>): Boolean {
    val rides = candidate.rides
    if (named.isEmpty() || rides.size != named.size) return false
    return named.indices.all { i ->
        val want = named[i]
        val leg = rides[i]
        if (want.lineId !in leg.lineChoices) return@all false
        val byTrip = want.tripId != 0L &&
            (leg.tripId == want.tripId || leg.options.any { it.tripId == want.tripId })
        byTrip || kotlin.math.abs(leg.dep - want.depSec) <= 60
    }
}

internal fun sameLines(candidate: Moovit.Itinerary, named: List<MoovitLink.Ride>): Boolean {
    val rides = candidate.rides
    if (named.isEmpty() || rides.size != named.size) return false
    return named.indices.all { i -> named[i].lineId in rides[i].lineChoices }
}

@Composable
internal fun ShiftButton(label: String, onClick: () -> Unit) {
    Text(
        label, fontSize = 12.sp, color = K.text,
        modifier = Modifier.panel(K.rPill)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    )
}

private fun whenLabel(departAt: Long, timeType: Int): String {
    if (timeType == Moovit.TIME_LAST) return T("Latest departure", "יציאה אחרונה")
    if (departAt <= 0L) return T("Depart now", "יציאה עכשיו")
    val now = java.util.Calendar.getInstance()
    val then = java.util.Calendar.getInstance().apply { timeInMillis = departAt }
    val sameDay = now.get(java.util.Calendar.YEAR) == then.get(java.util.Calendar.YEAR) &&
        now.get(java.util.Calendar.DAY_OF_YEAR) == then.get(java.util.Calendar.DAY_OF_YEAR)
    val stamp = java.text.SimpleDateFormat(
        if (sameDay) "HH:mm" else "EEE HH:mm", java.util.Locale.getDefault(),
    ).format(java.util.Date(departAt))
    return when (timeType) {
        Moovit.TIME_ARRIVAL -> T("Arrive by ", "הגעה עד ") + stamp
        else -> T("Depart ", "יציאה ") + stamp
    }
}
