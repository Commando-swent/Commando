package com.android.sample.ui.orders

// AI assistance: OpenAI Codex (shared session guard).
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.android.sample.data.repository.OrderError
import com.android.sample.data.repository.OrderRepository
import com.android.sample.data.repository.OrderResult
import com.android.sample.data.repository.TripError
import com.android.sample.data.repository.TripRepository
import com.android.sample.data.repository.TripResult
import com.android.sample.model.Order
import com.android.sample.model.OrderDecision
import com.android.sample.model.OrderStatus
import com.android.sample.model.Trip
import com.android.sample.ui.session.SessionGuard
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch

sealed class OrderDetailsUiState {
  data object Loading : OrderDetailsUiState()

  data class Content(
      val order: Order,
      val trip: Trip,
      val viewerId: String? = null,
      val isSaving: Boolean = false,
      val decisionError: OrderError? = null,
      val decisionSaved: Boolean = false,
  ) : OrderDetailsUiState() {
    val canDecide: Boolean
      get() =
          viewerId != null &&
              viewerId == trip.ownerId &&
              order.tripId == trip.id &&
              order.requesterId != trip.ownerId &&
              order.status == OrderStatus.PENDING
  }

  data object NotFound : OrderDetailsUiState()

  data object SessionEnded : OrderDetailsUiState()

  sealed class Error : OrderDetailsUiState() {
    data class OrderFailure(val error: OrderError) : Error()

    data class TripFailure(val error: TripError) : Error()
  }
}

/** Loads an authorized order before its trip; repositories own all access decisions. */
class OrderDetailsViewModel(
    private val orderId: String,
    private val orderRepository: OrderRepository,
    private val tripRepository: TripRepository,
    private val currentUserId: () -> String? = { null },
    sessionChanges: Flow<String?> = emptyFlow(),
) : ViewModel() {
  private val mutableState = MutableStateFlow<OrderDetailsUiState>(OrderDetailsUiState.Loading)
  val uiState: StateFlow<OrderDetailsUiState> = mutableState.asStateFlow()
  private var isLoading = false

  private val session = SessionGuard(currentUserId)

  init {
    viewModelScope.launch {
      sessionChanges.collect { userId ->
        if (session.hasEnded(userId)) {
          mutableState.value = OrderDetailsUiState.SessionEnded
        }
      }
    }
    load()
  }

  fun retry() {
    load()
  }

  private fun load() {
    if (
        isLoading ||
            session.ended ||
            (mutableState.value as? OrderDetailsUiState.Content)?.isSaving == true
    )
        return
    // Set before launching so rapid retries cannot start simultaneous loads.
    isLoading = true
    mutableState.value = OrderDetailsUiState.Loading
    viewModelScope.launch {
      try {
        val state = fetchOrder()
        // A repository may return even after the destination's coroutine was cancelled.
        currentCoroutineContext().ensureActive()
        if (sessionIsValid()) mutableState.value = state
      } finally {
        isLoading = false
      }
    }
  }

  private fun sessionIsValid(): Boolean {
    if (session.hasEnded()) {
      mutableState.value = OrderDetailsUiState.SessionEnded
      return false
    }
    return true
  }

  fun decide(decision: OrderDecision) {
    if (!sessionIsValid()) return
    val content = mutableState.value as? OrderDetailsUiState.Content ?: return
    if (!content.canDecide || content.isSaving || isLoading) return
    mutableState.value = content.copy(isSaving = true, decisionError = null, decisionSaved = false)
    viewModelScope.launch {
      val result =
          try {
            orderRepository.decideOrder(orderId, decision)
          } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            OrderResult.Error(OrderError.Unknown)
          }
      currentCoroutineContext().ensureActive()
      if (!sessionIsValid()) return@launch
      mutableState.value =
          when (result) {
            is OrderResult.Success -> content.copy(order = result.data, decisionSaved = true)
            is OrderResult.Error ->
                when (val error = result.error) {
                  OrderError.NotFound -> OrderDetailsUiState.NotFound
                  OrderError.NotAuthenticated,
                  OrderError.PermissionDenied -> OrderDetailsUiState.Error.OrderFailure(error)
                  is OrderError.AlreadyDecided ->
                      content.copy(order = error.persistedOrder, decisionError = error)
                  else -> content.copy(decisionError = error)
                }
          }
    }
  }

  private suspend fun fetchOrder(): OrderDetailsUiState {
    val result =
        try {
          orderRepository.getOrderById(orderId)
        } catch (_: Exception) {
          currentCoroutineContext().ensureActive()
          return OrderDetailsUiState.Error.OrderFailure(OrderError.Unknown)
        }
    currentCoroutineContext().ensureActive()
    return when (result) {
      is OrderResult.Success -> fetchTrip(result.data)
      is OrderResult.Error ->
          if (result.error == OrderError.NotFound) OrderDetailsUiState.NotFound
          else OrderDetailsUiState.Error.OrderFailure(result.error)
    }
  }

  private suspend fun fetchTrip(order: Order): OrderDetailsUiState {
    val result =
        try {
          tripRepository.getTripById(order.tripId)
        } catch (_: Exception) {
          currentCoroutineContext().ensureActive()
          return OrderDetailsUiState.Error.TripFailure(TripError.Unknown)
        }
    currentCoroutineContext().ensureActive()
    return when (result) {
      is TripResult.Success ->
          OrderDetailsUiState.Content(order, result.data, session.initialUserId)
      is TripResult.Error -> OrderDetailsUiState.Error.TripFailure(result.error)
    }
  }
}
