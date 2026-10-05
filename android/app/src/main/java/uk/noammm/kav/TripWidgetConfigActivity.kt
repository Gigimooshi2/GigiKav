package uk.noammm.kav

import android.Manifest
import android.app.TimePickerDialog
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import uk.noammm.kav.ui.K
import uk.noammm.kav.ui.T

/** Settings for one "Kav trip" widget: destination, start, time, and whether it keeps refreshing. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
class TripWidgetConfigActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }
        val result = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
        setResult(RESULT_CANCELED, result)
        T.lang = Prefs.lang(this)
        K.pack = Prefs.pack(this)
        K.accent = Color(Prefs.accent(this))
        K.applyTheme(Prefs.look(this))

        val places = Prefs.favourites(this).filter { it.place != null }
        val start = TripWidget.cfg(this, id)
        setContent {
            var dest by remember { mutableStateOf(start.destId ?: places.firstOrNull()?.id) }
            var origin by remember { mutableStateOf(start.originId) }
            var mode by remember { mutableIntStateOf(start.mode) }
            var minute by remember { mutableIntStateOf(start.minute) }
            var paused by remember { mutableStateOf(start.paused) }
            var bgAllowed by remember { mutableStateOf(backgroundLocation()) }
            val askBg = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { bgAllowed = backgroundLocation() }

            @Composable
            fun Pill(label: String, on: Boolean, click: () -> Unit) = Text(
                label, fontSize = 14.sp, color = if (on) K.onAccent else K.text,
                modifier = Modifier.padding(end = 8.dp, bottom = 8.dp).clip(RoundedCornerShape(999.dp))
                    .background(if (on) K.accent else Color.White.copy(alpha = .08f))
                    .clickable(onClick = click).padding(horizontal = 14.dp, vertical = 9.dp),
            )

            @Composable
            fun Heading(text: String) = Text(
                text, fontSize = 13.sp, color = K.dim, fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(top = 18.dp, bottom = 8.dp),
            )

            Column(
                Modifier.fillMaxSize().background(K.bg).systemBarsPadding().padding(16.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(T("Trip widget", "ווידג׳ט נסיעה"), fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = K.text)
                if (places.isEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        T("Save a place in Kav first (Home, Work…), then come back.", "שמרו קודם מקום ב-Kav (בית, עבודה…), ואז חזרו."),
                        fontSize = 14.sp, color = K.dim,
                    )
                }

                Heading(T("To", "אל"))
                FlowRow { for (f in places) Pill(f.name, dest == f.id) { dest = f.id } }

                Heading(T("From", "מ"))
                FlowRow {
                    Pill(T("Where I am", "איפה שאני"), origin == null) { origin = null }
                    for (f in places) Pill(f.name, origin == f.id) { origin = f.id }
                }
                if (origin == null && !bgAllowed) Text(
                    T(
                        "To use where you are while Kav is closed, Android needs location \"Allow all the time\". " +
                            "Without it, the widget uses where Kav last saw you (up to 6 h old).",
                        "כדי להשתמש במיקום שלכם כש-Kav סגורה, אנדרואיד צריכה הרשאת מיקום \"תמיד\". " +
                            "בלעדיה הווידג׳ט משתמש במקום האחרון ש-Kav ראתה (עד 6 שעות).",
                    ),
                    fontSize = 12.sp, color = K.dim,
                    modifier = Modifier.clickable {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) askBg.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    },
                )
                if (origin == null && !bgAllowed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Pill(
                    T("Allow all the time", "לאפשר תמיד"), false,
                ) { askBg.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION) }

                Heading(T("When", "מתי"))
                FlowRow {
                    Pill(T("Leave now", "יציאה עכשיו"), mode == TripWidget.MODE_NOW) { mode = TripWidget.MODE_NOW }
                    Pill(T("Leave at", "יציאה ב-"), mode == TripWidget.MODE_DEPART) { mode = TripWidget.MODE_DEPART }
                    Pill(T("Arrive by", "הגעה עד"), mode == TripWidget.MODE_ARRIVE) { mode = TripWidget.MODE_ARRIVE }
                }
                if (mode != TripWidget.MODE_NOW) Pill("🕒 " + TripWidget.clock(minute), false) {
                    TimePickerDialog(this@TripWidgetConfigActivity, { _, h, m -> minute = h * 60 + m }, minute / 60, minute % 60, true).show()
                }

                Heading(T("Refreshing", "רענון"))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(T("Stop refreshing", "הפסקת רענון"), fontSize = 16.sp, color = K.text)
                        Text(
                            T(
                                "Off: refreshes every 15 min and when you leave Kav. On: only when you tap ↻.",
                                "כבוי: מתרענן כל 15 דק׳ וכשיוצאים מ-Kav. פועל: רק בלחיצה על ↻.",
                            ),
                            fontSize = 12.sp, color = K.dim,
                        )
                    }
                    Switch(
                        checked = paused, onCheckedChange = { paused = it },
                        colors = SwitchDefaults.colors(checkedTrackColor = K.accent),
                    )
                }

                Spacer(Modifier.height(24.dp))
                Text(
                    T("Save", "שמירה"), fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                    color = if (dest != null) K.onAccent else K.dim,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))
                        .background(if (dest != null) K.accent else Color.White.copy(alpha = .08f))
                        .clickable(enabled = dest != null) {
                            TripWidget.save(this@TripWidgetConfigActivity, id, TripWidget.Cfg(dest, origin, mode, minute, paused))
                            TripWidget.render(this@TripWidgetConfigActivity, id)
                            TripWidget.schedule(this@TripWidgetConfigActivity, id)
                            TripWidget.refreshNow(this@TripWidgetConfigActivity, id)
                            setResult(RESULT_OK, result)
                            finish()
                        }
                        .padding(16.dp),
                )
            }
        }
    }

    private fun backgroundLocation() = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
}
