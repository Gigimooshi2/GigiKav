package uk.noammm.kav.data

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

/**
 * Google Places (New) with the user's own key, pasted in Settings and kept only on the phone.
 * Autocomplete calls share a session token that ends with one Place Details (Essentials) call,
 * which is how Google bills them as a single free-tier session.
 */
object GooglePlaces {
    private const val CERT_SHA1 = "1703F30BE750EBBFAD109DCBAB9C16ACF9F159EA"

    class Suggestion(val placeId: String, val main: String, val secondary: String, val meters: Int)

    @Volatile private var session: String = UUID.randomUUID().toString()

    private fun lang() = if (uk.noammm.kav.ui.T.rtl) "he" else "en"

    private fun open(url: String, key: String, pkg: String, fieldMask: String? = null) =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 4000; readTimeout = 4000
            setRequestProperty("X-Goog-Api-Key", key)
            setRequestProperty("X-Android-Package", pkg)
            setRequestProperty("X-Android-Cert", CERT_SHA1)
            fieldMask?.let { setRequestProperty("X-Goog-FieldMask", it) }
        }

    private fun read(c: HttpURLConnection): JSONObject {
        val code = c.responseCode
        val body = (if (code in 200..299) c.inputStream else c.errorStream)?.use { String(it.readBytes(), Charsets.UTF_8) }.orEmpty()
        if (code !in 200..299) throw RuntimeException("Google Places HTTP $code")
        return JSONObject(body)
    }

    fun autocomplete(key: String, pkg: String, query: String, at: Pair<Double, Double>?): List<Suggestion> {
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
        val c = open("https://places.googleapis.com/v1/places:autocomplete", key, pkg).apply {
            requestMethod = "POST"; doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }
        c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val root = read(c)
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
    fun resolve(key: String, pkg: String, s: Suggestion): Moovit.Place {
        val url = "https://places.googleapis.com/v1/places/" + URLEncoder.encode(s.placeId, "UTF-8") +
            "?sessionToken=" + URLEncoder.encode(session, "UTF-8") + "&languageCode=" + lang()
        session = UUID.randomUUID().toString()
        val root = read(open(url, key, pkg, fieldMask = "location,formattedAddress"))
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
