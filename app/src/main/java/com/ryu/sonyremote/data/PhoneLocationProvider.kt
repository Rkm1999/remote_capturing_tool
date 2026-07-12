package com.ryu.sonyremote.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.annotation.RequiresApi
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

class PhoneLocationProvider(context: Context) {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(LocationManager::class.java)
    private val directExecutor = Executor(Runnable::run)

    suspend fun currentLocation(): Location? {
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) return null
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter(manager::isProviderEnabled)
        for (provider in providers) {
            val fresh = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                withTimeoutOrNull(4_000) { request(provider) }
            } else null
            if (fresh != null) return fresh
        }
        return providers.mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull(Location::getTime)
    }

    @SuppressLint("MissingPermission")
    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun request(provider: String): Location? = suspendCancellableCoroutine { continuation ->
        val cancellation = CancellationSignal()
        continuation.invokeOnCancellation { cancellation.cancel() }
        try {
            manager.getCurrentLocation(provider, cancellation, directExecutor) { location ->
                if (continuation.isActive) continuation.resume(location)
            }
        } catch (_: SecurityException) {
            if (continuation.isActive) continuation.resume(null)
        }
    }
}
