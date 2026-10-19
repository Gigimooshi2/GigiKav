package uk.noammm.kav.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The Moovit pack: Moovit's own screens, piece by piece. Colours are sampled from Moovit's
 * store screenshots; the layouts (charcoal bars, white rows on section strips, underlined line
 * badges, green live directions) follow the real app. Kav's brand stays: no Moovit logo.
 */
object Mv {
    val on get() = K.pack == Pack.MOOVIT

    val orange = Color(0xFFFF6400)
    val blue = Color(0xFF1864E5)
    val green = Color(0xFF038844)
    val red = Color(0xFFE10C32)
    val stepGrey = Color(0xFF7A7C7F)
    val banner = Color(0xFF292A30)

    /** Charcoal app bars: home, stations, lines, plan, settings. */
    val bar get() = if (K.look == Look.OLED) Color(0xFF16161A) else Color(0xFF2A292E)
    /** Pale strips between list sections ("Favorites", "Recent"). */
    val strip get() = if (K.light) Color(0xFFE7F0F8) else K.surface2
    /** Text links and outlined pills: Moovit's blue, lifted on dark looks. */
    val link get() = if (K.light) blue else Color(0xFF6CA2FF)
    /** Fields sitting on a charcoal bar. */
    val field get() = if (K.light) Color.White else K.surface2
    val fieldInk get() = if (K.light) Color(0xFF1C1D23) else K.text
    val fieldHint get() = if (K.light) Color(0xFF8A8B90) else K.dim
    val badgeEdge get() = if (K.light) Color(0xFFD3D6DA) else K.surface4

    /** Paints the status-bar band; navigation turns it green while it's on screen. */
    var statusBar by mutableStateOf<Color?>(null)
}

/** True inside a charcoal or coloured bar: icons and text there turn white. */
val LocalOnBar = staticCompositionLocalOf { false }

@Composable
fun ink(default: Color): Color = if (LocalOnBar.current) Color.White else default

/** Flat Moovit app bar: back arrow, title, white actions. */
@Composable
fun MvTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    color: Color = Mv.bar,
    subtitle: String? = null,
    centred: Boolean = false,
    lead: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    CompositionLocalProvider(LocalOnBar provides true) {
        Row(
            Modifier.fillMaxWidth().background(color).heightIn(min = 56.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) MvIconButton(T("Back", "חזרה"), onBack) { MvBackArrow(Color.White) }
            else Spacer(Modifier.width(12.dp))
            if (lead != null) { lead(); Spacer(Modifier.width(8.dp)) }
            Column(
                Modifier.weight(1f).padding(horizontal = 4.dp),
                horizontalAlignment = if (centred) Alignment.CenterHorizontally else Alignment.Start,
            ) {
                Text(
                    title, fontSize = if (subtitle != null) 16.sp else 20.sp, fontWeight = FontWeight.Medium,
                    color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) Text(
                    subtitle, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            actions()
            if (onBack == null && !centred) Spacer(Modifier.width(4.dp))
        }
    }
}

@Composable
fun MvIconButton(label: String, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier.size(48.dp).clip(RoundedCornerShape(24.dp))
            .semantics { contentDescription = label }
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
fun MvBackArrow(tint: Color) {
    Canvas(Modifier.size(22.dp)) {
        val w = size.width; val h = size.height; val sw = w * .09f
        val tip = mirrorX(.16f); val tail = mirrorX(.86f); val wing = mirrorX(.46f)
        drawLine(tint, Offset(w * tip, h * .5f), Offset(w * tail, h * .5f), sw, StrokeCap.Round)
        drawLine(tint, Offset(w * tip, h * .5f), Offset(w * wing, h * .20f), sw, StrokeCap.Round)
        drawLine(tint, Offset(w * tip, h * .5f), Offset(w * wing, h * .80f), sw, StrokeCap.Round)
    }
}

/** Moovit's hamburger: here it opens Settings. */
@Composable
fun MvMenuButton(badge: Boolean, onClick: () -> Unit) =
    MvIconButton(T("Settings", "הגדרות"), onClick) {
        Canvas(Modifier.size(22.dp)) {
            val w = size.width; val sw = w * .09f
            for (y in listOf(.24f, .5f, .76f)) {
                drawLine(Color.White, Offset(w * .12f, w * y), Offset(w * .88f, w * y), sw, StrokeCap.Round)
            }
            if (badge) {
                drawCircle(Mv.bar, w * .22f, Offset(w * .9f, w * .14f))
                drawCircle(Mv.orange, w * .15f, Offset(w * .9f, w * .14f))
            }
        }
    }

fun DrawScope.drawMvMagnifier(tint: Color) {
    val w = size.width
    drawCircle(tint, w * .30f, Offset(w * .42f, w * .42f), style = Stroke(w * .10f))
    drawLine(tint, Offset(w * .64f, w * .64f), Offset(w * .90f, w * .90f), w * .11f, StrokeCap.Round)
}

/** The white "Where do you want to go?" field that sits on the charcoal header. */
@Composable
fun MvSearchButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 50.dp)
            .shadow(3.dp, RoundedCornerShape(25.dp))
            .clip(RoundedCornerShape(25.dp)).background(Mv.field)
            .semantics { contentDescription = label }
            .clickable(role = Role.Button, onClick = onClick)
            .padding(start = 18.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 17.sp, color = Mv.fieldHint, modifier = Modifier.weight(1f), maxLines = 1)
        Canvas(Modifier.size(22.dp)) { drawMvMagnifier(Mv.orange) }
    }
}

/** Moovit's search box: white, square-ish corners, magnifier at the end. */
@Composable
fun MvSearchField(
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
        textStyle = TextStyle(color = Mv.fieldInk, fontSize = 16.sp),
        cursorBrush = SolidColor(Mv.orange),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        modifier = modifier.fillMaxWidth().heightIn(min = 46.dp)
            .clip(RoundedCornerShape(6.dp)).background(Mv.field)
            .then(if (K.light && !LocalOnBar.current) Modifier.border(1.dp, Mv.badgeEdge, RoundedCornerShape(6.dp)) else Modifier)
            .focusRequester(focus)
            .semantics { contentDescription = placeholder }
            .padding(start = 14.dp, end = 12.dp),
        decorationBox = { inner ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) Text(placeholder, fontSize = 16.sp, color = Mv.fieldHint, maxLines = 1)
                    inner()
                }
                if (value.isEmpty()) Canvas(Modifier.size(20.dp)) { drawMvMagnifier(Mv.fieldHint) }
                else Box(
                    Modifier.size(28.dp).clip(RoundedCornerShape(14.dp))
                        .semantics { contentDescription = T("Clear", "ניקוי") }
                        .clickable(role = Role.Button) { onValue("") },
                    contentAlignment = Alignment.Center,
                ) {
                    Canvas(Modifier.size(12.dp)) {
                        val w = size.width; val sw = w * .14f
                        drawLine(Mv.fieldHint, Offset(0f, 0f), Offset(w, w), sw, StrokeCap.Round)
                        drawLine(Mv.fieldHint, Offset(w, 0f), Offset(0f, w), sw, StrokeCap.Round)
                    }
                }
            }
        },
    )
}

/** Charcoal header holding a title row and a search box, like Moovit's Lines and Stations tabs. */
@Composable
fun MvSearchHeader(
    title: String,
    value: String,
    onValue: (String) -> Unit,
    placeholder: String,
    onBack: (() -> Unit)? = null,
    below: @Composable ColumnScope.() -> Unit = {},
) {
    Column(Modifier.fillMaxWidth().background(Mv.bar)) {
        MvTopBar(title, onBack)
        CompositionLocalProvider(LocalOnBar provides true) {
            MvSearchField(value, onValue, placeholder, Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp))
            below()
        }
    }
}

/** Moovit's tab row on a charcoal bar: white labels, orange underline under the chosen one. */
@Composable
fun MvTabs(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Mv.bar)
            .horizontalScroll(androidx.compose.foundation.rememberScrollState()),
    ) {
        labels.forEachIndexed { i, label ->
            val on = i == selected
            Column(
                Modifier.width(IntrinsicSize.Max).heightIn(min = 46.dp).semantics { this.selected = on }
                    .clickable(role = Role.Tab) { onSelect(i) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom,
            ) {
                Text(
                    label, fontSize = 15.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                    color = if (on) Color.White else Color.White.copy(alpha = .62f),
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                )
                Box(Modifier.fillMaxWidth().height(3.dp).background(if (on) Mv.orange else Color.Transparent))
            }
        }
    }
}

/** The pale strip that heads a block of rows, with an optional blue action at the end. */
@Composable
fun MvSection(title: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().background(Mv.strip).heightIn(min = 44.dp)
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, fontSize = 15.sp, color = K.muted, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (action != null && onAction != null) Text(
            action, fontSize = 15.sp, color = Mv.link, fontWeight = FontWeight.Medium,
            modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(role = Role.Button, onClick = onAction)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        )
        else Spacer(Modifier.width(12.dp))
    }
}

/** A white list row with Moovit's hairline under it. */
fun Modifier.mvRow(divider: Boolean = true, inset: Dp = 16.dp): Modifier =
    background(K.surface1).then(
        if (!divider) Modifier else Modifier.drawBehind {
            val y = size.height - 1.dp.toPx() / 2
            val start = inset.toPx()
            val (x0, x1) = if (layoutDirection == androidx.compose.ui.unit.LayoutDirection.Rtl)
                0f to size.width - start else start to size.width
            drawLine(K.border, Offset(x0, y), Offset(x1, y), 1.dp.toPx())
        },
    )

/** A Moovit card: white, 8dp corners, soft shadow on the light look. */
fun Modifier.mvCard(radius: Dp = 8.dp): Modifier {
    val shape = RoundedCornerShape(radius)
    return (if (K.light) shadow(2.dp, shape, clip = false, ambientColor = Color(0x33000000), spotColor = Color(0x33000000)) else this)
        .clip(shape).background(K.surface1)
}

/** Moovit's pill buttons: filled (Start, Stop, Live location) or outlined (Favorite, report). */
@Composable
fun MvPill(
    label: String?,
    color: Color = Mv.blue,
    filled: Boolean = true,
    modifier: Modifier = Modifier,
    description: String? = label,
    icon: (@Composable (Color) -> Unit)? = null,
    onClick: () -> Unit,
) {
    val tint = if (filled) Color.White else if (color == Mv.blue) Mv.link else color
    Row(
        modifier.heightIn(min = 40.dp).clip(RoundedCornerShape(20.dp))
            .then(if (filled) Modifier.background(color) else Modifier.border(1.5.dp, tint, RoundedCornerShape(20.dp)))
            .semantics { if (description != null) contentDescription = description }
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = if (label == null) 18.dp else 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) icon(tint)
        if (icon != null && label != null) Spacer(Modifier.width(8.dp))
        if (label != null) Text(label, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = tint, maxLines = 1)
    }
}

@Composable
fun MvPlayGlyph(tint: Color) {
    Canvas(Modifier.size(14.dp)) {
        val w = size.width; val h = size.height
        val p = Path().apply {
            moveTo(w * mirrorX(.12f), h * .10f); lineTo(w * mirrorX(.92f), h * .50f)
            lineTo(w * mirrorX(.12f), h * .90f); close()
        }
        drawPath(p, tint)
    }
}

@Composable
fun MvStopGlyph(tint: Color) {
    Canvas(Modifier.size(13.dp)) {
        drawRoundRect(tint, cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.width * .14f))
    }
}

@Composable
fun MvShareGlyph(tint: Color) {
    Canvas(Modifier.size(18.dp)) {
        val w = size.width; val h = size.height; val sw = w * .10f
        drawLine(tint, Offset(w * .5f, h * .08f), Offset(w * .5f, h * .62f), sw, StrokeCap.Round)
        drawLine(tint, Offset(w * .5f, h * .08f), Offset(w * .30f, h * .28f), sw, StrokeCap.Round)
        drawLine(tint, Offset(w * .5f, h * .08f), Offset(w * .70f, h * .28f), sw, StrokeCap.Round)
        drawLine(tint, Offset(w * .22f, h * .44f), Offset(w * .14f, h * .44f), sw, StrokeCap.Round)
        drawLine(tint, Offset(w * .14f, h * .44f), Offset(w * .14f, h * .92f), sw, StrokeCap.Round)
        drawLine(tint, Offset(w * .14f, h * .92f), Offset(w * .86f, h * .92f), sw, StrokeCap.Round)
        drawLine(tint, Offset(w * .86f, h * .92f), Offset(w * .86f, h * .44f), sw, StrokeCap.Round)
        drawLine(tint, Offset(w * .86f, h * .44f), Offset(w * .78f, h * .44f), sw, StrokeCap.Round)
    }
}

@Composable
fun MvStarGlyph(tint: Color, filled: Boolean = false) {
    Canvas(Modifier.size(17.dp)) {
        val w = size.width
        val p = Path()
        for (i in 0 until 10) {
            val r = if (i % 2 == 0) w * .48f else w * .20f
            val a = Math.toRadians(-90.0 + i * 36.0)
            val x = w * .5f + r * kotlin.math.cos(a).toFloat()
            val y = w * .54f + r * kotlin.math.sin(a).toFloat()
            if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
        }
        p.close()
        if (filled) drawPath(p, tint) else drawPath(p, tint, style = Stroke(w * .09f, join = androidx.compose.ui.graphics.StrokeJoin.Round))
    }
}

/** Moovit's real-time mark: a dot with two arcs, tilted like a signal. */
fun DrawScope.drawMvLive(tint: Color) {
    val w = size.width; val h = size.height; val sw = w * .13f
    val c = Offset(w * mirrorX(.18f), h * .82f)
    drawCircle(tint, w * .12f, c)
    for (r in listOf(.42f, .72f)) {
        drawArc(
            tint, startAngle = if (T.rtl) -180f else -90f, sweepAngle = 90f, useCenter = false,
            topLeft = Offset(c.x - w * r, c.y - w * r), size = Size(w * r * 2, w * r * 2),
            style = Stroke(sw, cap = StrokeCap.Round),
        )
    }
}

/** Boxed live time, as on Moovit's line card: arcs, a big green number, "min". */
@Composable
fun MvLiveBox(minutes: String, unit: String?, tint: Color, live: Boolean) {
    Row(
        Modifier.clip(RoundedCornerShape(6.dp)).background(if (K.light) Color(0xFFF8F8F8) else K.surface2)
            .border(1.dp, if (K.light) Color(0xFFE1E4E5) else K.border, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (live) { Canvas(Modifier.size(12.dp)) { drawMvLive(tint) }; Spacer(Modifier.width(5.dp)) }
        Text(minutes, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = tint)
        if (unit != null) Text(" $unit", fontSize = 12.sp, color = K.dim, modifier = Modifier.padding(top = 4.dp))
    }
}

// Operators Moovit colours by brand; any other operator gets a steady colour of its own.
private val MvLineColours = listOf(
    Color(0xFF0091D2), Color(0xFF00A65A), Color(0xFFE8710A), Color(0xFF7B3FA0),
    Color(0xFFD6336C), Color(0xFF0B7A75), Color(0xFF3F51B5), Color(0xFFB8860B),
)

fun mvLineColour(routeType: Int, agencyId: Int, operator: String?): Color {
    plateFor(routeType, agencyId)?.let { return it.fill }
    val name = operator.orEmpty().trim()
    return when {
        name.startsWith("אגד") || name.startsWith("Egged", true) -> Color(0xFF0A8A3C)
        name.startsWith("דן") || name.startsWith("Dan ", true) || name.equals("Dan", true) -> Color(0xFF1B5FAA)
        name.isNotEmpty() -> MvLineColours[(name.hashCode() and 0x7fffffff) % MvLineColours.size]
        agencyId >= 0 -> MvLineColours[agencyId % MvLineColours.size]
        else -> Color(0xFF0091D2)
    }
}

/**
 * Moovit's line badge: a white tile with a thin grey edge, the mode icon and the line number in
 * bold, and the operator's colour as a bar along the bottom.
 */
@Composable
fun MvBadge(
    routeType: Int,
    agencyId: Int,
    label: String?,
    underline: Color,
    modifier: Modifier = Modifier,
    big: Boolean = false,
) {
    val shape = RoundedCornerShape(4.dp)
    Column(
        modifier.width(IntrinsicSize.Max).clip(shape).background(if (K.light) Color.White else K.surface2)
            .border(1.dp, Mv.badgeEdge, shape),
    ) {
        Row(
            Modifier.padding(start = 6.dp, end = if (label == null) 6.dp else 8.dp, top = if (big) 4.dp else 3.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AgencyMark(routeType, agencyId, K.text, if (big) 18.dp else 15.dp)
            if (label != null) {
                Spacer(Modifier.width(5.dp))
                Text(
                    label, fontSize = if (big) 18.sp else 15.sp, fontWeight = FontWeight.Bold, color = K.text,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 120.dp),
                )
            }
        }
        Box(Modifier.fillMaxWidth().height(3.dp).background(underline))
    }
}

/** Moovit's little round mode tile used beside stops (white glyph on the line's colour). */
@Composable
fun MvStopTile(mode: Mode, tint: Color, size: Dp = 32.dp) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size / 2)).background(tint),
        contentAlignment = Alignment.Center,
    ) { ModeGlyph(mode, Color.White, size * .56f) }
}

/** A stop on a line's list: Moovit's coloured line running through white-centred dots. */
@Composable
fun MvStopDot(tint: Color, first: Boolean, last: Boolean, bleed: Dp = 9.dp) {
    // Fills the row's height (the row sizes itself with IntrinsicSize.Min); the line runs on
    // through the row's own padding so neighbouring stops join up.
    Canvas(Modifier.width(18.dp).fillMaxHeight()) {
        val x = size.width / 2; val cy = size.height / 2; val sw = 4.dp.toPx()
        if (!first) drawLine(tint, Offset(x, -bleed.toPx()), Offset(x, cy), sw)
        if (!last) drawLine(tint, Offset(x, cy), Offset(x, size.height + bleed.toPx()), sw)
        val r = if (first || last) 6.dp.toPx() else 4.5.dp.toPx()
        drawCircle(tint, r, Offset(x, cy))
        drawCircle(K.surface1, r - 2.dp.toPx(), Offset(x, cy))
    }
}

/** The Israeli bus-stop sign Moovit uses for stops here: blue top, yellow body. */
@Composable
fun MvStopSign(size: Dp = 28.dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width; val h = this.size.height
        val r = androidx.compose.ui.geometry.CornerRadius(w * .12f)
        drawRoundRect(Color(0xFFFFD200), cornerRadius = r)
        val top = Path().apply {
            moveTo(0f, h * .40f); lineTo(0f, w * .12f)
            quadraticTo(0f, 0f, w * .12f, 0f)
            lineTo(w * .88f, 0f); quadraticTo(w, 0f, w, h * .12f)
            lineTo(w, h * .30f); lineTo(w * .55f, h * .30f); lineTo(w * .45f, h * .40f); close()
        }
        drawPath(top, Color(0xFF1C4FA0))
        drawRoundRect(Color(0x33000000), cornerRadius = r, style = Stroke(w * .04f))
    }
}

/** The step dots under navigation's green bar: ‹ • • ● • › with the live step in green. */
@Composable
fun MvStepDots(current: Int, count: Int, live: Int, goTo: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(K.surface1).height(44.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MvChevronButton(back = true, enabled = current > 0) { goTo(current - 1) }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            for (i in 0 until count) {
                val on = i == current
                Box(
                    Modifier.size(width = 20.dp, height = 40.dp)
                        .semantics { contentDescription = T("Step ${i + 1} of $count", "שלב ${i + 1} מתוך $count"); selected = on }
                        .clickable(role = Role.Button) { goTo(i) },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier.size(if (on) 10.dp else 8.dp).clip(RoundedCornerShape(5.dp))
                            .background(
                                when {
                                    i == live -> K.live
                                    on -> K.text
                                    else -> if (K.light) Color(0xFF434449) else K.surface4
                                },
                            )
                            .then(if (on && i == live) Modifier.border(2.dp, K.live.copy(alpha = .35f), RoundedCornerShape(5.dp)) else Modifier),
                    )
                }
            }
        }
        MvChevronButton(back = false, enabled = current < count - 1) { goTo(current + 1) }
    }
}

@Composable
private fun MvChevronButton(back: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val tint = if (enabled) K.text else K.surface4
    Box(
        Modifier.size(44.dp).clip(RoundedCornerShape(22.dp))
            .semantics { contentDescription = if (back) T("Previous step", "שלב קודם") else T("Next step", "שלב הבא") }
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(12.dp)) {
            val w = size.width; val h = size.height
            val x1 = mirrorX(if (back) .70f else .30f)
            val x2 = mirrorX(if (back) .30f else .70f)
            drawLine(tint, Offset(w * x1, h * .10f), Offset(w * x2, h * .5f), w * .14f, StrokeCap.Round)
            drawLine(tint, Offset(w * x2, h * .5f), Offset(w * x1, h * .90f), w * .14f, StrokeCap.Round)
        }
    }
}

/** Moovit's step-card header: grey for a step ahead or behind, green for the one you're on. */
@Composable
fun MvStepHeader(text: String, trailing: String?, active: Boolean) {
    Row(
        Modifier.fillMaxWidth().background(if (active) Mv.green else Mv.stepGrey)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color.White, modifier = Modifier.weight(1f),
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (trailing != null) Text(trailing, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color.White)
    }
}

/** The orange-ink banner Moovit shows across the top of the map ("Proceed to highlighted route"). */
@Composable
fun MvBanner(text: String) {
    Text(
        text, fontSize = 14.sp, color = Color(0xFFF3A23A), textAlign = TextAlign.Start,
        modifier = Modifier.fillMaxWidth().background(Mv.banner).padding(horizontal = 16.dp, vertical = 12.dp),
        maxLines = 2, overflow = TextOverflow.Ellipsis,
    )
}

/** Kav's mark in Moovit's header slot: an orange pin and the name. */
@Composable
fun MvWordmark() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(width = 20.dp, height = 26.dp)) {
            val w = size.width; val h = size.height
            val p = Path().apply {
                moveTo(w * .5f, h * .98f)
                cubicTo(w * .12f, h * .62f, 0f, h * .48f, 0f, h * .36f)
                cubicTo(0f, h * .14f, w * .22f, 0f, w * .5f, 0f)
                cubicTo(w * .78f, 0f, w, h * .14f, w, h * .36f)
                cubicTo(w, h * .48f, w * .88f, h * .62f, w * .5f, h * .98f)
                close()
            }
            drawPath(p, Mv.orange)
            drawCircle(Color.White, w * .07f, Offset(w * .36f, h * .32f))
            drawCircle(Color.White, w * .07f, Offset(w * .64f, h * .32f))
            drawArc(
                Color.White, 20f, 140f, false,
                topLeft = Offset(w * .30f, h * .26f), size = Size(w * .40f, h * .22f),
                style = Stroke(w * .08f, cap = StrokeCap.Round),
            )
        }
        Spacer(Modifier.width(6.dp))
        Text("kav", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White, letterSpacing = (-0.5).sp)
    }
}
