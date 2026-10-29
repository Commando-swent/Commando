package com.android.sample.ui.orders

// AI assistance: Claude Code.
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.android.sample.data.repository.OrderError
import com.android.sample.data.repository.OrderRepository
import com.android.sample.data.repository.OrderResult
import com.android.sample.model.Order
import com.android.sample.model.itemCount
import com.android.sample.model.totalCents
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** One incoming-order row, built only from fields stored on the order itself. */
data class IncomingOrderRow(val order: Order) {
  /** Total quantity across products; see [Order.itemCount]. */
  val itemCount: Long
    get() = order.itemCount

  /** Products plus commission, in CHF cents; see [Order.totalCents]. */
  val totalCents: Long
    get() = order.totalCents
}

/** What the incoming-orders list of one trip shows. */
sealed class IncomingOrdersUiState {
  /** A load is in progress; previous rows are no longer shown. */
  data object Loading : IncomingOrdersUiState()

  /** The trip's orders, newest first, as returned by the repository. */
  data class Content(val rows: List<IncomingOrderRow>) : IncomingOrdersUiState()

  /** The trip has no orders yet. */
  data object Empty : IncomingOrdersUiState()

  /** The load failed with [error]; [IncomingOrdersViewModel.refresh] retries it. */
  data class Error(val error: OrderError) : IncomingOrdersUiState()
}

/**
 * Lists the orders of [tripId] for its owner. The repository alone decides which orders it returns.
 * Each load clears the previous rows at once and only the latest load may publish, so a late reply
 * (for example from before an account change) never reappears.
 *
 * @param loadImmediately false when navigation starts the first load itself, e.g. on resume.
 */
class IncomingOrdersViewModel(
    private val orderRepository: OrderRepository,
    private val tripId: String,
    loadImmediately: Boolean = true,
) : ViewModel() {
  private val mutableState = MutableStateFlow<IncomingOrdersUiState>(IncomingOrdersUiState.Loading)
  /** Current state of the list, read by the screen. */
  val uiState: StateFlow<IncomingOrdersUiState> = mutableState.asStateFlow()
  private var loadJob: Job? = null
  private var latestLoad = 0

  init {
    // Navigation may own the first load through ON_RESUME, which also refreshes after Details.
    if (loadImmediately) refresh()
  }

  /** Reloads the list, e.g. on retry or when returning from Order Details after a decision. */
  fun refresh() {
    loadJob?.cancel()
    // A repository may still return after cancellation; the counter discards superseded results.
    val load = ++latestLoad
    mutableState.value = IncomingOrdersUiState.Loading
    loadJob = viewModelScope.launch {
      val state = fetch()
      if (load == latestLoad) mutableState.value = state
    }
  }

  private suspend fun fetch(): IncomingOrdersUiState =
      try {
        when (val result = orderRepository.getOrdersForTrip(tripId)) {
          is OrderResult.Error -> IncomingOrdersUiState.Error(result.error)
          is OrderResult.Success -> {
            val rows = result.data.map(::IncomingOrderRow)
            if (rows.isEmpty()) IncomingOrdersUiState.Empty else IncomingOrdersUiState.Content(rows)
          }
        }
      } catch (_: Exception) {
        // Rethrows if this load was cancelled; any other failure becomes a retryable error.
        currentCoroutineContext().ensureActive()
        IncomingOrdersUiState.Error(OrderError.Unknown)
      }
}
