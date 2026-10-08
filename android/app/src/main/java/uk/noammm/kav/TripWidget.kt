package uk.noammm.kav

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.net.Uri
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
import uk.noammm.kav.ui.routeTypesFor
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * "Kav trip" widget: bus options from here (or a saved place) to a saved destination.
 * Android gives widgets no "page became visible" signal, so it refreshes every 15 minutes,
 * whenever Kav goes to the background, and on tap of its refresh button.
 */
class TripWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        for (id in ids) { render(ctx, id); schedule(ctx, id); refreshNow(ctx, id) }
    }

    override fun onDeleted(ctx: Context, ids: IntArray) {
        val e = store(ctx).edit()
        for (id in ids) {
            WorkManager.getInstance(ctx).cancelUniqueWork("tripwidget-$id")
            for (k in listOf("dest", "origin", "mode", "minute", "paused", "rows")) e.remove("${k}_$id")
        }
        e.apply()
    }

    class Cfg(val destId: String?, val originId: String?, val mode: Int, val minute: Int, val paused: Boolean)

    companion object {
        const val MODE_NOW = 0
        const val MODE_DEPART = 1
        const val MODE_ARRIVE = 2
        private const val ACTION_REFRESH = "uk.noammm.kav.TRIP_WIDGET_REFRESH"

        internal fun store(ctx: Context) = ctx.getSharedPreferences("kav_trip_widget", Context.MODE_PRIVATE)

        fun cfg(ctx: Context, id: Int): Cfg = store(ctx).let { p ->
            Cfg(
                p.getString("dest_$id", null), p.getString("origin_$id", null),
                p.getInt("mode_$id", MODE_NOW), p.getInt("minute_$id", 8 * 60), p.getBoolean("paused_$id", false),
            )
        }

        fun save(ctx: Context, id: Int, c: Cfg) {
            store(ctx).edit()
                .putString("dest_$id", c.destId).putString("origin_$id", c.originId)
                .putInt("mode_$id", c.mode).putInt("minute_$id", c.minute).putBoolean("paused_$id", c.paused)
                .remove("rows_$id")
                .apply()
        }

        private fun ids(ctx: Context): IntArray =
            AppWidgetManager.getInstance(ctx).getAppWidgetIds(ComponentName(ctx, TripWidget::class.java))

        /** Kav just went to the background: freshen every trip widget. */
        fun refreshAll(ctx: Context) = runCatching { for (id in ids(ctx)) if (!cfg(ctx, id).paused) refreshNow(ctx, id) }

        private val net = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun schedule(ctx: Context, id: Int) {
            val wm = WorkManager.getInstance(ctx)
            if (cfg(ctx, id).paused) { wm.cancelUniqueWork("tripwidget-$id"); return }
            wm.enqueueUniquePeriodicWork(
                "tripwidget-$id", ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<TripWidgetWorker>(15, TimeUnit.MINUTES)
                    .setConstraints(net).setInputData(workDataOf("id" to id)).build(),
            )
        }

        fun refreshNow(ctx: Context, id: Int) {
            WorkManager.getInstance(ctx).enqueueUniqueWork(
                "tripwidget-now-$id", ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<TripWidgetWorker>().setConstraints(net).setInputData(workDataOf("id" to id)).build(),
            )
        }

        // Last position Kav saw, for when the phone won't give a fresh one in the background.
        @Volatile private var appCtx: Context? = null
        @Volatile private var lastSaved = 0L
        fun init(ctx: Context) { appCtx = ctx.applicationContext }
        fun noteHere(lat: Double, lon: Double) {
            val c = appCtx ?: return
            val now = System.currentTimeMillis()
            if (now - lastSaved < 60_000) return
            lastSaved = now
            store(c).edit().putString("here", "$lat,$lon,$now").apply()
        }
        internal fun savedHere(ctx: Context): Triple<Double, Double, Long>? =
            store(ctx).getString("here", null)?.split(',')?.takeIf { it.size == 3 }?.let {
                Triple(it[0].toDouble(), it[1].toDouble(), it[2].toLong())
            }

        fun render(ctx: Context, id: Int) {
            val c = cfg(ctx, id)
            val favs = Prefs.favourites(ctx)
            val dest = favs.firstOrNull { it.id == c.destId }
            val origin = favs.firstOrNull { it.id == c.originId }
            val hm = SimpleDateFormat("HH:mm", Locale.US)
            val v = RemoteViews(ctx.packageName, R.layout.widget_trip)
            v.setTextViewText(
                R.id.trip_title,
                if (dest == null) T("Kav trip", "נסיעה ב-Kav") else T("To ${dest.name}", "אל ${dest.name}"),
            )
            val saved = store(ctx).getString("rows_$id", null)?.let { runCatching { JSONObject(it) }.getOrNull() }
            val whenText = when (c.mode) {
                MODE_DEPART -> T("leave %s", "יציאה %s").format(clock(c.minute))
                MODE_ARRIVE -> T("arrive by %s", "הגעה עד %s").format(clock(c.minute))
                else -> T("leave now", "יציאה עכשיו")
            }
            val sub = listOfNotNull(
                T("from ", "מ") + (origin?.name ?: T("here", "כאן")),
                whenText,
                saved?.optLong("at")?.takeIf { it > 0 }?.let {
                    (if (saved.optBoolean("offline")) T("offline · from ", "לא מחובר · מ-") else T("updated ", "עודכן ")) + hm.format(Date(it))
                },
                T("paused", "מושהה").takeIf { c.paused },
            ).joinToString(" · ")
            v.setTextViewText(R.id.trip_sub, sub)

            v.removeAllViews(R.id.trip_rows)
            val rows = saved?.optJSONArray("rows")
            val note = when {
                dest == null || dest.place == null -> T("Tap ✏️ to choose a destination", "הקישו ✏️ לבחירת יעד")
                saved == null -> T("Loading…", "טוען…")
                saved.optString("error").isNotBlank() -> saved.optString("error")
                rows == null || rows.length() == 0 -> T("No bus options found", "לא נמצאו אוטובוסים")
                else -> null
            }
            v.setViewVisibility(R.id.trip_note, if (note != null) View.VISIBLE else View.GONE)
            note?.let { v.setTextViewText(R.id.trip_note, it) }
            if (rows != null && note == null) for (i in 0 until rows.length()) {
                val r = rows.getJSONObject(i)
                val row = RemoteViews(ctx.packageName, R.layout.widget_trip_row)
                row.setTextViewText(R.id.row_lines, r.optString("lines"))
                row.setTextViewText(R.id.row_times, r.optString("times"))
                row.setTextColor(R.id.row_times, if (r.optBoolean("live")) 0xFF7FD69A.toInt() else 0xFFFFFFFF.toInt())
                row.setTextViewText(R.id.row_detail, r.optString("detail"))
                dest?.place?.let { row.setOnClickPendingIntent(R.id.row_root, openPlan(ctx, id, origin?.place, it)) }
                v.addView(R.id.trip_rows, row)
            }
            dest?.place?.let { v.setOnClickPendingIntent(R.id.trip_head, openPlan(ctx, id, origin?.place, it)) }
            v.setOnClickPendingIntent(R.id.trip_refresh, PendingIntent.getBroadcast(
                ctx, 7000 + id, Intent(ctx, TripWidgetRefresh::class.java).setAction(ACTION_REFRESH).putExtra("id", id),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ))
            v.setOnClickPendingIntent(R.id.trip_edit, PendingIntent.getActivity(
                ctx, 8000 + id,
                Intent(ctx, TripWidgetConfigActivity::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ))
            AppWidgetManager.getInstance(ctx).updateAppWidget(id, v)
        }

        internal fun clock(minute: Int) = "%02d:%02d".format(minute / 60, minute % 60)

        private fun openPlan(ctx: Context, id: Int, from: Moovit.Place?, to: Moovit.Place): PendingIntent {
            val b = Uri.Builder().scheme("moovit").authority("directions")
                .appendQueryParameter("dest_lat", to.lat.toString()).appendQueryParameter("dest_lon", to.lon.toString())
                .appendQueryParameter("dest_name", to.name)
            if (from != null) b.appendQueryParameter("orig_lat", from.lat.toString())
                .appendQueryParameter("orig_lon", from.lon.toString()).appendQueryParameter("orig_name", from.name)
            return PendingIntent.getActivity(
                ctx, 9000 + id, Intent(Intent.ACTION_VIEW, b.build()).setPackage(ctx.packageName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }
}

class TripWidgetRefresh : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getIntExtra("id", -1).takeIf { it >= 0 } ?: return
        TripWidget.refreshNow(ctx, id)
    }
}

class TripWidgetWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val id = inputData.getInt("id", -1).takeIf { it >= 0 } ?: return Result.success()
        val prev = TripWidget.store(ctx).getString("rows_$id", null)?.let { runCatching { JSONObject(it) }.getOrNull() }
        val out = try {
            JSONObject().put("at", System.currentTimeMillis()).put("rows", fetch(ctx, id))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: java.io.IOException) {
            // No internet (or Moovit didn't answer): keep the last good times on screen, marked as old,
            // and let WorkManager retry with backoff once the network is back.
            android.util.Log.w("KavTripWidget", "offline", e)
            val keep = prev?.takeIf { (it.optJSONArray("rows")?.length() ?: 0) > 0 }
            val o = keep?.put("offline", true) ?: JSONObject().put("at", 0L)
                .put("error", T("No internet right now. Retrying…", "אין אינטרנט כרגע. מנסה שוב…"))
            TripWidget.store(ctx).edit().putString("rows_$id", o.toString()).apply()
            TripWidget.render(ctx, id)
            return if (runAttemptCount < 4) Result.retry() else Result.success()
        } catch (e: Exception) {
            android.util.Log.w("KavTripWidget", "refresh failed", e)
            JSONObject().put("at", System.currentTimeMillis()).put("error", e.message ?: T("Couldn't reach Moovit", "לא ניתן להגיע ל-Moovit"))
        }
        TripWidget.store(ctx).edit().putString("rows_$id", out.toString()).apply()
        TripWidget.render(ctx, id)
        return Result.success()
    }

    private suspend fun fetch(ctx: Context, id: Int): JSONArray {
        val c = TripWidget.cfg(ctx, id)
        val favs = Prefs.favourites(ctx)
        val to = favs.firstOrNull { it.id == c.destId }?.place ?: return JSONArray()
        val from = favs.firstOrNull { it.id == c.originId }?.place?.let { it.lat to it.lon }
            ?: here(ctx) ?: throw IllegalStateException(
                T("No location yet: open Kav once, or set a starting place", "אין מיקום: פתחו את Kav פעם אחת, או בחרו נקודת מוצא"),
            )
        T.lang = Prefs.lang(ctx)
        Moovit.shareLocation = !Prefs.privateSearch(ctx)
        if (Online.session == null) Online.init(ctx)
        val s = Online.open(from)
        val (at, type) = when (c.mode) {
            TripWidget.MODE_DEPART -> next(c.minute) to Moovit.TIME_DEPARTURE
            TripWidget.MODE_ARRIVE -> next(c.minute) to Moovit.TIME_ARRIVAL
            else -> System.currentTimeMillis() to Moovit.TIME_DEPARTURE
        }
        val plan = Moovit.planItineraries(
            s, from, to.lat to to.lon, at, type,
            routeTypes = routeTypesFor(Prefs.filters(ctx)), skipTaxi = true,
        )
        // Bus options only: transit plus walking to it, no bikes, taxis, scooters or walk-only routes.
        val ok = setOf(Moovit.LegKind.WALK, Moovit.LegKind.WAIT, Moovit.LegKind.RIDE)
        val list = plan.laidOut()
            .filter { t -> t.rides.isNotEmpty() && t.legs.all { it.kind in ok } }
            .sortedBy { if (type == Moovit.TIME_ARRIVAL) -it.arr else it.dep }
            .take(4)
        if (list.isEmpty()) return JSONArray()
        val r = Moovit.hydrate(s, list)
        val hm = SimpleDateFormat("HH:mm", Locale.US)
        val rows = JSONArray()
        for (t in list) {
            val first = t.rides.first()
            val wait = t.legs.getOrNull(t.legs.indexOf(first) - 1)?.takeIf { it.kind == Moovit.LegKind.WAIT }
            val live = r.departures(first, wait).firstOrNull { it.tripId == first.tripId }
            val board = live?.timeUtc ?: first.dep
            val lines = t.rides.joinToString(" › ") { l ->
                l.shortName.ifBlank { r.line(l.lineId)?.number.orEmpty() }.ifBlank { "?" }
            }
            val walk = t.legs.takeWhile { it !== first }.filter { it.kind == Moovit.LegKind.WALK }.sumOf { it.minutes }
            rows.put(JSONObject()
                .put("lines", lines)
                .put("times", hm.format(Date(board * 1000)) + " → " + hm.format(Date(t.arr * 1000)))
                .put("live", live?.live == true)
                .put("detail", listOfNotNull(
                    r.stopName(first.fromStop),
                    T("walk $walk min", "הליכה $walk דק׳").takeIf { walk > 0 },
                ).joinToString(" · ")))
        }
        return rows
    }

    private fun next(minute: Int): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, minute / 60); set(Calendar.MINUTE, minute % 60)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        if (timeInMillis < System.currentTimeMillis() - 60_000) add(Calendar.DAY_OF_YEAR, 1)
    }.timeInMillis

    // Freshest position available: the phone's own (needs "allow all the time" in the background),
    // else where Kav last saw you within the past few hours.
    @SuppressLint("MissingPermission")
    private fun here(ctx: Context): Pair<Double, Double>? {
        if (hasLocationPermission(ctx)) runCatching {
            val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            lm.getProviders(true).mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
                .maxByOrNull { it.time }
                ?.takeIf { System.currentTimeMillis() - it.time < 20 * 60_000 }
                ?.let { return it.latitude to it.longitude }
        }
        return TripWidget.savedHere(ctx)?.takeIf { System.currentTimeMillis() - it.third < 6 * 3_600_000L }
            ?.let { it.first to it.second }
    }
}
