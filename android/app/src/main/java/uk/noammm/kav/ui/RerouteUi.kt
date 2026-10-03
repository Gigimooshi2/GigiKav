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

/** Smart-reroute on/off. glass = draw its own glass plate (trip page); false inside an existing bar. */
@Composable
fun RerouteToggle(glass: Boolean = true) {
    val ctx = LocalContext.current
    val on = Reroute.enabled
    Box(
        Modifier.size(48.dp)
            .then(if (glass) Modifier.glassSurface(24.dp) else Modifier.clip(RoundedCornerShape(24.dp)))
            .then(if (on) Modifier.clip(RoundedCornerShape(24.dp)).background(K.accent.copy(alpha = .22f)) else Modifier)
            .semantics {
                contentDescription = T("Smart reroute", "ניתוב חכם")
                stateDescription = if (on) T("On", "פועל") else T("Off", "כבוי")
            }
            .clickable(role = Role.Switch) {
                Reroute.toggle(ctx)
                Toast.makeText(
                    ctx,
                    if (Reroute.enabled) T("Smart reroute on: you'll hear about routes 3+ min faster.", "ניתוב חכם פועל: תקבלו התראה על מסלול מהיר ב-3 דק׳ ומעלה.")
                    else T("Smart reroute off", "ניתוב חכם כבוי"),
                    Toast.LENGTH_SHORT,
                ).show()
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.AltRoute, null, tint = if (on) K.accent else K.muted, modifier = Modifier.size(22.dp))
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
