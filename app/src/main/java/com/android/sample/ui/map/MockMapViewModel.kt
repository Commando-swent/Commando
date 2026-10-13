package com.android.sample.ui.map

// AI assistance: OpenAI Codex.
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** No repository or network: this slice deliberately previews injected local samples only. */
class MockMapViewModel(
    samples: List<MockMapTrip> = MockMapSamples.trips,
    initialStatus: MockMapStatus = MockMapStatus.Content,
    locationOff: Boolean = false,
) : ViewModel() {
  private val mutableUiState =
      MutableStateFlow(
          MockMapUiState(samples.toList(), status = initialStatus, locationOff = locationOff)
      )
  val uiState = mutableUiState.asStateFlow()

  fun selectTrip(id: String) {
    mutableUiState.update { state ->
      if (state.status == MockMapStatus.Content && state.trips.any { it.id == id })
          state.copy(selectedTripId = id)
      else state
    }
  }

  /** Simulates recovery using the same injected fixtures; never performs I/O. */
  fun retry() {
    mutableUiState.update { it.copy(status = MockMapStatus.Content, selectedTripId = null) }
  }

  /** Simulates Allow only; does not represent or request Android location permission. */
  fun enableSampleLocation() {
    mutableUiState.update { it.copy(locationOff = false) }
  }

  fun clearSelection() {
    mutableUiState.update { it.copy(selectedTripId = null) }
  }
}
