package com.commando.app.ui.orders

// AI assistance: Claude (Anthropic).
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.commando.app.data.cache.SessionCache
import com.commando.app.data.repository.OrderError
import com.commando.app.data.repository.OrderRepository
import com.commando.app.data.repository.OrderResult
import com.commando.app.data.repository.TripRepository
import com.commando.app.data.repository.TripResult
import com.commando.app.model.Order
import com.commando.app.model.Trip
import com.commando.app.model.itemCount
import java.time.Instant
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/** One My Orders row: the stored order and its trip, or null when the trip could not be read. */
data class MyOrderRow(val order: Order, val trip: Trip?) {
  /** Total quantity across products; see [Order.itemCount]. */
  val itemCount: Long
    get() = order.itemCount
}

sealed class MyOrdersUiState {
  data object Loading : MyOrdersUiState()

  data class Content(val rows: List<MyOrderRow>, val confirmedAt: Instant? = null) :
      MyOrdersUiState()

  data object OfflineUnavailable : MyOrdersUiState()

  data object Empty : MyOrdersUiState()

  data class Error(val error: OrderError) : MyOrdersUiState()

  /** Rows of another requester are never shown; they are replaced by Loading until reloaded. */
  fun forRequester(requesterId: String): MyOrdersUiState =
      if (this is Content && rows.any { it.order.requesterId != requesterId }) Loading else this
}

/**
 * Lists the supplied requester's orders from the shared repository. Changing the requester clears
 * the previous rows at once, and only the latest load may publish its result.
 */
class MyOrdersViewModel(
    private val orderRepository: OrderRepository,
    private val tripRepository: TripRepository,
    private var requesterId: String,
    loadImmediately: Boolean = true,
    private val cache: SessionCache? = null,
    private val online: StateFlow<Boolean>? = null,
) : ViewModel() {
  private val mutableState = MutableStateFlow<MyOrdersUiState>(MyOrdersUiState.Loading)
  val uiState: StateFlow<MyOrdersUiState> = mutableState.asStateFlow()
  private var loadJob: Job? = null
  private var latestLoad = 0

  init {
    // Navigation may own the first load through ON_RESUME, as well as refreshes on return.
    online?.let {
      viewModelScope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
        it.drop(1).collect { load() }
      }
    }
    if (loadImmediately) load()
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
    val key = historyKey(requester)
    fun cached(): MyOrdersUiState.Content? =
        cache?.get(key)?.let { MyOrdersUiState.Content(it.value, it.confirmedAt) }
    if (online?.value == false) {
      mutableState.value = cached() ?: MyOrdersUiState.OfflineUnavailable
      return
    }
    // Keep the last confirmation visible while a potentially slow reconnect is in flight.
    mutableState.value = cached() ?: MyOrdersUiState.Loading
    loadJob = viewModelScope.launch {
      val state = fetch(requester)
      if (load != latestLoad) return@launch
      mutableState.value =
          when {
            cache == null -> state
            online?.value == false -> cached() ?: MyOrdersUiState.OfflineUnavailable
            state is MyOrdersUiState.Error && state.error == OrderError.Unknown -> cached() ?: state
            state is MyOrdersUiState.Error -> {
              cache.remove(key)
              state
            }
            else -> {
              val rows = (state as? MyOrdersUiState.Content)?.rows.orEmpty()
              if (cache.put(key, rows)) state else MyOrdersUiState.Loading
            }
          }
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

internal fun historyKey(uid: String) = SessionCache.key<List<MyOrderRow>>("my-orders/$uid")
