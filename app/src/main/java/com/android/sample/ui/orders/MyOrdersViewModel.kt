package com.android.sample.ui.orders

// AI assistance: Claude (Anthropic).
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.android.sample.data.repository.OrderError
import com.android.sample.data.repository.OrderRepository
import com.android.sample.data.repository.OrderResult
import com.android.sample.data.repository.TripRepository
import com.android.sample.data.repository.TripResult
import com.android.sample.model.Order
import com.android.sample.model.Trip
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** One My Orders row: the stored order and its trip, or null when the trip could not be read. */
data class MyOrderRow(val order: Order, val trip: Trip?) {
  /** Total quantity across products, e.g. two apples and one milk count as three items. */
  val itemCount: Long
    get() = order.items.sumOf { it.quantity.toLong() }
}

sealed class MyOrdersUiState {
  data object Loading : MyOrdersUiState()

  data class Content(val rows: List<MyOrderRow>) : MyOrdersUiState()

  data object Empty : MyOrdersUiState()

  data class Error(val error: OrderError) : MyOrdersUiState()
}

/**
 * Lists the supplied requester's orders from the shared repository. Changing the requester clears
 * the previous rows at once, and only the latest load may publish its result.
 */
class MyOrdersViewModel(
    private val orderRepository: OrderRepository,
    private val tripRepository: TripRepository,
    private var requesterId: String,
) : ViewModel() {
  private val mutableState = MutableStateFlow<MyOrdersUiState>(MyOrdersUiState.Loading)
  val uiState: StateFlow<MyOrdersUiState> = mutableState.asStateFlow()
  private var loadJob: Job? = null
  private var latestLoad = 0

  init {
    load()
  }

  fun setRequester(requesterId: String) {
    if (requesterId == this.requesterId) return
    this.requesterId = requesterId
    load()
  }

  fun retry() {
    load()
  }

  private fun load() {
    loadJob?.cancel()
    // A repository may still return after cancellation; the counter discards superseded results.
    val load = ++latestLoad
    val requester = requesterId
    mutableState.value = MyOrdersUiState.Loading
    loadJob = viewModelScope.launch {
      val state = fetch(requester)
      if (load == latestLoad) mutableState.value = state
    }
  }

  private suspend fun fetch(requester: String): MyOrdersUiState =
      try {
        when (val result = orderRepository.getMyOrders()) {
          is OrderResult.Error -> MyOrdersUiState.Error(result.error)
          is OrderResult.Success -> {
            // The repository scopes by session; this also guards against a session/route mismatch.
            val orders = result.data.filter { it.requesterId == requester }
            if (orders.isEmpty()) MyOrdersUiState.Empty
            else {
              // ponytail: one sequential trip lookup per distinct trip; batch if lists grow.
              val trips = orders.map { it.tripId }.distinct().associateWith { findTrip(it) }
              MyOrdersUiState.Content(orders.map { MyOrderRow(it, trips[it.tripId]) })
            }
          }
        }
      } catch (_: Exception) {
        // Rethrows if this load was cancelled; a stray cancellation (e.g. timeout) is an error.
        currentCoroutineContext().ensureActive()
        MyOrdersUiState.Error(OrderError.Unknown)
      }

  /** A missing or unreadable trip keeps the order visible without its trip summary. */
  private suspend fun findTrip(tripId: String): Trip? =
      try {
        (tripRepository.getTripById(tripId) as? TripResult.Success)?.data
      } catch (_: Exception) {
        currentCoroutineContext().ensureActive()
        null
      }
}
