package com.android.sample.ui.trips

// AI assistance: OpenAI Codex (shared feed classification); Claude Code (search criteria).
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.android.sample.data.repository.TripError
import com.android.sample.data.repository.TripRepository
import com.android.sample.model.CriteriaProblem
import com.android.sample.model.Location
import com.android.sample.model.Trip
import com.android.sample.model.TripFeed
import com.android.sample.model.TripSearchCriteria
import java.time.Clock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed class AvailableTripsUiState {
  data object Loading : AvailableTripsUiState()

  data class Content(val trips: List<Trip>) : AvailableTripsUiState()

  data object Empty : AvailableTripsUiState()

  /**
   * The feed has trips, but none match. [location] is set when only the delivery point filtered
   * them out, and null when store or departure criteria are active too.
   */
  data class NoMatches(val location: Location?) : AvailableTripsUiState()

  /** The search criteria cannot match any upcoming trip (invalid or expired range). */
  data class InvalidCriteria(val problem: CriteriaProblem) : AvailableTripsUiState()

  data class Error(val error: TripError) : AvailableTripsUiState()
}

/**
 * Shows the repository's trips that serve [selectedPoint] and match [criteria], via
 * [com.android.sample.model.classifyTripSearch]. The repository owns eligibility; [criteria] may
 * re-sort. A new selection or criteria is applied to the last loaded trips without a network call;
 * reload and retry keep both and apply them to the fresh trips.
 */
class AvailableTripsViewModel(
    private val repository: TripRepository,
    private val selectedPoint: StateFlow<Location?> = MutableStateFlow(null),
    loadImmediately: Boolean = true,
    criteria: StateFlow<TripSearchCriteria> = MutableStateFlow(TripSearchCriteria()),
    clock: Clock = Clock.systemDefaultZone(),
) : ViewModel() {
  private val mutableState = MutableStateFlow<AvailableTripsUiState>(AvailableTripsUiState.Loading)
  val uiState: StateFlow<AvailableTripsUiState> = mutableState.asStateFlow()
  private val feed =
      FilteredTripFeed(
          viewModelScope,
          repository,
          selectedPoint,
          criteria,
          clock,
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
          is TripFeed.InvalidCriteria -> AvailableTripsUiState.InvalidCriteria(feed.problem)
        }
  }
}
