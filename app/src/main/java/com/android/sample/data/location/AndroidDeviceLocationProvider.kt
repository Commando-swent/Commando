package com.android.sample.data.location

// AI assistance: OpenAI Codex.
import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.CancellationSignal
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.android.sample.model.DeviceLocation
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine

/** Approximate one-shot location through Android's network provider, without a new SDK. */
class AndroidDeviceLocationProvider(context: Context) : DeviceLocationProvider {
  private val application = context.applicationContext

  @SuppressLint(
      "MissingPermission"
  ) // Checked immediately before requesting; revocation is handled.
  override suspend fun currentLocation(): DeviceLocationResult {
    if (
        ContextCompat.checkSelfPermission(
            application,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ) != PackageManager.PERMISSION_GRANTED
    )
        return DeviceLocationResult.PermissionDenied
    val manager =
        application.getSystemService(LocationManager::class.java)
            ?: return DeviceLocationResult.Unavailable
    return try {
      when {
        !manager.isLocationEnabled -> DeviceLocationResult.ServicesDisabled
        !manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) ->
            DeviceLocationResult.Unavailable
        else -> read(manager)
      }
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (_: SecurityException) {
      DeviceLocationResult.PermissionDenied
    } catch (_: IllegalArgumentException) {
      DeviceLocationResult.Unavailable
    } catch (_: IllegalStateException) {
      DeviceLocationResult.Failure
    }
  }

  @RequiresPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
  private suspend fun read(manager: LocationManager): DeviceLocationResult =
      suspendCancellableCoroutine { continuation ->
        val cancellation = CancellationSignal()
        continuation.invokeOnCancellation { cancellation.cancel() }
        LocationManagerCompat.getCurrentLocation(
            manager,
            LocationManager.NETWORK_PROVIDER,
            cancellation,
            ContextCompat.getMainExecutor(application),
        ) { fix ->
          if (continuation.isActive) {
            val result =
                if (fix == null || !fix.hasAccuracy()) DeviceLocationResult.Unavailable
                else
                    DeviceLocationResult.Available(
                        DeviceLocation(
                            fix.latitude,
                            fix.longitude,
                            fix.accuracy,
                            fix.elapsedRealtimeNanos / NANOS_PER_MILLISECOND,
                        )
                    )
            continuation.resume(result)
          }
        }
      }

  private companion object {
    const val NANOS_PER_MILLISECOND = 1_000_000L
  }
}
