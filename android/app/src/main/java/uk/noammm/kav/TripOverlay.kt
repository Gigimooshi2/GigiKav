package uk.noammm.kav

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import uk.noammm.kav.ui.K
import uk.noammm.kav.ui.KavTheme
import uk.noammm.kav.ui.PipOverlay
import uk.noammm.kav.ui.T

/**
 * Beta: the current trip step in a window drawn over other apps, instead of picture-in-picture.
 * Android keeps one PiP window at a time, so a video going to PiP pushes Kav's out (or the other
 * way round). An overlay window doesn't compete with PiP, so both can float together.
 */
object TripOverlay {
    @Volatile var model: KavModel? = null

    private var root: View? = null
    private var owner: OverlayOwner? = null
    // Where you last dragged it, kept for the session.
    private var savedX = -1
    private var savedY = -1

    fun permitted(ctx: Context): Boolean = Settings.canDrawOverlays(ctx)

    /** The toggle is on and Android lets us draw over other apps. */
    fun active(ctx: Context): Boolean = Prefs.floatingWindow(ctx) && permitted(ctx)

    fun askPermission(ctx: Context) {
        runCatching {
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + ctx.packageName))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    fun show(ctx: Context) {
        if (root != null) return
        val model = model ?: return
        if (model.activeJourney == null || !active(ctx)) return
        val app = ctx.applicationContext
        val wm = app.getSystemService(WindowManager::class.java) ?: return
        val dm = app.resources.displayMetrics
        val d = dm.density
        val w = minOf((340 * d).toInt(), dm.widthPixels - (24 * d).toInt())
        val h = (140 * d).toInt()
        val params = WindowManager.LayoutParams(
            w, h,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = if (savedX >= 0) savedX else dm.widthPixels - w - (12 * d).toInt()
            y = if (savedY >= 0) savedY else (96 * d).toInt()
            x = x.coerceIn(0, maxOf(0, dm.widthPixels - w))
            y = y.coerceIn(0, maxOf(0, dm.heightPixels - h))
        }

        val life = OverlayOwner()
        val frame = DragFrame(
            app, wm, params,
            onTap = { openApp(app) },
            onMoved = { nx, ny -> savedX = nx; savedY = ny },
        )
        frame.setViewTreeLifecycleOwner(life)
        frame.setViewTreeViewModelStoreOwner(life)
        frame.setViewTreeSavedStateRegistryOwner(life)

        val compose = ComposeView(app).apply {
            setContent {
                KavTheme {
                    CompositionLocalProvider(
                        LocalLayoutDirection provides if (T.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                    ) {
                        val shape = RoundedCornerShape(18.dp)
                        Column(
                            Modifier.fillMaxSize().clip(shape).background(K.bg)
                                .border(1.dp, K.dim.copy(alpha = .3f), shape),
                        ) {
                            // Grab handle: the strip also leaves room for the close button.
                            Box(Modifier.fillMaxWidth().height(24.dp), contentAlignment = Alignment.Center) {
                                Box(
                                    Modifier.width(36.dp).height(4.dp).clip(RoundedCornerShape(999.dp))
                                        .background(K.dim.copy(alpha = .5f)),
                                )
                            }
                            Box(Modifier.fillMaxWidth().weight(1f)) { PipOverlay(model) }
                        }
                        // Trip ended while you were away: show "Trip ended" briefly, then go.
                        LaunchedEffect(Unit) {
                            snapshotFlow { model.activeJourney == null }.collectLatest { ended ->
                                if (ended) {
                                    delay(4_000)
                                    Handler(Looper.getMainLooper()).post { hide() }
                                }
                            }
                        }
                    }
                }
            }
        }
        frame.addView(compose, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        val close = TextView(app).apply {
            text = "✕"
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(K.dim.toArgb())
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(K.surface1.toArgb())
            }
            contentDescription = T("Close", "סגירה")
            setOnClickListener { hide() }
        }
        frame.addView(
            close,
            FrameLayout.LayoutParams((22 * d).toInt(), (22 * d).toInt(), Gravity.TOP or Gravity.RIGHT).apply {
                topMargin = (3 * d).toInt(); rightMargin = (8 * d).toInt()
            },
        )
        frame.close = close

        life.start()
        try {
            wm.addView(frame, params)
        } catch (e: Exception) {
            android.util.Log.w("KavOverlay", "could not add overlay", e)
            life.destroy()
            return
        }
        root = frame
        owner = life
    }

    fun hide() {
        val v = root ?: return
        root = null
        runCatching { v.context.getSystemService(WindowManager::class.java)?.removeView(v) }
        owner?.destroy()
        owner = null
    }

    private fun openApp(ctx: Context) {
        runCatching {
            ctx.startActivity(
                Intent(ctx, MainActivity::class.java)
                    .setAction(PendingLink.ACTION_OPEN_TRIP)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            )
        }
    }
}

/** Drag anywhere to move, tap to open Kav. Raw screen coordinates, so moving the window doesn't feed back into the drag. */
@SuppressLint("ViewConstructor")
private class DragFrame(
    ctx: Context,
    private val wm: WindowManager,
    private val params: WindowManager.LayoutParams,
    private val onTap: () -> Unit,
    private val onMoved: (Int, Int) -> Unit,
) : FrameLayout(ctx) {
    var close: View? = null
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var startX = 0
    private var startY = 0
    private var dragging = false
    private val hit = Rect()

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked != MotionEvent.ACTION_DOWN) return false
        close?.let { c ->
            c.getHitRect(hit)
            hit.inset(-hit.width() / 3, -hit.height() / 3)
            if (hit.contains(e.x.toInt(), e.y.toInt())) return false
        }
        return true
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.rawX; downY = e.rawY
                startX = params.x; startY = params.y
                dragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.rawX - downX
                val dy = e.rawY - downY
                if (!dragging && dx * dx + dy * dy > slop * slop) dragging = true
                if (dragging) {
                    val dm = resources.displayMetrics
                    params.x = (startX + dx).toInt().coerceIn(0, maxOf(0, dm.widthPixels - params.width))
                    params.y = (startY + dy).toInt().coerceIn(0, maxOf(0, dm.heightPixels - params.height))
                    runCatching { wm.updateViewLayout(this, params) }
                }
            }
            MotionEvent.ACTION_UP -> if (dragging) onMoved(params.x, params.y) else { performClick(); onTap() }
            MotionEvent.ACTION_CANCEL -> if (dragging) onMoved(params.x, params.y)
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()
}

/** The overlay's own lifecycle: always resumed while shown, so Compose keeps drawing while the activity is stopped. */
private class OverlayOwner : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val saved = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()

    override val lifecycle: Lifecycle get() = registry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry

    fun start() {
        saved.performRestore(null)
        registry.currentState = Lifecycle.State.RESUMED
    }

    fun destroy() {
        if (registry.currentState == Lifecycle.State.INITIALIZED) return
        registry.currentState = Lifecycle.State.DESTROYED
        store.clear()
    }
}
