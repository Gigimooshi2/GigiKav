package uk.noammm.kav.data

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

/**
 * Google Places (New), for businesses in search. Either with the user's own key, pasted in Settings
 * and kept only on the phone, or through Kav's shared relay (`relay/places-worker.js`), which holds
 * a key for everyone, caps use per install per day and counts requests (never the search text).
 * Autocomplete calls share a session token that ends with one Place Details (Essentials) call,
 * which is how Google bills them as a single free-tier session.
 */
object GooglePlaces {
    private const val CERT_SHA1 = "1703F30BE750EBBFAD109DCBAB9C16ACF9F159EA"
    private const val RELAY = "https://kav-places.gigimooshi2.workers.dev/v1/"
    private const val RELAY_APP = "kav-places-1"
    // After the relay says no (daily cap, not set up), leave it alone for a while: Moovit's results stand.
    private const val RELAY_REST_MS = 15 * 60_000L

    /** How Kav reaches Google: the user's own key, or the shared relay. */
    sealed interface Via {
        class Key(val key: String, val pkg: String) : Via
        class Relay(val install: String) : Via
    }

    @Volatile private var relayRestUntil = 0L

    /** The way to reach Google right now, or null when business search is off (or resting). */
    fun via(ctx: android.content.Context): Via? {
        uk.noammm.kav.Prefs.placesKey(ctx)?.let { return Via.Key(it, ctx.packageName) }
        if (!uk.noammm.kav.Prefs.sharedPlaces(ctx)) return null
        if (System.currentTimeMillis() < relayRestUntil) return null
        return Via.Relay(uk.noammm.kav.Prefs.installId(ctx))
    }

    class Suggestion(val placeId: String, val main: String, val secondary: String, val meters: Int)

    @Volatile private var session: String = UUID.randomUUID().toString()

    private fun lang() = if (uk.noammm.kav.ui.T.rtl) "he" else "en"

    private fun open(url: String, via: Via, fieldMask: String? = null) =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 4000; readTimeout = 4000
            when (via) {
                is Via.Key -> {
                    setRequestProperty("X-Goog-Api-Key", via.key)
                    setRequestProperty("X-Android-Package", via.pkg)
                    setRequestProperty("X-Android-Cert", CERT_SHA1)
                    fieldMask?.let { setRequestProperty("X-Goog-FieldMask", it) }
                }
                is Via.Relay -> {
                    setRequestProperty("x-kav-app", RELAY_APP)
                    setRequestProperty("x-kav-install", via.install)
                }
            }
        }

    private fun read(c: HttpURLConnection, via: Via, kind: String): JSONObject {
        val t0 = System.currentTimeMillis()
        val code = c.responseCode
        val body = (if (code in 200..299) c.inputStream else c.errorStream)?.use { String(it.readBytes(), Charsets.UTF_8) }.orEmpty()
        val relay = via is Via.Relay
        uk.noammm.kav.Beta.log("places", "kind" to kind, "via" to if (relay) "relay" else "key", "code" to code, "ms" to System.currentTimeMillis() - t0)
        // 429: today's cap is reached; 503: the relay has no key yet. Either way, Moovit only for a while.
        if (relay && (code == 429 || code == 503)) relayRestUntil = System.currentTimeMillis() + RELAY_REST_MS
        if (code !in 200..299) throw RuntimeException("Google Places HTTP $code")
        return JSONObject(body)
    }

    fun autocomplete(via: Via, query: String, at: Pair<Double, Double>?): List<Suggestion> {
        val body = JSONObject()
            .put("input", query)
            .put("sessionToken", session)
            .put("languageCode", lang())
            .put("includedRegionCodes", JSONArray().put("il"))
        if (at != null) {
            val center = JSONObject().put("latitude", at.first).put("longitude", at.second)
            body.put("locationBias", JSONObject().put("circle", JSONObject().put("center", center).put("radius", 30000.0)))
            body.put("origin", center)
        }
        val url = if (via is Via.Relay) RELAY + "autocomplete" else "https://places.googleapis.com/v1/places:autocomplete"
        val c = open(url, via).apply {
            requestMethod = "POST"; doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }
        c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val root = read(c, via, "autocomplete")
        val out = ArrayList<Suggestion>()
        val arr = root.optJSONArray("suggestions") ?: return out
        for (i in 0 until arr.length()) {
            val p = arr.optJSONObject(i)?.optJSONObject("placePrediction") ?: continue
            val id = p.optString("placeId")
            if (id.isBlank()) continue
            val fmt = p.optJSONObject("structuredFormat")
            val main = fmt?.optJSONObject("mainText")?.optString("text").orEmpty()
                .ifBlank { p.optJSONObject("text")?.optString("text").orEmpty() }
            val secondary = fmt?.optJSONObject("secondaryText")?.optString("text").orEmpty()
            out.add(Suggestion(id, main, secondary, p.optInt("distanceMeters", -1)))
        }
        return out
    }

    /** Ends the session: one Essentials-tier details call (location + address only). */
    fun resolve(via: Via, s: Suggestion): Moovit.Place {
        val base = if (via is Via.Relay) RELAY + "place/" else "https://places.googleapis.com/v1/places/"
        val url = base + URLEncoder.encode(s.placeId, "UTF-8") +
            "?sessionToken=" + URLEncoder.encode(session, "UTF-8") + "&languageCode=" + lang()
        session = UUID.randomUUID().toString()
        val root = read(open(url, via, fieldMask = "location,formattedAddress"), via, "details")
        val loc = root.optJSONObject("location") ?: throw RuntimeException("No location for this place")
        return Moovit.Place(
            name = s.main,
            detail = s.secondary.ifBlank { root.optString("formattedAddress") },
            lat = loc.optDouble("latitude"),
            lon = loc.optDouble("longitude"),
            meters = s.meters,
        )
    }
}
