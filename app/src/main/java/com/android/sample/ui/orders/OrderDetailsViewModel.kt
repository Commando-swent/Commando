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
import com.android.sample.model.TripStatus
import com.android.sample.model.isActiveForTracking
import com.android.sample.ui.session.SessionGuard
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch

/** Mutually exclusive feedback for the latest order-status operation. */
sealed interface OrderMutationState {
  data object Idle : OrderMutationState

  data object Saving : OrderMutationState

  data object Saved : OrderMutationState

  data class Failed(val error: OrderError) : OrderMutationState
}

sealed class OrderDetailsUiState {
  data object Loading : OrderDetailsUiState()

  data class Content(
      val order: Order,
      val trip: Trip,
      val viewerId: String? = null,
      val mutation: OrderMutationState = OrderMutationState.Idle,
  ) : OrderDetailsUiState() {
    private val isEligibleOwner: Boolean
      get() =
          viewerId != null &&
              viewerId == trip.ownerId &&
              order.tripId == trip.id &&
              order.requesterId != trip.ownerId

    /** Requester-only entry point; the tracking repository independently checks access. */
    val canTrack: Boolean
      get() =
          viewerId != null &&
              viewerId == order.requesterId &&
              order.tripId == trip.id &&
              order.status.isActiveForTracking()

    val canDecide: Boolean
      get() = isEligibleOwner && order.status == OrderStatus.PENDING

    val canMarkReadyForHandoff: Boolean
      get() = isEligibleOwner && order.status == OrderStatus.ACCEPTED

    /**
     * Presentation eligibility; the repository independently authorizes the persisted requester.
     */
    val canConfirmReceipt: Boolean
      get() =
          !viewerId.isNullOrBlank() &&
              viewerId == order.requesterId &&
              order.tripId == trip.id &&
              order.status == OrderStatus.READY_FOR_HANDOFF

    val isSaving: Boolean
      get() = mutation == OrderMutationState.Saving

    /**
     * Presentation eligibility only; [OrderRepository.cancelOrder] enforces persisted ownership.
     */
    val canCancel: Boolean
      get() =
          viewerId != null &&
              viewerId == order.requesterId &&
              order.tripId == trip.id &&
              order.status == OrderStatus.PENDING

    /** Editing is requester-only and additionally requires an open trip. */
    val canEdit: Boolean
      get() = canCancel && trip.status == TripStatus.PUBLISHED && viewerId != trip.ownerId
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
        if (!endSessionIfChanged()) mutableState.value = state
      } finally {
        isLoading = false
      }
    }
  }

  private fun endSessionIfChanged(): Boolean {
    if (session.hasEnded()) {
      mutableState.value = OrderDetailsUiState.SessionEnded
      return true
    }
    return false
  }

  /** Saves an eligible owner's decision; concurrent taps are ignored until it finishes. */
  fun decide(decision: OrderDecision) =
      saveOrder(isAllowed = { it.canDecide }) {
        orderRepository.decideOrder(orderId, decision, it.order.revision)
      }

  /** Marks an accepted order ready, displaying only repository-confirmed status and feedback. */
  fun markReadyForHandoff() =
      saveOrder(isAllowed = { it.canMarkReadyForHandoff }) {
        orderRepository.markReadyForHandoff(orderId)
      }

  /**
   * Submits a confirmed cancellation for the currently eligible requester.
   *
   * The screen asks for confirmation before calling this action. Repeated taps while saving are
   * ignored. Only the repository result updates the displayed order; a recoverable failure keeps
   * the last confirmed content for retry, and a conflict displays the persisted winner. Results
   * arriving after the session ends are discarded.
   */
  fun cancel() = saveOrder(isAllowed = { it.canCancel }) { orderRepository.cancelOrder(orderId) }

  /** Confirms requester receipt, displaying only repository-confirmed status and feedback. */
  fun confirmReceipt() =
      saveOrder(isAllowed = { it.canConfirmReceipt }) { orderRepository.confirmReceipt(orderId) }

  private fun saveOrder(
      isAllowed: (OrderDetailsUiState.Content) -> Boolean,
      save: suspend (OrderDetailsUiState.Content) -> OrderResult<Order>,
  ) {
    if (endSessionIfChanged()) return
    val content = mutableState.value as? OrderDetailsUiState.Content ?: return
    if (!isAllowed(content) || content.isSaving || isLoading) return
    mutableState.value = content.copy(mutation = OrderMutationState.Saving)
    viewModelScope.launch {
      val result =
          try {
            save(content)
          } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            OrderResult.Error(OrderError.Unknown)
          }
      currentCoroutineContext().ensureActive()
      if (!endSessionIfChanged()) mutableState.value = mutationResult(content, result)
    }
  }

  private fun mutationResult(
      content: OrderDetailsUiState.Content,
      result: OrderResult<Order>,
  ): OrderDetailsUiState =
      when (result) {
        is OrderResult.Success ->
            content.copy(order = result.data, mutation = OrderMutationState.Saved)
        is OrderResult.Error ->
            when (val error = result.error) {
              OrderError.NotFound -> OrderDetailsUiState.NotFound
              OrderError.NotAuthenticated,
              OrderError.PermissionDenied -> OrderDetailsUiState.Error.OrderFailure(error)
              is OrderError.AlreadyDecided ->
                  content.copy(
                      order = error.persistedOrder,
                      mutation = OrderMutationState.Failed(error),
                  )
              else -> content.copy(mutation = OrderMutationState.Failed(error))
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
