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

sealed class AvailableTripsUiState {
  data object Loading : AvailableTripsUiState()

  data class Content(val trips: List<Trip>) : AvailableTripsUiState()

  data object Empty : AvailableTripsUiState()

  data class Error(val error: TripError) : AvailableTripsUiState()
}

/** Displays repository results unchanged; the repository owns trip eligibility and ordering. */
class AvailableTripsViewModel(private val repository: TripRepository) : ViewModel() {
  private val mutableState = MutableStateFlow<AvailableTripsUiState>(AvailableTripsUiState.Loading)
  val uiState: StateFlow<AvailableTripsUiState> = mutableState.asStateFlow()
  private var isLoading = false

  init {
    loadTrips()
  }

  fun retry() {
    loadTrips()
  }

  private fun loadTrips() {
    if (isLoading) return
    // Set this before launching so repeated calls cannot start concurrent requests.
    isLoading = true
    mutableState.value = AvailableTripsUiState.Loading
    viewModelScope.launch {
      try {
        mutableState.value =
            when (val result = repository.getUpcomingTrips()) {
              is TripResult.Success ->
                  if (result.data.isEmpty()) AvailableTripsUiState.Empty
                  else AvailableTripsUiState.Content(result.data)
              is TripResult.Error -> AvailableTripsUiState.Error(result.error)
            }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (error: Exception) {
        mutableState.value = AvailableTripsUiState.Error(TripError.Unknown)
      } finally {
        isLoading = false
      }
    }
  }
}
