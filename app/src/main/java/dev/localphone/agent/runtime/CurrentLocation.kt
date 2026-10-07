package dev.localphone.agent.runtime

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume

object CurrentLocation {
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    suspend fun get(context: Context): Location? = withContext(Dispatchers.Main) {
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return@withContext null
        val manager = context.getSystemService(LocationManager::class.java)
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).filter { manager.isProviderEnabled(it) }
        if (providers.isEmpty()) return@withContext null
        try {
            withTimeout(20_000) {
                suspendCancellableCoroutine { continuation ->
                    val listener = object : LocationListener {
                        override fun onLocationChanged(location: Location) {
                            if (!location.hasAccuracy() || location.accuracy > 100f) return
                            manager.removeUpdates(this)
                            if (continuation.isActive) continuation.resume(location)
                        }
                        override fun onProviderDisabled(provider: String) {
                            if (providers.none { manager.isProviderEnabled(it) }) {
                                manager.removeUpdates(this)
                                if (continuation.isActive) continuation.resume(null)
                            }
                        }
                        override fun onProviderEnabled(provider: String) {}
                        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                    }
                    continuation.invokeOnCancellation { manager.removeUpdates(listener) }
                    for (provider in providers) manager.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
                }
            }
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) { null }
    }
}
