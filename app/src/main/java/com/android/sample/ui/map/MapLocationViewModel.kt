package com.android.sample.ui.map

// AI assistance: OpenAI Codex.
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.android.sample.data.location.DeviceLocationProvider
import com.android.sample.data.location.DeviceLocationResult
import com.android.sample.model.DeviceLocation
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Optional location state, independent of trip loading, marker selection and delivery filtering.
 */
sealed interface MapLocationUiState {
  data object Idle : MapLocationUiState

  data object Loading : MapLocationUiState

  data class Resolved(val result: DeviceLocationResult) : MapLocationUiState
}

/**
 * Owns a user-triggered location request. The map host must call [stop] on leaving the foreground
 * or ending the session, and [request] only from an explicit action/permission result.
 */
class MapLocationViewModel(
    private val provider: DeviceLocationProvider,
    private val nowElapsedMillis: () -> Long = SystemClock::elapsedRealtime,
) : ViewModel() {
  private val mutableState = MutableStateFlow<MapLocationUiState>(MapLocationUiState.Idle)
  val uiState: StateFlow<MapLocationUiState> = mutableState.asStateFlow()
  private var requestJob: Job? = null

  /** Replaces any previous request; a denied permission never touches the location provider. */
  fun request(permissionGranted: Boolean) {
    stop()
    if (!permissionGranted) {
      mutableState.value = MapLocationUiState.Resolved(DeviceLocationResult.PermissionDenied)
      return
    }
    mutableState.value = MapLocationUiState.Loading
    requestJob = viewModelScope.launch {
      val result =
          withTimeoutOrNull(REQUEST_TIMEOUT_MILLIS) { provider.currentLocation() }
              ?: DeviceLocationResult.Unavailable
      currentCoroutineContext().ensureActive()
      if (result is DeviceLocationResult.Available) showUntilExpired(result)
      else mutableState.value = MapLocationUiState.Resolved(result)
    }
  }

  /** Publishes a fresh fix, then hides it when it reaches [DeviceLocation.MAX_AGE_MILLIS]. */
  private suspend fun showUntilExpired(fix: DeviceLocationResult.Available) {
    val now = nowElapsedMillis()
    if (!fix.location.isUsable(now)) {
      mutableState.value = MapLocationUiState.Resolved(DeviceLocationResult.Unavailable)
      return
    }
    mutableState.value = MapLocationUiState.Resolved(fix)
    delay(fix.location.elapsedRealtimeMillis + DeviceLocation.MAX_AGE_MILLIS - now)
    mutableState.value = MapLocationUiState.Idle
  }

  /** Cancels acquisition and forgets coordinates so an old session cannot restore a position. */
  fun stop() {
    requestJob?.cancel()
    requestJob = null
    mutableState.value = MapLocationUiState.Idle
  }

  override fun onCleared() {
    stop()
  }

  companion object {
    const val REQUEST_TIMEOUT_MILLIS = 15_000L
  }
}
