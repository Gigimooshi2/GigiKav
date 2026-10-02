package uk.noammm.kav.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import kotlinx.coroutines.flow.collect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.layout
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import uk.noammm.kav.KavModel
import uk.noammm.kav.data.Moovit
import uk.noammm.kav.data.Net
import uk.noammm.kav.data.representativeTrip
import uk.noammm.kav.data.nearestStops
import uk.noammm.kav.data.searchRoutes

private val FILTERS = listOf(
    "All" to intArrayOf(),
    "Bus" to intArrayOf(3, 11, 715, 8),
    "Light rail" to intArrayOf(0, 1, 12),
    "Israel Railways" to intArrayOf(2),
    "Carmelit / Rakavlit" to intArrayOf(7, 5, 6),
    "Shuttle" to intArrayOf(711),
)

private fun filterLabel(label: String): String = when (label) {
    "Bus" -> T("Bus", "אוטובוס")
    "Light rail" -> T("Light rail", "רכבת קלה")
    "Israel Railways" -> T("Israel Railways", "רכבת ישראל")
    "Carmelit / Rakavlit" -> T("Carmelit / Rakavlit", "כרמלית / רכבלית")
    "Shuttle" -> T("Shuttle", "שאטל")
    else -> T("All", "הכול")
}

@Composable
fun LinesScreen(model: KavModel) {
    WithTimetable(model) { net -> LinesBody(model, net) }
}

@Composable
private fun LinesBody(model: KavModel, net: Net) {
    androidx.activity.compose.BackHandler(model.lineRoute >= 0) { model.lineRoute = -1 }
    androidx.compose.animation.AnimatedContent(
        targetState = model.lineRoute,
        modifier = Modifier.fillMaxSize(),
        transitionSpec = { if (targetState >= 0) forward() else backward() },
        label = "line",
    ) { route ->
        if (route >= 0) LineDetail(model, net, route) { model.lineRoute = -1 }
        else LineList(model, net)
    }
}

@Composable
private fun LineList(model: KavModel, net: Net) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var q by model::lineQuery
    var types by remember { mutableStateOf(FILTERS[0].second) }
    var sections by remember(net) { mutableStateOf(emptyList<Pair<String, List<LineRow>>>()) }
    var endpoints by remember(net) { mutableStateOf(emptyMap<Int, Pair<Int, Int>>()) }
    LaunchedEffect(net) {
        endpoints = withContext(Dispatchers.Default) { lineEndpoints(net) }
    }
    LaunchedEffect(net, q, types, endpoints) {
        sections = withContext(Dispatchers.Default) { foldLines(net, q, types, endpoints) }
    }
    var lead by remember(net) { mutableStateOf(emptyList<Pair<String, List<LineRow>>>()) }
    LaunchedEffect(Unit) {
        if (model.here == null && uk.noammm.kav.hasLocationPermission(ctx)) {
            uk.noammm.kav.requestLocationOnce(ctx) { model.here = it }
        }
    }
    LaunchedEffect(net, endpoints, model.here, q, types) {
        lead = if (q.isNotBlank() || types.isNotEmpty()) emptyList()
        else withContext(Dispatchers.Default) { leadSections(ctx, net, model.here, endpoints) }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(T("Browse the", "עיינו"), T("lines", "בקווים"))
        val listState = rememberLazyListState()
        var scrolled by remember(net) { mutableStateOf(false) }
        LaunchedEffect(listState) {
            snapshotFlow { listState.isScrollInProgress }.collect { if (it) scrolled = true }
        }
        LaunchedEffect(lead) { if (lead.isNotEmpty() && !scrolled) listState.scrollToItem(0) }
        Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(
            start = K.gap2, end = K.gap2, bottom = LocalBottomBarInset.current,
        )) {
            item(key = "search") {
                Column {
                    KavField(q, { q = it }, T("line number or name…", "מספר או שם קו…"), Modifier.padding(horizontal = K.gap1).fillMaxWidth())
                    Spacer(Modifier.height(K.gap2))
                    Row(
                        Modifier
                            .bleed(K.gap2)
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = K.gap3),
                        horizontalArrangement = Arrangement.spacedBy(K.gap2),
                    ) {
                        FILTERS.forEach { (label, t) -> Chip(filterLabel(label), types === t) { types = t } }
                    }
                    Spacer(Modifier.height(K.gap3))
                }
            }
            item(key = "count") {
                val total = sections.sumOf { it.second.size }
                Text(
                    T("$total lines · choose a line to see its stops", "$total קווים · בחרו קו כדי לראות את התחנות שלו"),
                    fontSize = 12.sp, color = K.dim,
                    modifier = Modifier.padding(horizontal = K.gap3).padding(bottom = K.gap1),
                )
            }
            lead.forEach { (label, rows) ->
                item(key = "lead-$label") { SectionLabel(label) }
                items(rows, key = { "lead-$label-${it.route}" }) { row -> LineRowCard(model, net, row) }
            }
            sections.forEach { (operator, rows) ->
                if (operator.isNotBlank()) item(key = "op-$operator") {
                    SectionLabel(operatorLabel(operator))
                }
                items(rows, key = { it.route }) { row -> LineRowCard(model, net, row) }
            }
        }
        ScrollEdge(listState.canScrollBackward)
        }
    }
}

private fun Modifier.bleed(by: androidx.compose.ui.unit.Dp): Modifier = layout { measurable, constraints ->
    val side = by.roundToPx()
    val placeable = measurable.measure(
        constraints.copy(minWidth = constraints.minWidth + 2 * side, maxWidth = constraints.maxWidth + 2 * side),
    )
    layout(constraints.maxWidth, placeable.height) { placeable.place(-side, 0) }
}

@Composable
private fun SectionLabel(label: String) {
    Text(
        label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = K.dim,
        modifier = Modifier.padding(start = K.gap2, end = K.gap2, top = K.gap3, bottom = K.gap1),
    )
}

@Composable
private fun LineRowCard(model: KavModel, net: Net, row: LineRow) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = K.gap1)
            .panel(K.rControl)
            .clickable { model.lineRoute = row.route }
            .padding(K.gap3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(K.gap3),
    ) {
        LineIdentity(net, row.route)
        LineSpan(net, row, Modifier.weight(1f))
        Text(T.onward, fontSize = 22.sp, color = K.dim)
    }
}

private fun lineEndpoints(net: Net): Map<Int, Pair<Int, Int>> {
    val longest = IntArray(net.nRoutes) { -1 }
    for (t in net.tripRoute.indices) {
        val route = net.tripRoute[t]
        val previous = longest[route]
        if (previous < 0 || net.tripStart[t + 1] - net.tripStart[t] >
            net.tripStart[previous + 1] - net.tripStart[previous]) longest[route] = t
    }
    return buildMap {
        longest.forEachIndexed { route, t ->
            if (t >= 0 && net.tripStart[t + 1] > net.tripStart[t])
                put(route, net.stStop[net.tripStart[t]] to net.stStop[net.tripStart[t + 1] - 1])
        }
    }
}

private data class LineRow(val route: Int, val a: Int, val b: Int, val both: Boolean)

private fun terminusName(net: Net, stop: Int): String = net.name[stop].substringBefore('/').trim()

internal fun lineKey(net: Net, r: Int) =
    "${net.rAgency.getOrElse(r) { -1 }}|${net.rType[r]}|${net.rShort[r]}|${net.rLong[r]}"

private fun namesakeEnds(net: Net, ends: Map<Int, Pair<Int, Int>>): Map<String, Pair<Int, Int>> {
    val byName = HashMap<String, Pair<Int, Int>>(ends.size * 2)
    for ((r, e) in ends) byName.putIfAbsent(lineKey(net, r), e)
    return byName
}

private fun foldRoutes(
    net: Net, routes: Iterable<Int>, ends: Map<Int, Pair<Int, Int>>,
    byName: Map<String, Pair<Int, Int>>,
): Collection<LineRow> {
    val rows = LinkedHashMap<String, LineRow>()
    for (r in routes) {
        val e = ends[r] ?: byName[lineKey(net, r)]
        if (e == null && ends.isNotEmpty()) continue
        val key = if (e == null) "lone-$r" else {
            val a = terminusName(net, e.first); val b = terminusName(net, e.second)
            val (lo, hi) = if (a <= b) a to b else b to a
            "${net.rAgency.getOrElse(r) { -1 }}|${net.rType[r]}|${net.rShort[r]}|$lo $hi"
        }
        val prev = rows[key]
        if (prev == null) rows[key] = LineRow(r, e?.first ?: -1, e?.second ?: -1, false)
        else if (!prev.both) rows[key] = prev.copy(both = true)
    }
    return rows.values
}

private fun foldLines(
    net: Net, q: String, types: IntArray, ends: Map<Int, Pair<Int, Int>>,
): List<Pair<String, List<LineRow>>> {
    val rows = foldRoutes(net, net.searchRoutes(q, types).asIterable(), ends, namesakeEnds(net, ends))
    return rows.groupBy { net.agencyOf(it.route) }.mapValues { (_, rs) ->
        rs.sortedWith(compareBy(
            { net.rShort[it.route].toIntOrNull() ?: Int.MAX_VALUE },
            { net.rShort[it.route] },
            { if (it.a >= 0) terminusName(net, it.a) else "" },
        ))
    }.toList()
}

private fun leadSections(
    ctx: android.content.Context, net: Net, here: Pair<Double, Double>?,
    ends: Map<Int, Pair<Int, Int>>,
): List<Pair<String, List<LineRow>>> {
    if (ends.isEmpty()) return emptyList()
    val byName = namesakeEnds(net, ends)
    val out = ArrayList<Pair<String, List<LineRow>>>()

    val recent = uk.noammm.kav.Prefs.recentLines(ctx)
    if (recent.isNotEmpty()) {
        val slot = HashMap<String, Int>()
        recent.forEachIndexed { i, k -> slot.putIfAbsent(k, i) }
        val routes = arrayOfNulls<Int>(recent.size)
        for (r in 0 until net.nRoutes) {
            val i = slot[lineKey(net, r)] ?: continue
            if (routes[i] == null) routes[i] = r
        }
        val rows = foldRoutes(net, routes.filterNotNull(), ends, byName).toList()
        if (rows.isNotEmpty()) out.add(T("Recent", "אחרונים") to rows)
    }

    if (here != null) {
        val near = net.nearestStops(here.first, here.second, k = 20, radius = 900.0)
        if (near.isNotEmpty()) {
            val rank = HashMap<Int, Int>(near.size * 2)
            near.forEachIndexed { i, (s, _) -> rank[s] = i }
            val best = HashMap<Int, Int>()
            for (t in net.tripRoute.indices) {
                val route = net.tripRoute[t]
                for (i in net.tripStart[t] until net.tripStart[t + 1]) {
                    val o = rank[net.stStop[i]] ?: continue
                    val held = best[route]
                    if (held == null || o < held) best[route] = o
                }
            }
            val routes = best.entries.sortedBy { it.value }.map { it.key }
            val rows = foldRoutes(net, routes, ends, byName).take(10)
            if (rows.isNotEmpty()) out.add(T("Nearby", "בקרבת מקום") to rows)
        }
    }
    return out
}

private fun operatorLabel(name: String): String = when (name) {
    "רכבת ישראל" -> T("Israel Railways", "רכבת ישראל")
    "כרמלית" -> T("Carmelit", "כרמלית")
    "כבל אקספרס" -> T("Rakavlit (Cable Express)", "רכבלית (כבל אקספרס)")
    "דן נתיבים בעמ" -> T("Dan Nativim", "דן נתיבים")
    else -> name
}

@Composable
private fun LineSpan(net: Net, row: LineRow, modifier: Modifier = Modifier) {
    if (row.a < 0) {
        Text(T("Route stops", "תחנות המסלול"), fontSize = 15.sp, color = K.muted, modifier = modifier)
        return
    }
    val a = terminusName(net, row.a); val b = terminusName(net, row.b)
    val text = when {
        a == b -> "⁨$a⁩"
        row.both -> "⁨$a⁩ ↔ ⁨$b⁩"
        else -> "⁨$a⁩ → ⁨$b⁩"
    }
    Text(
        text, fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.Medium,
        color = K.text, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = modifier,
    )
}

@Composable
private fun LineIdentity(net: Net, route: Int) {
    val rt = net.rType[route]
    val marked = rt == 711 || isRail(rt, -1) || modeOf(rt) == Mode.FUNICULAR ||
        modeOf(rt) == Mode.CABLE || modeOf(rt) == Mode.GONDOLA
    val logoOnly = modeOf(rt) == Mode.FUNICULAR || modeOf(rt) == Mode.CABLE ||
        modeOf(rt) == Mode.GONDOLA
    val number = net.rShort[route]
    Column(Modifier.widthIn(min = 54.dp, max = 88.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (marked) {
            AgencyMark(rt, -1, K.muted, if (number.isBlank() || logoOnly) 26.dp else 17.dp)
            Spacer(Modifier.height(2.dp))
        }
        if ((number.isNotBlank() && !logoOnly) || !marked) {
            Text(number.ifBlank { "-" }, fontSize = 23.sp, color = K.text,
                fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(typeName(rt), fontSize = 11.sp, color = K.dim)
    }
}

@Composable
private fun LineDirection(net: Net, endpoints: Pair<Int, Int>?, modifier: Modifier = Modifier) {
    fun name(stop: Int): String = net.name[stop] + net.cityOf(stop).takeIf { it.isNotBlank() }
        ?.let { " · $it" }.orEmpty()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(K.gap1)) {
        if (endpoints == null) Text(T("Route stops", "\u05ea\u05d7\u05e0\u05d5\u05ea \u05d4\u05de\u05e1\u05dc\u05d5\u05dc"), fontSize = 15.sp, color = K.muted)
        else {
            Text(T("To \u2068${name(endpoints.second)}\u2069", "\u05d0\u05dc \u2068${name(endpoints.second)}\u2069"), fontSize = 15.sp, lineHeight = 20.sp,
                fontWeight = FontWeight.Medium, color = K.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(T("From \u2068${name(endpoints.first)}\u2069", "\u05de\u05be\u2068${name(endpoints.first)}\u2069"), fontSize = 12.sp, lineHeight = 17.sp,
                color = K.dim, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
internal fun LineDetail(model: KavModel, net: Net, route: Int, onBack: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(route) { uk.noammm.kav.Prefs.rememberLine(ctx, lineKey(net, route)) }
    var stops by remember(route) { mutableStateOf<List<Int>?>(null) }
    LaunchedEffect(route) {
        stops = withContext(Dispatchers.Default) {
            val t = net.representativeTrip(route)
            if (t < 0) emptyList()
            else (net.tripStart[t] until net.tripStart[t + 1]).map { net.stStop[it] }
        }
    }

    Column(Modifier.fillMaxSize().background(K.bg)) {
        ScreenHeader(T("Line", "קו"), net.rShort[route], back = onBack)
        Row(
            Modifier.padding(horizontal = K.gap4).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(K.gap3),
        ) {
            LineIdentity(net, route)
            LineDirection(net, stops?.takeIf { it.isNotEmpty() }?.let { it.first() to it.last() }, Modifier.weight(1f))
        }
        val list = stops
        if (!list.isNullOrEmpty()) {
            val live = rememberLineLive(net, route, list)
            LineMap(
                net, route, list, live,
                Modifier.padding(horizontal = K.gap3).padding(top = K.gap3).fillMaxWidth().height(210.dp).panel(K.rCard),
            )
            val next = remember(route) { nextStart(net, route) }?.let { (t, at) ->
                val from = net.name[net.stStop[net.tripStart[t]]]
                T("Next departure from $from: ${startLabel(at)}", "היציאה הבאה מ-$from: ${startLabel(at)}")
            }
            LineLiveNote(live, next)
        }
        if (list == null) LoadingBlock(T("Loading stops", "טוען תחנות…"))
        else Text(
            if (list.isEmpty()) T("no trips on this line in the loaded timetable", "אין נסיעות בקו הזה בלוח הזמנים הטעון")
            else T("${list.size} stops · full route", "${list.size} תחנות · המסלול המלא"),
            fontSize = 11.sp, color = K.dim,
            modifier = Modifier.padding(horizontal = K.gap4, vertical = K.gap2),
        )
        if (list != null) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(
                start = K.gap2, end = K.gap2, bottom = LocalBottomBarInset.current,
            )) {
                items(list.size) { i ->
                    val s = list[i]
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { model.stationStop = s; model.tab = uk.noammm.kav.Tab.Stations }
                            .padding(horizontal = K.gap3, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.width(18.dp), contentAlignment = Alignment.Center) {
                            Box(
                                Modifier.size(7.dp)
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(if (i == 0 || i == list.lastIndex) K.text else K.surface4),
                            )
                        }
                        Spacer(Modifier.width(K.gap3))
                        Column(Modifier.weight(1f)) {
                            Text(
                                net.name[s], fontSize = 13.sp,
                                color = if (i == 0 || i == list.lastIndex) K.text else K.muted,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            val c = net.cityOf(s)
                            if (c.isNotBlank()) Text(c, fontSize = 11.sp, color = K.dim, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

private class LineLive(
    val checked: Boolean,
    val failed: Boolean = false,
    val vehicles: List<Moovit.Arrival> = emptyList(),
    val shapeId: Int = -1,
)

@Composable
private fun rememberLineLive(net: Net, route: Int, stops: List<Int>): LineLive {
    val live by produceState(LineLive(checked = false), route, stops) {
        val number = net.rShort[route].trim()
        while (true) {
            value = try {
                withContext(Dispatchers.IO) { lineLive(net, stops, number) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (value.checked && !value.failed) value else LineLive(checked = true, failed = true)
            }
            delay(20_000)
        }
    }
    return live
}

private suspend fun lineLive(net: Net, stops: List<Int>, number: String): LineLive = coroutineScope {
    val ids = stops.chunked(8).flatMap { batch -> batch.map { async { StopPhotos.idOf(net, it) } }.awaitAll() }
        .filterNotNull().distinct()
    if (ids.isEmpty()) return@coroutineScope LineLive(checked = true)
    val s = Online.open(net.lat[stops[0]] to net.lon[stops[0]])
    val arrivals = Moovit.stopArrivals(s, ids).first.values
    val ours = arrivals.map { it.lineId }.distinct().chunked(8).flatMap { batch ->
        batch.map { id -> async { id to runCatching { Moovit.lineInfo(s, id) }.getOrNull() } }.awaitAll()
    }.filter { (_, info) -> info?.number?.trim() == number }.map { it.first }.toSet()
    val candidates = arrivals.filter { it.lineId in ours }
    val order = ids.withIndex().associate { (i, id) -> id to i }
    val sameWay = candidates.map { it.patternId }.filter { it > 0 }.distinct().map { id ->
        async { id to runCatching { Moovit.tripPattern(s, id) }.getOrDefault(emptyList()) }
    }.awaitAll().filter { (_, pattern) ->
        val at = pattern.mapNotNull { order[it] }
        at.size >= 2 && at.zipWithNext().all { (a, b) -> a < b }
    }.map { it.first }.toSet()
    val mine = candidates.filter { it.patternId in sameWay }
    val vehicles = mine.filter { it.hasLocation }.groupBy { it.tripId }.values
        .map { at -> at.minBy { it.departure().timeUtc } }
    LineLive(checked = true, vehicles = vehicles, shapeId = (vehicles.firstOrNull() ?: mine.firstOrNull())?.tripShapeId ?: -1)
}

@Composable
private fun LineMap(net: Net, route: Int, stops: List<Int>, live: LineLive, modifier: Modifier) {
    val shape = rememberLineRoute(live.shapeId)
    val tint = plateFor(net.rType[route])?.fill ?: K.route
    val calls = remember(stops) { stops.map { net.lat[it] to net.lon[it] } }
    val path = shape.ifEmpty { calls }
    val geometry = remember(path, calls, tint, K.light) {
        val start = onRoute(calls.first(), path)
        val end = onRoute(calls.last(), path)
        MapGeometry(
            lines = listOf(MapLine(path, tint, 4f, casing = 8f)),
            dots = calls.drop(1).dropLast(1).flatMap { at ->
                val (lat, lon) = onRoute(at, path)
                listOf(MapDot(lat, lon, K.bg, 6f), MapDot(lat, lon, Color.Transparent, 3.5f, tint, 2f))
            } + listOf(
                MapDot(start.first, start.second, K.bg, 7f),
                MapDot(start.first, start.second, Color.Transparent, 5f, K.text, 2f),
                MapDot(end.first, end.second, K.bg, 8f),
                MapDot(end.first, end.second, K.text, 5f),
            ),
        )
    }
    val mode = modeOf(net.rType[route])
    TileMap(
        path, modifier, geometry = geometry,
        live = vehicleGeometry(live.vehicles.map { it to mode }),
    )
}

@Composable
private fun LineLiveNote(live: LineLive, next: String?) {
    val count = live.vehicles.size
    val (text, tint) = when {
        !live.checked -> T("Checking for a live location…", "בודקים מיקום בזמן אמת…") to K.dim
        live.failed -> T("Couldn't check for a live location", "לא ניתן היה לבדוק מיקום בזמן אמת") to K.dim
        count == 0 -> T("This line doesn't have a live location right now", "לקו הזה אין כרגע מיקום בזמן אמת") to K.dim
        else -> T("Live location · $count on the road", "מיקום בזמן אמת · $count בדרך") to K.live
    }
    Row(
        Modifier.padding(horizontal = K.gap4).padding(top = K.gap2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (count > 0) { LiveGlyph(tint, 13.dp); Spacer(Modifier.width(6.dp)) }
        Text(text, fontSize = 13.sp, color = tint)
    }
    if (live.checked && count == 0 && next != null) Text(
        next, fontSize = 13.sp, color = K.muted,
        modifier = Modifier.padding(horizontal = K.gap4).padding(top = K.gap1),
    )
}

// Seconds from today's midnight.
private fun nextStart(net: Net, route: Int): Pair<Int, Long>? {
    val today = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_WEEK) - 1
    val now = nowSec()
    var best = -1
    var bestAt = Long.MAX_VALUE
    for (t in net.tripRoute.indices) {
        if (net.tripRoute[t] != route || net.tripStart[t + 1] <= net.tripStart[t]) continue
        val dep = net.stDep[net.tripStart[t]].toLong()
        for (k in -1..7) {
            if (!net.runsOn(t, ((today + k) % 7 + 7) % 7)) continue
            val at = k * 86_400L + dep
            if (at >= now && at < bestAt) { bestAt = at; best = t }
        }
    }
    return if (best < 0) null else best to bestAt
}

private fun startLabel(at: Long): String {
    val time = hhmm((at % 86_400).toInt())
    return when (val days = (at / 86_400).toInt()) {
        0 -> T("today $time", "היום $time")
        1 -> T("tomorrow $time", "מחר $time")
        else -> {
            val day = java.util.Calendar.getInstance().apply { add(java.util.Calendar.DAY_OF_YEAR, days) }
            java.text.SimpleDateFormat("EEEE", T.locale).format(day.time) + " " + time
        }
    }
}
