package uk.noammm.kav.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import uk.noammm.kav.ActiveJourney
import uk.noammm.kav.KavModel
import uk.noammm.kav.data.Moovit
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun PipOverlay(model: KavModel) {
    val journey = model.activeJourney
    var now by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(Unit) {
        while (true) { now = System.currentTimeMillis() / 1000; kotlinx.coroutines.delay(15_000) }
    }
    Box(Modifier.fillMaxSize().background(K.bg), contentAlignment = Alignment.CenterStart) {
        if (journey == null) {
            Text(T("Trip ended", "הנסיעה הסתיימה"), fontSize = 15.sp, color = K.dim, modifier = Modifier.padding(K.gap4))
            return@Box
        }
        val steps = remember(journey.trip, journey.fromLabel, journey.toLabel) {
            buildSteps(journey.trip, journey.fromLabel, journey.toLabel)
        }
        val index = model.journeyStep.coerceIn(0, steps.lastIndex)
        val (title, detail) = stepInstruction(steps[index], journey, index == steps.lastIndex - 1, now, model.fix)
        // Waiting on a live bus: its time glows, like live times in the app.
        val waitLive = (steps[index] as? Step.Wait)?.let { w ->
            val (ride, wait) = boardingChoice(w.ride, w.wait, journey.chosen[w.legIndex] ?: 0)
            journey.resolved.departures(ride, wait).firstOrNull { it.tripId == ride.tripId }?.live == true
        } ?: false
        val cells = pipCells(steps, index, journey, model.fix, now)
        val showDetail = cells.isEmpty() || steps[index] !is Step.Walk
        Row(Modifier.fillMaxSize().padding(horizontal = K.gap3, vertical = K.gap2), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(4.dp).fillMaxHeight(.7f).clip(RoundedCornerShape(999.dp)).background(K.accent))
            Spacer(Modifier.width(K.gap3))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                uk.noammm.kav.Reroute.offer?.let { o ->
                    Text(
                        o.headline + " · −" + ((o.gain + 30) / 60) + T(" min", " דק׳"),
                        fontSize = 11.sp, lineHeight = 13.sp, color = K.accent, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    title, fontSize = 15.sp, lineHeight = 19.sp, color = K.text, fontWeight = FontWeight.SemiBold,
                    maxLines = if (cells.isEmpty()) 2 else 1, overflow = TextOverflow.Ellipsis,
                )
                if (showDetail && waitLive && detail.contains(" · ")) {
                    val pulse by rememberInfiniteTransition(label = "pipLive").animateFloat(
                        .55f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "pipPulse",
                    )
                    val cut = detail.lastIndexOf(" · ") + 3
                    Text(
                        buildAnnotatedString {
                            append(detail.substring(0, cut))
                            withStyle(SpanStyle(color = K.live.copy(alpha = pulse), fontWeight = FontWeight.SemiBold)) {
                                append(detail.substring(cut))
                            }
                        },
                        fontSize = 12.sp, lineHeight = 15.sp, color = K.dim,
                        maxLines = if (cells.isEmpty()) 2 else 1, overflow = TextOverflow.Ellipsis,
                    )
                } else if (showDetail) Text(
                    detail, fontSize = 12.sp, lineHeight = 15.sp, color = K.dim,
                    maxLines = if (cells.isEmpty()) 2 else 1, overflow = TextOverflow.Ellipsis,
                )
                if (cells.isNotEmpty()) Row(
                    Modifier.fillMaxWidth().padding(top = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(K.gap3),
                ) {
                    for (c in cells) Column(Modifier.weight(1f)) {
                        Text(c.label, fontSize = 10.sp, lineHeight = 12.sp, color = K.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            c.value, fontSize = 14.sp, lineHeight = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = if (c.live) K.live else K.text, fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }
}

private class PipCell(val label: String, val value: String, val live: Boolean = false)

private fun pipCells(steps: List<Step>, index: Int, journey: ActiveJourney, fix: Fix?, now: Long): List<PipCell> {
    val r = journey.resolved
    val step = steps[index]
    if (step !is Step.Start && step !is Step.Walk && step !is Step.Ride) return emptyList()
    val hm = SimpleDateFormat("HH:mm", Locale.US)
    fun until(t: Long): String {
        val m = ((t - now + 30) / 60).toInt()
        return when {
            m <= 0 -> T("now", "עכשיו")
            m < 60 -> T("$m min", "$m דק׳")
            else -> hm.format(Date(t * 1000))
        }
    }
    fun line(ride: Moovit.Leg) = ride.shortName.ifBlank { r.line(ride.lineId)?.number.orEmpty() }
        .ifBlank { T("Bus", "אוטובוס") }

    val out = ArrayList<PipCell>()

    // On a ride: when do I get off (GPS-estimated if aboard, else timetable)
    if (step is Step.Ride) {
        val ride = boardingChoice(step.ride, step.wait, journey.chosen[step.legIndex] ?: 0).first
        val f = fix?.takeIf { it.isFresh(now) && it.aboard(ride.shape) }
        val len = if (f != null) pathLength(ride.shape) else 0.0
        val eta = if (f != null && len > 0 && ride.arr > ride.dep) {
            val left = 1.0 - (alongPath(f.lat, f.lon, ride.shape) / len).coerceIn(0.0, 1.0)
            now + (left * (ride.arr - ride.dep)).toLong()
        } else ride.arr
        out.add(PipCell(T("Get off", "ירידה"), (if (f == null) "~" else "") + until(eta)))
    }

    // Next boarding after this step (or after the Start/Walk that leads to it)
    val next = (index + 1..steps.lastIndex).firstOrNull { steps[it] is Step.Wait }
    val walkSteps = (index until (next ?: (steps.lastIndex + 1)))
        .map { steps[it] }.filterIsInstance<Step.Walk>()
    // The walk you're on counts down from your GPS position; later walks use the plan.
    fun walked(w: Step.Walk) = if (w === step) walkLeft(w.leg, fix, now) else w.leg.meters to w.leg.minutes
    val walkMin = walkSteps.sumOf { walked(it).second }
    val walkM = walkSteps.sumOf { walked(it).first }
    if (walkSteps.isNotEmpty() && (walkMin > 0 || walkM > 0)) {
        val label = if (step is Step.Walk && walkM > 0) T("Walk", "הליכה") + " · " + distanceLabel(walkM.toDouble())
        else T("Walk", "הליכה")
        out.add(PipCell(label, T("$walkMin min", "$walkMin דק׳")))
    }
    if (next != null) {
        val w = steps[next] as Step.Wait
        val (ride, wait) = boardingChoice(w.ride, w.wait, journey.chosen[w.legIndex] ?: 0)
        val dep = r.departures(ride, wait).firstOrNull { it.tripId == ride.tripId }
            ?: Moovit.Departure(ride.tripId, ride.dep)
        out.add(
            if (dep.status == 3) PipCell(line(ride), T("cancelled", "מבוטל"))
            else PipCell(line(ride) + " " + T("arrives", "מגיע"), until(dep.timeUtc), live = dep.live)
        )
    }
    return out
}
