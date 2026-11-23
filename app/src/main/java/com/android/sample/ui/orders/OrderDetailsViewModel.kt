package com.android.sample.ui.orders

// AI assistance: OpenAI Codex (shared session guard).
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.android.sample.ui.session.SessionGuard
import com.commando.app.data.repository.OrderError
import com.commando.app.data.repository.OrderRepository
import com.commando.app.data.repository.OrderResult
import com.commando.app.data.repository.RatingError
import com.commando.app.data.repository.RatingRepository
import com.commando.app.data.repository.RatingResult
import com.commando.app.data.repository.TripError
import com.commando.app.data.repository.TripRepository
import com.commando.app.data.repository.TripResult
import com.commando.app.model.MAX_RATING_COMMENT_LENGTH
import com.commando.app.model.NewRating
import com.commando.app.model.Order
import com.commando.app.model.OrderDecision
import com.commando.app.model.OrderStatus
import com.commando.app.model.Rating
import com.commando.app.model.Trip
import com.commando.app.model.TripStatus
import com.commando.app.model.isActiveForTracking
import com.commando.app.model.normalizeRatingComment
import kotlinx.coroutines.Job
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

internal val RATING_SCORE_RANGE = 1..5

/** Transient receipt feedback, independent of the submitted rating loaded with order details. */
data class RatingDialogState(
    val visible: Boolean = false,
    /** Selected score in 1..5; 0 means no selection. */
    val score: Int = 0,
    val comment: String = "",
    val submitting: Boolean = false,
    val error: RatingError? = null,
    val submitted: Boolean = false,
) {
  /** Matches repository normalization: blank comments are absent before length validation. */
  val commentTooLong: Boolean
    get() = (normalizeRatingComment(comment)?.length ?: 0) > MAX_RATING_COMMENT_LENGTH

  /** Only unexpected failures can be retried within this receipt flow. */
  val terminalError: Boolean
    get() = error != null && error != RatingError.Unknown

  /** Input validity is independent of submission progress and repository errors. */
  val valid: Boolean
    get() = score in RATING_SCORE_RANGE && !commentTooLong
}

sealed class OrderDetailsUiState {
  data object Loading : OrderDetailsUiState()

  data class Content(
      val order: Order,
      val trip: Trip,
      val viewerId: String? = null,
      val mutation: OrderMutationState = OrderMutationState.Idle,
      /** Repository-confirmed requester feedback; absent while loading or unavailable. */
      val submittedRating: Rating? = null,
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

    /** The repository independently authorizes these persisted participants. */
    val isChatParticipant: Boolean
      get() =
          viewerId != null &&
              order.tripId == trip.id &&
              (viewerId == order.requesterId || viewerId == trip.ownerId)

    val canOpenChat: Boolean
      get() = isChatParticipant && order.status != OrderStatus.PENDING

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

    /** Presentation eligibility; session validity and the one-time offer are checked separately. */
    val canRate: Boolean
      get() =
          !viewerId.isNullOrBlank() &&
              viewerId == order.requesterId &&
              order.tripId == trip.id &&
              order.requesterId != trip.ownerId &&
              order.status == OrderStatus.DELIVERED

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

/**
 * Loads an authorized order before its trip; repositories own all access decisions.
 *
 * [ratingRepositoryFactory] is invoked lazily when ratings are first needed, using an identity
 * permanently invalidated by an observed session change. Existing order-only callers may omit it;
 * rating operations require a configured factory, supplied by the production navigation route.
 */
class OrderDetailsViewModel(
    private val orderId: String,
    private val orderRepository: OrderRepository,
    private val tripRepository: TripRepository,
    private val currentUserId: () -> String? = { null },
    sessionChanges: Flow<String?> = emptyFlow(),
    ratingRepositoryFactory: ((() -> String?) -> RatingRepository)? = null,
) : ViewModel() {
  private val mutableState = MutableStateFlow<OrderDetailsUiState>(OrderDetailsUiState.Loading)
  val uiState: StateFlow<OrderDetailsUiState> = mutableState.asStateFlow()
  private val mutableRating = MutableStateFlow(RatingDialogState())
  val ratingState: StateFlow<RatingDialogState> = mutableRating.asStateFlow()
  private var ratingOffered = false
  private var ratingUncertain = false
  private var isLoading = false
  private val ratingsConfigured = ratingRepositoryFactory != null
  private var ratingReadJob: Job? = null
  private var ratingReadGeneration = 0L

  private val session = SessionGuard(currentUserId)
  private val ratingRepository: RatingRepository by lazy {
    checkNotNull(ratingRepositoryFactory) { "Rating repository factory is required" }
        .invoke { if (session.hasEnded()) null else session.initialUserId }
  }

  init {
    viewModelScope.launch {
      sessionChanges.collect { userId ->
        if (session.hasEnded(userId)) {
          invalidateRatingRead()
          mutableState.value = OrderDetailsUiState.SessionEnded
          mutableRating.value = RatingDialogState()
        }
      }
    }
    load()
  }

  /**
   * Reloads order details and eligible submitted feedback without interrupting the rating dialog.
   */
  fun retry() {
    load()
  }

  private fun load() {
    // Preserve modal input during reloads; the independent session observer still clears it.
    if (
        isLoading ||
            mutableRating.value.visible ||
            session.ended ||
            (mutableState.value as? OrderDetailsUiState.Content)?.isSaving == true
    )
        return
    // Set before launching so rapid retries cannot start simultaneous loads.
    isLoading = true
    invalidateRatingRead()
    mutableState.value = OrderDetailsUiState.Loading
    viewModelScope.launch {
      try {
        val state = fetchOrder()
        // A repository may return even after the destination's coroutine was cancelled.
        currentCoroutineContext().ensureActive()
        if (!endSessionIfChanged()) {
          mutableState.value = state
          loadSubmittedRating()
        }
      } finally {
        isLoading = false
      }
    }
  }

  private fun endSessionIfChanged(): Boolean {
    if (session.hasEnded()) {
      invalidateRatingRead()
      mutableState.value = OrderDetailsUiState.SessionEnded
      mutableRating.value = RatingDialogState()
      return true
    }
    return false
  }

  private fun invalidateRatingRead() {
    ratingReadGeneration++
    ratingReadJob?.cancel()
    ratingReadJob = null
  }

  private fun loadSubmittedRating() {
    val content = mutableState.value as? OrderDetailsUiState.Content ?: return
    if (
        !ratingsConfigured ||
            !content.canRate ||
            content.order.id != orderId ||
            content.submittedRating != null ||
            ratingReadJob?.isActive == true ||
            endSessionIfChanged()
    )
        return
    val generation = ratingReadGeneration
    ratingReadJob = viewModelScope.launch {
      if (endSessionIfChanged() || generation != ratingReadGeneration) return@launch
      val result =
          try {
            ratingRepository.getRatingForOrder(orderId)
          } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            RatingResult.Error(RatingError.Unknown)
          }
      currentCoroutineContext().ensureActive()
      if (endSessionIfChanged() || generation != ratingReadGeneration) return@launch
      if (result is RatingResult.Success) publishSubmittedRating(result.data)
    }
  }

  private fun publishSubmittedRating(rating: Rating?) {
    val content = mutableState.value as? OrderDetailsUiState.Content ?: return
    if (content.canRate && content.order.id == orderId && rating?.orderId == orderId)
        mutableState.value = content.copy(submittedRating = rating)
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

  /**
   * Confirms receipt using repository-confirmed status, offering rating once after eligible
   * success.
   */
  fun confirmReceipt() =
      saveOrder(
          isAllowed = { it.canConfirmReceipt },
          onSuccess = { order ->
            val content = mutableState.value as? OrderDetailsUiState.Content
            if (!ratingOffered && order.id == orderId && content?.canRate == true) {
              ratingOffered = true
              mutableRating.value = RatingDialogState(visible = true)
            }
          },
      ) {
        orderRepository.confirmReceipt(orderId)
      }

  /** Selects an editable score in 1..5; invalid scores cannot clear the current selection. */
  fun selectScore(score: Int) {
    if (endSessionIfChanged()) return
    val state = mutableRating.value
    if (state.visible && !state.submitting && !state.terminalError && score in RATING_SCORE_RANGE)
        mutableRating.value = state.copy(score = score, error = null)
  }

  /** Preserves editable comment text verbatim; validation follows repository normalization. */
  fun updateComment(comment: String) {
    if (endSessionIfChanged()) return
    val state = mutableRating.value
    if (state.visible && !state.submitting && !state.terminalError)
        mutableRating.value = state.copy(comment = comment, error = null)
  }

  /** Dismisses an idle dialog without changing the already confirmed delivery. */
  fun dismissRating() {
    if (endSessionIfChanged()) return
    if (!mutableRating.value.submitting)
        mutableRating.value = mutableRating.value.copy(visible = false)
  }

  /**
   * Retries only Unknown failures, reconciling uncertain writes before attempting another write.
   */
  fun submitRating() {
    if (endSessionIfChanged()) return
    val state = mutableRating.value
    if (!state.visible || state.submitting || !state.valid || state.terminalError) return
    invalidateRatingRead()
    mutableRating.value = state.copy(submitting = true, error = null)
    viewModelScope.launch {
      val result =
          try {
            reconcileOrSubmit(NewRating(orderId, state.score, state.comment))
          } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            RatingResult.Error(RatingError.Unknown)
          }
      currentCoroutineContext().ensureActive()
      if (endSessionIfChanged()) return@launch
      if (result is RatingResult.Success) {
        invalidateRatingRead()
        publishSubmittedRating(result.data)
      } else if (
          result is RatingResult.Error &&
              result.error == RatingError.AlreadyRated &&
              !ratingUncertain
      ) {
        // Uncertain-write reconciliation already read and published any existing feedback.
        loadSubmittedRating()
      }
      mutableRating.value =
          when (result) {
            is RatingResult.Success -> state.copy(visible = false, submitted = true, error = null)
            is RatingResult.Error ->
                state.copy(visible = result.error != RatingError.AlreadyRated, error = result.error)
          }
    }
  }

  private suspend fun reconcileOrSubmit(input: NewRating): RatingResult<Rating> {
    if (ratingUncertain) {
      when (val existing = ratingRepository.getRatingForOrder(orderId)) {
        is RatingResult.Error -> return existing
        is RatingResult.Success ->
            existing.data?.let { saved ->
              currentCoroutineContext().ensureActive()
              if (endSessionIfChanged()) return RatingResult.Error(RatingError.NotAuthenticated)
              publishSubmittedRating(saved)
              return if (
                  saved.orderId == input.orderId &&
                      saved.score == input.score &&
                      saved.comment == normalizeRatingComment(input.comment)
              )
                  RatingResult.Success(saved)
              else RatingResult.Error(RatingError.AlreadyRated)
            }
      }
      currentCoroutineContext().ensureActive()
      if (endSessionIfChanged()) return RatingResult.Error(RatingError.NotAuthenticated)
    }
    // An exception may arrive after the server commits, so assume uncertainty before submitting.
    ratingUncertain = true
    return ratingRepository.submitRating(input).also {
      ratingUncertain = it == RatingResult.Error(RatingError.Unknown)
    }
  }

  private fun saveOrder(
      isAllowed: (OrderDetailsUiState.Content) -> Boolean,
      onSuccess: (Order) -> Unit = {},
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
      if (!endSessionIfChanged()) {
        mutableState.value = mutationResult(content, result)
        if (result is OrderResult.Success) onSuccess(result.data)
      }
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
