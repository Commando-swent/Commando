package com.android.sample.ui.trips

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.android.sample.data.repository.TripError
import com.android.sample.data.repository.TripRepository
import com.android.sample.data.repository.TripResult
import com.android.sample.model.Location
import com.android.sample.model.Trip
import com.android.sample.model.filterTripsServing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class AvailableTripsUiState {
  data object Loading : AvailableTripsUiState()

  data class Content(val trips: List<Trip>) : AvailableTripsUiState()

  data object Empty : AvailableTripsUiState()

  /** The feed has trips, but none serve [location]. */
  data class NoMatches(val location: Location) : AvailableTripsUiState()

  data class Error(val error: TripError) : AvailableTripsUiState()
}

/**
 * Shows the repository's trips that serve [selectedPoint], via `filterTripsServing`. The repository
 * owns eligibility and ordering. A new selection is applied to the last loaded trips without a
 * network call; reload and retry keep it and apply it to the fresh trips.
 */
class AvailableTripsViewModel(
    private val repository: TripRepository,
    private val selectedPoint: StateFlow<Location?> = MutableStateFlow(null),
    loadImmediately: Boolean = true,
) : ViewModel() {
  private val mutableState = MutableStateFlow<AvailableTripsUiState>(AvailableTripsUiState.Loading)
  val uiState: StateFlow<AvailableTripsUiState> = mutableState.asStateFlow()
  private var isLoading = false
  // The last successful feed; null while loading or after an error, so a new selection then waits.
  private var loadedTrips: List<Trip>? = null

  init {
    viewModelScope.launch { selectedPoint.collect { loadedTrips?.let(::show) } }
    if (loadImmediately) loadTrips()
  }

  fun retry() {
    loadTrips()
  }

  private fun loadTrips() {
    if (isLoading) return
    // Set this before launching so repeated calls cannot start concurrent requests.
    isLoading = true
    loadedTrips = null
    mutableState.value = AvailableTripsUiState.Loading
    viewModelScope.launch {
      try {
        when (val result = repository.getUpcomingTrips()) {
          is TripResult.Success -> {
            loadedTrips = result.data
            show(result.data)
          }
          is TripResult.Error -> mutableState.value = AvailableTripsUiState.Error(result.error)
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

  private fun show(trips: List<Trip>) {
    val point = selectedPoint.value
    val matches = filterTripsServing(trips, point)
    mutableState.value =
        when {
          trips.isEmpty() -> AvailableTripsUiState.Empty
          point != null && matches.isEmpty() -> AvailableTripsUiState.NoMatches(point)
          else -> AvailableTripsUiState.Content(matches)
        }
  }
}
