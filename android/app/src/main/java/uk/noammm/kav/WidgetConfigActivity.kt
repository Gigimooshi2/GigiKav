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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material3.Icon
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
import uk.noammm.kav.ui.FavouriteIcons
import uk.noammm.kav.ui.K
import uk.noammm.kav.ui.T

/** Lets you pick which saved places a Kav places widget shows. */
class WidgetConfigActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }
        val result = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
        setResult(RESULT_CANCELED, result)

        T.lang = Prefs.lang(this)
        K.accent = Color(Prefs.accent(this))
        K.pack = Prefs.pack(this)
        K.applyTheme(Prefs.look(this))

        setContent {
            var favs by remember { mutableStateOf(Prefs.favourites(this)) }
            var hidden by remember { mutableStateOf(PlacesWidget.hidden(this, id)) }
            // Coming back from the app after adding a place: pick it up.
            val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
            DisposableEffect(owner) {
                val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
                    if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) favs = Prefs.favourites(this@WidgetConfigActivity)
                }
                owner.lifecycle.addObserver(obs)
                onDispose { owner.lifecycle.removeObserver(obs) }
            }
            Column(
                Modifier.fillMaxSize().background(K.bg).systemBarsPadding().padding(16.dp),
            ) {
                Text(T("Widget places", "מקומות בווידג׳ט"), fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = K.text)
                Spacer(Modifier.height(4.dp))
                Text(
                    T("Choose which saved places show on this widget.", "בחרו אילו מקומות שמורים יופיעו בווידג׳ט."),
                    fontSize = 14.sp, color = K.dim,
                )
                Spacer(Modifier.height(16.dp))
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(favs, key = { it.id }) { f ->
                        val shown = f.id !in hidden
                        fun toggle() { hidden = if (shown) hidden + f.id else hidden - f.id }
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
                                .background(Color.White.copy(alpha = .06f))
                                .clickable { toggle() }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                FavouriteIcons.firstOrNull { it.first == f.icon }?.second ?: Icons.Rounded.Place,
                                null, tint = if (f.place != null) K.accent else K.dim, modifier = Modifier.size(24.dp),
                            )
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(f.name, fontSize = 16.sp, color = K.text)
                                Text(
                                    f.place?.name ?: T("Not set yet, tap it on the widget to set", "עוד לא הוגדר, הקישו עליו בווידג׳ט להגדרה"),
                                    fontSize = 12.sp, color = K.dim, maxLines = 1,
                                )
                            }
                            Switch(
                                checked = shown, onCheckedChange = { toggle() },
                                colors = SwitchDefaults.colors(checkedTrackColor = K.accent, checkedThumbColor = Color.White),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    T("Add or change saved places in Kav", "הוספה או שינוי של מקומות שמורים ב-Kav"),
                    fontSize = 15.sp, color = K.text,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))
                        .background(Color.White.copy(alpha = .08f))
                        .clickable {
                            startActivity(Intent(this@WidgetConfigActivity, MainActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                        .padding(16.dp),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    T("Save", "שמירה"),
                    fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color.White,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))
                        .background(K.accent)
                        .clickable {
                            PlacesWidget.setHidden(this@WidgetConfigActivity, id, hidden)
                            PlacesWidget.refresh(this@WidgetConfigActivity)
                            setResult(RESULT_OK, result)
                            finish()
                        }
                        .padding(16.dp),
                )
            }
        }
    }
}
