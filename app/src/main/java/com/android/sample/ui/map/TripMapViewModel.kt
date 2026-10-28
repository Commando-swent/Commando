package com.android.sample.ui.map

// AI assistance: OpenAI Codex.
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.android.sample.data.repository.TripRepository
import com.android.sample.model.Location
import com.android.sample.model.TripFeed
import com.android.sample.ui.session.SessionGuard
import com.android.sample.ui.trips.FilteredTripFeed
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch

/**
 * Maps the shared trip feed to map selection state. Scope to the authenticated graph and refresh on
 * entry. The explicit session signal also clears data before navigation disposes that graph.
 */
class TripMapViewModel(
    repository: TripRepository,
    selectedPoint: StateFlow<Location?>,
    currentUserId: () -> String?,
    sessionChanges: Flow<String?> = emptyFlow(),
    loadImmediately: Boolean = false,
) : ViewModel() {
  private val mutableState = MutableStateFlow<TripMapUiState>(TripMapUiState.Loading)
  val uiState: StateFlow<TripMapUiState> = mutableState.asStateFlow()
  private val session = SessionGuard(currentUserId)
  private var selectedTripId: String? = null
  private val feed =
      FilteredTripFeed(
          viewModelScope,
          repository,
          selectedPoint,
          ::show,
          { mutableState.value = TripMapUiState.Loading },
          { if (!endSessionIfChanged()) mutableState.value = TripMapUiState.Error(it) },
      )

  init {
    viewModelScope.launch { sessionChanges.collect { if (session.hasEnded(it)) endSession() } }
    if (loadImmediately) refresh()
  }

  /** Replaces an in-flight refresh without losing the selection during Loading. */
  fun refresh() {
    if (!endSessionIfChanged()) feed.refresh()
  }

  /** Selects a visible real ID; unknown IDs and non-content states are ignored. */
  fun selectTrip(id: String) {
    if (endSessionIfChanged()) return
    val content = mutableState.value as? TripMapUiState.Content ?: return
    if (content.trips.any { it.id == id }) {
      selectedTripId = id
      mutableState.value = content.copy(selectedTripId = id)
    }
  }

  /** Dismisses the card, including a selection retained during an in-flight refresh. */
  fun clearSelection() {
    if (endSessionIfChanged()) return
    selectedTripId = null
    (mutableState.value as? TripMapUiState.Content)?.let {
      mutableState.value = it.copy(selectedTripId = null)
    }
  }

  private fun show(result: TripFeed) {
    if (endSessionIfChanged()) return
    val trips = (result as? TripFeed.Matches)?.trips.orEmpty()
    selectedTripId = selectedTripId?.takeIf { id -> trips.any { it.id == id } }
    mutableState.value =
        when (result) {
          TripFeed.Empty -> TripMapUiState.Empty
          is TripFeed.NoMatches -> TripMapUiState.NoMatches(result.location)
          is TripFeed.Matches -> TripMapUiState.Content(result.trips, selectedTripId)
        }
  }

  private fun endSessionIfChanged(): Boolean {
    val ended = session.initialUserId == null || session.hasEnded()
    if (ended) endSession()
    return ended
  }

  private fun endSession() {
    selectedTripId = null
    feed.clear()
    mutableState.value = TripMapUiState.SessionEnded
  }
}
