package uk.noammm.kav

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.RemoteViews
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import uk.noammm.kav.data.JourneyFile
import uk.noammm.kav.ui.T
import uk.noammm.kav.ui.buildSteps
import uk.noammm.kav.ui.journeyProgress
import uk.noammm.kav.ui.stepInstruction

class TripService : Service() {

    private var pushedTitle: String? = null
    private var pushedText: String? = null
    private var pushedStep = -1
    private var pushedAt = 0L

    private var offset = 0

    private var cached: Pair<ActiveJourney, Int>? = null
    private var cachedAt = -1L

    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            if (System.currentTimeMillis() - pushedAt > 45_000) {
                val (journey, step) = journey() ?: run { finish(); return }
                val steps = buildSteps(journey.trip, journey.fromLabel, journey.toLabel)
                val moved = journeyProgress(
                    steps, step, journey.resolved, journey.chosen,
                    System.currentTimeMillis() / 1000, null,
                )
                if (moved != step) {
                    JourneyFile.save(this@TripService, journey, moved)
                    cached = journey to moved
                    cachedAt = JourneyFile.mtime(this@TripService)
                    offset = 0
                }
                post()
            }
            handler.postDelayed(this, 30_000)
        }
    }

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        T.lang = Prefs.lang(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACT_PREV -> page(-1)
            ACT_NEXT -> page(+1)
            ACT_END -> { end(); return START_NOT_STICKY }
            else -> if (intent?.hasExtra(TITLE) == true) {
                pushedTitle = intent.getStringExtra(TITLE)
                pushedText = intent.getStringExtra(TEXT)
                val step = intent.getIntExtra(STEP, -1)
                if (step != pushedStep) { pushedStep = step; offset = 0 }
                pushedAt = System.currentTimeMillis()
            }
        }
        if (pushedTitle == null && journey() == null) { finish(); return START_NOT_STICKY }
        post()
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, 30_000)
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun journey(): Pair<ActiveJourney, Int>? {
        val at = JourneyFile.mtime(this)
        if (at != cachedAt) { cached = JourneyFile.load(this); cachedAt = at }
        return cached
    }

    private fun page(d: Int) {
        val (journey, saved) = journey() ?: return
        val steps = buildSteps(journey.trip, journey.fromLabel, journey.toLabel)
        if (steps.isEmpty()) return
        val current = (if (pushedStep >= 0) pushedStep else saved).coerceIn(0, steps.lastIndex)
        offset = (current + offset + d).coerceIn(0, steps.lastIndex) - current
    }

    private fun end() {
        val shell = TripBridge.end
        if (shell != null) shell() else { JourneyFile.clear(this); finish() }
    }

    private fun finish() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun content(): Pair<String, String> {
        val fallback = (pushedTitle ?: getString(R.string.app_name)) to pushedText.orEmpty()
        if (offset == 0 && pushedTitle != null && System.currentTimeMillis() - pushedAt < 90_000) return fallback
        val (journey, saved) = journey() ?: return fallback
        val steps = buildSteps(journey.trip, journey.fromLabel, journey.toLabel)
        if (steps.isEmpty()) return fallback
        val current = (if (pushedStep >= 0) pushedStep else saved).coerceIn(0, steps.lastIndex)
        val shown = (current + offset).coerceIn(0, steps.lastIndex)
        return stepInstruction(steps[shown], journey, shown == steps.lastIndex - 1, System.currentTimeMillis() / 1000)
    }

    private fun post() {
        val (title, text) = content()
        ServiceCompat.startForeground(
            this, ID, build(this, title, text),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
    }

    companion object {
        private const val ID = 7
        private const val ALERT_ID = 8
        private const val CHANNEL = "navigation"
        private const val TITLE = "title"
        private const val TEXT = "text"
        private const val STEP = "step"
        private const val ACT_PREV = "uk.noammm.kav.step.PREV"
        private const val ACT_NEXT = "uk.noammm.kav.step.NEXT"
        private const val ACT_END = "uk.noammm.kav.trip.END"
        private const val ACT_REPOST = "uk.noammm.kav.trip.REPOST"

        fun show(ctx: Context, title: String, text: String, step: Int) {
            if (!NotificationManagerCompat.from(ctx).areNotificationsEnabled()) return
            val intent = Intent(ctx, TripService::class.java)
                .putExtra(TITLE, title).putExtra(TEXT, text).putExtra(STEP, step)
            runCatching { ctx.startForegroundService(intent) }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, TripService::class.java))
        }

        fun alert(ctx: Context, title: String, text: String) {
            if (!NotificationManagerCompat.from(ctx).areNotificationsEnabled()) return
            ensureChannel(ctx)
            val n = NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_trip_notice)
                .setContentTitle(title)
                .setContentText(text)
                .setColor(Prefs.accent(ctx))
                .setContentIntent(openApp(ctx))
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .build()
            runCatching { NotificationManagerCompat.from(ctx).notify(ALERT_ID, n) }
        }

        private fun ensureChannel(ctx: Context) {
            val nm = NotificationManagerCompat.from(ctx)
            nm.deleteNotificationChannel("trip")
            nm.createNotificationChannel(
                NotificationChannelCompat.Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_HIGH)
                    .setName(T("Live navigation", "ניווט חי"))
                    .setDescription(T(
                        "Step-by-step guidance while a trip is on",
                        "הנחיה צעד־אחר־צעד בזמן נסיעה",
                    ))
                    .build(),
            )
        }

        private fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        private fun act(ctx: Context, action: String): PendingIntent = PendingIntent.getService(
            ctx, action.hashCode(), Intent(ctx, TripService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        private fun build(ctx: Context, title: String, text: String): Notification {
            ensureChannel(ctx)
            val accent = Prefs.accent(ctx)
            fun rv(layout: Int) = RemoteViews(ctx.packageName, layout).apply {
                setTextViewText(R.id.notice_title, title)
                setTextViewText(R.id.notice_text, text)
                setInt(R.id.notice_prev, "setColorFilter", accent)
                setInt(R.id.notice_next, "setColorFilter", accent)
                setOnClickPendingIntent(R.id.notice_prev, act(ctx, ACT_PREV))
                setOnClickPendingIntent(R.id.notice_next, act(ctx, ACT_NEXT))
            }
            val expanded = rv(R.layout.notification_trip_expanded).apply {
                setTextViewText(R.id.notice_end, T("End trip", "סיום נסיעה"))
                setTextColor(R.id.notice_end, accent)
                setOnClickPendingIntent(R.id.notice_end, act(ctx, ACT_END))
            }
            return NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_trip_notice)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.DecoratedCustomViewStyle())
                .setCustomContentView(rv(R.layout.notification_trip_collapsed))
                .setCustomBigContentView(expanded)
                .setContentIntent(openApp(ctx))
                .setDeleteIntent(act(ctx, ACT_REPOST))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setColor(accent)
                .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build()
        }
    }
}

object TripBridge {
    @Volatile var end: (() -> Unit)? = null
}
