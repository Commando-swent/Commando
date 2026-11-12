package com.android.sample.ui.map

// AI assistance: OpenAI Codex.
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.android.sample.data.repository.*
import com.android.sample.model.*
import com.android.sample.ui.session.SessionGuard
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** State for one selected order; null positions never stand in for a repository failure. */
sealed interface OrderTrackingState {
  data object Loading : OrderTrackingState

  data object Unavailable : OrderTrackingState

  data class Position(val fix: CommandoPosition?) : OrderTrackingState
}

/**
 * Authorizes one selected order, then observes only its commando. Retry replaces the subscription.
 */
class OrderTrackingViewModel(
    private val orderId: String,
    private val orders: OrderRepository,
    private val trips: TripRepository,
    private val positions: CommandoPositionRepository,
    currentUserId: () -> String?,
    sessionChanges: Flow<String?>,
) : ViewModel() {
  private val session = SessionGuard(currentUserId)
  private val mutableState = MutableStateFlow<OrderTrackingState>(OrderTrackingState.Loading)
  val uiState = mutableState.asStateFlow()
  private var observer: Job? = null

  init {
    viewModelScope.launch { sessionChanges.collect { if (session.hasEnded(it)) stop() } }
  }

  /** Rechecks persisted ownership/status before starting or retrying tracking. */
  fun start() {
    stop()
    if (session.initialUserId == null || session.hasEnded()) return
    observer = viewModelScope.launch {
      try {
        val order = (orders.getOrderById(orderId) as? OrderResult.Success)?.data
        check(
            order?.id == orderId &&
                order.requesterId == session.initialUserId &&
                order.status.isActiveForTracking()
        )
        val trip = (trips.getTripById(order.tripId) as? TripResult.Success)?.data
        check(
            trip?.id == order.tripId &&
                !trip.ownerId.isBlank() &&
                trip.ownerId != session.initialUserId
        )
        positions.observe(trip.ownerId, orderId).collect {
          currentCoroutineContext().ensureActive()
          if (!session.hasEnded()) mutableState.value = OrderTrackingState.Position(it)
        }
        if (!session.hasEnded()) mutableState.value = OrderTrackingState.Unavailable
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        if (!session.hasEnded()) mutableState.value = OrderTrackingState.Unavailable
      }
    }
  }

  /** Immediately clears coordinates on backgrounding, navigation or account change. */
  fun stop() {
    observer?.cancel()
    observer = null
    mutableState.value = OrderTrackingState.Loading
  }
}
