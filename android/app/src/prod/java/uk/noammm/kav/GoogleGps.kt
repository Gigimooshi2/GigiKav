package uk.noammm.kav

import android.content.Context
import android.location.Location

/** Kav has no Google location; only Kav Beta does (src/beta), to compare it with Kav's own. */
object GoogleGps {
    fun available(ctx: Context): Boolean = false
    fun track(ctx: Context, onFix: (Location) -> Unit): () -> Unit = {}
    fun once(ctx: Context, onFix: (Location?) -> Unit) = onFix(null)
}
