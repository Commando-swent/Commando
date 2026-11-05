package com.android.sample.data.location

// AI assistance: OpenAI Codex.
import com.android.sample.model.DeviceLocation

/** Injectable foreground, single-fix source. Cancellation must release any platform request. */
fun interface DeviceLocationProvider {
  /** Reads only after user consent; never opens a permission dialog or uploads coordinates. */
  suspend fun currentLocation(): DeviceLocationResult
}

/** Expected outcomes shared by the Android provider and map location state. */
sealed interface DeviceLocationResult {
  data class Available(val location: DeviceLocation) : DeviceLocationResult

  data object PermissionDenied : DeviceLocationResult

  data object ServicesDisabled : DeviceLocationResult

  data object Unavailable : DeviceLocationResult

  data object Failure : DeviceLocationResult
}
