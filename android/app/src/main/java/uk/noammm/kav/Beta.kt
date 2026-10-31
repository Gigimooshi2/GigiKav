package uk.noammm.kav

import android.content.Context
import android.location.Location
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.util.zip.GZIPOutputStream

/**
 * Kav Beta only (BuildConfig.BETA). Two things the normal Kav never does:
 *
 *  - Remote switches: experiments ship turned off and are flipped from `beta/flags.json` on the beta
 *    branch, re-read every few minutes, so a change can be tried and taken back without a new build.
 *  - Full log: everything the app does (its whole Android log, crashes, every Moovit request, screens,
 *    searches, trips, GPS fixes, Moovit's raw live records for your buses) is kept on the phone for
 *    7 days and streamed in batches to a ntfy.sh topic whose random name is made on the phone and
 *    never leaves it except when you copy it from Settings. Includes your location; "Pause sharing"
 *    in Settings stops the stream (the local file keeps going).
 *
 * In the normal Kav every flag is off and log() does nothing.
 */
object Beta {
    val on: Boolean get() = BuildConfig.BETA

    private const val OWNER = "Gigimooshi2"
    private const val REPO = "GigiKav"
    private const val FLAGS_EVERY_MS = 5 * 60_000L
    private const val FLUSH_TRIP_MS = 2 * 60_000L
    private const val FLUSH_IDLE_MS = 10 * 60_000L
    // ntfy.sh (anonymous): 250 messages a day per IP, messages kept 12 h, attachments up to 2 MB kept
    // 3 h. Small batches go inline (kept 12 h); bigger ones as a gzip attachment.
    private const val INLINE_MAX = 3_000
    private const val ATTACH_MAX = 1_900_000
    private const val MAX_SENDS_PER_DAY = 230
    private const val MAX_BUFFERED = 40_000
    private const val KEEP_DAYS = 7
    private const val DAY_FILE_MAX = 60L * 1024 * 1024

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loop: Job? = null
    @Volatile private var appCtx: Context? = null

    /** Current switches. Compose state, so screens redraw when a switch flips. */
    var flags by mutableStateOf<Map<String, Any?>>(emptyMap())
        private set
    var flagsAt by mutableStateOf(0L)
        private set
    var sharing by mutableStateOf(true)
        private set
    var lastSent by mutableStateOf(0L)
        private set

    fun flag(name: String): Boolean = on && flags[name] == true

    private fun store(ctx: Context) = ctx.getSharedPreferences("kav_beta", Context.MODE_PRIVATE)

    /** The secret ntfy topic, made once on this phone. */
    fun topic(ctx: Context): String {
        val p = store(ctx)
        p.getString("topic", null)?.let { return it }
        val abc = "abcdefghijkmnopqrstuvwxyz23456789"
        val r = SecureRandom()
        val t = "kavbeta-" + (1..24).map { abc[r.nextInt(abc.length)] }.joinToString("")
        p.edit().putString("topic", t).apply()
        return t
    }

    fun setSharing(ctx: Context, on: Boolean) {
        sharing = on
        store(ctx).edit().putBoolean("sharing", on).apply()
        log("sharing", "on" to on)
    }

    fun init(ctx: Context) {
        if (!on || loop != null) return
        val c = ctx.applicationContext
        appCtx = c
        sharing = store(c).getBoolean("sharing", true)
        runCatching { flags = toMap(JSONObject(store(c).getString("flags", "{}") ?: "{}")) }
        topic(c)
        openLog(c)
        catchCrashes(c)
        captureLogcat()
        loop = scope.launch {
            var flagsNext = 0L
            var flushNext = 0L
            while (true) {
                val now = System.currentTimeMillis()
                if (now >= flagsNext) { fetchFlags(c); flagsNext = now + FLAGS_EVERY_MS }
                if (now >= flushNext) {
                    flush(c)
                    flushNext = now + flushGap(c)
                }
                delay(15_000)
            }
        }
        log("app", "version" to uk.noammm.kav.data.Updates.installedVersion(c), "sdk" to android.os.Build.VERSION.SDK_INT,
            "device" to android.os.Build.MODEL, "flags" to JSONObject(flags))
    }

    /** Re-read the switches now (Settings → "Check switches"). */
    fun refreshFlags() { val c = appCtx ?: return; scope.launch { fetchFlags(c) } }
    fun sendNow() { val c = appCtx ?: return; scope.launch { flush(c) } }

    @Volatile var tripRunning = false

    private fun fetchFlags(ctx: Context) {
        runCatching {
            val c = (URL("https://api.github.com/repos/$OWNER/$REPO/contents/beta/flags.json?ref=${BuildConfig.BETA_BRANCH}")
                .openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000; readTimeout = 15_000
                setRequestProperty("Accept", "application/vnd.github.raw+json")
                setRequestProperty("User-Agent", "KavBeta")
            }
            if (c.responseCode != 200) return
            val text = c.inputStream.use { String(it.readBytes(), Charsets.UTF_8) }
            val fresh = toMap(JSONObject(text))
            flagsAt = System.currentTimeMillis()
            if (fresh != flags) {
                flags = fresh
                store(ctx).edit().putString("flags", text).apply()
                log("flags", "flags" to JSONObject(fresh))
            }
        }.onFailure { android.util.Log.w("KavBeta", "flags", it) }
    }

    private fun toMap(o: JSONObject): Map<String, Any?> = o.keys().asSequence().associateWith { o.opt(it) }

    // ---- the log ----

    private val buffer = ArrayDeque<String>()
    private val fileLock = Any()
    private var logDir: java.io.File? = null
    private var dayFile: java.io.File? = null
    private var writer: java.io.Writer? = null

    /** One event: a type and its fields. Kept on the phone and queued for the stream. No-op in Kav. */
    fun log(type: String, vararg fields: Pair<String, Any?>) {
        if (!on) return
        val o = JSONObject().put("t", System.currentTimeMillis()).put("e", type)
        for ((k, v) in fields) o.put(k, v ?: JSONObject.NULL)
        record(o.toString())
    }

    private fun record(line: String) {
        write(line)
        synchronized(buffer) {
            buffer.addLast(line)
            while (buffer.size > MAX_BUFFERED) buffer.removeFirst()
        }
    }

    private fun openLog(ctx: Context) {
        val dir = java.io.File(ctx.filesDir, "beta-log").apply { mkdirs() }
        logDir = dir
        val cutoff = System.currentTimeMillis() - KEEP_DAYS * 86_400_000L
        dir.listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
        // A crash from last time, written as the app went down: queue it for the stream.
        val p = store(ctx)
        p.getString("crash", null)?.let { synchronized(buffer) { buffer.addLast(it) } }
        p.edit().remove("crash").apply()
    }

    private fun write(line: String) {
        val dir = logDir ?: return
        synchronized(fileLock) {
            runCatching {
                val name = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date()) + ".jsonl"
                if (dayFile?.name != name) {
                    writer?.close()
                    dayFile = java.io.File(dir, name)
                    writer = java.io.BufferedWriter(java.io.FileWriter(dayFile, true), 16 * 1024)
                }
                if ((dayFile?.length() ?: 0) > DAY_FILE_MAX) return
                writer?.apply { write(line); write("\n") }
            }
        }
    }

    private fun syncFile() = synchronized(fileLock) { runCatching { writer?.flush() } }

    /** Uncaught crashes: the stack trace goes to the log file and is queued for the next stream. */
    private fun catchCrashes(ctx: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching {
                val line = JSONObject().put("t", System.currentTimeMillis()).put("e", "crash")
                    .put("thread", thread.name).put("stack", android.util.Log.getStackTraceString(e).take(20_000)).toString()
                write(line); syncFile()
                store(ctx).edit().putString("crash", line).commit()
            }
            previous?.uncaughtException(thread, e)
        }
    }

    /** Kav's own Android log (every Log.* line, its warnings and errors, system notes about it). */
    private fun captureLogcat() {
        Thread({
            runCatching {
                val p = Runtime.getRuntime().exec(arrayOf("logcat", "-v", "epoch", "-T", "1", "--pid=${android.os.Process.myPid()}"))
                p.inputStream.bufferedReader().forEachLine { line ->
                    if (line.isNotBlank() && !line.startsWith("-----")) {
                        record(JSONObject().put("t", System.currentTimeMillis()).put("e", "logcat").put("l", line.take(2000)).toString())
                    }
                }
            }
        }, "KavBetaLogcat").apply { isDaemon = true }.start()
    }

    private var lastFixLogged = 0L

    /** A GPS fix, at most every 10 s: where, how sure, from which provider. */
    fun fix(l: Location) {
        if (!on) return
        val now = System.currentTimeMillis()
        if (now - lastFixLogged < 10_000) return
        lastFixLogged = now
        log(
            "fix", "lat" to "%.5f".format(java.util.Locale.US, l.latitude).toDouble(),
            "lon" to "%.5f".format(java.util.Locale.US, l.longitude).toDouble(),
            "acc" to l.accuracy.toInt(), "spd" to "%.1f".format(java.util.Locale.US, l.speed).toDouble(),
            "prov" to l.provider, "age" to (now - l.time) / 1000,
        )
    }

    /** Every Moovit request: which service, answer code, time taken, size. */
    fun api(path: String, code: Int, ms: Long, bytes: Int) {
        if (on) log("api", "path" to path.substringBefore('?'), "code" to code, "ms" to ms, "bytes" to bytes)
    }

    // ---- the stream ----

    private fun day() = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date())

    private fun sentToday(ctx: Context): Int {
        val p = store(ctx)
        return if (p.getString("sendDay", "") == day()) p.getInt("sends", 0) else 0
    }

    // Spread the day's message budget: every 2 min on a trip, 10 min otherwise, slower if it's running out.
    private fun flushGap(ctx: Context): Long {
        val left = MAX_SENDS_PER_DAY - sentToday(ctx)
        val cal = java.util.Calendar.getInstance()
        val minutesLeft = 24 * 60 - (cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE))
        val want = if (tripRunning) FLUSH_TRIP_MS else FLUSH_IDLE_MS
        val fair = if (left <= 0) 60 * 60_000L else (minutesLeft * 60_000L / left)
        return maxOf(want, if (tripRunning) minOf(fair, FLUSH_IDLE_MS) else fair)
    }

    private fun flush(ctx: Context) {
        if (!sharing) return
        syncFile()
        val lines = synchronized(buffer) { buffer.toList().also { buffer.clear() } }
        if (lines.isEmpty()) return
        val ok = send(ctx, lines, "Kav Beta " + uk.noammm.kav.data.Updates.installedVersion(ctx))
        if (!ok) synchronized(buffer) { lines.asReversed().forEach { buffer.addFirst(it) } }
    }

    /** Send lines as one or more messages: inline when small, else gzip attachments of ≤1.9 MB. */
    private fun send(ctx: Context, lines: List<String>, title: String): Boolean {
        val topic = topic(ctx)
        val chunks = pack(lines)
        var sent = sentToday(ctx)
        for (gz in chunks) {
            if (sent >= MAX_SENDS_PER_DAY) return false
            val inline = Base64.encodeToString(gz, Base64.NO_WRAP).takeIf { it.length <= INLINE_MAX }
            val ok = runCatching {
                val c = (URL("https://ntfy.sh/$topic").openConnection() as HttpURLConnection).apply {
                    requestMethod = if (inline != null) "POST" else "PUT"; doOutput = true
                    connectTimeout = 15_000; readTimeout = 60_000
                    setRequestProperty("Title", title)
                    setRequestProperty("Priority", "1")
                    if (inline != null) setRequestProperty("Tags", "gz")
                    else {
                        setRequestProperty("Tags", "gzfile")
                        setRequestProperty("Filename", "kav-${System.currentTimeMillis()}.jsonl.gz")
                    }
                }
                c.outputStream.use { it.write(inline?.toByteArray() ?: gz) }
                c.responseCode in 200..299
            }.getOrDefault(false)
            if (!ok) return false
            sent++
            store(ctx).edit().putString("sendDay", day()).putInt("sends", sent).apply()
            lastSent = System.currentTimeMillis()
        }
        return true
    }

    /** Settings: upload the full local log for the last [hours] hours (e.g. after a trip you want checked). */
    fun uploadLast(hours: Int, done: (Boolean) -> Unit = {}) {
        val c = appCtx ?: return
        scope.launch {
            syncFile()
            val since = System.currentTimeMillis() - hours * 3_600_000L
            val lines = logDir?.listFiles()?.filter { it.lastModified() >= since }?.sortedBy { it.name }
                ?.flatMap { f -> f.readLines().filter { l -> (runCatching { JSONObject(l).optLong("t") }.getOrDefault(0L)) >= since } }
                .orEmpty()
            val ok = lines.isNotEmpty() && send(c, lines, "Kav Beta full log, last $hours h")
            log("upload", "hours" to hours, "lines" to lines.size, "ok" to ok)
            kotlinx.coroutines.withContext(Dispatchers.Main) { done(ok) }
        }
    }

    /** Gzip [lines] into pieces that each fit one ntfy attachment, halving until they do. */
    private fun pack(lines: List<String>): List<ByteArray> {
        val gz = gzip(lines.joinToString("\n", postfix = "\n"))
        if (gz.size <= ATTACH_MAX || lines.size < 2) return listOf(gz)
        val mid = lines.size / 2
        return pack(lines.subList(0, mid)) + pack(lines.subList(mid, lines.size))
    }

    private fun gzip(s: String): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(s.toByteArray(Charsets.UTF_8)) }
        return out.toByteArray()
    }
}
