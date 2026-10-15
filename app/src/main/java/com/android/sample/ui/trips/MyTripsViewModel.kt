package com.android.sample.ui.trips

// AI assistance: Claude Code.
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.android.sample.data.repository.TripError
import com.android.sample.data.repository.TripRepository
import com.android.sample.data.repository.TripResult
import com.android.sample.model.Trip
import com.android.sample.model.TripStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class MyTripsUiState {
  data object Loading : MyTripsUiState()

  data class Content(val trips: List<Trip>) : MyTripsUiState()

  data object Empty : MyTripsUiState()

  data class Error(val error: TripError) : MyTripsUiState()
}

/**
 * Loads the trips published by [ownerId]. It is created per signed-in account, so an account change
 * discards it together with the previous account's trips; trips owned by anyone else are never
 * displayed, even if a repository returns them.
 */
class MyTripsViewModel(private val repository: TripRepository, private val ownerId: String) :
    ViewModel() {
  private val mutableState = MutableStateFlow<MyTripsUiState>(MyTripsUiState.Loading)
  val uiState: StateFlow<MyTripsUiState> = mutableState.asStateFlow()
  private var isLoading = false

  init {
    loadTrips()
  }

  fun retry() {
    loadTrips()
  }

  private fun loadTrips() {
    if (isLoading) return
    if (ownerId.isBlank()) {
      mutableState.value = MyTripsUiState.Error(TripError.PermissionDenied)
      return
    }
    // Set this before launching so repeated calls cannot start concurrent requests.
    isLoading = true
    mutableState.value = MyTripsUiState.Loading
    viewModelScope.launch {
      try {
        mutableState.value =
            when (val result = repository.getMyTrips()) {
              is TripResult.Success -> {
                val trips = result.data.filter { it.ownerId == ownerId }.sortedWith(DISPLAY_ORDER)
                if (trips.isEmpty()) MyTripsUiState.Empty else MyTripsUiState.Content(trips)
              }
              is TripResult.Error -> MyTripsUiState.Error(result.error)
            }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (error: Exception) {
        mutableState.value = MyTripsUiState.Error(TripError.Unknown)
      } finally {
        isLoading = false
      }
    }
  }

  private companion object {
    /** Active trips come first by departure; closed trips follow, most recent first. */
    val DISPLAY_ORDER: Comparator<Trip> =
        compareBy<Trip> { it.isClosed() }
            .thenComparator { a, b ->
              if (a.isClosed()) b.scheduledAt.compareTo(a.scheduledAt)
              else a.scheduledAt.compareTo(b.scheduledAt)
            }
            .thenBy(Trip::id)

    fun Trip.isClosed(): Boolean = status == TripStatus.COMPLETED || status == TripStatus.CANCELLED
  }
}
