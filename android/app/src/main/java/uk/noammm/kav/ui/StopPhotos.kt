package uk.noammm.kav.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import uk.noammm.kav.data.Moovit
import uk.noammm.kav.data.Net
import java.io.File
import java.util.concurrent.ConcurrentHashMap

object StopPhotos {
    private const val THUMB_PX = 192
    private const val FULL_PX = 1280
    private const val DISK_CAP = 80L shl 20
    private const val KEEP_PHOTOS_DAYS = 60
    private const val KEEP_EMPTY_DAYS = 7

    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Semaphore(3)

    private val ids = ConcurrentHashMap<String, Int>()
    private val misses = ConcurrentHashMap.newKeySet<String>()
    private class Urls(val list: List<String>, val day: Long)
    private val urls = ConcurrentHashMap<Int, Urls>()

    private val thumbs = object : LruCache<String, Bitmap>(24 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }
    private val fulls = object : LruCache<String, Bitmap>(16 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }
    private val inflight = ConcurrentHashMap<String, Deferred<Bitmap?>>()

    private val idFile get() = File(app.filesDir, "stop-ids.txt")
    private val urlFile get() = File(app.filesDir, "stop-photos.txt")
    private val imageDir get() = File(app.cacheDir, "stop-photos").apply { mkdirs() }

    fun init(ctx: Context) {
        app = ctx.applicationContext
        scope.launch { load() }
    }

    private fun today() = System.currentTimeMillis() / 86_400_000L

    private fun load() {
        runCatching {
            idFile.takeIf { it.exists() }?.forEachLine { line ->
                val tab = line.indexOf('\t')
                if (tab > 0) line.substring(tab + 1).toIntOrNull()?.let { ids[line.substring(0, tab)] = it }
            }
        }
        runCatching {
            urlFile.takeIf { it.exists() }?.forEachLine { line ->
                val parts = line.split('\t', limit = 3)
                if (parts.size < 2) return@forEachLine
                val id = parts[0].toIntOrNull() ?: return@forEachLine
                val day = parts[1].toLongOrNull() ?: return@forEachLine
                val list = parts.getOrNull(2)?.split(' ')?.filter { it.isNotBlank() }.orEmpty()
                urls[id] = Urls(list, day)
            }
            if (urlFile.length() > 2L shl 20) compactUrls()
        }
    }

    @Synchronized private fun appendId(key: String, id: Int) =
        runCatching { idFile.appendText("$key\t$id\n") }

    @Synchronized private fun appendUrls(id: Int, entry: Urls) =
        runCatching { urlFile.appendText("$id\t${entry.day}\t${entry.list.joinToString(" ")}\n") }

    @Synchronized private fun compactUrls() = runCatching {
        val tmp = File(urlFile.parentFile, urlFile.name + ".tmp")
        tmp.bufferedWriter().use { w ->
            for ((id, e) in urls) w.write("$id\t${e.day}\t${e.list.joinToString(" ")}\n")
        }
        tmp.renameTo(urlFile)
    }

    private fun fresh(e: Urls) =
        today() - e.day <= if (e.list.isEmpty()) KEEP_EMPTY_DAYS else KEEP_PHOTOS_DAYS

    private fun keyOf(net: Net, stop: Int): String {
        val code = net.code.getOrElse(stop) { 0 }
        return if (code > 0) "c$code" else "p%.5f,%.5f".format(net.lat[stop], net.lon[stop])
    }

    fun idNow(net: Net, stop: Int): Int? = ids[keyOf(net, stop)]

    fun thumbNow(stopId: Int): Bitmap? =
        urls[stopId]?.list?.firstOrNull()?.let { thumbs.get(it) }

    suspend fun idOf(net: Net, stop: Int): Int? = withContext(Dispatchers.IO) {
        val key = keyOf(net, stop)
        ids[key]?.let { return@withContext it }
        if (key in misses) return@withContext null
        try {
            val lat = net.lat[stop].toDouble()
            val lon = net.lon[stop].toDouble()
            val id = Moovit.searchStopId(Online.open(lat to lon), net.name.getOrElse(stop) { "" }, lat to lon)
            if (id == null) misses.add(key) else { ids[key] = id; appendId(key, id) }
            id
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun urlsOf(stopId: Int): List<String>? {
        urls[stopId]?.takeIf(::fresh)?.let { return it.list }
        return try {
            val s = Online.open()
            val entry = Urls(Moovit.stopImages(s, stopId), today())
            urls[stopId] = entry
            appendUrls(stopId, entry)
            entry.list
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    private fun fileOf(url: String) = File(imageDir, Integer.toHexString(url.hashCode()) + "-" + url.length + ".jpg")

    private fun ensureFile(url: String): File? {
        val f = fileOf(url)
        if (f.exists() && f.length() > 0) return f.also { it.setLastModified(System.currentTimeMillis()) }
        return runCatching {
            val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            c.connectTimeout = 8000; c.readTimeout = 8000
            val bytes = c.inputStream.use { it.readBytes() }
            val bmp = decode(bytes, FULL_PX) ?: return null
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            tmp.renameTo(f)
            trimDisk()
            f
        }.getOrNull()
    }

    private fun decode(bytes: ByteArray, maxPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val opts = BitmapFactory.Options().apply { inSampleSize = sampleFor(bounds, maxPx) }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    private fun decode(file: File, maxPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        val opts = BitmapFactory.Options().apply { inSampleSize = sampleFor(bounds, maxPx) }
        return BitmapFactory.decodeFile(file.path, opts)
    }

    private fun sampleFor(bounds: BitmapFactory.Options, maxPx: Int): Int {
        var sample = 1
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        while (longest / (sample * 2) >= maxPx) sample *= 2
        return sample
    }

    private fun trimDisk() {
        val files = imageDir.listFiles()?.filter { it.name.endsWith(".jpg") } ?: return
        var total = files.sumOf { it.length() }
        if (total <= DISK_CAP) return
        for (f in files.sortedBy { it.lastModified() }) {
            if (total <= DISK_CAP * 3 / 4) break
            total -= f.length(); f.delete()
        }
    }

    suspend fun thumb(stopId: Int): Bitmap? {
        if (stopId <= 0) return null
        thumbNow(stopId)?.let { return it }
        val url = urlsOf(stopId)?.firstOrNull() ?: return null
        thumbs.get(url)?.let { return it }
        val job = inflight.getOrPut(url) {
            scope.async {
                gate.withPermit {
                    val f = ensureFile(url) ?: return@withPermit null
                    decode(f, THUMB_PX)?.also { thumbs.put(url, it) }
                }
            }
        }
        return try { job.await() } finally { inflight.remove(url, job) }
    }

    suspend fun full(stopId: Int): Bitmap? = withContext(Dispatchers.IO) {
        val url = urlsOf(stopId)?.firstOrNull() ?: return@withContext null
        fulls.get(url) ?: ensureFile(url)?.let { decode(it, FULL_PX) }?.also { fulls.put(url, it) }
    }

    fun prefetchNet(net: Net, stops: List<Int>) {
        for (s in stops) scope.launch { idOf(net, s)?.let { thumb(it) } }
    }

    fun prefetchIds(stopIds: List<Int>) {
        for (id in stopIds.distinct()) if (id > 0) scope.launch { thumb(id) }
    }
}
