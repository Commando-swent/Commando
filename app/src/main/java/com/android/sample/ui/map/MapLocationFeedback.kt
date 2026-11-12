package com.android.sample.ui.map

// AI assistance: OpenAI Codex.
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.android.sample.R
import com.android.sample.data.location.DeviceLocationResult
import com.android.sample.model.canIdentifyNearbyTrips

/** Stable semantics for the device-location overlay. */
internal object MapLocationTestTags {
  const val POSITION = "map_device_position"
}

/** Localized feedback leaves the trip feed usable for every optional-location outcome. */
@Composable
internal fun mapLocationFeedback(state: MapLocationUiState): String? {
  val resource =
      when (state) {
        MapLocationUiState.Idle -> return null
        MapLocationUiState.Loading -> R.string.map_location_loading
        is MapLocationUiState.Resolved ->
            when (val result = state.result) {
              DeviceLocationResult.PermissionDenied -> R.string.map_location_denied
              DeviceLocationResult.ServicesDisabled -> R.string.map_location_disabled
              DeviceLocationResult.Unavailable -> R.string.trip_map_location_unavailable
              DeviceLocationResult.Failure -> R.string.map_location_failure
              is DeviceLocationResult.Available ->
                  when {
                    !result.location.canIdentifyNearbyTrips() -> R.string.map_location_imprecise
                    result.location.campusPosition() == null -> R.string.map_location_outside
                    else -> return null
                  }
            }
      }
  return stringResource(resource)
}
