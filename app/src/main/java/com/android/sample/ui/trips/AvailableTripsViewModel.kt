package com.android.sample.ui.trips

// AI assistance: OpenAI Codex (shared feed classification).
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.android.sample.data.repository.TripError
import com.android.sample.data.repository.TripRepository
import com.android.sample.model.Location
import com.android.sample.model.Trip
import com.android.sample.model.TripFeed
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed class AvailableTripsUiState {
  data object Loading : AvailableTripsUiState()

  data class Content(val trips: List<Trip>) : AvailableTripsUiState()

  data object Empty : AvailableTripsUiState()

  /** The feed has trips, but none serve [location]. */
  data class NoMatches(val location: Location) : AvailableTripsUiState()

  data class Error(val error: TripError) : AvailableTripsUiState()
}

/**
 * Shows the repository's trips that serve [selectedPoint], via
 * [com.android.sample.model.classifyTrips]. The repository owns eligibility and ordering. A new
 * selection is applied to the last loaded trips without a network call; reload and retry keep it
 * and apply it to the fresh trips.
 */
class AvailableTripsViewModel(
    private val repository: TripRepository,
    private val selectedPoint: StateFlow<Location?> = MutableStateFlow(null),
    loadImmediately: Boolean = true,
) : ViewModel() {
  private val mutableState = MutableStateFlow<AvailableTripsUiState>(AvailableTripsUiState.Loading)
  val uiState: StateFlow<AvailableTripsUiState> = mutableState.asStateFlow()
  private val feed =
      FilteredTripFeed(
          viewModelScope,
          repository,
          selectedPoint,
          ::show,
          { mutableState.value = AvailableTripsUiState.Loading },
          { mutableState.value = AvailableTripsUiState.Error(it) },
      )

  init {
    if (loadImmediately) retry()
  }

  /** Reloads or retries using the same cancellation policy as the map. */
  fun retry() = feed.refresh()

  private fun show(feed: TripFeed) {
    mutableState.value =
        when (feed) {
          TripFeed.Empty -> AvailableTripsUiState.Empty
          is TripFeed.NoMatches -> AvailableTripsUiState.NoMatches(feed.location)
          is TripFeed.Matches -> AvailableTripsUiState.Content(feed.trips)
        }
  }
}
