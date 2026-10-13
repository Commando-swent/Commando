package com.android.sample.ui.map

// AI assistance: OpenAI Codex.
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** No repository or network: this slice deliberately previews injected local samples only. */
class MockMapViewModel(samples: List<MockMapTrip> = MockMapSamples.trips) : ViewModel() {
  private val mutableUiState = MutableStateFlow(MockMapUiState(samples.toList()))
  val uiState = mutableUiState.asStateFlow()

  fun selectTrip(id: String) {
    mutableUiState.update { state ->
      if (state.trips.any { it.id == id }) state.copy(selectedTripId = id) else state
    }
  }

  fun clearSelection() {
    mutableUiState.update { it.copy(selectedTripId = null) }
  }
}
