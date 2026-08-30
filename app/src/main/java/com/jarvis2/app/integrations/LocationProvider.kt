package com.jarvis2.app.integrations

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * GPS/positioning using the framework LocationManager directly (no Google
 * Play Services fused location dependency, keeping the app installable on
 * de-Googled or AOSP-based Xiaomi ROMs too).
 */
class LocationProvider(private val context: Context) {

    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    @SuppressLint("MissingPermission") // caller must hold ACCESS_FINE_LOCATION before calling
    fun lastKnownLocation(): Location? {
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        return providers
            .filter { locationManager.isProviderEnabled(it) }
            .mapNotNull { runCatching { locationManager.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
    }

    @SuppressLint("MissingPermission")
    suspend fun requestSingleFreshLocation(): Location? = suspendCancellableCoroutine { cont ->
        val provider = when {
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> null
        }
        if (provider == null) {
            cont.resume(lastKnownLocation())
            return@suspendCancellableCoroutine
        }
        val listener = object : android.location.LocationListener {
            override fun onLocationChanged(location: Location) {
                locationManager.removeUpdates(this)
                if (cont.isActive) cont.resume(location)
            }
        }
        locationManager.requestSingleUpdate(provider, listener, context.mainLooper)
        cont.invokeOnCancellation { locationManager.removeUpdates(listener) }
    }
}
