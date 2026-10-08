package uk.noammm.kav.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AltRoute
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import uk.noammm.kav.Reroute

/**
 * Smart reroute button. Tap opens a small menu: the on/off switch, and other saved places to also
 * consider, so Kav can point you at whichever one you'd reach first.
 * glass = draw its own glass plate (trip page); false inside an existing bar.
 */
@Composable
fun RerouteToggle(glass: Boolean = true) {
    val ctx = LocalContext.current
    val on = Reroute.enabled
    var menu by remember { mutableStateOf(false) }
    Box {
        Box(
            Modifier.size(48.dp)
                .then(if (glass) Modifier.glassSurface(24.dp) else Modifier.clip(RoundedCornerShape(24.dp)))
                .then(if (on) Modifier.clip(RoundedCornerShape(24.dp)).background(K.accent.copy(alpha = .22f)) else Modifier)
                .semantics {
                    contentDescription = T("Smart reroute", "ניתוב חכם")
                    stateDescription = if (on) T("On", "פועל") else T("Off", "כבוי")
                }
                .clickable(role = Role.Button) { menu = true },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.AltRoute, null, tint = if (on) K.accent else K.muted, modifier = Modifier.size(22.dp))
            if (on && Reroute.alts.isNotEmpty()) Text(
                "+${Reroute.alts.size}", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = K.onAccent,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 4.dp, end = 4.dp)
                    .clip(RoundedCornerShape(999.dp)).background(K.accent).padding(horizontal = 4.dp),
            )
        }
        androidx.compose.material3.DropdownMenu(
            expanded = menu, onDismissRequest = { menu = false },
            modifier = Modifier.background(K.surface2).widthIn(min = 260.dp, max = 320.dp),
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(T("Smart reroute", "ניתוב חכם"), fontSize = 16.sp, color = K.text, fontWeight = FontWeight.Medium)
                        Text(T("Tells you about routes 3+ min faster.", "מתריע על מסלול מהיר ב-3 דק׳ ומעלה."), fontSize = 12.sp, color = K.dim)
                    }
                    androidx.compose.material3.Switch(
                        checked = on, onCheckedChange = { Reroute.toggle(ctx) },
                        colors = androidx.compose.material3.SwitchDefaults.colors(checkedTrackColor = K.accent),
                    )
                }
                val places = remember { uk.noammm.kav.Prefs.favourites(ctx).filter { it.place != null } }
                if (places.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text(T("Also consider", "לשקול גם"), fontSize = 13.sp, color = K.dim, fontWeight = FontWeight.Medium)
                    Text(
                        T("Kav will point you at whichever you'd reach first.", "Kav תכוון אתכם למקום שתגיעו אליו הכי מהר."),
                        fontSize = 12.sp, color = K.dim,
                    )
                    Spacer(Modifier.height(8.dp))
                    for (f in places) {
                        val picked = f.id in Reroute.alts
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                .clickable(enabled = on) { Reroute.setAlt(ctx, f.id, !picked) }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            androidx.compose.material3.Checkbox(
                                checked = picked, enabled = on,
                                onCheckedChange = { Reroute.setAlt(ctx, f.id, it) },
                                colors = androidx.compose.material3.CheckboxDefaults.colors(checkedColor = K.accent),
                            )
                            Text(f.name, fontSize = 15.sp, color = if (on) K.text else K.dim)
                        }
                    }
                    if (Reroute.alts.size > 3) Text(
                        T("Only the first 3 are checked at a time.", "רק 3 הראשונים נבדקים בכל פעם."),
                        fontSize = 11.sp, color = K.dim,
                    )
                }
            }
        }
    }
}

/** In-trip card offering the faster route. */
@Composable
fun RerouteBanner(modifier: Modifier = Modifier) {
    val o = Reroute.offer ?: return
    val ctx = LocalContext.current
    Column(
        modifier.fillMaxWidth().padding(horizontal = K.gap3).glassSurface(K.rCard)
            .background(K.accent.copy(alpha = .14f)).padding(K.gap3),
    ) {
        Text(o.headline, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = K.text, maxLines = 2)
        Text(o.detail, fontSize = 13.sp, color = K.accent)
        Spacer(Modifier.height(K.gap2))
        Row(horizontalArrangement = Arrangement.spacedBy(K.gap2)) {
            Text(
                T("Switch", "החלפה"), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = K.onAccent,
                modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(K.accent)
                    .clickable(role = Role.Button) { Reroute.accept(ctx) }
                    .padding(horizontal = 18.dp, vertical = 10.dp),
            )
            Text(
                T("Dismiss", "התעלמות"), fontSize = 14.sp, color = K.text,
                modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(K.surface4)
                    .clickable(role = Role.Button) { Reroute.dismiss(ctx) }
                    .padding(horizontal = 18.dp, vertical = 10.dp),
            )
        }
    }
}
