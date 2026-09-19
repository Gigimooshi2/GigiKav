package uk.noammm.kav.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import uk.noammm.kav.data.Moovit

private const val ROUTE_HUE_NUDGE = 34f
private const val ROUTE_HUE_RUNGS = 3

private fun routeBase(ride: Moovit.Leg, r: Moovit.Resolved): Color {
    val info = r.line(ride.lineId) ?: return K.route
    return plateFor(r.routeType(info.agencyId), info.agencyId)?.fill ?: K.route
}

private fun turned(base: Color, rung: Int): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(base.toArgb(), hsv)
    hsv[0] = (hsv[0] + ROUTE_HUE_NUDGE * rung).mod(360f)
    hsv[1] = hsv[1].coerceAtLeast(0.55f)
    hsv[2] = hsv[2].coerceAtLeast(0.82f)
    return Color(android.graphics.Color.HSVToColor(hsv))
}

internal fun routeTints(rides: List<Moovit.Leg>, r: Moovit.Resolved): List<Color> {
    var run = 0
    var last: Color? = null
    return rides.map { ride ->
        val base = routeBase(ride, r)
        run = if (base == last) (run + 1).mod(ROUTE_HUE_RUNGS) else 0
        last = base
        if (run == 0) base else turned(base, run)
    }
}

internal fun boardingDots(at: Pair<Double, Double>, tint: Color, core: Color = tint): List<MapDot> = listOf(
    MapDot(at.first, at.second, K.bg, 9f),
    MapDot(at.first, at.second, Color.Transparent, 7f, tint, 2.5f),
    MapDot(at.first, at.second, core, 3.5f),
)

internal fun boardingMarkers(
    rides: List<Moovit.Leg>,
    tints: List<Color>,
    r: Moovit.Resolved,
    stops: Map<Int, Moovit.StopInfo> = r.stops,
): List<MapDot> {
    val board = rides.map { l ->
        (stops[l.fromStop]?.point ?: l.stops.firstOrNull()?.let { stops[it] }?.point ?: l.shape.firstOrNull())
            ?.let { onRoute(it, l.shape) }
    }
    val alight = rides.map { l ->
        (stops[l.toStop]?.point ?: l.stops.lastOrNull()?.let { stops[it] }?.point ?: l.shape.lastOrNull())
            ?.let { onRoute(it, l.shape) }
    }
    val changes = rides.indices.map { i ->
        i < rides.lastIndex && rides[i].toStop > 0 && rides[i].toStop == rides[i + 1].fromStop
    }
    return rides.indices.flatMap { i ->
        val tint = tints[i]
        val getOn = if (i > 0 && changes[i - 1]) emptyList() else board[i]?.let { boardingDots(it, tint) }.orEmpty()
        val getOff = alight[i]?.let {
            if (!changes[i]) boardingDots(it, tint)
            else boardingDots(between(it, board[i + 1] ?: it), tint, tints[i + 1])
        }.orEmpty()
        getOn + getOff
    }
}

private fun between(a: Pair<Double, Double>, b: Pair<Double, Double>) =
    ((a.first + b.first) / 2) to ((a.second + b.second) / 2)

internal fun onRoute(at: Pair<Double, Double>, path: List<Pair<Double, Double>>): Pair<Double, Double> =
    if (path.size < 2) at else pointAlong(path, alongPath(at.first, at.second, path)) ?: at

@Composable
fun RouteMap(trip: Moovit.Itinerary, r: Moovit.Resolved = Moovit.Resolved(), height: Dp = 190.dp, modifier: Modifier = Modifier) {
    val legs = trip.legs.filter { it.shape.size >= 2 }
    if (legs.isEmpty()) return
    val points = legs.flatMap { it.shape }
    val vehicles = trip.rides.flatMap { it.options }.mapNotNull { r.arrival(it)?.takeIf { a -> a.hasLocation } }
    val pulse = rememberLivePulse()
    val vehicleAlpha = animateFloatAsState(if (vehicles.isEmpty()) 0f else 1f, tween(350), label = "vehicleReveal")

    val rides = legs.filter { it.kind != Moovit.LegKind.WALK }
    val tints = routeTints(rides, r)
    val geometry = remember(legs, r.stops, tints) {
        MapGeometry(
            lines = legs.filter { it.kind == Moovit.LegKind.WALK }
                .map { MapLine(it.shape, K.muted, 2f, dashed = true) } +
                rides.mapIndexed { i, l -> MapLine(l.shape, tints[i], 4f, casing = 7f) },
            dots = boardingMarkers(rides, tints, r) + listOfNotNull(
                legs.first().shape.firstOrNull()?.let { (lat, lon) -> MapDot(lat, lon, K.bg, 6f) },
                legs.first().shape.firstOrNull()?.let { (lat, lon) -> MapDot(lat, lon, Color.Transparent, 5f, K.text, 2f) },
                legs.last().shape.lastOrNull()?.let { (lat, lon) -> MapDot(lat, lon, K.bg, 7f) },
                legs.last().shape.lastOrNull()?.let { (lat, lon) -> MapDot(lat, lon, K.text, 5f) },
            ),
        )
    }

    TileMap(
        points,
        modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(12.dp)).background(K.surface1),
        geometry = geometry,
        animatedOverlay = { proj ->
            for (v in vehicles) {
                val p = proj.point(v.lat, v.lon)
                val tint = if (v.vehicleStatus == 2) K.problem else K.live
                drawCircle(tint.copy(alpha = 0.20f * vehicleAlpha.value), 15.dp.toPx() * pulse.value, p)
                drawCircle(K.bg.copy(alpha = vehicleAlpha.value), 8.dp.toPx(), p)
                drawCircle(tint.copy(alpha = vehicleAlpha.value), 5.dp.toPx(), p)
            }
        },
    )
}
