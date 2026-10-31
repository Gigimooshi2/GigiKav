package uk.noammm.kav

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Looper
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

/** Kav Beta: Google's fused location (Play services), the same source Google Maps uses. */
object GoogleGps {
    fun available(ctx: Context): Boolean = runCatching {
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(ctx) == ConnectionResult.SUCCESS
    }.getOrDefault(false)

    @SuppressLint("MissingPermission")
    fun track(ctx: Context, onFix: (Location) -> Unit): () -> Unit {
        if (!hasLocationPermission(ctx) || !available(ctx)) return {}
        val client = LocationServices.getFusedLocationProviderClient(ctx)
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateIntervalMillis(500L).build()
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) { result.lastLocation?.let(onFix) }
        }
        try {
            client.requestLocationUpdates(request, callback, Looper.getMainLooper())
        } catch (e: Exception) {
            return {}
        }
        return { runCatching { client.removeLocationUpdates(callback) } }
    }

    @SuppressLint("MissingPermission")
    fun once(ctx: Context, onFix: (Location?) -> Unit) {
        if (!hasLocationPermission(ctx) || !available(ctx)) return onFix(null)
        runCatching {
            LocationServices.getFusedLocationProviderClient(ctx)
                .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                .addOnSuccessListener { onFix(it) }
                .addOnFailureListener { onFix(null) }
        }.onFailure { onFix(null) }
    }
}
