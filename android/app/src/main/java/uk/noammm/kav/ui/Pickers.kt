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
fun StopRow(net: Net, stop: Int, trailing: String? = null, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(K.rControl))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = K.gap3, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
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
    var results by remember { mutableStateOf<List<Moovit.Place>>(emptyList()) }
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

    LaunchedEffect(q) {
        results = emptyList(); error = null; busy = q.isNotBlank()
        if (q.isBlank()) return@LaunchedEffect
        kotlinx.coroutines.delay(280)
        try {
            results = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val s = Online.session ?: Moovit.register(here?.first ?: 32.0759, here?.second ?: 34.7745)
                    .also { Online.session = it }
                Moovit.searchPlaces(s, q, here?.first ?: 32.0759, here?.second ?: 34.7745)
            }
            busy = false
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            results = emptyList(); error = e.message ?: e.javaClass.simpleName
            busy = false
        }
    }

    var onMap by remember { mutableStateOf(false) }
    if (onMap) {
        StopMapPicker(
            net, here, onPick = { p -> onMap = false; pick(p) }, onDismiss = { onMap = false },
            onLocate = onLocate,
        )
        return
    }
    val leave: () -> Unit = { if (setting != null && initialSetting == null) setting = null else onDismiss() }
    androidx.activity.compose.BackHandler(onBack = leave)

    Column(Modifier.fillMaxSize().background(K.bg)) {
        Row(
            Modifier.fillMaxWidth().padding(K.gap3).heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(K.gap2),
        ) {
            BackButton(leave)
            KavField(q, onQuery, setting?.let { T("where is ${it.label}?", "היכן נמצא ${it.label}?") } ?: title, Modifier.weight(1f), autoFocus = true)
        }
        if (q.isBlank() && setting == null) FavouriteStrip(
            favourites,
            onPick = { f -> f.place?.let(pick) ?: run { setting = f } },
            onAdd = { creating = true },
            onEdit = { editing = it },
        )
        setting?.let { f ->
            Note(
                T(
                    "Search for where ${f.label} is. The place you pick is kept as ${f.label}.",
                    "חפשו היכן נמצא ${f.label}. המקום שתבחרו יישמר בתור ${f.label}.",
                ),
                Modifier.padding(horizontal = K.gap4, vertical = K.gap2),
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = K.gap3)
                .heightIn(min = 48.dp).glassSurface(24.dp)
                .clickable(role = Role.Button) { onMap = true }
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
        if (allowMyLocation) {
            var locating by remember { mutableStateOf(false) }
            val askHere = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission(),
            ) { ok ->
                if (ok) { locating = true; requestLocationOnce(ctx) { onLocate(it); locating = false } }
                else locating = false
            }
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
            error != null -> Note(T("Search failed: $error", "החיפוש נכשל: $error"), Modifier.padding(horizontal = K.gap4, vertical = K.gap4))
            q.isBlank() && recents.isEmpty() ->
                Note(T("Search a station, street or place.", "חפשו תחנה, רחוב או מקום."), Modifier.padding(horizontal = K.gap4, vertical = K.gap4))
            q.isBlank() -> Unit
            busy && results.isEmpty() -> Box(
                Modifier.fillMaxWidth().weight(1f).padding(bottom = bottomCover()),
                contentAlignment = Alignment.Center,
            ) {
                LoadingPulse(T("Searching", "מחפשים"))
            }
            results.isEmpty() -> Note(T("Nothing found.", "לא נמצאו תוצאות."), Modifier.padding(horizontal = K.gap4, vertical = K.gap4))
        }
        val showRecents = q.isBlank() && recents.isNotEmpty()
        if (results.isNotEmpty() || showRecents) {
            Row(
                Modifier.fillMaxWidth().padding(start = K.gap4, end = K.gap3, top = K.gap2, bottom = K.gap1),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (showRecents) T("Recent", "אחרונים") else T("Places", "מקומות"),
                    fontSize = 14.sp, color = K.dim, modifier = Modifier.weight(1f),
                )
                if (showRecents) Chip(T("Clear", "ניקוי"), false) {
                    uk.noammm.kav.Prefs.clearRecents(ctx); recents = emptyList()
                }
            }
        }
        if (results.isNotEmpty() || showRecents) LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(
            start = K.gap2, end = K.gap2, bottom = LocalBottomBarInset.current,
        )) {
            items(if (showRecents) recents else results) { p -> PlaceRow(p) { pick(p) } }
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
fun StopMapPicker(
    net: Net?,
    here: Pair<Double, Double>?,
    onPick: (Moovit.Place) -> Unit,
    onDismiss: () -> Unit,
    onLocate: (Pair<Double, Double>) -> Unit = {},
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
            StopMapHeader(onDismiss)
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
    StopMapBody(open, here, onPick, onDismiss)
}

@Composable
private fun StopMapHeader(onDismiss: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(K.gap3).heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(K.gap2),
    ) {
        BackButton(onDismiss)
        Text(T("Tap a stop", "הקישו על תחנה"), fontSize = 17.sp, color = K.text, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun StopMapBody(
    net: Net,
    here: Pair<Double, Double>?,
    onPick: (Moovit.Place) -> Unit,
    onDismiss: () -> Unit,
) {
    val centre = here ?: (32.0759 to 34.7745)
    val stops = remember(net, centre) {
        net.nearestStops(centre.first, centre.second, k = 500, radius = 6000.0).map { it.first }
    }
    var chosen by remember { mutableStateOf<Int?>(null) }
    val reach = with(LocalDensity.current) { 26.dp.toPx() }
    val points = remember(stops, centre) { listOf(centre) + stops.take(12).map { net.lat[it] to net.lon[it] } }
    val geometry = remember(stops, chosen, here) {
        MapGeometry(
            dots = stops.flatMap { i ->
                val on = i == chosen
                listOf(
                    MapDot(net.lat[i], net.lon[i], K.bg, if (on) 8f else 6f),
                    MapDot(
                        net.lat[i], net.lon[i], androidx.compose.ui.graphics.Color.Transparent,
                        if (on) 6f else 4.2f, if (on) K.accent else K.muted, if (on) 2.4f else 1.6f,
                    ),
                )
            } + (
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
    Column(Modifier.fillMaxSize().background(K.bg)) {
        StopMapHeader(onDismiss)
        Box(Modifier.fillMaxWidth().weight(1f)) {
            TileMap(
                points,
                Modifier.fillMaxSize(),
                recenterOn = here,
                geometry = geometry,
                onTap = { at, proj ->
                    chosen = stops
                        .map { it to (proj.point(net.lat[it], net.lon[it]) - at).getDistance() }
                        .filter { it.second <= reach }.minByOrNull { it.second }?.first
                },
            )
            var last by remember { mutableStateOf<Int?>(null) }
            LaunchedEffect(chosen) { chosen?.let { last = it } }
            androidx.compose.animation.AnimatedVisibility(
                visible = chosen != null,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = slideInVertically(spring(dampingRatio = .9f, stiffness = Spring.StiffnessMediumLow)) { it } +
                    fadeIn(tween(140)),
                exit = slideOutVertically(tween(180)) { it } + fadeOut(tween(140)),
            ) {
                last?.let { i ->
                val code = net.code.getOrElse(i) { 0 }
                val detail = listOfNotNull(
                    net.cityOf(i).takeIf { it.isNotBlank() }, code.takeIf { it > 0 }?.toString(),
                ).joinToString(" · ")
                Column(
                    Modifier.fillMaxWidth()
                        .padding(bottom = maxOf(bottomCover(), LocalBottomBarInset.current))
                        .padding(K.gap3)
                        .clip(RoundedCornerShape(K.rCard)).background(K.surface1).padding(K.gap4),
                    verticalArrangement = Arrangement.spacedBy(K.gap2),
                ) {
                    Text(
                        net.name.getOrElse(i) { T("Stop", "תחנה") }, fontSize = 17.sp, color = K.text,
                        fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    if (detail.isNotBlank()) Text(detail, fontSize = 14.sp, color = K.dim, maxLines = 1)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(K.gap2)) {
                        Box(
                            Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(K.rPill))
                                .background(K.plateStrong).clickable(role = Role.Button) { chosen = null }
                                .padding(horizontal = K.gap4),
                            contentAlignment = Alignment.Center,
                        ) { Text(T("Not this one", "לא זו"), fontSize = 14.sp, color = K.text) }
                        Spacer(Modifier.weight(1f))
                        Box(
                            Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(K.rPill))
                                .background(K.accent).clickable(role = Role.Button) { onPick(placeOf(net, i)) }
                                .padding(horizontal = K.gap5),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(T("Choose this stop", "בחירת התחנה"), fontSize = 14.sp, color = K.bg, fontWeight = FontWeight.Medium)
                        }
                    }
                }
                }
            }
        }
    }
}

@Composable
private fun PlaceRow(p: Moovit.Place, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(K.rControl))
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
