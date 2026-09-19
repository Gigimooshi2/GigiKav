package uk.noammm.kav.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uk.noammm.kav.KavModel
import uk.noammm.kav.data.Moovit
import uk.noammm.kav.data.MoovitSession
import uk.noammm.kav.data.Net
import uk.noammm.kav.data.nearestStops
import uk.noammm.kav.data.StopStore
import uk.noammm.kav.hasLocationPermission
import uk.noammm.kav.loadNet
import uk.noammm.kav.requestLocationOnce
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.coroutineContext

object Online {
    @Volatile var session: MoovitSession? = null
    @Volatile var stops: List<Moovit.Stop> = emptyList()
    @Volatile var stopsNext: Int = 1
    @Volatile var stopsComplete: Boolean = false
}

private val hm = SimpleDateFormat("HH:mm", Locale.US)

private sealed interface LiveFocus {
    data class Vehicle(val tripId: Long, val from: Int? = null) : LiveFocus
    data class Stop(val id: Int) : LiveFocus
    data class Line(val number: String, val destination: String, val from: Int) : LiveFocus
}

private class Tracked(
    val arrival: Moovit.Arrival,
    val stop: Moovit.Stop?,
    val line: Moovit.LineInfo?,
    val looked: Boolean,
    val routeType: Int,
) {
    val tripId get() = arrival.tripId
    val number get() = line?.number?.ifBlank { null } ?: if (pending) "…" else "#${arrival.lineId}"
    val pending get() = line == null && !looked
    val eta get() = arrival.rtUtc.takeIf { it > 0 } ?: arrival.staticUtc
}

@Composable
fun LiveScreen(model: KavModel) {
    val ctx = LocalContext.current
    val here = model.here ?: (32.0759 to 34.7745)
    val located = model.here != null
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) requestLocationOnce(ctx) { model.here = it }
    }
    LaunchedEffect(Unit) {
        if (model.here == null) {
            if (hasLocationPermission(ctx)) requestLocationOnce(ctx) { model.here = it }
            else ask.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
    }
    var status by remember { mutableStateOf(T("connecting to Moovit…", "מתחברים ל-Moovit…")) }
    var loading by remember { mutableStateOf(true) }
    var scanned by remember {
        mutableFloatStateOf(if (Online.stopsComplete) 1f else Online.stopsNext.toFloat() / Moovit.STOP_ID_CEILING)
    }
    var stopsReady by remember { mutableStateOf(Online.stopsComplete) }
    var found by remember { mutableIntStateOf(Online.stops.size) }
    var stopsError by remember { mutableStateOf<String?>(null) }
    var arrivals by remember { mutableStateOf<Map<Moovit.ArrivalKey, Moovit.Arrival>>(emptyMap()) }
    var near by remember { mutableStateOf<List<Moovit.Stop>>(emptyList()) }
    var lines by remember { mutableStateOf<Map<Int, Moovit.LineInfo?>>(emptyMap()) }
    var modes by remember { mutableStateOf<Map<Int, Int>>(emptyMap()) }
    var pollSecs by remember { mutableIntStateOf(20) }
    var focus by remember { mutableStateOf<LiveFocus?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = System.currentTimeMillis() / 1000 } }

    LaunchedEffect(here) {
        try {
            val s = Online.session ?: withContext(Dispatchers.IO) { Moovit.register(here.first, here.second) }
                .also { Online.session = it }
            val app = ctx.applicationContext
            if (Online.stops.isEmpty()) {
                withContext(Dispatchers.IO) { StopStore.load(app) }?.let {
                    Online.stops = it.stops; Online.stopsNext = it.nextId; Online.stopsComplete = it.complete
                }
            }
            found = Online.stops.size
            stopsReady = Online.stopsComplete
            scanned = if (Online.stopsComplete) 1f else Online.stopsNext.toFloat() / Moovit.STOP_ID_CEILING
            fun nearby() = Moovit.nearbyStops(Online.stops, here.first, here.second, k = 120, radiusKm = 1.5)
            if (Online.stops.isNotEmpty()) near = withContext(Dispatchers.Default) { nearby() }
            if (!Online.stopsComplete) launch(Dispatchers.IO) {
                var blocks = 0
                while (isActive && !Online.stopsComplete) {
                    val from = Online.stopsNext
                    val pages = java.util.concurrent.atomic.AtomicInteger(0)
                    val block = try {
                        coroutineScope {
                            (0 until 4).map { i ->
                                async {
                                    Moovit.stopPages(s, from + i * 1000, 10) {
                                        scanned = ((from - 1 + pages.incrementAndGet() * 100).toFloat() /
                                            Moovit.STOP_ID_CEILING).coerceIn(0f, 1f)
                                    }
                                }
                            }.awaitAll().flatten()
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        stopsError = T("Could not load stops · retrying", "לא ניתן היה לטעון תחנות · מנסים שוב")
                        status = stopsError.orEmpty()
                        scanned = ((from - 1).toFloat() / Moovit.STOP_ID_CEILING).coerceIn(0f, 1f)
                        delay(10_000); continue
                    }
                    stopsError = null
                    val known = Online.stops.mapTo(HashSet()) { it.id }
                    Online.stops = Online.stops + block.filter { it.id !in known }
                    Online.stopsNext = from + 4000
                    Online.stopsComplete = Online.stopsNext >= Moovit.STOP_ID_CEILING
                    near = nearby()
                    found = Online.stops.size
                    scanned = if (Online.stopsComplete) 1f
                        else (Online.stopsNext.toFloat() / Moovit.STOP_ID_CEILING).coerceIn(0f, 1f)
                    stopsReady = Online.stopsComplete
                    blocks++
                    if (blocks % 3 == 0 || Online.stopsComplete) StopStore.save(app, Online.stops, Online.stopsNext, Online.stopsComplete)
                }
            }
            while (near.isEmpty()) { status = T("loading stops…", "טוענים תחנות…"); delay(300) }
            while (coroutineContext.isActive) {
                val ids = near.map { it.id }
                try {
                    val (found, poll) = withContext(Dispatchers.IO) { Moovit.stopArrivals(s, ids) }
                    arrivals = found; pollSecs = poll.coerceIn(10, 60); loading = false
                    val tracked = found.values.filter { it.hasLocation }
                    status = if (!Online.stopsComplete) {
                        val pct = (Online.stopsNext.toFloat() / Moovit.STOP_ID_CEILING * 100)
                            .toInt().coerceIn(0, 99)
                        T("Still finding the stops near you · $pct%", "עדיין מאתרים תחנות בסביבתכם · $pct%")
                    } else if (tracked.isEmpty()) {
                        T("No tracked vehicles right now", "אין כרגע כלי רכב במעקב")
                    } else {
                        T(
                            "${tracked.map { it.tripId }.distinct().size} live vehicles · ${ids.size} stops",
                            "${tracked.map { it.tripId }.distinct().size} כלי רכב בזמן אמת · ${ids.size} תחנות",
                        )
                    }
                    val missing = tracked.sortedBy { it.rtUtc.takeIf { t -> t > 0 } ?: it.staticUtc }
                        .map { it.lineId }.distinct().filter { it !in lines }
                    for (batch in missing.chunked(8)) {
                        val named = withContext(Dispatchers.IO) {
                            batch.map { id -> async { id to runCatching { Moovit.lineInfo(s, id) }.getOrNull() } }.awaitAll().toMap()
                        }
                        lines = lines + named
                        val agencies = named.values.mapNotNull { it?.agencyId }.distinct().filter { it !in modes }
                        if (agencies.isNotEmpty()) modes = modes + withContext(Dispatchers.IO) {
                            agencies.associateWith { runCatching { Moovit.agencyRouteType(s, it) }.getOrDefault(3) }
                        }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    loading = false
                    status = T("Could not refresh · retrying shortly", "לא ניתן היה לרענן · ננסה שוב בקרוב")
                }
                delay(pollSecs * 1000L)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            loading = false
            status = T("online error: ${e.message ?: e.javaClass.simpleName}", "שגיאת רשת: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    val stopsById = remember(near) { near.associateBy { it.id } }
    val vehicles = remember(arrivals, lines, modes, stopsById) {
        arrivals.values.filter { it.hasLocation }.groupBy { it.tripId }.values.map { at ->
            val first = at.minBy { it.rtUtc.takeIf { t -> t > 0 } ?: it.staticUtc }
            val line = lines[first.lineId]
            Tracked(first, stopsById[first.stopId], line, first.lineId in lines, modes[line?.agencyId ?: -1] ?: 3)
        }.sortedBy { it.eta }
    }

    androidx.activity.compose.BackHandler(focus != null) {
        focus = when (val f = focus) {
            is LiveFocus.Vehicle -> f.from?.let { LiveFocus.Stop(it) }
            is LiveFocus.Line -> LiveFocus.Stop(f.from)
            else -> null
        }
    }
    androidx.compose.animation.AnimatedContent(
        targetState = focus,
        modifier = Modifier.fillMaxSize(),
        transitionSpec = {
            val deeper = targetState != null &&
                (initialState == null || (targetState !is LiveFocus.Stop && initialState is LiveFocus.Stop))
            if (deeper) forward() else backward()
        },
        label = "live",
    ) { f ->
        when (f) {
            is LiveFocus.Vehicle -> {
                LiveVehicleScreen(vehicles.firstOrNull { it.tripId == f.tripId }, now) {
                    focus = f.from?.let { LiveFocus.Stop(it) }
                }
                return@AnimatedContent
            }
            is LiveFocus.Stop -> {
                LiveStopScreen(
                    stopsById[f.id], f.id, arrivals, lines, modes, now,
                    onVehicle = { focus = LiveFocus.Vehicle(it, from = f.id) },
                    onLine = { number, destination ->
                        focus = LiveFocus.Line(number, destination, from = f.id)
                    },
                    onBack = { focus = null },
                )
                return@AnimatedContent
            }
            is LiveFocus.Line -> {
                LiveLineRoute(model, f, stopsById[f.from]) { focus = LiveFocus.Stop(f.from) }
                return@AnimatedContent
            }
            null -> Unit
        }
        Column(Modifier.fillMaxSize()) {
            ScreenHeader(T("Live", "כלי רכב בזמן אמת"), "", onSettings = { model.settingsOpen = true })
            Text(
                T(
                    "Online mode: positions come from Moovit's servers, refreshed every ${pollSecs}s.",
                    "מצב מקוון: המיקומים מגיעים משרתי Moovit, מתעדכן כל ${pollSecs} שניות.",
                ),
                fontSize = 11.sp, color = K.dim, lineHeight = 15.sp,
                modifier = Modifier.padding(horizontal = K.gap4).padding(bottom = K.gap2),
            )
            if (!located) Row(
                Modifier.padding(horizontal = K.gap3).padding(bottom = K.gap2),
                horizontalArrangement = Arrangement.spacedBy(K.gap2),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Chip(T("Use my location", "השתמשו במיקום שלי"), false) {
                    if (hasLocationPermission(ctx)) requestLocationOnce(ctx) { model.here = it }
                    else ask.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                }
                Text(T("showing central Tel Aviv", "מוצג מרכז תל אביב"), style = DisplayItalic, fontSize = 12.sp, color = K.dim)
            }
            Box(
                Modifier.padding(horizontal = K.gap3).fillMaxWidth().weight(1f)
                    .clip(RoundedCornerShape(14.dp)).background(K.surface1),
            ) {
                if (stopsReady) {
                    LiveMap(
                        here, near, vehicles,
                        onVehicle = { focus = LiveFocus.Vehicle(it.tripId) },
                        onStop = { focus = LiveFocus.Stop(it.id) },
                    )
                    Text(
                        status, style = Mono, fontSize = 11.sp, color = K.muted,
                        modifier = Modifier.align(Alignment.TopStart)
                            .padding(K.gap2).clip(RoundedCornerShape(6.dp)).background(K.glassPlate).padding(6.dp),
                    )
                } else Column(
                    Modifier.align(Alignment.Center).fillMaxWidth().padding(horizontal = K.gap5),
                    verticalArrangement = Arrangement.spacedBy(K.gap2),
                ) {
                    Text(
                        T("Finding the stops near you", "מאתרים את התחנות בסביבתכם"),
                        fontSize = 15.sp, color = K.text, fontWeight = FontWeight.Medium,
                    )
                    ProgressBar(scanned, Modifier.fillMaxWidth())
                    Text(
                        T("$found stops found · ${(scanned * 100).toInt()}%",
                            "נמצאו $found תחנות · ${(scanned * 100).toInt()}%"),
                        style = Mono, fontSize = 11.sp, color = K.dim,
                    )
                    stopsError?.let { Text(it, fontSize = 11.sp, color = K.problem, lineHeight = 15.sp) }
                }
            }
            val below = when {
                !stopsReady -> 0
                loading -> 1
                vehicles.isEmpty() -> 2
                else -> 3
            }
            AnimatedContent(
                targetState = below,
                transitionSpec = {
                    (fadeIn(tween(200, delayMillis = 90)) togetherWith fadeOut(tween(140)))
                        .using(SizeTransform { _, _ -> tween(300, easing = FastOutSlowInEasing) })
                },
                label = "live-below",
            ) { state ->
                when (state) {
                    0 -> Spacer(Modifier.fillMaxWidth())
                    1 -> Box(
                        Modifier.fillMaxWidth().height(200.dp + LocalBottomBarInset.current)
                            .padding(bottom = LocalBottomBarInset.current),
                        contentAlignment = Alignment.Center,
                    ) { LoadingPulse(T("Finding vehicles", "מאתרים כלי רכב")) }
                    else -> LiveList(vehicles, now, Modifier.fillMaxWidth()
                        .heightIn(max = 260.dp + LocalBottomBarInset.current).padding(K.gap2)) {
                        focus = LiveFocus.Vehicle(it.tripId)
                    }
                }
            }
        }
    }
}

private val K.glassPlate get() = androidx.compose.ui.graphics.Color(0xCC000000)

@Composable
private fun LiveLineRoute(model: KavModel, line: LiveFocus.Line, at: Moovit.Stop?, onBack: () -> Unit) {
    val ctx = LocalContext.current
    androidx.activity.compose.BackHandler(onBack = onBack)
    var net by remember { mutableStateOf(model.net) }
    var netError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        if (net != null) return@LaunchedEffect
        try {
            net = loadNet(ctx).also { model.net = it }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            netError = e.message ?: e.javaClass.simpleName
        }
    }
    val open = net
    var route by remember(open) { mutableStateOf<Int?>(null) }
    LaunchedEffect(open) {
        val n = open ?: return@LaunchedEffect
        route = withContext(Dispatchers.Default) {
            matchRoute(n, line.number, line.destination, at?.lat ?: Double.NaN, at?.lon ?: Double.NaN)
        }
    }
    val found = route
    if (open != null && found != null && found >= 0) {
        LineDetail(model, open, found, onBack)
        return
    }
    Column(Modifier.fillMaxSize().background(K.bg)) {
        ScreenHeader(T("Line", "קו"), line.number, back = onBack)
        when {
            netError != null -> Note(
                T("The offline timetable could not be opened: $netError",
                    "לא ניתן היה לפתוח את לוח הזמנים הלא מקוון: $netError"),
                Modifier.padding(K.gap4), K.problem,
            )
            found != null -> Note(
                T("Line ${line.number} is not in the offline timetable, so its route cannot be drawn.",
                    "קו ${line.number} אינו נמצא בלוח הזמנים הלא מקוון, ולכן לא ניתן לשרטט את המסלול שלו."),
                Modifier.padding(K.gap4),
            )
            else -> LoadingBlock(T("Finding the route", "מאתרים את המסלול"))
        }
    }
}

private fun matchRoute(net: Net, number: String, destination: String, lat: Double, lon: Double): Int {
    val want = number.trim()
    if (want.isEmpty()) return -1
    val candidates = (0 until net.nRoutes).filter { net.rShort[it].trim() == want }
    if (candidates.isEmpty()) return -1
    val wanted = candidates.toHashSet()
    val longest = HashMap<Int, Int>()
    for (t in net.tripRoute.indices) {
        val r = net.tripRoute[t]
        if (r !in wanted) continue
        val best = longest[r]
        if (best == null || net.tripStart[t + 1] - net.tripStart[t] >
            net.tripStart[best + 1] - net.tripStart[best]
        ) longest[r] = t
    }
    val here = if (lat.isFinite() && lon.isFinite())
        net.nearestStops(lat, lon, k = 1, radius = 150.0).firstOrNull()?.first else null
    var bestRoute = -1
    var bestScore = Int.MIN_VALUE
    for (r in candidates) {
        val t = longest[r] ?: continue
        val stops = (net.tripStart[t] until net.tripStart[t + 1]).map { net.stStop[it] }
        var score = stops.size
        if (here != null && here in stops) score += 10_000
        val end = stops.lastOrNull()?.let { net.name[it] }.orEmpty()
        val namesDestination = end.isNotBlank() &&
            (destination.contains(end) || end.contains(destination))
        if (destination.isNotBlank() && (namesDestination || net.rLong[r].contains(destination))) score += 100_000
        if (score > bestScore) { bestScore = score; bestRoute = r }
    }
    return bestRoute
}

@Composable
private fun LiveMap(
    center: Pair<Double, Double>,
    stops: List<Moovit.Stop>,
    vehicles: List<Tracked>,
    onVehicle: (Tracked) -> Unit,
    onStop: (Moovit.Stop) -> Unit,
) {
    val pulse = rememberLivePulse()
    val reach = with(LocalDensity.current) { 22.dp.toPx() }
    val stopReach = with(LocalDensity.current) { 20.dp.toPx() }
    val points = remember(stops, center) { stops.map { it.lat to it.lon } + center }
    val geometry = remember(stops, center) {
        MapGeometry(dots = stops.flatMap { st ->
            listOf(
                MapDot(st.lat, st.lon, K.bg, 6f),
                MapDot(st.lat, st.lon, Color.Transparent, 4.2f, K.muted, 1.6f),
            )
        } + listOf(
            MapDot(center.first, center.second, K.text.copy(alpha = 0.18f), 13f),
            MapDot(center.first, center.second, K.bg, 6f),
            MapDot(center.first, center.second, K.text, 4f),
        ))
    }
    TileMap(points, Modifier.fillMaxSize(), geometry = geometry, animatedOverlay = { proj ->
        for (v in vehicles) {
            val a = v.arrival
            val o = proj.point(a.lat, a.lon)
            val tint = if (a.vehicleStatus == 2) K.problem else K.live
            drawCircle(tint.copy(alpha = 0.20f), 18.dp.toPx() * pulse.value, o)
            drawCircle(K.bg, 11.dp.toPx(), o)
            drawCircle(tint, 9.dp.toPx(), o)
            drawModeMark(modeOf(v.routeType), o, 12.dp.toPx())
        }
    }, onTap = { at, proj ->
        val vehicle = vehicles.map { it to (proj.point(it.arrival.lat, it.arrival.lon) - at).getDistance() }
            .filter { it.second <= reach }.minByOrNull { it.second }?.first
        if (vehicle != null) onVehicle(vehicle)
        else stops.map { it to (proj.point(it.lat, it.lon) - at).getDistance() }
            .filter { it.second <= stopReach }.minByOrNull { it.second }?.let { onStop(it.first) }
    })
}

@Composable
private fun LinePlate(v: Tracked) {
    val agency = v.line?.agencyId ?: -1
    val plate = plateFor(v.routeType, agency)
    Row(
        Modifier.clip(RoundedCornerShape(7.dp)).background(plate?.fill ?: K.plate)
            .border(1.dp, plate?.edge ?: K.borderStrong, RoundedCornerShape(7.dp))
            .padding(start = 6.dp, end = 8.dp, top = 3.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AgencyMark(v.routeType, agency, plate?.ink ?: K.muted, 15.dp)
        Spacer(Modifier.width(5.dp))
        Text(
            v.number, fontSize = 16.sp, color = plate?.ink ?: K.text, fontWeight = FontWeight.Medium,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 96.dp),
        )
    }
}

private fun stopName(stop: Moovit.Stop?, id: Int) = stop?.name?.ifBlank { null } ?: T("stop $id", "תחנה $id")

@Composable
private fun LiveList(vehicles: List<Tracked>, now: Long, modifier: Modifier, onSelect: (Tracked) -> Unit) {
    if (vehicles.isEmpty()) {
        Note(
            T(
                "Nothing tracked near you right now. Vehicles appear here as soon as one of your stops has a bus reporting its position.",
                "אין כרגע כלי רכב במעקב בסביבתכם. כלי רכב יופיעו כאן ברגע שאחת התחנות שלכם תקבל דיווח מיקום מאוטובוס.",
            ),
            Modifier.padding(horizontal = K.gap4, vertical = K.gap3)
                .padding(bottom = LocalBottomBarInset.current),
        )
        return
    }
    LazyColumn(modifier, contentPadding = PaddingValues(
        start = K.gap1, top = K.gap1, end = K.gap1, bottom = K.gap1 + LocalBottomBarInset.current,
    )) {
        items(vehicles, key = { it.tripId }) { v ->
            val a = v.arrival
            val ageS = (now - a.sampleUtc).coerceAtLeast(0)
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(K.rControl))
                    .clickable(role = Role.Button) { onSelect(v) }
                    .padding(horizontal = K.gap3, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(K.gap3),
            ) {
                LinePlate(v)
                Column(Modifier.weight(1f)) {
                    Text(
                        v.line?.destination?.ifBlank { null }?.let { T("to $it", "אל $it") }
                            ?: if (v.pending) T("Looking up the line…", "מאתרים את הקו…") else T("Line ${v.number}", "קו ${v.number}"),
                        fontSize = 14.sp, color = K.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${stopName(v.stop, a.stopId)} · ${whenLabel(v.eta, now)}",
                        fontSize = 12.sp, color = K.dim, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(T("${ageS}s ago", "לפני ${ageS} שנ׳"), style = Mono, fontSize = 11.sp, color = if (a.vehicleStatus == 2) K.problem else K.live)
                Text(T.onward, fontSize = 22.sp, color = K.dim)
            }
        }
    }
}

@Composable
private fun LiveVehicleScreen(v: Tracked?, now: Long, onBack: () -> Unit) {
    var last by remember { mutableStateOf(v) }
    if (v != null) last = v
    val shown = v ?: last
    val pulse = rememberLivePulse()
    Column(Modifier.fillMaxSize().background(K.bg).verticalScroll(rememberScrollState())
        .padding(bottom = LocalBottomBarInset.current)) {
        ScreenHeader(T("Line", "קו"), shown?.number.orEmpty(), back = onBack)
        if (shown == null) {
            Note(T("This vehicle is no longer reporting.", "כלי הרכב הזה כבר לא משדר מיקום."), Modifier.padding(K.gap4))
            return@Column
        }
        val a = shown.arrival
        Row(
            Modifier.fillMaxWidth().padding(horizontal = K.gap4).padding(bottom = K.gap3),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(K.gap3),
        ) {
            LinePlate(shown)
            Column(Modifier.weight(1f)) {
                shown.line?.destination?.ifBlank { null }?.let {
                    Text(T("to $it", "אל $it"), fontSize = 14.sp, color = K.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                shown.line?.origin?.ifBlank { null }?.let {
                    Text(T("from $it", "מ-$it"), fontSize = 12.sp, color = K.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        val route = rememberLineRoute(a.tripShapeId)
        val stop = shown.stop
        val points = remember(route, a.lat, a.lon, stop) {
            listOf(a.lat to a.lon) + listOfNotNull(stop?.let { it.lat to it.lon }) + route
        }
        val geometry = remember(route, stop) {
            MapGeometry(
                lines = listOf(MapLine(route, K.route, 4f, casing = 8f)),
                dots = stop?.let {
                    listOf(MapDot(it.lat, it.lon, K.bg, 8f), MapDot(it.lat, it.lon, Color.Transparent, 5f, K.text, 2f))
                }.orEmpty(),
            )
        }
        TileMap(
            points,
            Modifier.padding(horizontal = K.gap3).fillMaxWidth().height(320.dp)
                .clip(RoundedCornerShape(K.rCard)).background(K.surface1),
            geometry = geometry,
            animatedOverlay = { proj ->
                val p = proj.point(a.lat, a.lon)
                val tint = if (a.vehicleStatus == 2) K.problem else K.live
                drawCircle(tint.copy(alpha = 0.20f), 20.dp.toPx() * pulse.value, p)
                drawCircle(K.bg, 12.dp.toPx(), p)
                drawCircle(tint, 10.dp.toPx(), p)
                drawModeMark(modeOf(shown.routeType), p, 13.dp.toPx())
            },
        )
        Spacer(Modifier.height(K.gap3))
        Column(
            Modifier.fillMaxWidth().padding(horizontal = K.gap3)
                .clip(RoundedCornerShape(K.rCard)).background(K.surface1).padding(K.gap4),
        ) {
            val (headline, tint) = when {
                a.vehicleStatus == 3 -> T("Not departed yet", "טרם יצא") to K.dim
                a.vehicleStatus == 2 -> T("Out of route", "מחוץ למסלול") to K.problem
                now - a.sampleUtc <= 120 -> T("Location updated recently", "המיקום עודכן לאחרונה") to K.live
                else -> T("Location is estimated", "המיקום משוער") to K.problem
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                LiveGlyph(tint, 13.dp); Spacer(Modifier.width(6.dp))
                Text(headline, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = tint)
            }
            if (a.sampleUtc > 0) Text(
                T("Location updated: ${hm.format(Date(a.sampleUtc * 1000))}", "המיקום עודכן: ${hm.format(Date(a.sampleUtc * 1000))}"),
                fontSize = 14.sp, color = K.dim, modifier = Modifier.padding(top = K.gap1),
            )
            Spacer(Modifier.height(K.gap3))
            LiveFact(T("Next of your stops", "התחנה הבאה שלכם"), stopName(stop, a.stopId))
            LiveFact(T("Arriving", "הגעה"), whenLabel(shown.eta, now))
            if (a.platform.isNotBlank()) LiveFact(T("Platform", "רציף"), a.platform)
            val away = a.stopsAway
            if (away >= 0) LiveFact(T("Stops away", "תחנות"), if (away == 0) T("at the stop", "בתחנה") else "$away")
            if (route.isEmpty() && a.tripShapeId > 0) LiveFact(T("Route", "מסלול"), T("loading…", "טוען…"))
        }
        Spacer(Modifier.height(K.gap8))
    }
}

@Composable
private fun LiveStopScreen(
    stop: Moovit.Stop?,
    stopId: Int,
    arrivals: Map<Moovit.ArrivalKey, Moovit.Arrival>,
    lines: Map<Int, Moovit.LineInfo?>,
    modes: Map<Int, Int>,
    now: Long,
    onVehicle: (Long) -> Unit,
    onLine: (number: String, destination: String) -> Unit,
    onBack: () -> Unit,
) {
    val due = remember(arrivals, stopId) {
        arrivals.values.filter { it.stopId == stopId }
            .sortedBy { a -> a.rtUtc.takeIf { it > 0 } ?: a.staticUtc }
    }
    var extraLines by remember { mutableStateOf<Map<Int, Moovit.LineInfo?>>(emptyMap()) }
    var extraModes by remember { mutableStateOf<Map<Int, Int>>(emptyMap()) }
    val lineIds = remember(due) { due.map { it.lineId }.distinct() }
    LaunchedEffect(lineIds) {
        val s = Online.session ?: return@LaunchedEffect
        for (batch in lineIds.filter { it !in lines && it !in extraLines }.chunked(8)) {
            val named = withContext(Dispatchers.IO) {
                batch.map { id -> async { id to runCatching { Moovit.lineInfo(s, id) }.getOrNull() } }.awaitAll().toMap()
            }
            extraLines = extraLines + named
            val agencies = named.values.mapNotNull { it?.agencyId }.distinct()
                .filter { it !in modes && it !in extraModes }
            if (agencies.isNotEmpty()) extraModes = extraModes + withContext(Dispatchers.IO) {
                agencies.associateWith { runCatching { Moovit.agencyRouteType(s, it) }.getOrDefault(3) }
            }
        }
    }
    val info = lines + extraLines
    val kinds = modes + extraModes
    val rows = remember(due, info, kinds, stop) {
        due.map { a ->
            val line = info[a.lineId]
            Tracked(a, stop, line, a.lineId in info, kinds[line?.agencyId ?: -1] ?: 3)
        }
    }
    val moving = remember(due) { due.filter { it.hasLocation } }
    val pulse = rememberLivePulse()

    Column(Modifier.fillMaxSize().background(K.bg)) {
        ScreenHeader(T("Stop", "תחנה"), stopName(stop, stopId), back = onBack)
        if (stop != null) {
            val points = remember(stop, moving) {
                listOf(stop.lat to stop.lon) + moving.map { it.lat to it.lon }
            }
            val geometry = remember(stop) {
                MapGeometry(dots = listOf(
                    MapDot(stop.lat, stop.lon, K.bg, 8f),
                    MapDot(stop.lat, stop.lon, Color.Transparent, 5f, K.text, 2f),
                ))
            }
            val reach = with(LocalDensity.current) { 22.dp.toPx() }
            TileMap(
                points,
                Modifier.padding(horizontal = K.gap3).fillMaxWidth().height(240.dp)
                    .clip(RoundedCornerShape(K.rCard)).background(K.surface1),
                geometry = geometry,
                animatedOverlay = { proj ->
                    for (a in moving) {
                        val o = proj.point(a.lat, a.lon)
                        val tint = if (a.vehicleStatus == 2) K.problem else K.live
                        drawCircle(tint.copy(alpha = 0.20f), 18.dp.toPx() * pulse.value, o)
                        drawCircle(K.bg, 11.dp.toPx(), o)
                        drawCircle(tint, 9.dp.toPx(), o)
                        drawModeMark(modeOf(kinds[info[a.lineId]?.agencyId ?: -1] ?: 3), o, 12.dp.toPx())
                    }
                },
                onTap = { at, proj ->
                    moving.map { it to (proj.point(it.lat, it.lon) - at).getDistance() }
                        .filter { it.second <= reach }.minByOrNull { it.second }
                        ?.let { onVehicle(it.first.tripId) }
                },
            )
            Spacer(Modifier.height(K.gap3))
        }
        if (rows.isEmpty()) {
            Note(
                T(
                    "Nothing is due at this stop right now.",
                    "אין כרגע יציאות מהתחנה הזו.",
                ),
                Modifier.padding(horizontal = K.gap4, vertical = K.gap3),
            )
            return@Column
        }
        Text(
            T("${rows.size} due · ${moving.size} reporting a position",
                "${rows.size} יציאות · ${moving.size} מדווחות מיקום"),
            style = Mono, fontSize = 11.sp, color = K.dim,
            modifier = Modifier.padding(horizontal = K.gap4).padding(bottom = K.gap2),
        )
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(
            start = K.gap1, end = K.gap1, bottom = K.gap1 + LocalBottomBarInset.current,
        )) {
            items(rows, key = { it.tripId }) { v ->
                val live = v.arrival.hasLocation
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(K.rControl))
                        .clickable(
                            role = Role.Button,
                            onClickLabel = if (live) T("Follow this vehicle", "מעקב אחר כלי הרכב")
                            else T("See where line ${v.number} goes", "המסלול של קו ${v.number}"),
                        ) {
                            if (live) onVehicle(v.tripId)
                            else onLine(v.number, v.line?.destination.orEmpty())
                        }
                        .padding(horizontal = K.gap3, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(K.gap3),
                ) {
                    LinePlate(v)
                    Column(Modifier.weight(1f)) {
                        Text(
                            v.line?.destination?.ifBlank { null }?.let { T("to $it", "אל $it") }
                                ?: if (v.pending) T("Looking up the line…", "מאתרים את הקו…") else T("Line ${v.number}", "קו ${v.number}"),
                            fontSize = 14.sp, color = K.text, maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (v.arrival.platform.isNotBlank()) {
                            Spacer(Modifier.height(K.gap1))
                            PlatformTag(v.arrival.platform)
                        }
                    }
                    if (live) LiveGlyph(if (v.arrival.vehicleStatus == 2) K.problem else K.live, 11.dp)
                    Text(
                        whenLabel(v.eta, now), style = Mono, fontSize = 13.sp,
                        color = if (live) K.text else K.scheduled,
                    )
                    Text(T.onward, fontSize = 22.sp, color = K.dim)
                }
            }
        }
    }
}

@Composable
private fun LiveFact(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = K.gap2)) {
        Text(label, fontSize = 14.sp, color = K.dim, modifier = Modifier.width(130.dp))
        Text(value, fontSize = 14.sp, color = K.text, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    }
}
