package com.android.sample.ui.trips

// AI assistance: Claude Code.
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.android.sample.data.repository.OrderError
import com.android.sample.data.repository.OrderRepository
import com.android.sample.data.repository.OrderResult
import com.commando.app.model.TripEarnings
import com.commando.app.model.tripEarnings
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What the owner's earnings card of Trip Details shows. */
sealed class TripEarningsUiState {
  /** The trip's orders are being loaded. */
  data object Loading : TripEarningsUiState()

  /** The commission of the trip's delivered orders. */
  data class Content(val earnings: TripEarnings) : TripEarningsUiState()

  /** The orders could not be loaded; [TripEarningsViewModel.refresh] retries. */
  data class Error(val error: OrderError) : TripEarningsUiState()
}

/**
 * Computes the trip owner's earnings on [tripId] from the owner's orders-by-trip query, so the
 * repository and the Firestore rules still decide who may read them. Only the latest load may
 * publish, so a late reply never replaces a newer one.
 *
 * @param loadImmediately false when navigation starts the first load itself, e.g. on resume.
 */
class TripEarningsViewModel(
    private val orderRepository: OrderRepository,
    private val tripId: String,
    loadImmediately: Boolean = true,
) : ViewModel() {
  private val mutableState = MutableStateFlow<TripEarningsUiState>(TripEarningsUiState.Loading)

  /** Current earnings state, read by Trip Details. */
  val uiState: StateFlow<TripEarningsUiState> = mutableState.asStateFlow()
  private var loadJob: Job? = null
  private var latestLoad = 0

  init {
    if (loadImmediately) refresh()
  }

  /** Reloads the earnings, e.g. on retry or when the owner comes back to Trip Details. */
  fun refresh() {
    loadJob?.cancel()
    // A repository may still return after cancellation; the counter discards superseded results.
    val load = ++latestLoad
    mutableState.value = TripEarningsUiState.Loading
    loadJob = viewModelScope.launch {
      val state = fetch()
      if (load == latestLoad) mutableState.value = state
    }
  }

  private suspend fun fetch(): TripEarningsUiState =
      try {
        when (val result = orderRepository.getOrdersForTrip(tripId)) {
          is OrderResult.Success -> TripEarningsUiState.Content(tripEarnings(tripId, result.data))
          is OrderResult.Error -> TripEarningsUiState.Error(result.error)
        }
      } catch (_: Exception) {
        // Rethrows if this load was cancelled; any other failure becomes a retryable error.
        currentCoroutineContext().ensureActive()
        TripEarningsUiState.Error(OrderError.Unknown)
      }
}
