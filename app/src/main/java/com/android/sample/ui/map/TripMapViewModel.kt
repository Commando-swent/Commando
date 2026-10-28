package com.android.sample.ui.map

// AI assistance: OpenAI Codex.
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.android.sample.data.repository.TripError
import com.android.sample.data.repository.TripRepository
import com.android.sample.data.repository.TripResult
import com.android.sample.model.Location
import com.android.sample.model.Trip
import com.android.sample.model.TripFeed
import com.android.sample.model.classifyTrips
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch

/**
 * Loads the session's real map trips using the list's repository and shared location selection. The
 * repository owns eligibility and ordering; changing the filter does not trigger a read. Navigation
 * must scope this model to the authenticated graph and call [refresh] on map entry.
 */
class TripMapViewModel(
    private val repository: TripRepository,
    private val selectedPoint: StateFlow<Location?>,
    private val currentUserId: () -> String?,
    sessionChanges: Flow<String?> = emptyFlow(),
    loadImmediately: Boolean = false,
) : ViewModel() {
  private val mutableState = MutableStateFlow<TripMapUiState>(TripMapUiState.Loading)
  val uiState: StateFlow<TripMapUiState> = mutableState.asStateFlow()
  private val sessionId = currentUserId()
  private var sessionEnded = false
  private var loadedTrips: List<Trip>? = null
  private var loadJob: Job? = null
  private var loadVersion = 0

  init {
    viewModelScope.launch { sessionChanges.collect { if (it != sessionId) endSession() } }
    viewModelScope.launch {
      selectedPoint.collect { if (ensureSessionActive()) loadedTrips?.let { trips -> show(trips) } }
    }
    if (loadImmediately) refresh()
  }

  /** Reloads on entry, refresh or retry; only the latest request may publish a result. */
  fun refresh() {
    if (!ensureSessionActive()) return
    val selectedId = (mutableState.value as? TripMapUiState.Content)?.selectedTripId
    val version = ++loadVersion
    loadJob?.cancel()
    loadedTrips = null
    mutableState.value = TripMapUiState.Loading
    loadJob = viewModelScope.launch {
      val result =
          try {
            repository.getUpcomingTrips()
          } catch (cancelled: CancellationException) {
            throw cancelled
          } catch (_: Exception) {
            TripResult.Error(TripError.Unknown)
          }
      // Reject even a repository that swallows cancellation or returns an old session's data.
      currentCoroutineContext().ensureActive()
      if (version != loadVersion || !ensureSessionActive()) return@launch
      when (result) {
        is TripResult.Success -> {
          val trips = result.data.toList()
          loadedTrips = trips
          show(trips, selectedId)
        }
        is TripResult.Error -> mutableState.value = TripMapUiState.Error(result.error)
      }
    }
  }

  /** Selects an actual visible trip ID; unknown IDs and non-content states are ignored. */
  fun selectTrip(id: String) {
    if (!ensureSessionActive()) return
    val content = mutableState.value as? TripMapUiState.Content ?: return
    if (content.trips.any { it.id == id }) mutableState.value = content.copy(selectedTripId = id)
  }

  /** Dismisses the trip card without changing the shared delivery-location filter. */
  fun clearSelection() {
    if (!ensureSessionActive()) return
    val content = mutableState.value as? TripMapUiState.Content ?: return
    mutableState.value = content.copy(selectedTripId = null)
  }

  private fun show(
      trips: List<Trip>,
      selectedId: String? = (mutableState.value as? TripMapUiState.Content)?.selectedTripId,
  ) {
    mutableState.value =
        when (val feed = classifyTrips(trips, selectedPoint.value)) {
          TripFeed.Empty -> TripMapUiState.Empty
          is TripFeed.NoMatches -> TripMapUiState.NoMatches(feed.location)
          is TripFeed.Matches ->
              TripMapUiState.Content(
                  feed.trips,
                  selectedId?.takeIf { id -> feed.trips.any { it.id == id } },
              )
        }
  }

  private fun ensureSessionActive(): Boolean {
    if (sessionEnded || sessionId == null || currentUserId() != sessionId) {
      endSession()
      return false
    }
    return true
  }

  private fun endSession() {
    sessionEnded = true
    loadVersion++
    loadJob?.cancel()
    loadedTrips = null
    mutableState.value = TripMapUiState.SessionEnded
  }
}
