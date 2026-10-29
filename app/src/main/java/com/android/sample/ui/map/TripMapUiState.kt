package com.android.sample.ui.map

// AI assistance: OpenAI Codex.
import com.android.sample.data.repository.TripError
import com.android.sample.model.Location
import com.android.sample.model.Trip

/** Loading, filtering and trip-selection state for the campus map. */
sealed interface TripMapUiState {
  data object Loading : TripMapUiState

  data object Empty : TripMapUiState

  /** The repository has trips, but none serve the selected delivery point. */
  data class NoMatches(val location: Location) : TripMapUiState

  data class Error(val error: TripError) : TripMapUiState

  /** The owning account signed out or changed; its trips must no longer be displayed. */
  data object SessionEnded : TripMapUiState

  /**
   * Keeps list/map feed parity, including unprojectable trips. The renderer must use
   * [CampusMapProjection.project] to omit invalid/offscreen marker groups; Content does not imply
   * visible markers. Groups exact coordinates, without proximity clustering. Part 2 replaces the
   * illustration-only MockMapUiState and its different, fixture-ID grouping.
   */
  data class Content(val trips: List<Trip>, val selectedTripId: String? = null) : TripMapUiState {
    val markerGroups: List<List<Trip>>
      get() =
          trips
              .groupBy { it.handoffLocation.latitude to it.handoffLocation.longitude }
              .values
              .toList()

    val selectedTrip: Trip?
      get() = trips.firstOrNull { it.id == selectedTripId }

    val selectedGroup: List<Trip>
      get() = markerGroups.firstOrNull { group -> group.any { it.id == selectedTripId } }.orEmpty()
  }
}
