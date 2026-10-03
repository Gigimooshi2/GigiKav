package uk.noammm.kav

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
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
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorPath
import uk.noammm.kav.data.Moovit
import uk.noammm.kav.ui.FavouriteIcons

/** Home-screen widget: one button per saved place, each opening directions there from where you are. */
class PlacesWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) = render(ctx, mgr, ids)

    override fun onDeleted(ctx: Context, ids: IntArray) {
        val e = store(ctx).edit()
        ids.forEach { e.remove("hidden_$it") }
        e.apply()
    }

    override fun onAppWidgetOptionsChanged(ctx: Context, mgr: AppWidgetManager, id: Int, options: Bundle) =
        render(ctx, mgr, intArrayOf(id))

    companion object {
        const val EXTRA_SET = "uk.noammm.kav.SET_FAVOURITE"

        private fun store(ctx: Context) = ctx.getSharedPreferences("kav_widget", Context.MODE_PRIVATE)

        /** Places hidden on this widget. Stored as hidden (not shown) so new saved places appear by default. */
        fun hidden(ctx: Context, widgetId: Int): Set<String> =
            store(ctx).getStringSet("hidden_$widgetId", emptySet())?.toSet() ?: emptySet()

        fun setHidden(ctx: Context, widgetId: Int, ids: Set<String>) {
            store(ctx).edit().putStringSet("hidden_$widgetId", ids).apply()
        }

        fun refresh(ctx: Context) {
            runCatching {
                val mgr = AppWidgetManager.getInstance(ctx) ?: return
                val ids = mgr.getAppWidgetIds(ComponentName(ctx, PlacesWidget::class.java))
                if (ids.isNotEmpty()) render(ctx, mgr, ids)
            }
        }

        private fun render(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
            val all = Prefs.favourites(ctx)
            val iconPx = (26 * ctx.resources.displayMetrics.density).toInt()
            val bitmaps = all.associate { f ->
                f.id to iconBitmap(FavouriteIcons.firstOrNull { it.first == f.icon }?.second ?: Icons.Rounded.Place, iconPx)
            }
            val editIcon = iconBitmap(Icons.Rounded.Edit, (18 * ctx.resources.displayMetrics.density).toInt())
            for (id in ids) {
                val hide = hidden(ctx, id)
                val places = all.filter { it.id !in hide }
                val root = RemoteViews(ctx.packageName, R.layout.widget_places)
                root.removeAllViews(R.id.widget_row)
                root.setImageViewBitmap(R.id.widget_edit, editIcon)
                root.setOnClickPendingIntent(R.id.widget_edit, configure(ctx, id))
                if (places.isEmpty()) {
                    root.setViewVisibility(R.id.widget_empty, View.VISIBLE)
                    root.setOnClickPendingIntent(R.id.widget_empty, configure(ctx, id))
                } else {
                    root.setViewVisibility(R.id.widget_empty, View.GONE)
                    places.forEach { f ->
                        val item = RemoteViews(ctx.packageName, R.layout.widget_place)
                        item.setTextViewText(R.id.widget_label, f.name)
                        bitmaps[f.id]?.let { item.setImageViewBitmap(R.id.widget_icon, it) }
                        val p = f.place
                        if (p != null) {
                            item.setOnClickPendingIntent(R.id.widget_item, directions(ctx, p, f.id))
                        } else {
                            // Not set yet (e.g. Home): tap to set it in the app.
                            item.setInt(R.id.widget_icon, "setBackgroundResource", R.drawable.widget_icon_bg_unset)
                            item.setTextColor(R.id.widget_label, 0x99FFFFFF.toInt())
                            item.setOnClickPendingIntent(R.id.widget_item, setPlace(ctx, f.id))
                        }
                        root.addView(R.id.widget_row, item)
                    }
                }
                mgr.updateAppWidget(id, root)
            }
        }

        private fun configure(ctx: Context, widgetId: Int): PendingIntent = PendingIntent.getActivity(
            ctx, 5000 + widgetId,
            Intent(ctx, WidgetConfigActivity::class.java)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        private fun setPlace(ctx: Context, favId: String): PendingIntent = PendingIntent.getActivity(
            ctx, 2000 + (favId.hashCode() and 0xFFFF),
            Intent(ctx, MainActivity::class.java).putExtra(EXTRA_SET, favId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        private fun directions(ctx: Context, p: Moovit.Place, favId: String): PendingIntent {
            // Same link Kav already opens for shared trips; no origin means "from where I am".
            val uri = Uri.Builder().scheme("moovit").authority("directions")
                .appendQueryParameter("dest_lat", p.lat.toString())
                .appendQueryParameter("dest_lon", p.lon.toString())
                .appendQueryParameter("dest_name", p.name)
                .build()
            val intent = Intent(Intent.ACTION_VIEW, uri).setPackage(ctx.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return PendingIntent.getActivity(
                ctx, 1000 + (favId.hashCode() and 0xFFFF), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        private fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
            ctx, 999,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

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
}
