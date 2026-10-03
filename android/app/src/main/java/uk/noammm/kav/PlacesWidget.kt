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

    override fun onAppWidgetOptionsChanged(ctx: Context, mgr: AppWidgetManager, id: Int, options: Bundle) =
        render(ctx, mgr, intArrayOf(id))

    companion object {
        fun refresh(ctx: Context) {
            runCatching {
                val mgr = AppWidgetManager.getInstance(ctx) ?: return
                val ids = mgr.getAppWidgetIds(ComponentName(ctx, PlacesWidget::class.java))
                if (ids.isNotEmpty()) render(ctx, mgr, ids)
            }
        }

        private fun render(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
            val places = Prefs.favourites(ctx).filter { it.place != null }
            val iconPx = (26 * ctx.resources.displayMetrics.density).toInt()
            val bitmaps = places.associate { f ->
                f.id to iconBitmap(FavouriteIcons.firstOrNull { it.first == f.icon }?.second ?: Icons.Rounded.Place, iconPx)
            }
            for (id in ids) {
                val root = RemoteViews(ctx.packageName, R.layout.widget_places)
                root.removeAllViews(R.id.widget_row)
                if (places.isEmpty()) {
                    root.setViewVisibility(R.id.widget_empty, View.VISIBLE)
                    root.setOnClickPendingIntent(R.id.widget_empty, openApp(ctx))
                } else {
                    root.setViewVisibility(R.id.widget_empty, View.GONE)
                    places.forEachIndexed { i, f ->
                        val item = RemoteViews(ctx.packageName, R.layout.widget_place)
                        item.setTextViewText(R.id.widget_label, f.name)
                        bitmaps[f.id]?.let { item.setImageViewBitmap(R.id.widget_icon, it) }
                        item.setOnClickPendingIntent(R.id.widget_item, directions(ctx, f.place!!, i))
                        root.addView(R.id.widget_row, item)
                    }
                }
                mgr.updateAppWidget(id, root)
            }
        }

        private fun directions(ctx: Context, p: Moovit.Place, i: Int): PendingIntent {
            // Same link Kav already opens for shared trips; no origin means "from where I am".
            val uri = Uri.Builder().scheme("moovit").authority("directions")
                .appendQueryParameter("dest_lat", p.lat.toString())
                .appendQueryParameter("dest_lon", p.lon.toString())
                .appendQueryParameter("dest_name", p.name)
                .build()
            val intent = Intent(Intent.ACTION_VIEW, uri).setPackage(ctx.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return PendingIntent.getActivity(
                ctx, 1000 + i, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
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
