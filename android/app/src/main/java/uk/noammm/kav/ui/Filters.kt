package uk.noammm.kav.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ElectricScooter
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import uk.noammm.kav.data.Moovit

enum class ResultFilter {
    BUS, TRAIN, LIGHT_RAIL, CARMELIT_RAKAVLIT, SHUTTLE, TAXI, BIKE, SHARED, WALK;

    val label: String
        get() = when (this) {
            BUS -> T("Bus", "אוטובוס")
            TRAIN -> T("Train", "רכבת")
            LIGHT_RAIL -> T("Light rail", "רכבת קלה")
            CARMELIT_RAKAVLIT -> T("Carmelit & Rakavlit", "כרמלית ורכבלית")
            SHUTTLE -> T("Shuttle", "שאטל")
            TAXI -> T("Taxi", "מונית")
            BIKE -> T("Bike", "אופניים")
            SHARED -> T("Shared mobility", "תחבורה שיתופית")
            WALK -> T("Walking only", "הליכה בלבד")
        }

    val desc: String
        get() = when (this) {
            BUS -> T("Every bus operator", "כל מפעילי האוטובוסים")
            TRAIN -> T("Israel Railways", "רכבת ישראל")
            LIGHT_RAIL -> T("The Jerusalem and Tel Aviv light rail", "הרכבת הקלה בירושלים ובתל אביב")
            CARMELIT_RAKAVLIT -> T("Haifa's funicular and the Cable Express cable car", "הכרמלית וכבל אקספרס, הרכבלית של חיפה")
            SHUTTLE -> T("Municipal shuttle lines", "קווי שאטל עירוניים")
            TAXI -> T("Gett rides, on their own or before a train", "נסיעות Gett, בפני עצמן או לפני רכבת")
            BIKE -> T("Cycling routes", "מסלולי אופניים")
            SHARED -> T("Rental scooters and bike share", "קורקינטים ואופניים שיתופיים")
            WALK -> T("Routes done entirely on foot", "מסלולים המתבצעים כולם ברגל")
        }
}

fun routeTypesFor(on: Set<ResultFilter>): List<Int> {
    val all = listOf(0, 1, 2, 3, 4, 5, 6, 7)
    val off = HashSet<Int>()
    if (ResultFilter.BUS !in on) off.add(3)
    if (ResultFilter.TRAIN !in on) off.add(2)
    if (ResultFilter.LIGHT_RAIL !in on) off.addAll(listOf(0, 1))
    if (ResultFilter.CARMELIT_RAKAVLIT !in on) off.addAll(listOf(5, 6, 7))
    return all.filterNot { it in off }
}

fun filterResults(list: List<Moovit.Itinerary>, on: Set<ResultFilter>, r: Moovit.Resolved): List<Moovit.Itinerary> {
    if (on.size == ResultFilter.entries.size) return list
    return list.filter { it ->
        val legs = it.legs
        val hasRide = legs.any { l -> l.kind == Moovit.LegKind.RIDE }
        val hasTaxi = legs.any { l -> l.kind == Moovit.LegKind.TAXI }
        val hasBike = legs.any { l -> l.kind == Moovit.LegKind.BIKE }
        // Moovit's scooter / bike-share legs are the ones Kav doesn't parse.
        val hasShared = legs.any { l -> l.kind == Moovit.LegKind.OTHER }
        if (ResultFilter.SHARED !in on && hasShared) return@filter false
        if (ResultFilter.TAXI !in on && hasTaxi) return@filter false
        if (ResultFilter.BIKE !in on && hasBike) return@filter false
        if (ResultFilter.WALK !in on && !hasRide && !hasTaxi && !hasBike && !hasShared) return@filter false
        legs.filter { l -> l.kind == Moovit.LegKind.RIDE }.all { ride ->
            val info = r.line(ride.lineId) ?: return@all true
            val rt = r.routeType(info.agencyId)
            if (rt == 711) return@all ResultFilter.SHUTTLE in on
            when (modeOf(rt)) {
                Mode.BUS -> ResultFilter.BUS in on
                Mode.TRAIN -> ResultFilter.TRAIN in on
                Mode.TAXI -> ResultFilter.BUS in on
                Mode.TRAM, Mode.SUBWAY -> ResultFilter.LIGHT_RAIL in on
                Mode.FUNICULAR, Mode.CABLE, Mode.GONDOLA -> ResultFilter.CARMELIT_RAKAVLIT in on
                else -> true
            }
        }
    }
}

@Composable
fun SwitchRow(
    label: String,
    desc: String,
    on: Boolean,
    onToggle: (Boolean) -> Unit,
    leading: @Composable () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().panel(14.dp)
            .then(if (on) Modifier.border(1.dp, K.borderStrong, RoundedCornerShape(14.dp)) else Modifier)
            .semantics { contentDescription = label; toggleableState = if (on) ToggleableState.On else ToggleableState.Off }
            .clickable(role = Role.Switch) { onToggle(!on) }
            .padding(horizontal = K.gap3, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(K.gap3),
    ) {
        Box(Modifier.width(22.dp), contentAlignment = Alignment.Center) { leading() }
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 14.sp, color = if (on) K.text else K.muted)
            Text(desc, fontSize = 11.sp, color = K.dim, lineHeight = 15.sp, modifier = Modifier.padding(top = 2.dp))
        }
        Switch(on)
    }
}

@Composable
fun FilterRows(enabled: Set<ResultFilter>, onToggle: (ResultFilter, Boolean) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = K.gap3), verticalArrangement = Arrangement.spacedBy(K.gap1)) {
        ResultFilter.entries.forEach { f ->
            val on = f in enabled
            SwitchRow(f.label, f.desc, on, { onToggle(f, it) }) {
                when (f) {
                    ResultFilter.BUS -> ModeGlyph(Mode.BUS, if (on) K.text else K.dim, 18.dp)
                    ResultFilter.TRAIN -> ModeGlyph(Mode.TRAIN, if (on) K.text else K.dim, 18.dp)
                    ResultFilter.LIGHT_RAIL -> ModeGlyph(Mode.TRAM, if (on) K.text else K.dim, 18.dp)
                    ResultFilter.CARMELIT_RAKAVLIT -> CarmelitMark(18.dp)
                    ResultFilter.SHUTTLE -> ShuttleMark(18.dp)
                    ResultFilter.TAXI -> ModeGlyph(Mode.TAXI, if (on) K.text else K.dim, 18.dp)
                    ResultFilter.BIKE -> BikeGlyph(if (on) K.text else K.dim)
                    ResultFilter.SHARED -> androidx.compose.material3.Icon(
                        Icons.Rounded.ElectricScooter, null,
                        tint = if (on) K.text else K.dim, modifier = Modifier.size(18.dp),
                    )
                    ResultFilter.WALK -> WalkGlyph(if (on) K.text else K.dim, 16.dp)
                }
            }
        }
    }
}

@Composable
private fun Switch(on: Boolean) {
    val track by animateColorAsState(if (on) K.accent else K.surface4, label = "track")
    val knob by animateDpAsState(if (on) 20.dp else 2.dp, label = "knob")
    Box(Modifier.width(40.dp).height(22.dp).clip(RoundedCornerShape(999.dp)).background(track)) {
        Box(
            Modifier.padding(start = knob, top = 2.dp).size(18.dp).clip(RoundedCornerShape(999.dp))
                .background(if (on) K.bg else K.muted),
        )
    }
}
