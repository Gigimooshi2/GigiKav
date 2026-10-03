package uk.noammm.kav

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uk.noammm.kav.data.Updates
import uk.noammm.kav.ui.T
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Background updates: check GitHub every few hours, download quietly, and install only while
 * Kav is closed and no trip is running, so opening the app is never blocked.
 * Android 12+ lets an app update itself with no prompt once it is the app's installer of record;
 * the very first time it may still ask once, via a notification.
 */
object AutoUpdate {
    @Volatile var visible = false
    @Volatile var tripRunning = false

    private const val PERIODIC = "kav-auto-update"
    private const val SOON = "kav-auto-update-soon"
    private const val CHANNEL = "updates"
    private const val NOTIFY_ID = 4801

    private val net = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun schedule(ctx: Context) {
        val wm = WorkManager.getInstance(ctx)
        if (!Prefs.autoUpdate(ctx)) { wm.cancelUniqueWork(PERIODIC); wm.cancelUniqueWork(SOON); return }
        wm.enqueueUniquePeriodicWork(
            PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<UpdateWorker>(4, TimeUnit.HOURS).setConstraints(net).build(),
        )
    }

    /** App just went to the background: try shortly, if it stays closed. */
    fun soon(ctx: Context) {
        if (!Prefs.autoUpdate(ctx)) return
        WorkManager.getInstance(ctx).enqueueUniqueWork(
            SOON, ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<UpdateWorker>().setInitialDelay(90, TimeUnit.SECONDS).setConstraints(net).build(),
        )
    }

    fun apkFor(ctx: Context, version: String) = File(File(ctx.cacheDir, "updates"), "kav-$version.apk")

    /** Session install. silent: ask Android not to prompt (works once Kav installed itself before). */
    fun install(ctx: Context, file: File, silent: Boolean) {
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(ctx.packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(
                    if (silent) PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED
                    else PackageInstaller.SessionParams.USER_ACTION_UNSPECIFIED,
                )
            }
            if (Build.VERSION.SDK_INT >= 34) setRequestUpdateOwnership(true)
        }
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            s.openWrite("base.apk", 0, file.length()).use { out ->
                file.inputStream().use { it.copyTo(out) }
                s.fsync(out)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
            val result = PendingIntent.getBroadcast(ctx, id, Intent(ctx, UpdateResultReceiver::class.java), flags)
            s.commit(result.intentSender)
        }
    }

    @android.annotation.SuppressLint("MissingPermission")
    internal fun askToConfirm(ctx: Context, confirm: Intent) {
        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (visible) { runCatching { ctx.startActivity(confirm) }; return }
        val nm = NotificationManagerCompat.from(ctx)
        nm.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                .setName(T("App updates", "עדכוני אפליקציה")).build(),
        )
        val tap = PendingIntent.getActivity(
            ctx, NOTIFY_ID, confirm, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_trip_notice)
            .setContentTitle(T("Kav update ready", "עדכון ל-Kav מוכן"))
            .setContentText(T("Tap to install. Later updates install on their own.", "הקישו להתקנה. העדכונים הבאים יותקנו לבד."))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(NOTIFY_ID, n) }
    }
}

class UpdateWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val ctx = applicationContext
        if (!Prefs.autoUpdate(ctx)) return@withContext Result.success()
        try {
            val installed = Updates.installedVersion(ctx)
            val now = System.currentTimeMillis()
            // GitHub allows 60 unauthenticated checks an hour; once every 30 min is plenty.
            if (now - Prefs.lastUpdateCheck(ctx) >= 30 * 60_000L) {
                Prefs.setLastUpdateCheck(ctx, now)
                val rel = Updates.latest()
                if (Updates.isNewer(rel.version, installed)) {
                    Updates.download(ctx, rel) { _, _ -> }
                    Prefs.setPendingUpdate(ctx, rel.version)
                }
            }
            val pending = Prefs.pendingUpdate(ctx)?.takeIf { Updates.isNewer(it, installed) }
                ?: return@withContext Result.success()
            val file = AutoUpdate.apkFor(ctx, pending).takeIf { it.exists() } ?: return@withContext Result.success()
            if (AutoUpdate.visible || AutoUpdate.tripRunning || !Updates.canInstall(ctx)) return@withContext Result.success()
            AutoUpdate.install(ctx, file, silent = true)
            Result.success()
        } catch (e: Exception) {
            android.util.Log.w("KavUpdate", "background update failed", e)
            Result.retry()
        }
    }
}

class UpdateResultReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    else intent.getParcelableExtra(Intent.EXTRA_INTENT)
                confirm?.let { AutoUpdate.askToConfirm(ctx, it) }
            }
            PackageInstaller.STATUS_SUCCESS -> Unit
            else -> android.util.Log.w(
                "KavUpdate", "install status $status: " + intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE),
            )
        }
    }
}
