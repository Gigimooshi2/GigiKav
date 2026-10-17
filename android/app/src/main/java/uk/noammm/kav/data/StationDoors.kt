package uk.noammm.kav.data

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.cos

/**
 * Which station entrance/exit a walk uses. Moovit only gives the point where the walk leaves (or
 * enters) the station; OpenStreetMap has the doors mapped with their letter and street. Looked up
 * once per door and kept on the phone. Sends OSM only the door's position, never yours.
 */
object StationDoors {
    /** "lat,lon" -> label ("" = looked up, nothing mapped there). Compose state, so screens redraw. */
    val names = mutableStateMapOf<String, String>()
    private val inFlight = HashSet<String>()

    fun key(p: Pair<Double, Double>) = "%.5f,%.5f".format(java.util.Locale.US, p.first, p.second)

    fun label(p: Pair<Double, Double>?): String? = p?.let { names[key(it)] }?.takeIf { it.isNotBlank() }

    suspend fun lookup(ctx: Context, p: Pair<Double, Double>) {
        val k = key(p)
        if (names.containsKey(k)) return
        val prefs = ctx.getSharedPreferences("kav_doors", Context.MODE_PRIVATE)
        prefs.getString(k, null)?.let { names[k] = it; return }
        synchronized(inFlight) { if (!inFlight.add(k)) return }
        try {
            val found = withContext(Dispatchers.IO) { fetch(p) } ?: return   // network error: try again later
            names[k] = found
            prefs.edit().putString(k, found).apply()
        } finally {
            synchronized(inFlight) { inFlight.remove(k) }
        }
    }

    private fun fetch(p: Pair<Double, Double>): String? = runCatching {
        val (lat, lon) = p
        val q = "[out:json][timeout:8];(" +
            "node(around:40,$lat,$lon)[railway=subway_entrance];" +
            "node(around:40,$lat,$lon)[railway=train_station_entrance];" +
            "node(around:40,$lat,$lon)[entrance][public_transport];" +
            ");out;"
        val c = (URL("https://overpass-api.de/api/interpreter?data=" + URLEncoder.encode(q, "UTF-8"))
            .openConnection() as HttpURLConnection).apply {
            connectTimeout = 6000; readTimeout = 10000
            setRequestProperty("User-Agent", "GigiKav (station exits)")
        }
        if (c.responseCode != 200) return null
        val arr = JSONObject(c.inputStream.use { String(it.readBytes(), Charsets.UTF_8) }).optJSONArray("elements")
            ?: return ""
        val he = uk.noammm.kav.ui.T.rtl
        var best: Pair<Double, String>? = null
        for (i in 0 until arr.length()) {
            val e = arr.getJSONObject(i)
            val tags = e.optJSONObject("tags") ?: continue
            val dy = (e.optDouble("lat") - lat) * 111_000
            val dx = (e.optDouble("lon") - lon) * 111_000 * cos(Math.toRadians(lat))
            val d = Math.hypot(dx, dy)
            val ref = tags.optString("ref").trim()
            val name = (if (he) tags.optString("name:he") else tags.optString("name:en"))
                .ifBlank { tags.optString("name") }.trim()
            val text = when {
                ref.isNotEmpty() && name.isNotEmpty() && !name.contains(ref) -> "$ref · $name"
                name.isNotEmpty() -> name
                ref.isNotEmpty() -> ref
                else -> continue
            }
            if (best == null || d < best.first) best = d to text
        }
        best?.second ?: ""
    }.getOrNull()
}
