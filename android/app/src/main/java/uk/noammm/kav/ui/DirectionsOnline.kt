@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package uk.noammm.kav.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import uk.noammm.kav.ActiveJourney
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
    var timeType by remember { mutableIntStateOf(Moovit.TIME_DEPARTURE) }
    var whenOpen by remember { mutableStateOf(false) }
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
        showResults = link.autoRun
    }

    var hereOrigin by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    LaunchedEffect(showResults, fromPlace == null, here == null) {
        hereOrigin = if (showResults && fromPlace == null) hereOrigin ?: here else null
    }
    val fromLL = fromPlace?.let { it.lat to it.lon } ?: hereOrigin ?: here
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
        if (departAt != 0L) delay(250)
        try {
            val s = Online.session ?: withContext(Dispatchers.IO) { Moovit.register(fromLL.first, fromLL.second) }
                .also { Online.session = it }
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

    androidx.compose.animation.AnimatedContent(
        targetState = Triple(open, showResults, autoOpen != null || linkTrip != null),
        modifier = Modifier.fillMaxSize(),
        transitionSpec = {
            if (targetState.first != null || (targetState.second && !initialState.second)) forward()
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

    Column(Modifier.fillMaxSize()) {
        PlanHeader(
            from = endpointName(fromPlace) ?: if (here != null) hereName() else T("Choose a start…", "בחרו נקודת התחלה…"),
            to = endpointName(toPlace) ?: T("Where do you want to go?…", "לאן תרצו להגיע?…"),
            fromIsHere = fromIsHere,
            toIsHere = isHere(toPlace),
            onFrom = { picking = "from" },
            onTo = { picking = "to" },
            onSwap = {
                val a = fromPlace
                fromPlace = toPlace
                toPlace = a ?: here?.let(::herePlace)
            },
            onBack = { showResults = false },
        )
        DepartRow(
            whenLabel(departAt, timeType),
            onWhen = { whenOpen = true },
            onMap = null,
        )
        PreciseLocationNudge()

        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .padding(horizontal = K.gap3, vertical = K.gap3),
            horizontalArrangement = Arrangement.spacedBy(K.gap2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            var sortMenu by remember { mutableStateOf(false) }
            Box {
                FilterChip(sort.labelText(), lit = sort != Sort.RECOMMENDED, caret = true) {
                    sortMenu = true
                }
                androidx.compose.material3.DropdownMenu(
                    expanded = sortMenu,
                    onDismissRequest = { sortMenu = false },
                ) {
                    Sort.entries.filter { it != Sort.LOWEST_CO2 || Shown.co2 }.forEach { s ->
                        androidx.compose.material3.DropdownMenuItem(
                            text = {
                                Text(
                                    s.labelText(), fontSize = 14.sp,
                                    color = if (s == sort) K.text else K.muted,
                                    fontWeight = if (s == sort) FontWeight.SemiBold else FontWeight.Normal,
                                )
                            },
                            trailingIcon = if (s == sort) ({ CheckSmall() }) else null,
                            onClick = { sort = s; sortMenu = false },
                        )
                    }
                }
            }
            Box(Modifier.height(20.dp).width(1.dp).background(K.border))
            FilterChip(T("Fewest transfers", "פחות החלפות"), lit = sort == Sort.LEAST_TRANSFERS) {
                sort = if (sort == Sort.LEAST_TRANSFERS) Sort.RECOMMENDED else Sort.LEAST_TRANSFERS
            }
            FilterChip(T("Least walking", "פחות הליכה"), lit = sort == Sort.LEAST_WALKING) {
                sort = if (sort == Sort.LEAST_WALKING) Sort.RECOMMENDED else Sort.LEAST_WALKING
            }

        }

        when {
            error == TOO_CLOSE -> Note(
                T(
                    "You're too close to your destination to plan a route.",
                    "אתם קרובים מדי ליעד כדי לתכנן מסלול.",
                ),
                Modifier.padding(K.gap4),
            )
            error != null -> Note(error!!, Modifier.padding(K.gap4))
            toLL == null -> Note(
                if (here == null) T(
                    "Choose where you are starting from, and where you are going.",
                    "בחרו מהיכן אתם יוצאים ולאן אתם רוצים להגיע.",
                )
                else T("Where do you want to go?", "לאן תרצו להגיע?"),
                Modifier.padding(K.gap4),
            )
            fromLL == null -> Note(T("Choose a start to find routes.", "בחרו נקודת התחלה כדי למצוא מסלולים."), Modifier.padding(K.gap4))
            planning -> LoadingBlock(T("Finding routes", "מחפשים מסלולים"))
            shown.isEmpty() -> Note(
                if (raw.isEmpty()) T("No routes found for this trip.", "לא נמצאו מסלולים לנסיעה הזו.")
                else T("Every route found is switched off in your filters.", "כל המסלולים שנמצאו הוסתרו על ידי המסננים שלכם."),
                Modifier.padding(K.gap4),
            )
            else -> Column {
                if (timeType != Moovit.TIME_LAST) Row(
                    Modifier.fillMaxWidth().padding(horizontal = K.gap3, vertical = K.gap1),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    fun nudge(delta: Long) {
                        val now = System.currentTimeMillis()
                        val base = if (departAt == 0L) now else departAt
                        departAt = (base + delta).let { if (it < now + 60_000L) 0L else it }
                        if (departAt == 0L) timeType = Moovit.TIME_DEPARTURE
                    }
                    ShiftButton(T("‹ Earlier", "› מוקדם יותר")) { nudge(-15 * 60_000L) }
                    Spacer(Modifier.weight(1f))
                    Text(
                        shown.firstOrNull()?.let {
                            java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
                                .format(java.util.Date(it.dep * 1000))
                        }.orEmpty(),
                        fontSize = 12.sp, color = K.dim,
                    )
                    Spacer(Modifier.weight(1f))
                    ShiftButton(T("Later ›", "מאוחר יותר ‹")) { nudge(15 * 60_000L) }
                }
                LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = K.gap3, end = K.gap3,
                    bottom = K.gap6 + LocalBottomBarInset.current),
                verticalArrangement = Arrangement.spacedBy(K.gap2),
            ) {
                items(shown.size) { i ->
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

    }

    val settingFav = model.settingFavourite
    LaunchedEffect(settingFav) { if (settingFav != null) picking = "fav" }

    androidx.compose.animation.AnimatedContent(
        targetState = picking,
        modifier = Modifier.fillMaxSize(),
        transitionSpec = { if (targetState != null) forward() else backward() },
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

@Composable
private fun FilterChip(label: String, lit: Boolean, caret: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.glassSurface(K.rPill)
            .then(if (lit) Modifier.background(K.plateStrong) else Modifier)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 13.sp, color = if (lit) K.text else K.muted)
        if (caret) { Spacer(Modifier.width(7.dp)); CaretSmall() }
    }
}

@Composable
private fun CheckSmall() {
    androidx.compose.foundation.Canvas(Modifier.size(12.dp)) {
        val w = size.width; val h = size.height
        drawLine(K.text, androidx.compose.ui.geometry.Offset(w * .14f, h * .55f),
            androidx.compose.ui.geometry.Offset(w * .40f, h * .80f), w * .14f,
            androidx.compose.ui.graphics.StrokeCap.Round)
        drawLine(K.text, androidx.compose.ui.geometry.Offset(w * .40f, h * .80f),
            androidx.compose.ui.geometry.Offset(w * .86f, h * .24f), w * .14f,
            androidx.compose.ui.graphics.StrokeCap.Round)
    }
}

@Composable
private fun CaretSmall() {
    androidx.compose.foundation.Canvas(Modifier.size(10.dp)) {
        val w = size.width; val h = size.height
        drawLine(K.muted, androidx.compose.ui.geometry.Offset(w * .18f, h * .38f),
            androidx.compose.ui.geometry.Offset(w * .5f, h * .66f), w * .14f,
            androidx.compose.ui.graphics.StrokeCap.Round)
        drawLine(K.muted, androidx.compose.ui.geometry.Offset(w * .5f, h * .66f),
            androidx.compose.ui.geometry.Offset(w * .82f, h * .38f), w * .14f,
            androidx.compose.ui.graphics.StrokeCap.Round)
    }
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
private fun ShiftButton(label: String, onClick: () -> Unit) {
    Text(
        label, fontSize = 12.sp, color = K.text,
        modifier = Modifier.clip(RoundedCornerShape(K.rPill)).background(K.surface1)
            .border(1.dp, K.border, RoundedCornerShape(K.rPill))
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
