package uk.noammm.kav.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import uk.noammm.kav.hasLocationPermission
import uk.noammm.kav.requestLocationOnce
import uk.noammm.kav.data.Moovit
import uk.noammm.kav.data.Net
import uk.noammm.kav.data.nearestStops
import uk.noammm.kav.data.stopsMatching
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight

@Composable
fun KavField(
    value: String,
    onValue: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    autoFocus: Boolean = false,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(autoFocus) { if (autoFocus) runCatching { focus.requestFocus() } }
    BasicTextField(
        value = value,
        onValueChange = onValue,
        singleLine = true,
        textStyle = TextStyle(color = K.text, fontSize = 15.sp),
        cursorBrush = SolidColor(K.text),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .glassSurface(24.dp)
            .focusRequester(focus)
            .semantics { contentDescription = placeholder }
            .padding(horizontal = K.gap4, vertical = 12.dp),
        decorationBox = { innerTextField ->
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) Text(placeholder, fontSize = 15.sp, color = K.dim)
                innerTextField()
            }
        },
    )
}

@Composable
fun StopRow(net: Net, stop: Int, trailing: String? = null, leading: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = K.gap1)
            .heightIn(min = 48.dp)
            .panel(K.rControl)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = K.gap3, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(K.gap3))
        }
        Column(Modifier.weight(1f)) {
            Text(
                net.name[stop], fontSize = 15.sp, color = K.text,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            val city = net.cityOf(stop)
            val code = net.code.getOrElse(stop) { 0 }
            val second = listOf(city.takeIf { it.isNotBlank() }, code.takeIf { it > 0 }?.toString())
                .filterNotNull().joinToString(" · ")
            if (second.isNotBlank()) {
                Text(second, fontSize = 14.sp, color = K.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(K.gap2))
            Text(trailing, fontSize = 14.sp, color = K.dim)
        }
    }
}

@Composable
fun PlacePicker(
    title: String,
    here: Pair<Double, Double>?,
    allowMyLocation: Boolean,
    onMyLocation: () -> Unit,
    onPick: (Moovit.Place) -> Unit,
    onDismiss: () -> Unit,
    initialSetting: Favourite? = null,
    favourites: List<Favourite>,
    onSaveFavourites: (List<Favourite>) -> Unit,
    query: String,
    onQuery: (String) -> Unit,
    onLocate: (Pair<Double, Double>) -> Unit = {},
    net: Net? = null,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val q = query
    var places by remember { mutableStateOf<List<Moovit.Place>>(emptyList()) }
    var google by remember { mutableStateOf<List<uk.noammm.kav.data.GooglePlaces.Suggestion>>(emptyList()) }
    var resolving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val placesKey = remember { uk.noammm.kav.Prefs.placesKey(ctx) }
    var stations by remember { mutableStateOf<List<Moovit.Place>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var recents by remember { mutableStateOf(uk.noammm.kav.Prefs.recents(ctx)) }
    var setting by remember { mutableStateOf(initialSetting) }
    var editing by remember { mutableStateOf<Favourite?>(null) }
    var creating by remember { mutableStateOf(false) }
    fun save(list: List<Favourite>) = onSaveFavourites(list)
    val pick: (Moovit.Place) -> Unit = { p ->
        val f = setting
        if (f != null) {
            save(favourites.map { if (it.id == f.id) it.copy(place = p) else it })
            setting = null
            onQuery("")
            if (initialSetting != null) onDismiss()
        } else {
            uk.noammm.kav.Prefs.remember(ctx, p)
            onPick(p)
        }
    }

    LaunchedEffect(Unit) {
        try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                val n = net ?: uk.noammm.kav.loadNet(ctx)
                n.stopWords; n.stopType
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    LaunchedEffect(q) {
        error = null
        if (q.isBlank()) {
            places = emptyList(); stations = emptyList(); google = emptyList(); busy = false
            return@LaunchedEffect
        }
        busy = true
        kotlinx.coroutines.delay(280)
        val at = here
        val near = async(kotlinx.coroutines.Dispatchers.Default) {
            try {
                val n = net ?: uk.noammm.kav.loadNet(ctx)
                n.stopsMatching(q, at) { modeName(modeOf(it)) }.also { StopPhotos.prefetchNet(n, it) }.map { i ->
                    placeOf(n, i, at?.let { Math.round(metres(it.first, it.second, n.lat[i], n.lon[i])).toInt() } ?: -1)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
        }
        val online = async(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val s = Online.open(at ?: (32.0759 to 34.7745))
                Result.success(Moovit.searchPlaces(s, q, at))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
        // Businesses come from Google when the user has set a key; any failure (quota, no network) just leaves Moovit's results.
        val fromGoogle = async<List<uk.noammm.kav.data.GooglePlaces.Suggestion>>(kotlinx.coroutines.Dispatchers.IO) {
            val key = placesKey ?: return@async emptyList()
            if (q.trim().length < 3) return@async emptyList()
            try {
                uk.noammm.kav.data.GooglePlaces.autocomplete(key, ctx.packageName, q.trim(), at?.takeIf { Moovit.shareLocation })
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("KavPlaces", "Google autocomplete failed", e)
                emptyList()
            }
        }
        val waited = kotlinx.coroutines.withTimeoutOrNull(3000) { online.await() }
        stations = near.await()
        busy = waited == null && stations.isEmpty()
        val answer = waited ?: online.await()
        places = answer.getOrDefault(emptyList())
        google = kotlinx.coroutines.withTimeoutOrNull(2500) { fromGoogle.await() } ?: emptyList()
        error = answer.exceptionOrNull()?.takeIf { stations.isEmpty() && google.isEmpty() }?.let { it.message ?: it.javaClass.simpleName }
        busy = false
    }

    var onMap by remember { mutableStateOf(false) }
    if (onMap) {
        StopMapPicker(
            net, here, onPick = { p -> onMap = false; pick(p) }, onDismiss = { onMap = false },
            onLocate = onLocate, allowPin = true,
        )
        return
    }
    val leave: () -> Unit = { if (setting != null && initialSetting == null) setting = null else onDismiss() }
    androidx.activity.compose.BackHandler(onBack = leave)
    var locating by remember { mutableStateOf(false) }
    val askHere = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { ok ->
        if (ok) { locating = true; requestLocationOnce(ctx) { onLocate(it); locating = false } }
        else locating = false
    }
    val showRecents = q.isBlank() && recents.isNotEmpty()
    val found = q.isNotBlank() && (places.isNotEmpty() || stations.isNotEmpty() || google.isNotEmpty())

    Box(Modifier.fillMaxSize().background(K.bg)) {
    FloatingTop(top = {
        Row(
            Modifier.fillMaxWidth().padding(K.gap3).heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(K.gap2),
        ) {
            BackButton(leave)
            KavField(q, onQuery, setting?.let { T("where is ${it.label}?", "היכן נמצא ${it.label}?") } ?: title, Modifier.weight(1f), autoFocus = true)
        }
    }, estimate = 72.dp) { topSpace, backdrop ->
    LazyColumn(
        Modifier.fillMaxSize().then(backdrop),
        contentPadding = PaddingValues(top = topSpace, bottom = LocalBottomBarInset.current),
    ) {
        if (q.isBlank() && setting == null) item(key = "favourites") {
            FavouriteStrip(
                favourites,
                onPick = { f -> f.place?.let(pick) ?: run { setting = f } },
                onAdd = { creating = true },
                onEdit = { editing = it },
            )
        }
        setting?.let { f ->
            item(key = "setting") {
                Note(
                    T(
                        "Search for where ${f.label} is. The place you pick is kept as ${f.label}.",
                        "חפשו היכן נמצא ${f.label}. המקום שתבחרו יישמר בתור ${f.label}.",
                    ),
                    Modifier.padding(horizontal = K.gap4, vertical = K.gap2),
                )
            }
        }
        item(key = "map") { SelectOnMapRow { onMap = true } }
        if (allowMyLocation) item(key = "here") {
            val ready = here != null
            Row(
                Modifier.fillMaxWidth().padding(horizontal = K.gap3, vertical = K.gap2).heightIn(min = 48.dp)
                    .glassSurface(24.dp)
                    .clickable(role = Role.Button) {
                        if (ready) onMyLocation()
                        else if (hasLocationPermission(ctx)) {
                            locating = true
                            requestLocationOnce(ctx) { onLocate(it); locating = false }
                        } else {
                            askHere.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                        }
                    }
                    .padding(horizontal = K.gap4, vertical = K.gap3),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(K.gap2),
            ) {
                Box(
                    Modifier.size(8.dp).clip(RoundedCornerShape(999.dp))
                        .background(if (ready) K.live else K.dim),
                )
                Text(
                    if (ready) T("My location", "המיקום שלי") else if (locating) T("Finding you…", "מאתרים אתכם…") else T("Use my location", "השתמשו במיקום שלי"),
                    fontSize = 15.sp, color = if (ready) K.live else K.muted,
                )
                if (!ready && !locating) Text(
                    T("location is off", "המיקום כבוי"),
                    fontSize = 12.sp, color = K.dim, modifier = Modifier.padding(start = K.gap1),
                )
            }
        }
        when {
            error != null -> item { Note(T("Search failed: $error", "החיפוש נכשל: $error"), Modifier.padding(horizontal = K.gap4, vertical = K.gap4)) }
            q.isBlank() && recents.isEmpty() -> item {
                Note(T("Search a station, street or place.", "חפשו תחנה, רחוב או מקום."), Modifier.padding(horizontal = K.gap4, vertical = K.gap4))
            }
            q.isBlank() -> Unit
            busy && places.isEmpty() && stations.isEmpty() && google.isEmpty() -> item {
                Box(Modifier.fillMaxWidth().padding(vertical = K.gap8), contentAlignment = Alignment.Center) {
                    LoadingPulse(T("Searching", "מחפשים"))
                }
            }
            places.isEmpty() && stations.isEmpty() && google.isEmpty() -> item {
                Note(T("Nothing found.", "לא נמצאו תוצאות."), Modifier.padding(horizontal = K.gap4, vertical = K.gap4))
            }
        }
        if (showRecents) {
            item(key = "recentHead") {
                Row(
                    Modifier.fillMaxWidth().padding(start = K.gap4, end = K.gap3, top = K.gap2, bottom = K.gap1),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(T("Recent", "אחרונים"), fontSize = 14.sp, color = K.dim, modifier = Modifier.weight(1f))
                    Chip(T("Clear", "ניקוי"), false) {
                        uk.noammm.kav.Prefs.clearRecents(ctx); recents = emptyList()
                    }
                }
            }
            items(recents) { p -> Box(Modifier.padding(horizontal = K.gap2)) { PlaceRow(p) { pick(p) } } }
        } else if (found) {
            // With Google results, they take the Places section (like Moovit's app); Moovit's addresses otherwise.
            if (google.isNotEmpty() || places.isNotEmpty()) item { Box(Modifier.padding(horizontal = K.gap2)) { SectionTitle(T("Places", "מקומות")) } }
            if (google.isNotEmpty()) items(google, key = { "g:" + it.placeId }) { s ->
                val shown = Moovit.Place(s.main, s.secondary, Double.NaN, Double.NaN, meters = s.meters)
                Box(Modifier.padding(horizontal = K.gap2)) {
                    PlaceRow(shown) {
                        val key = placesKey ?: return@PlaceRow
                        if (resolving) return@PlaceRow
                        resolving = true
                        scope.launch {
                            val p = try {
                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    uk.noammm.kav.data.GooglePlaces.resolve(key, ctx.packageName, s)
                                }
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                android.util.Log.w("KavPlaces", "Google details failed", e)
                                null
                            }
                            resolving = false
                            if (p != null && !p.lat.isNaN() && !p.lon.isNaN()) pick(p)
                            else android.widget.Toast.makeText(
                                ctx, T("Couldn't open that place.", "לא הצלחנו לפתוח את המקום."),
                                android.widget.Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }
                }
            }
            else items(places) { p -> Box(Modifier.padding(horizontal = K.gap2)) { PlaceRow(p) { pick(p) } } }
            if (stations.isNotEmpty()) item { Box(Modifier.padding(horizontal = K.gap2)) { SectionTitle(T("Stations", "תחנות")) } }
            items(stations) { p -> Box(Modifier.padding(horizontal = K.gap2)) { PlaceRow(p) { pick(p) } } }
        }
    }
    }
    }

    if (creating) FavouriteEditor(
        existing = null,
        onSave = { name, icon ->
            val fresh = Favourite("f${System.currentTimeMillis()}", name, icon, null)
            save(favourites + fresh)
            creating = false
            setting = fresh
        },
        onRemove = null,
        onDismiss = { creating = false },
    )
    editing?.let { f ->
        FavouriteEditor(
            existing = f,
            onSave = { name, icon ->
                save(favourites.map { if (it.id == f.id) it.copy(name = name, icon = icon) else it })
                editing = null
            },
            onRemove = if (f.id == Favourite.HOME) null else { { save(favourites.filter { it.id != f.id }); editing = null } },
            onDismiss = { editing = null },
            onChangePlace = { editing = null; setting = f; onQuery("") },
        )
    }
}

@Composable
internal fun SelectOnMapRow(sides: androidx.compose.ui.unit.Dp = K.gap3, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = sides)
            .heightIn(min = 48.dp).glassSurface(24.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = K.gap4, vertical = K.gap3),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(K.gap2),
    ) {
        Canvas(Modifier.size(14.dp)) {
            val w = size.width
            drawCircle(K.muted, w * .30f, Offset(w * .5f, w * .38f), style = Stroke(w * .13f))
            drawLine(K.muted, Offset(w * .5f, w * .68f), Offset(w * .5f, w * .98f), w * .13f, StrokeCap.Round)
        }
        Text(T("Select on map", "בחירה על המפה"), fontSize = 15.sp, color = K.muted)
    }
}

@Composable
fun StopMapPicker(
    net: Net?,
    here: Pair<Double, Double>?,
    onPick: (Moovit.Place) -> Unit,
    onDismiss: () -> Unit,
    onLocate: (Pair<Double, Double>) -> Unit = {},
    allowPin: Boolean = false,
    onStop: ((Int) -> Unit)? = null,
) {
    androidx.activity.compose.BackHandler { onDismiss() }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(here == null) {
        if (here == null && hasLocationPermission(ctx)) requestLocationOnce(ctx, onLocate)
    }
    var loaded by remember { mutableStateOf(net) }
    var loadError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(net) {
        if (net != null) { loaded = net; return@LaunchedEffect }
        if (loaded != null) return@LaunchedEffect
        try {
            loaded = uk.noammm.kav.loadNet(ctx)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            loadError = e.message ?: e.javaClass.simpleName
        }
    }
    val open = loaded
    if (open == null) {
        Column(Modifier.fillMaxSize().background(K.bg)) {
            StopMapHeader(allowPin, onDismiss)
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (loadError == null) LoadingPulse(T("Opening the map", "פותחים את המפה"))
                else Note(
                    T("The stop list could not be opened: $loadError", "לא ניתן היה לפתוח את רשימת התחנות: $loadError"),
                    Modifier.padding(K.gap4), K.problem,
                )
            }
        }
        return
    }
    StopMapBody(open, here, onPick, onDismiss, allowPin, onStop)
}

@Composable
private fun StopMapHeader(pin: Boolean, onDismiss: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(K.gap3).heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(K.gap2),
    ) {
        BackButton(onDismiss)
        Box(
            Modifier.heightIn(min = 48.dp).glassSurface(24.dp).padding(horizontal = K.gap4),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (pin) T("Tap a stop, or anywhere", "הקישו על תחנה, או על כל מקום") else T("Tap a stop", "הקישו על תחנה"),
                fontSize = 17.sp, color = K.text, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private sealed class MapPick {
    class Stop(val index: Int) : MapPick()
    class Point(val lat: Double, val lon: Double) : MapPick()
}

private fun nearestLabel(net: Net, lat: Double, lon: Double): String =
    net.nearestStops(lat, lon, k = 1, radius = 400.0).firstOrNull()?.let { (i, d) ->
        T("near ", "ליד ") + net.name.getOrElse(i) { "" } + " · " + distanceLabel(d)
    } ?: ""

@Composable
private fun StopMapBody(
    net: Net,
    here: Pair<Double, Double>?,
    onPick: (Moovit.Place) -> Unit,
    onDismiss: () -> Unit,
    allowPin: Boolean,
    onStop: ((Int) -> Unit)?,
) {
    val centre = here ?: (32.0759 to 34.7745)
    val stops = remember(net, centre) {
        net.nearestStops(centre.first, centre.second, k = 500, radius = 6000.0).map { it.first }
    }
    var chosen by remember { mutableStateOf<MapPick?>(null) }
    val reach = with(LocalDensity.current) { 26.dp.toPx() }
    val points = remember(stops, centre) { listOf(centre) + stops.take(12).map { net.lat[it] to net.lon[it] } }
    val geometry = remember(stops, chosen, here, K.light) {
        val pick = chosen
        MapGeometry(
            dots = stops.flatMap { i ->
                val on = pick is MapPick.Stop && pick.index == i
                listOf(
                    MapDot(net.lat[i], net.lon[i], K.bg, if (on) 8f else 6f),
                    MapDot(
                        net.lat[i], net.lon[i], androidx.compose.ui.graphics.Color.Transparent,
                        if (on) 6f else 4.2f, if (on) K.accent else K.muted, if (on) 2.4f else 1.6f,
                    ),
                )
            } + (
                (pick as? MapPick.Point)?.let { p ->
                    listOf(MapDot(p.lat, p.lon, K.bg, 9f), MapDot(p.lat, p.lon, K.accent, 6f))
                } ?: emptyList()
                ) + (
                here?.let { (lat, lon) ->
                    listOf(
                        MapDot(lat, lon, K.live.copy(alpha = .18f), 13f),
                        MapDot(lat, lon, K.bg, 6f),
                        MapDot(lat, lon, K.live, 4f),
                    )
                } ?: emptyList()
                ),
        )
    }
    val liquid = rememberLiquidBackdrop()
    CompositionLocalProvider(LocalLiquidBackdrop provides liquid) {
        Box(Modifier.fillMaxSize().background(K.bg)) {
            TileMap(
                points,
                Modifier.fillMaxSize().glassBackdrop(liquid),
                contentPadding = PaddingValues(top = 72.dp),
                recenterOn = here,
                geometry = geometry,
                onTap = { at, proj ->
                    val stop = stops
                        .map { it to (proj.point(net.lat[it], net.lon[it]) - at).getDistance() }
                        .filter { it.second <= reach }.minByOrNull { it.second }?.first
                    chosen = when {
                        stop != null -> MapPick.Stop(stop)
                        allowPin -> proj.latLon(at).let { MapPick.Point(it.first, it.second) }
                        else -> null
                    }
                },
            )
            StopMapHeader(allowPin, onDismiss)
            var last by remember { mutableStateOf<MapPick?>(null) }
            LaunchedEffect(chosen) { chosen?.let { last = it } }
            androidx.compose.animation.AnimatedVisibility(
                visible = chosen != null,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = slideInVertically(spring(dampingRatio = .9f, stiffness = Spring.StiffnessMediumLow)) { it } +
                    fadeIn(tween(140)),
                exit = slideOutVertically(tween(180)) { it } + fadeOut(tween(140)),
            ) {
                last?.let { pick ->
                val stopAt = (pick as? MapPick.Stop)?.index
                val detail = if (stopAt != null) listOfNotNull(
                    net.cityOf(stopAt).takeIf { it.isNotBlank() },
                    net.code.getOrElse(stopAt) { 0 }.takeIf { it > 0 }?.toString(),
                ).joinToString(" · ")
                else (pick as MapPick.Point).let { nearestLabel(net, it.lat, it.lon) }
                Column(
                    Modifier.fillMaxWidth()
                        .padding(bottom = maxOf(bottomCover(), LocalBottomBarInset.current))
                        .padding(K.gap3)
                        .glassSurface(K.rCard).padding(K.gap4),
                    verticalArrangement = Arrangement.spacedBy(K.gap2),
                ) {
                    Text(
                        if (stopAt != null) net.name.getOrElse(stopAt) { T("Stop", "תחנה") }
                        else T("Pinned location", "מיקום מסומן"),
                        fontSize = 17.sp, color = K.text,
                        fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    if (detail.isNotBlank()) Text(detail, fontSize = 14.sp, color = K.dim, maxLines = 1)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(K.gap2)) {
                        Box(
                            Modifier.heightIn(min = 44.dp).panel(K.rPill)
                                .clickable(role = Role.Button) { chosen = null }
                                .padding(horizontal = K.gap4),
                            contentAlignment = Alignment.Center,
                        ) { Text(T("Not this one", "לא זו"), fontSize = 14.sp, color = K.text) }
                        Spacer(Modifier.weight(1f))
                        Box(
                            Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(K.rPill))
                                .background(K.accent).clickable(role = Role.Button) {
                                    when (pick) {
                                        is MapPick.Stop ->
                                            if (onStop != null) onStop(pick.index) else onPick(placeOf(net, pick.index))
                                        is MapPick.Point ->
                                            onPick(Moovit.Place(
                                                T("Pinned location", "מיקום מסומן"),
                                                nearestLabel(net, pick.lat, pick.lon), pick.lat, pick.lon,
                                            ))
                                    }
                                }
                                .padding(horizontal = K.gap5),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                if (stopAt != null) T("Choose this stop", "בחירת התחנה") else T("Go here", "לכאן"),
                                fontSize = 14.sp, color = K.onAccent, fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text, fontSize = 14.sp, color = K.dim,
        modifier = Modifier.padding(start = K.gap2, end = K.gap1, top = K.gap2, bottom = K.gap1),
    )
}

@Composable
private fun PlaceRow(p: Moovit.Place, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = K.gap1).heightIn(min = 48.dp).panel(K.rControl)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = K.gap3, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            Modifier.width(56.dp), horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            PlaceGlyph(p.type)
            if (p.meters >= 0) Text(
                distanceLabel(p.meters.toDouble()), fontSize = 14.sp, color = K.dim,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Spacer(Modifier.width(K.gap2))
        Column(Modifier.weight(1f)) {
            Text(p.name, fontSize = 15.sp, color = K.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (p.detail.isNotBlank()) Text(
                p.detail, fontSize = 14.sp, color = K.dim, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun PlaceGlyph(type: Int) {
    when (type) {
        1 -> ModeGlyph(Mode.BUS, K.muted, 17.dp)
        2 -> androidx.compose.foundation.Canvas(Modifier.size(17.dp)) {
            val w = size.width; val h = size.height; val sw = w * .10f
            drawLine(K.muted, androidx.compose.ui.geometry.Offset(w * .30f, h * .12f),
                androidx.compose.ui.geometry.Offset(w * .18f, h * .88f), sw,
                androidx.compose.ui.graphics.StrokeCap.Round)
            drawLine(K.muted, androidx.compose.ui.geometry.Offset(w * .70f, h * .12f),
                androidx.compose.ui.geometry.Offset(w * .82f, h * .88f), sw,
                androidx.compose.ui.graphics.StrokeCap.Round)
            drawLine(K.muted, androidx.compose.ui.geometry.Offset(w * .50f, h * .28f),
                androidx.compose.ui.geometry.Offset(w * .50f, h * .48f), sw,
                androidx.compose.ui.graphics.StrokeCap.Round)
            drawLine(K.muted, androidx.compose.ui.geometry.Offset(w * .50f, h * .62f),
                androidx.compose.ui.geometry.Offset(w * .50f, h * .82f), sw,
                androidx.compose.ui.graphics.StrokeCap.Round)
        }
        else -> androidx.compose.foundation.Canvas(Modifier.size(17.dp)) {
            val w = size.width; val h = size.height
            drawCircle(K.muted, w * .22f, androidx.compose.ui.geometry.Offset(w * .5f, h * .38f),
                style = androidx.compose.ui.graphics.drawscope.Stroke(w * .11f))
            drawLine(K.muted, androidx.compose.ui.geometry.Offset(w * .5f, h * .58f),
                androidx.compose.ui.geometry.Offset(w * .5f, h * .90f), w * .11f,
                androidx.compose.ui.graphics.StrokeCap.Round)
        }
    }
}
