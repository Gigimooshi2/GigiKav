package uk.noammm.kav

import android.content.Context
import android.location.Location
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Kav's own location (Android's GPS and network providers) and Google's (Play services' fused
 * location, what Google Maps uses) run side by side during trips (switch `dual_gps`). Both show on the trip map
 * and both are logged; the one you pick as main drives the app. If the main one goes quiet for 15 s
 * while the other still reports, the other stands in until it's back.
 */
object DualGps {
    enum class Source(val label: String) { KAV("Kav"), GOOGLE("Google") }

    const val FLAG = "dual_gps"
    private const val QUIET_MS = 15_000L

    val enabled: Boolean get() = Beta.shipped(FLAG)

    var main by mutableStateOf(Source.GOOGLE)
        private set
    var kav by mutableStateOf<Location?>(null)
        private set
    var google by mutableStateOf<Location?>(null)
        private set
    /** True while the other source is standing in for a silent main one. */
    var standIn by mutableStateOf(false)
        private set

    private fun store(ctx: Context) = ctx.getSharedPreferences("kav_beta", Context.MODE_PRIVATE)

    fun load(ctx: Context) {
        // Google's is the default main source: on the first logged trips its fixes were about twice as
        // precise (median ±8 m vs ±17 m for Kav's own; 90th percentile ±36 m vs ±76 m).
        main = runCatching { Source.valueOf(store(ctx).getString("gpsMain", null) ?: "GOOGLE") }.getOrDefault(Source.GOOGLE)
    }

    fun setMain(ctx: Context, s: Source) {
        if (s == main) return
        main = s
        store(ctx).edit().putString("gpsMain", s.name).apply()
        Beta.log("gps_main", "main" to s.name)
    }

    fun last(s: Source) = if (s == Source.KAV) kav else google

    /** A fix from one source: remember it, log it, and pass it on if it's the one driving the app. */
    fun got(src: Source, l: Location, onFix: (Location) -> Unit) {
        if (l.provider != "cached") { if (src == Source.KAV) kav = l else google = l }
        Beta.fix(l, src.name.lowercase())
        if (src == main) {
            if (standIn) { standIn = false; Beta.log("gps_standin", "on" to false) }
            onFix(l)
            return
        }
        val m = last(main)
        val quiet = m == null || System.currentTimeMillis() - m.time > QUIET_MS
        if (quiet) {
            if (!standIn) { standIn = true; Beta.log("gps_standin", "on" to true, "by" to src.name) }
            onFix(l)
        }
    }

    /** Metres between the latest fixes of the two, when both are fresh. */
    fun apart(now: Long = System.currentTimeMillis()): Float? {
        val a = kav ?: return null
        val b = google ?: return null
        if (now - a.time > 60_000 || now - b.time > 60_000) return null
        return a.distanceTo(b)
    }
}
