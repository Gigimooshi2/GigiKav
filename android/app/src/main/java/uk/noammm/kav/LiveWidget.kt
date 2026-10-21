package uk.noammm.kav

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorPath
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject
import uk.noammm.kav.data.Moovit
import uk.noammm.kav.ui.Favourite
import uk.noammm.kav.ui.FavouriteIcons
import uk.noammm.kav.ui.Online
import uk.noammm.kav.ui.T
import uk.noammm.kav.ui.routeTypesFor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * "Kav live places": every saved place, and under each one the next bus there from where you
 * are, how soon it leaves its stop (live when Moovit has it) and the walk to that stop.
 * Tap a place for the full live search.
 */
class LivePlacesWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) = LiveWidgets.updated(ctx, ids)
    override fun onDeleted(ctx: Context, ids: IntArray) = LiveWidgets.deleted(ctx, ids)
    override fun onAppWidgetOptionsChanged(ctx: Context, mgr: AppWidgetManager, id: Int, options: Bundle) =
        LiveWidgets.render(ctx, id)
}

/** "Kav live place": the 1×1 version, one saved place, one tap to its live search. */
class LivePlaceWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) = LiveWidgets.updated(ctx, ids)
    override fun onDeleted(ctx: Context, ids: IntArray) = LiveWidgets.deleted(ctx, ids)
}

object LiveWidgets {
    private const val ACTION_REFRESH = "uk.noammm.kav.LIVE_WIDGET_REFRESH"
    internal const val ACTION_TURN = "uk.noammm.kav.LIVE_WIDGET_TURN"
    // Fetched in the last few minutes: count down in minutes. Older than that the countdown would lie, so show the clock time.
    private const val FRESH_MS = 3 * 60_000L

    internal fun store(ctx: Context) = ctx.getSharedPreferences("kav_live_widget", Context.MODE_PRIVATE)

    /** The 1×1 widget's place. */
    fun dest(ctx: Context, id: Int): String? = store(ctx).getString("dest_$id", null)
    fun setDest(ctx: Context, id: Int, favId: String) = store(ctx).edit().putString("dest_$id", favId).remove("data_$id").apply()

    /** How many upcoming buses a widget keeps per place, cycled with its arrows. */
    fun count(ctx: Context, id: Int): Int = store(ctx).getInt("count_$id", 3)
    fun setCount(ctx: Context, id: Int, n: Int) = store(ctx).edit().putInt("count_$id", n.coerceIn(1, 6)).putInt("page_$id", 0).apply()
    private fun page(ctx: Context, id: Int): Int = store(ctx).getInt("page_$id", 0)

    /** Arrow tapped: show the next (or previous) bus, wrapping round. No fetch, the times are already here. */
    fun turn(ctx: Context, id: Int, by: Int) {
        val n = count(ctx, id)
        store(ctx).edit().putInt("page_$id", ((page(ctx, id) + by) % n + n) % n).apply()
        render(ctx, id)
    }

    fun isSingle(ctx: Context, id: Int): Boolean = runCatching {
        AppWidgetManager.getInstance(ctx).getAppWidgetInfo(id)?.provider?.className == LivePlaceWidget::class.java.name
    }.getOrDefault(false)

    private fun ids(ctx: Context): IntArray = runCatching {
        val mgr = AppWidgetManager.getInstance(ctx)
        mgr.getAppWidgetIds(ComponentName(ctx, LivePlacesWidget::class.java)) +
            mgr.getAppWidgetIds(ComponentName(ctx, LivePlaceWidget::class.java))
    }.getOrDefault(IntArray(0))

    fun liveIds(ctx: Context): List<Int> = ids(ctx).toList()

    /** The places a widget follows, in your saved order. */
    fun places(ctx: Context, id: Int): List<Favourite> {
        val all = Prefs.favourites(ctx)
        return if (isSingle(ctx, id)) listOfNotNull(all.firstOrNull { it.id == dest(ctx, id) })
        else PlacesWidget.hidden(ctx, id).let { hide -> all.filter { it.id !in hide } }
    }

    fun updated(ctx: Context, ids: IntArray) {
        for (id in ids) { render(ctx, id); schedule(ctx, id); refreshNow(ctx, id) }
    }

    fun deleted(ctx: Context, ids: IntArray) {
        val e = store(ctx).edit()
        for (id in ids) {
            WorkManager.getInstance(ctx).cancelUniqueWork("livewidget-$id")
            e.remove("dest_$id").remove("data_$id").remove("count_$id").remove("page_$id")
        }
        e.apply()
    }

    /** Saved places changed, or a widget was set up: redraw at once, then fetch fresh times. */
    fun refreshAll(ctx: Context) {
        for (id in ids(ctx)) { render(ctx, id); refreshNow(ctx, id) }
    }

    private val net = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun schedule(ctx: Context, id: Int) {
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
            "livewidget-$id", ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<LiveWidgetWorker>(15, TimeUnit.MINUTES)
                .setConstraints(net).setInputData(workDataOf("id" to id)).build(),
        )
    }

    fun refreshNow(ctx: Context, id: Int) {
        WorkManager.getInstance(ctx).enqueueUniqueWork(
            "livewidget-now-$id", ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<LiveWidgetWorker>().setConstraints(net).setInputData(workDataOf("id" to id)).build(),
        )
    }

    private fun data(ctx: Context, id: Int): JSONObject? =
        store(ctx).getString("data_$id", null)?.let { runCatching { JSONObject(it) }.getOrNull() }

    /** What goes under a place: "🚌 72 · 4 min" and "🚶 6 min", or why there's nothing. */
    private class Line(val bus: String, val walk: String, val live: Boolean)

    private fun lineFor(entry: JSONObject?, at: Long, offline: Boolean, page: Int): Line? {
        if (entry == null) return null
        entry.optString("none").takeIf { it.isNotBlank() }?.let { return Line(it, "", false) }
        val opts = entry.optJSONArray("opts") ?: return null
        if (page >= opts.length()) return Line("—", T("no more buses", "אין עוד"), false)
        val o = opts.getJSONObject(page)
        val board = o.optLong("board", 0L).takeIf { it > 0 } ?: return null
        val now = System.currentTimeMillis()
        val fresh = !offline && now - at < FRESH_MS
        val mins = ((board * 1000 - now + 59_999) / 60_000).toInt()
        val time = when {
            fresh && mins <= 0 -> T("now", "עכשיו")
            fresh && mins <= 60 -> T("$mins min", "$mins דק׳")
            else -> SimpleDateFormat("HH:mm", Locale.US).format(Date(board * 1000))
        }
        val walk = o.optInt("walk", 0)
        return Line(
            "🚌 ${o.optString("line")} · $time",
            if (walk > 0) T("🚶 $walk min", "🚶 $walk דק׳") else T("🚶 at the stop", "🚶 בתחנה"),
            o.optBoolean("live") && fresh,
        )
    }

    private fun turnIntent(ctx: Context, id: Int, by: Int): PendingIntent = PendingIntent.getBroadcast(
        ctx, 14000 + id * 2 + (if (by > 0) 1 else 0),
        Intent(ctx, LiveWidgetRefresh::class.java).setAction(ACTION_TURN).putExtra("id", id).putExtra("by", by),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun render(ctx: Context, id: Int) {
        T.lang = Prefs.lang(ctx)
        val mgr = AppWidgetManager.getInstance(ctx) ?: return
        val single = isSingle(ctx, id)
        val places = places(ctx, id)
        val d = data(ctx, id)
        val at = d?.optLong("at", 0L) ?: 0L
        val offline = d?.optBoolean("offline") == true
        val error = d?.optString("error")?.takeIf { it.isNotBlank() }
        val byPlace = d?.optJSONObject("places")
        val n = count(ctx, id)
        val page = page(ctx, id).coerceIn(0, n - 1)
        val density = ctx.resources.displayMetrics.density
        fun icon(f: Favourite, dp: Int) =
            iconBitmap(FavouriteIcons.firstOrNull { it.first == f.icon }?.second ?: Icons.Rounded.Place, (dp * density).toInt())

        if (single) {
            val v = RemoteViews(ctx.packageName, R.layout.widget_live_single)
            val f = places.firstOrNull()
            if (f == null) {
                v.setImageViewBitmap(R.id.live_icon, iconBitmap(Icons.Rounded.Edit, (22 * density).toInt()))
                v.setTextViewText(R.id.live_bus, T("Pick", "בחירה"))
                v.setTextViewText(R.id.live_walk, T("a place", "מקום"))
                v.setOnClickPendingIntent(R.id.live_root, configure(ctx, id))
            } else {
                v.setImageViewBitmap(R.id.live_icon, icon(f, 22))
                val line = lineFor(byPlace?.optJSONObject(f.id), at, offline, page)
                v.setTextViewText(R.id.live_bus, line?.bus?.removePrefix("🚌 ") ?: error?.let { "—" } ?: "…")
                v.setTextColor(R.id.live_bus, if (line?.live == true) LIVE else Color.WHITE)
                v.setTextViewText(R.id.live_walk, line?.walk?.takeIf { it.isNotBlank() } ?: f.name)
                v.setOnClickPendingIntent(R.id.live_root, f.place?.let { directions(ctx, it, id) } ?: setPlace(ctx, f.id))
            }
            arrows(v, ctx, id, n, page)
            mgr.updateAppWidget(id, v)
            return
        }

        val root = RemoteViews(ctx.packageName, R.layout.widget_live_places)
        root.removeAllViews(R.id.live_row)
        root.setImageViewBitmap(R.id.live_edit, iconBitmap(Icons.Rounded.Edit, (18 * density).toInt()))
        root.setOnClickPendingIntent(R.id.live_edit, configure(ctx, id))
        root.setImageViewBitmap(R.id.live_refresh, iconBitmap(Icons.Rounded.Refresh, (18 * density).toInt()))
        root.setOnClickPendingIntent(R.id.live_refresh, PendingIntent.getBroadcast(
            ctx, 11000 + id, Intent(ctx, LiveWidgetRefresh::class.java).setAction(ACTION_REFRESH).putExtra("id", id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        ))
        val note = when {
            places.isEmpty() -> T("Tap ✏️ to choose places", "הקישו ✏️ לבחירת מקומות")
            error != null && byPlace == null -> error
            else -> null
        }
        root.setViewVisibility(R.id.live_note, if (note != null) View.VISIBLE else View.GONE)
        note?.let { root.setTextViewText(R.id.live_note, it) }
        if (places.isNotEmpty()) for (f in places) {
            val item = RemoteViews(ctx.packageName, R.layout.widget_live_place)
            item.setTextViewText(R.id.live_label, f.name)
            item.setImageViewBitmap(R.id.live_icon, icon(f, 22))
            val p = f.place
            if (p == null) {
                item.setInt(R.id.live_icon, "setBackgroundResource", R.drawable.widget_icon_bg_unset)
                item.setTextViewText(R.id.live_bus, T("tap to set", "הקישו להגדרה"))
                item.setTextViewText(R.id.live_walk, "")
                item.setOnClickPendingIntent(R.id.live_item, setPlace(ctx, f.id))
            } else {
                val line = lineFor(byPlace?.optJSONObject(f.id), at, offline, page)
                item.setTextViewText(R.id.live_bus, line?.bus ?: if (d == null) T("loading…", "טוען…") else "—")
                item.setTextColor(R.id.live_bus, if (line?.live == true) LIVE else Color.WHITE)
                item.setTextViewText(R.id.live_walk, line?.walk.orEmpty())
                item.setOnClickPendingIntent(R.id.live_item, directions(ctx, p, id * 64 + places.indexOf(f)))
            }
            root.addView(R.id.live_row, item)
        }
        arrows(root, ctx, id, n, page)
        mgr.updateAppWidget(id, root)
    }

    private const val LIVE = 0xFF7FD69A.toInt()

    /** ‹ 2/3 › under the times; hidden when the widget only keeps one bus. */
    private fun arrows(v: RemoteViews, ctx: Context, id: Int, n: Int, page: Int) {
        val on = n > 1
        for (view in listOf(R.id.live_prev, R.id.live_next, R.id.live_page)) v.setViewVisibility(view, if (on) View.VISIBLE else View.GONE)
        if (!on) return
        // Arrows point the way the pages run: mirrored in Hebrew.
        v.setTextViewText(R.id.live_prev, if (T.rtl) "›" else "‹")
        v.setTextViewText(R.id.live_next, if (T.rtl) "‹" else "›")
        v.setTextViewText(R.id.live_page, "${page + 1}/$n")
        v.setOnClickPendingIntent(R.id.live_prev, turnIntent(ctx, id, -1))
        v.setOnClickPendingIntent(R.id.live_next, turnIntent(ctx, id, 1))
    }

    private fun configure(ctx: Context, id: Int): PendingIntent = PendingIntent.getActivity(
        ctx, 12000 + id,
        Intent(ctx, WidgetConfigActivity::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun setPlace(ctx: Context, favId: String): PendingIntent = PendingIntent.getActivity(
        ctx, 2000 + (favId.hashCode() and 0xFFFF),
        Intent(ctx, MainActivity::class.java).putExtra(PlacesWidget.EXTRA_SET, favId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** Kav's own directions link with no origin: plans from where you are, live, at once. */
    private fun directions(ctx: Context, p: Moovit.Place, code: Int): PendingIntent {
        val uri = Uri.Builder().scheme("moovit").authority("directions")
            .appendQueryParameter("dest_lat", p.lat.toString())
            .appendQueryParameter("dest_lon", p.lon.toString())
            .appendQueryParameter("dest_name", p.name)
            .build()
        return PendingIntent.getActivity(
            ctx, 13000 + code,
            Intent(Intent.ACTION_VIEW, uri).setPackage(ctx.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** Draws a Material ImageVector into a bitmap, since widgets can't host Compose. */
    private fun iconBitmap(v: ImageVector, px: Int): Bitmap {
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.scale(px / v.viewportWidth, px / v.viewportHeight)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        fun draw(g: VectorGroup) {
            for (n in g) {
                if (n is VectorPath) {
                    val path = PathParser().addPathNodes(n.pathData).toPath().asAndroidPath()
                    path.fillType = if (n.pathFillType == PathFillType.EvenOdd)
                        android.graphics.Path.FillType.EVEN_ODD else android.graphics.Path.FillType.WINDING
                    c.drawPath(path, paint)
                } else if (n is VectorGroup) draw(n)
            }
        }
        draw(v.root)
        return bmp
    }
}

class LiveWidgetRefresh : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getIntExtra("id", -1).takeIf { it >= 0 } ?: return
        if (intent.action == LiveWidgets.ACTION_TURN) LiveWidgets.turn(ctx, id, intent.getIntExtra("by", 1))
        else LiveWidgets.refreshNow(ctx, id)
    }
}

class LiveWidgetWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getInt("id", -1).takeIf { it >= 0 } ?: return Result.success()
        return if (run(applicationContext, id, runAttemptCount)) Result.success() else Result.retry()
    }

    companion object {
        /** Refresh one widget now. false = offline and worth retrying. Used by the worker and by the open app. */
        suspend fun run(ctx: Context, id: Int, runAttemptCount: Int = 99): Boolean {
            val store = LiveWidgets.store(ctx)
            val prev = store.getString("data_$id", null)?.let { runCatching { JSONObject(it) }.getOrNull() }
            val out = try {
                JSONObject().put("at", System.currentTimeMillis()).put("places", fetch(ctx, id))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: java.io.IOException) {
                // No internet: keep the last times (shown as clock times, not a countdown) and retry later.
                android.util.Log.w("KavLiveWidget", "offline", e)
                val o = prev?.takeIf { it.has("places") }?.put("offline", true)
                    ?: JSONObject().put("at", 0L).put("error", T("No internet right now", "אין אינטרנט כרגע"))
                store.edit().putString("data_$id", o.toString()).apply()
                LiveWidgets.render(ctx, id)
                return runAttemptCount >= 4
            } catch (e: Exception) {
                android.util.Log.w("KavLiveWidget", "refresh failed", e)
                JSONObject().put("at", System.currentTimeMillis())
                    .put("error", e.message ?: T("Couldn't reach Moovit", "לא ניתן להגיע ל-Moovit"))
            }
            store.edit().putString("data_$id", out.toString()).apply()
            LiveWidgets.render(ctx, id)
            return true
        }

        private suspend fun fetch(ctx: Context, id: Int): JSONObject {
            val places = LiveWidgets.places(ctx, id).filter { it.place != null }.take(8)
            val out = JSONObject()
            if (places.isEmpty()) return out
            val from = TripWidgetWorker.here(ctx) ?: throw IllegalStateException(
                T("No location yet: open Kav once", "אין מיקום: פתחו את Kav פעם אחת"),
            )
            T.lang = Prefs.lang(ctx)
            Moovit.shareLocation = !Prefs.privateSearch(ctx)
            if (Online.session == null) Online.init(ctx)
            val s = Online.open(from)
            val types = routeTypesFor(Prefs.filters(ctx))
            val found = coroutineScope {
                places.map { f -> async { f.id to runCatching { nextBuses(s, from, f.place!!, types, LiveWidgets.count(ctx, id)) } } }.awaitAll()
            }
            // Only offline if every place failed for lack of a connection; one bad answer shouldn't blank the rest.
            found.mapNotNull { it.second.exceptionOrNull() as? java.io.IOException }
                .takeIf { it.size == found.size }?.firstOrNull()?.let { throw it }
            for ((favId, r) in found) {
                val entry = r.getOrNull()
                    ?: if (r.exceptionOrNull() is java.io.IOException) null else JSONObject().put("none", "—")
                entry?.let { out.put(favId, it) }
            }
            return out
        }

        /**
         * The next [n] buses there you can still make (transit plus walking, nothing else): every
         * departure of each route's first bus, after the walk to its stop, soonest first.
         */
        private fun nextBuses(
            s: uk.noammm.kav.data.MoovitSession, from: Pair<Double, Double>, to: Moovit.Place, types: List<Int>, n: Int,
        ): JSONObject {
            if (uk.noammm.kav.ui.metres(from.first, from.second, to.lat, to.lon) < 150) {
                return JSONObject().put("none", T("you're here", "אתם כאן"))
            }
            val plan = Moovit.planItineraries(
                s, from, to.lat to to.lon, System.currentTimeMillis(), Moovit.TIME_DEPARTURE,
                routeTypes = types, skipTaxi = true,
            )
            val ok = setOf(Moovit.LegKind.WALK, Moovit.LegKind.WAIT, Moovit.LegKind.RIDE)
            val options = plan.laidOut().filter { t -> t.rides.isNotEmpty() && t.legs.all { it.kind in ok } }.take(5)
            if (options.isEmpty()) return JSONObject().put("none", T("no bus now", "אין אוטובוס"))
            val r = Moovit.hydrate(s, options)
            val now = System.currentTimeMillis() / 1000
            class Bus(val line: String, val board: Long, val live: Boolean, val walk: Int, val trip: Long)
            val buses = options.flatMap { t ->
                val first = t.rides.first()
                val wait = t.legs.getOrNull(t.legs.indexOf(first) - 1)?.takeIf { it.kind == Moovit.LegKind.WAIT }
                val line = t.rides.joinToString(" › ") { l -> l.shortName.ifBlank { r.line(l.lineId)?.number.orEmpty() }.ifBlank { "?" } }
                val walk = walkMinutes(t)
                r.departures(first, wait).ifEmpty { listOf(Moovit.Departure(first.tripId, first.dep)) }
                    .filter { it.state != Moovit.TimeState.CANCELED }
                    .map { Bus(line, it.timeUtc, it.live, walk, it.tripId) }
                    .filter { it.board >= now + walk * 60 - 60 }
            }.distinctBy { if (it.trip != 0L) "${it.line}|${it.trip}" else "${it.line}|${it.board}" }
                .sortedBy { it.board }.take(n)
            if (buses.isEmpty()) return JSONObject().put("none", T("no bus now", "אין אוטובוס"))
            val opts = org.json.JSONArray()
            for (b in buses) opts.put(JSONObject().put("line", b.line).put("board", b.board).put("live", b.live).put("walk", b.walk))
            return JSONObject().put("opts", opts)
        }

        private fun walkMinutes(t: Moovit.Itinerary): Int {
            val first = t.rides.first()
            return t.legs.takeWhile { it !== first }.filter { it.kind == Moovit.LegKind.WALK }.sumOf { it.minutes }
        }
    }
}
