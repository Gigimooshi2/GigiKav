package uk.noammm.kav

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import org.json.JSONArray
import org.json.JSONObject
import uk.noammm.kav.data.Moovit
import uk.noammm.kav.ui.Online
import uk.noammm.kav.ui.T
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/** "Kav station" widget: every upcoming arrival of one line at one stop, live where Moovit has it. */
class StationWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        for (id in ids) { render(ctx, id); schedule(ctx, id); refreshNow(ctx, id) }
    }

    override fun onDeleted(ctx: Context, ids: IntArray) {
        val e = store(ctx).edit()
        for (id in ids) {
            WorkManager.getInstance(ctx).cancelUniqueWork("stationwidget-$id")
            for (k in listOf("cfg", "rows")) e.remove("${k}_$id")
        }
        e.apply()
    }

    /** One widget's choice. moovitStop is Moovit's id for the stop, found when you set it up. */
    class Cfg(
        val stopName: String, val stopCode: Int, val lat: Double, val lon: Double,
        val moovitStop: Int, val line: String, val paused: Boolean,
    ) {
        fun json(): String = JSONObject().put("name", stopName).put("code", stopCode).put("lat", lat).put("lon", lon)
            .put("mid", moovitStop).put("line", line).put("paused", paused).toString()
    }

    companion object {
        internal fun store(ctx: Context) = ctx.getSharedPreferences("kav_station_widget", Context.MODE_PRIVATE)

        fun cfg(ctx: Context, id: Int): Cfg? = store(ctx).getString("cfg_$id", null)?.let {
            runCatching {
                val o = JSONObject(it)
                Cfg(o.getString("name"), o.optInt("code"), o.getDouble("lat"), o.getDouble("lon"),
                    o.getInt("mid"), o.getString("line"), o.optBoolean("paused"))
            }.getOrNull()
        }

        fun save(ctx: Context, id: Int, c: Cfg) {
            store(ctx).edit().putString("cfg_$id", c.json()).remove("rows_$id").apply()
        }

        private fun ids(ctx: Context): IntArray =
            AppWidgetManager.getInstance(ctx).getAppWidgetIds(ComponentName(ctx, StationWidget::class.java))

        fun refreshAll(ctx: Context) = runCatching { for (id in ids(ctx)) if (cfg(ctx, id)?.paused != true) refreshNow(ctx, id) }

        private val net = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun schedule(ctx: Context, id: Int) {
            val wm = WorkManager.getInstance(ctx)
            if (cfg(ctx, id)?.paused != false) { wm.cancelUniqueWork("stationwidget-$id"); return }
            wm.enqueueUniquePeriodicWork(
                "stationwidget-$id", ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<StationWidgetWorker>(15, TimeUnit.MINUTES)
                    .setConstraints(net).setInputData(workDataOf("id" to id)).build(),
            )
        }

        fun refreshNow(ctx: Context, id: Int) {
            WorkManager.getInstance(ctx).enqueueUniqueWork(
                "stationwidget-now-$id", ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<StationWidgetWorker>().setConstraints(net).setInputData(workDataOf("id" to id)).build(),
            )
        }

        fun render(ctx: Context, id: Int) {
            val c = cfg(ctx, id)
            val hm = SimpleDateFormat("HH:mm", Locale.US)
            val v = RemoteViews(ctx.packageName, R.layout.widget_trip)
            val saved = store(ctx).getString("rows_$id", null)?.let { runCatching { JSONObject(it) }.getOrNull() }
            v.setTextViewText(R.id.trip_title, if (c == null) T("Kav station", "תחנה ב-Kav") else T("Line ${c.line}", "קו ${c.line}"))
            v.setTextViewText(R.id.trip_sub, listOfNotNull(
                c?.stopName,
                saved?.optLong("at")?.takeIf { it > 0 }?.let { T("updated ", "עודכן ") + hm.format(Date(it)) },
                T("paused", "מושהה").takeIf { c?.paused == true },
            ).joinToString(" · "))
            v.removeAllViews(R.id.trip_rows)
            val rows = saved?.optJSONArray("rows")
            val note = when {
                c == null -> T("Tap ✏️ to pick a station and line", "הקישו ✏️ לבחירת תחנה וקו")
                saved == null -> T("Loading…", "טוען…")
                saved.optString("error").isNotBlank() -> saved.optString("error")
                rows == null || rows.length() == 0 -> T("No upcoming ${c.line} here right now", "אין כרגע ${c.line} קרוב בתחנה")
                else -> null
            }
            v.setViewVisibility(R.id.trip_note, if (note != null) View.VISIBLE else View.GONE)
            note?.let { v.setTextViewText(R.id.trip_note, it) }
            val now = System.currentTimeMillis() / 1000
            if (rows != null && note == null) for (i in 0 until rows.length()) {
                val r = rows.getJSONObject(i)
                val at = r.getLong("t")
                val mins = ((at - now) / 60).toInt()
                val row = RemoteViews(ctx.packageName, R.layout.widget_trip_row)
                row.setTextViewText(R.id.row_lines, when {
                    mins <= 0 -> T("now", "עכשיו")
                    mins < 60 -> T("in $mins min", "בעוד $mins דק׳")
                    else -> hm.format(Date(at * 1000))
                })
                row.setTextViewText(R.id.row_times, hm.format(Date(at * 1000)))
                row.setTextColor(R.id.row_times, r.optInt("c", 0xFFFFFFFF.toInt()))
                row.setTextViewText(R.id.row_detail, listOf(r.optString("state"), r.optString("to")).filter { it.isNotBlank() }.joinToString(" · "))
                row.setOnClickPendingIntent(R.id.row_root, openApp(ctx, id))
                v.addView(R.id.trip_rows, row)
            }
            v.setOnClickPendingIntent(R.id.trip_head, openApp(ctx, id))
            v.setOnClickPendingIntent(R.id.trip_refresh, PendingIntent.getBroadcast(
                ctx, 17000 + id, Intent(ctx, StationWidgetRefresh::class.java).putExtra("id", id),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ))
            v.setOnClickPendingIntent(R.id.trip_edit, PendingIntent.getActivity(
                ctx, 18000 + id,
                Intent(ctx, StationWidgetConfigActivity::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ))
            AppWidgetManager.getInstance(ctx).updateAppWidget(id, v)
        }

        private fun openApp(ctx: Context, id: Int) = PendingIntent.getActivity(
            ctx, 19000 + id, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}

class StationWidgetRefresh : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getIntExtra("id", -1).takeIf { it >= 0 } ?: return
        StationWidget.refreshNow(ctx, id)
    }
}

class StationWidgetWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val id = inputData.getInt("id", -1).takeIf { it >= 0 } ?: return Result.success()
        val c = StationWidget.cfg(ctx, id) ?: return Result.success()
        val out = try {
            JSONObject().put("at", System.currentTimeMillis()).put("rows", fetch(ctx, c))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("KavStationWidget", "refresh failed", e)
            JSONObject().put("at", System.currentTimeMillis()).put("error", T("Couldn't reach Moovit", "לא ניתן להגיע ל-Moovit"))
        }
        StationWidget.store(ctx).edit().putString("rows_$id", out.toString()).apply()
        StationWidget.render(ctx, id)
        return Result.success()
    }

    private suspend fun fetch(ctx: Context, c: StationWidget.Cfg): JSONArray = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        T.lang = Prefs.lang(ctx)
        Moovit.shareLocation = !Prefs.privateSearch(ctx)
        if (Online.session == null) Online.init(ctx)
        val s = Online.open(c.lat to c.lon)
        val arrivals = Moovit.stopArrivals(s, listOf(c.moovitStop)).first.values.filter { it.stopId == c.moovitStop }
        // Line ids -> numbers, remembered so later refreshes don't ask again.
        val names = StationWidget.store(ctx)
        val lines = arrivals.map { it.lineId }.distinct().associateWith { lid ->
            names.getString("line_$lid", null) ?: runCatching { Moovit.lineInfo(s, lid) }.getOrNull()?.also { info ->
                names.edit().putString("line_$lid", info.number.trim()).putString("to_$lid", info.destination).apply()
            }?.number?.trim()
        }
        val now = System.currentTimeMillis() / 1000
        val rows = JSONArray()
        arrivals.filter { lines[it.lineId] == c.line.trim() }
            .map { it to it.departure() }
            .filter { (_, d) -> d.timeUtc >= now - 30 && d.state != Moovit.TimeState.CANCELED }
            .sortedBy { (_, d) -> d.timeUtc }
            .distinctBy { (a, _) -> a.tripId }
            .take(6)
            .forEach { (a, d) ->
                val (word, colour) = when {
                    d.state == Moovit.TimeState.OUT_OF_SHAPE -> T("Off route", "מחוץ למסלול") to 0xFFF0706A.toInt()
                    d.state == Moovit.TimeState.REAL_TIME_DROPPED -> T("Live lost", "זמן אמת אבד") to 0xFF9AA0A6.toInt()
                    d.delayed -> T("Delayed", "מתעכב") to 0xFFF5C24C.toInt()
                    d.state == Moovit.TimeState.REAL_TIME_LOW -> T("Live (weak)", "בזמן אמת (חלש)") to 0xFFF0706A.toInt()
                    d.state == Moovit.TimeState.REAL_TIME_MEDIUM -> T("Live", "בזמן אמת") to 0xFFF5C24C.toInt()
                    d.live -> T("Live", "בזמן אמת") to 0xFF7FD69A.toInt()
                    else -> T("Scheduled", "מתוזמן") to 0xFFFFFFFF.toInt()
                }
                rows.put(JSONObject().put("t", d.timeUtc).put("state", word).put("c", colour)
                    .put("to", names.getString("to_${a.lineId}", "").orEmpty().let { if (it.isBlank()) "" else T("to $it", "ל$it") }))
            }
        rows
    }
}
