package uk.noammm.kav

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uk.noammm.kav.data.Net
import uk.noammm.kav.ui.K
import uk.noammm.kav.ui.KavField
import uk.noammm.kav.ui.Online
import uk.noammm.kav.ui.StopPhotos
import uk.noammm.kav.ui.T

/** Settings for a "Kav station" widget: which stop, which line, keep refreshing or not. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
class StationWidgetConfigActivity : ComponentActivity() {
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
        Online.init(this)
        val before = StationWidget.cfg(this, id)

        setContent {
            val scope = rememberCoroutineScope()
            var net by remember { mutableStateOf<Net?>(null) }
            LaunchedEffect(Unit) { net = runCatching { loadNet(this@StationWidgetConfigActivity) }.getOrNull() }
            var q by remember { mutableStateOf("") }
            var stop by remember { mutableIntStateOf(-1) }
            var lines by remember { mutableStateOf<List<String>>(emptyList()) }
            var line by remember { mutableStateOf(before?.line) }
            var paused by remember { mutableStateOf(before?.paused ?: false) }
            var busy by remember { mutableStateOf(false) }
            var error by remember { mutableStateOf<String?>(null) }

            val n = net
            val matches by produceState(emptyList<Int>(), n, q) {
                value = if (n == null || q.trim().length < 2) emptyList() else withContext(Dispatchers.Default) {
                    val t = q.trim()
                    val code = t.toIntOrNull()
                    (0 until n.nStops).filter { i -> n.code[i] == code || n.name[i].contains(t, ignoreCase = true) }
                        .sortedBy { n.name[it].length }.take(40)
                }
            }
            LaunchedEffect(stop, n) {
                val nn = n ?: return@LaunchedEffect
                if (stop < 0) { lines = emptyList(); return@LaunchedEffect }
                lines = withContext(Dispatchers.Default) {
                    val out = HashSet<String>()
                    for (k in nn.stStop.indices) if (nn.stStop[k] == stop) out.add(nn.rShort[nn.tripRoute[nn.tripOf(k)]])
                    out.filter { it.isNotBlank() }.sortedWith(compareBy({ it.filter(Char::isDigit).toIntOrNull() ?: Int.MAX_VALUE }, { it }))
                }
                if (line !in lines) line = null
            }

            @Composable
            fun Pill(label: String, on: Boolean, click: () -> Unit) = Text(
                label, fontSize = 14.sp, color = if (on) K.onAccent else K.text,
                modifier = Modifier.padding(end = 8.dp, bottom = 8.dp).clip(RoundedCornerShape(999.dp))
                    .background(if (on) K.accent else Color.White.copy(alpha = .08f))
                    .clickable(onClick = click).padding(horizontal = 14.dp, vertical = 9.dp),
            )

            Column(Modifier.fillMaxSize().background(K.bg).systemBarsPadding().padding(16.dp)) {
                Text(T("Station widget", "ווידג׳ט תחנה"), fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = K.text)
                before?.let {
                    Text(T("Now: line ${it.line} at ${it.stopName}", "כרגע: קו ${it.line} ב${it.stopName}"), fontSize = 13.sp, color = K.dim)
                }
                Spacer(Modifier.height(12.dp))
                if (n == null) {
                    Text(T("Loading the timetable…", "טוען את לוח הזמנים…"), fontSize = 14.sp, color = K.dim)
                } else if (stop < 0) {
                    KavField(q, { q = it }, T("Station name or stop code", "שם תחנה או מספר תחנה"), Modifier.fillMaxWidth(), autoFocus = true)
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(Modifier.weight(1f)) {
                        items(matches) { i ->
                            Column(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { stop = i }.padding(12.dp),
                            ) {
                                Text(n.name[i], fontSize = 16.sp, color = K.text)
                                Text(listOf(n.cityOf(i), T("Stop ${n.code[i]}", "תחנה ${n.code[i]}")).filter { it.isNotBlank() }.joinToString(" · "),
                                    fontSize = 12.sp, color = K.dim)
                            }
                        }
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(n.name[stop], fontSize = 17.sp, color = K.text, fontWeight = FontWeight.Medium)
                            Text(T("Stop ${n.code[stop]}", "תחנה ${n.code[stop]}"), fontSize = 12.sp, color = K.dim)
                        }
                        Pill(T("Change", "החלפה"), false) { stop = -1; error = null }
                    }
                    Text(T("Line", "קו"), fontSize = 13.sp, color = K.dim, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
                    if (lines.isEmpty()) Text(T("Finding lines…", "מחפש קווים…"), fontSize = 13.sp, color = K.dim)
                    FlowRow { for (l in lines) Pill(l, line == l) { line = l } }
                    Spacer(Modifier.height(16.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(T("Stop refreshing", "הפסקת רענון"), fontSize = 16.sp, color = K.text)
                            Text(T("Off: every 15 min and when you leave Kav. On: only when you tap ↻.",
                                "כבוי: כל 15 דק׳ וכשיוצאים מ-Kav. פועל: רק בלחיצה על ↻."), fontSize = 12.sp, color = K.dim)
                        }
                        Switch(paused, { paused = it }, colors = SwitchDefaults.colors(checkedTrackColor = K.accent))
                    }
                    error?.let { Text(it, fontSize = 13.sp, color = K.critical, modifier = Modifier.padding(top = 8.dp)) }
                    Spacer(Modifier.weight(1f))
                    val ready = line != null && !busy
                    Text(
                        if (busy) T("Finding the stop on Moovit…", "מאתר את התחנה ב-Moovit…") else T("Save", "שמירה"),
                        fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = if (ready) K.onAccent else K.dim,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))
                            .background(if (ready) K.accent else Color.White.copy(alpha = .08f))
                            .clickable(enabled = ready) {
                                val l = line ?: return@clickable
                                busy = true; error = null
                                scope.launch {
                                    val mid = runCatching {
                                        withContext(Dispatchers.IO) {
                                            val at = n.lat[stop].toDouble() to n.lon[stop].toDouble()
                                            uk.noammm.kav.data.Moovit.stopIdByCode(Online.open(at), n.name[stop], n.code[stop], at)
                                        }
                                    }.getOrNull()
                                    busy = false
                                    if (mid == null) {
                                        error = T("Couldn't find this stop on Moovit. Check your connection and try again.",
                                            "לא הצלחנו למצוא את התחנה ב-Moovit. בדקו את החיבור ונסו שוב.")
                                        return@launch
                                    }
                                    val ctx = this@StationWidgetConfigActivity
                                    StationWidget.save(ctx, id, StationWidget.Cfg(
                                        n.name[stop], n.code[stop], n.lat[stop], n.lon[stop], mid, l, paused,
                                    ))
                                    StationWidget.render(ctx, id)
                                    StationWidget.schedule(ctx, id)
                                    StationWidget.refreshNow(ctx, id)
                                    setResult(RESULT_OK, result)
                                    finish()
                                }
                            }
                            .padding(16.dp),
                    )
                }
            }
        }
    }
}
