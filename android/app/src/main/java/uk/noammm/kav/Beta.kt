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
 *  - Live log: what the trip is doing (step, GPS fixes, Moovit's live data for your buses, what the
 *    screen showed) goes in small batches to a ntfy.sh topic whose random name is made on the phone
 *    and never leaves it except when you copy it from Settings. Includes your location while a trip
 *    runs; "Pause sharing" in Settings stops it.
 *
 * In the normal Kav every flag is off and log() does nothing.
 */
object Beta {
    val on: Boolean get() = BuildConfig.BETA

    private const val OWNER = "Gigimooshi2"
    private const val REPO = "GigiKav"
    private const val FLAGS_EVERY_MS = 5 * 60_000L
    private const val FLUSH_TRIP_MS = 60_000L
    private const val FLUSH_IDLE_MS = 10 * 60_000L
    // ntfy.sh keeps a message to ~4 KB before turning it into an attachment; batches are gzipped to fit.
    private const val CHUNK_CHARS = 8_000
    private const val MAX_SENDS_PER_DAY = 200
    private const val MAX_BUFFERED = 3_000

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
        loop = scope.launch {
            var flagsNext = 0L
            var flushNext = 0L
            while (true) {
                val now = System.currentTimeMillis()
                if (now >= flagsNext) { fetchFlags(c); flagsNext = now + FLAGS_EVERY_MS }
                if (now >= flushNext) {
                    flush(c)
                    flushNext = now + if (tripRunning) FLUSH_TRIP_MS else FLUSH_IDLE_MS
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

    // ---- live log ----

    private val buffer = ArrayDeque<String>()

    /** One event: a type and a few fields. Cheap; does nothing outside Kav Beta or while paused. */
    fun log(type: String, vararg fields: Pair<String, Any?>) {
        if (!on) return
        val o = JSONObject().put("t", System.currentTimeMillis()).put("e", type)
        for ((k, v) in fields) o.put(k, v ?: JSONObject.NULL)
        synchronized(buffer) {
            buffer.addLast(o.toString())
            while (buffer.size > MAX_BUFFERED) buffer.removeFirst()
        }
    }

    private var lastFixLogged = 0L

    /** A GPS fix during a trip, at most every 10 s: where, how sure, from which provider. */
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

    private fun flush(ctx: Context) {
        if (!sharing) return
        val lines = synchronized(buffer) { buffer.toList().also { buffer.clear() } }
        if (lines.isEmpty()) return
        val day = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date())
        val p = store(ctx)
        var sent = if (p.getString("sendDay", "") == day) p.getInt("sends", 0) else 0
        val chunks = ArrayList<String>()
        val sb = StringBuilder()
        for (l in lines) {
            if (sb.isNotEmpty() && sb.length + l.length > CHUNK_CHARS) { chunks.add(sb.toString()); sb.clear() }
            sb.append(l).append('\n')
        }
        if (sb.isNotEmpty()) chunks.add(sb.toString())
        val topic = topic(ctx)
        val title = "Kav Beta " + uk.noammm.kav.data.Updates.installedVersion(ctx)
        for ((i, chunk) in chunks.withIndex()) {
            if (sent >= MAX_SENDS_PER_DAY) {
                // Over the day's budget: keep the rest for tomorrow rather than lose it.
                synchronized(buffer) { chunks.drop(i).flatMap { it.lines() }.filter { it.isNotBlank() }.reversed().forEach { buffer.addFirst(it) } }
                break
            }
            val ok = runCatching {
                val c = (URL("https://ntfy.sh/$topic").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"; doOutput = true; connectTimeout = 10_000; readTimeout = 15_000
                    setRequestProperty("Title", title)
                    setRequestProperty("Tags", "gz")
                    setRequestProperty("Priority", "1")
                }
                c.outputStream.use { it.write(gzipBase64(chunk).toByteArray()) }
                c.responseCode in 200..299
            }.getOrDefault(false)
            if (!ok) {
                // Offline or refused: put this and the rest back for the next try.
                synchronized(buffer) { chunks.drop(i).flatMap { it.lines() }.filter { it.isNotBlank() }.reversed().forEach { buffer.addFirst(it) } }
                break
            }
            sent++
            lastSent = System.currentTimeMillis()
        }
        p.edit().putString("sendDay", day).putInt("sends", sent).apply()
    }

    private fun gzipBase64(s: String): String {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(s.toByteArray(Charsets.UTF_8)) }
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }
}
