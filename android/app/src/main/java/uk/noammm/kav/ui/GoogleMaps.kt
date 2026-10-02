package uk.noammm.kav.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import uk.noammm.kav.data.Moovit

/** Hands a leg off to Google Maps turn-by-turn. mode: "b" bicycle, "w" walk. */
@Composable
fun GoogleMapsButton(dest: Pair<Double, Double>?, mode: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Row(
        modifier.heightIn(min = 48.dp).glassSurface(24.dp)
            .clickable(enabled = dest != null, role = Role.Button) {
                val (lat, lon) = dest ?: return@clickable
                try {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$lat,$lon&mode=$mode"))
                            .setPackage("com.google.android.apps.maps"),
                    )
                } catch (_: ActivityNotFoundException) {
                    val travel = if (mode == "b") "bicycling" else "walking"
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(
                            "https://www.google.com/maps/dir/?api=1&destination=$lat,$lon&travelmode=$travel",
                        )))
                    }.onFailure {
                        Toast.makeText(context, T("Install Google Maps to navigate.", "יש להתקין את Google Maps כדי לנווט."), Toast.LENGTH_LONG).show()
                    }
                }
            }.padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            if (dest == null) T("Destination unavailable", "היעד לא זמין") else T("Navigate in Google Maps", "ניווט ב-Google Maps"),
            fontSize = 13.sp, color = if (dest == null) K.dim else K.text, fontWeight = FontWeight.Medium,
        )
        if (dest != null) Text("↗", fontSize = 16.sp, color = K.muted)
    }
}
