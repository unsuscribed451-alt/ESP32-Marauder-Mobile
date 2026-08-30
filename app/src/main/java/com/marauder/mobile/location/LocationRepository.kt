package com.marauder.mobile.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat
import com.marauder.mobile.data.PhoneLocation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Streams the phone's own GNSS fixes — the on-phone replacement for the ESP32
 * GPS module. Used to tag wardrive rows (see WardriveAssembler). Requires the
 * ACCESS_FINE_LOCATION runtime permission; without it [start] returns false and
 * [location] stays null so callers degrade gracefully.
 */
class LocationRepository(context: Context) {

    private val appContext = context.applicationContext
    private val lm = appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private val _location = MutableStateFlow<PhoneLocation?>(null)
    val location: StateFlow<PhoneLocation?> = _location.asStateFlow()

    private val listener = LocationListener { loc -> _location.value = loc.toPhone() }

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** Begin GPS updates (~1 Hz). Returns false if permission is missing or GPS is off. */
    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (!hasPermission()) return false
        return try {
            lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { _location.value = it.toPhone() }
            lm.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                MIN_INTERVAL_MS,
                MIN_DISTANCE_M,
                listener,
                Looper.getMainLooper(),
            )
            true
        } catch (e: SecurityException) {
            false
        } catch (e: IllegalArgumentException) {
            false // GPS provider unavailable on this device
        }
    }

    fun stop() {
        runCatching { lm.removeUpdates(listener) }
    }

    private fun Location.toPhone() = PhoneLocation(
        lat = latitude,
        lon = longitude,
        altMeters = if (hasAltitude()) altitude else 0.0,
        accuracyMeters = if (hasAccuracy()) accuracy.toDouble() else 0.0,
        timeMillis = if (time > 0) time else System.currentTimeMillis(),
    )

    private companion object {
        const val MIN_INTERVAL_MS = 1000L
        const val MIN_DISTANCE_M = 0f
    }
}
