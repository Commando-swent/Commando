package com.android.sample.ui.trips

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.android.sample.data.repository.TripError
import com.android.sample.data.repository.TripRepository
import com.android.sample.data.repository.TripResult
import com.android.sample.model.Trip
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class TripDetailsUiState {
  data object Loading : TripDetailsUiState()

  data class Content(val trip: Trip) : TripDetailsUiState()

  data class Error(val error: TripError) : TripDetailsUiState()
}

/** Loads the supplied trip ID and displays repository results unchanged. */
class TripDetailsViewModel(
    private val repository: TripRepository,
    private val tripId: String,
) : ViewModel() {
  private val mutableState = MutableStateFlow<TripDetailsUiState>(TripDetailsUiState.Loading)
  val uiState: StateFlow<TripDetailsUiState> = mutableState.asStateFlow()
  private var isLoading = false

  init {
    loadTrip()
  }

  fun retry() {
    loadTrip()
  }

  private fun loadTrip() {
    if (isLoading) return
    // Set this before launching so repeated calls cannot start concurrent requests.
    isLoading = true
    mutableState.value = TripDetailsUiState.Loading
    viewModelScope.launch {
      try {
        mutableState.value =
            when (val result = repository.getTripById(tripId)) {
              is TripResult.Success -> TripDetailsUiState.Content(result.data)
              is TripResult.Error -> TripDetailsUiState.Error(result.error)
            }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (error: Exception) {
        mutableState.value = TripDetailsUiState.Error(TripError.Unknown)
      } finally {
        isLoading = false
      }
    }
  }
}
