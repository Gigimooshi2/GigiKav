package uk.noammm.kav

import android.app.Application

/**
 * Runs whenever Kav's process starts: the app, but also a widget refresh, the trip service or an
 * update in the background. Kav Beta starts its log here, so background work is logged too.
 */
class KavApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Beta.init(this)
        DualGps.load(this)
    }
}
