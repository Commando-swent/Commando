package com.commando.app.ui.orders

// AI assistance: OpenAI Codex (session guard and offline rating); cancellation (#402): Claude Code.
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.commando.app.data.cache.SessionCache
import com.commando.app.data.repository.OrderError
import com.commando.app.data.repository.OrderRepository
import com.commando.app.data.repository.OrderResult
import com.commando.app.data.repository.PaymentError
import com.commando.app.data.repository.PaymentRepository
import com.commando.app.data.repository.PaymentResult
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
import com.commando.app.model.Payment
import com.commando.app.model.PaymentStatus
import com.commando.app.model.Rating
import com.commando.app.model.Trip
import com.commando.app.model.TripStatus
import com.commando.app.model.isActiveForTracking
import com.commando.app.model.normalizeRatingComment
import com.commando.app.ui.session.SessionGuard
import java.time.Instant
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
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
      /**
       * The destination's only payment read, for the requester or trip owner; null while loading,
       * when payments are not configured, or when the viewer is neither. Refund presentation (#407)
       * reuses this result and reads only the refund, so Order Details shows one payment card.
       */
      val payment: PaymentResult<Payment?>? = null,
      val live: Boolean = true,
      val cachedAt: Instant? = null,
  ) : OrderDetailsUiState() {
    private val isEligibleOwner: Boolean
      get() =
          live &&
              viewerId != null &&
              viewerId == trip.ownerId &&
              order.tripId == trip.id &&
              order.requesterId != trip.ownerId

    /** Requester-only entry point; the tracking repository independently checks access. */
    val canTrack: Boolean
      get() =
          live &&
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
          live &&
              !viewerId.isNullOrBlank() &&
              viewerId == order.requesterId &&
              order.tripId == trip.id &&
              order.status == OrderStatus.READY_FOR_HANDOFF

    /** Presentation eligibility; session validity and the one-time offer are checked separately. */
    val canRate: Boolean
      get() =
          live &&
              !viewerId.isNullOrBlank() &&
              viewerId == order.requesterId &&
              order.tripId == trip.id &&
              order.requesterId != trip.ownerId &&
              order.status == OrderStatus.DELIVERED

    val isSaving: Boolean
      get() = mutation == OrderMutationState.Saving

    /** Requester checkout entry; fails closed until the persisted payment was read. */
    val canPay: Boolean
      get() =
          live &&
              !viewerId.isNullOrBlank() &&
              viewerId == order.requesterId &&
              order.tripId == trip.id &&
              order.status == OrderStatus.ACCEPTED &&
              payment is PaymentResult.Success &&
              payment.data?.status != PaymentStatus.CONFIRMED

    /**
     * Presentation eligibility only; [OrderRepository.cancelOrder] enforces persisted ownership.
     */
    val canCancel: Boolean
      get() =
          live &&
              viewerId != null &&
              viewerId == order.requesterId &&
              order.tripId == trip.id &&
              order.status == OrderStatus.PENDING

    /**
     * Owner-only presentation eligibility for cancelling an accepted order;
     * [OrderRepository.cancelAcceptedOrder] enforces persisted ownership and status.
     */
    val canCancelAccepted: Boolean
      get() = isEligibleOwner && order.status == OrderStatus.ACCEPTED

    /** Editing is requester-only and additionally requires an open trip. */
    val canEdit: Boolean
      get() = canCancel && trip.status == TripStatus.PUBLISHED && viewerId != trip.ownerId
  }

  data object NotFound : OrderDetailsUiState()

  data object OfflineUnavailable : OrderDetailsUiState()

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
    paymentRepositoryFactory: ((() -> String?) -> PaymentRepository)? = null,
    private val cache: SessionCache? = null,
    private val online: StateFlow<Boolean>? = null,
) : ViewModel() {
  private val mutableState = MutableStateFlow<OrderDetailsUiState>(OrderDetailsUiState.Loading)
  val uiState: StateFlow<OrderDetailsUiState> = mutableState.asStateFlow()
  private val mutableRating = MutableStateFlow(RatingDialogState())
  val ratingState: StateFlow<RatingDialogState> = mutableRating.asStateFlow()
  private var ratingOffered = false
  private var ratingUncertain = false
  private val detailsKey = orderDetailsKey(orderId)
  private var readJob: Job? = null
  private val isLoading: Boolean
    get() = readJob?.isActive == true

  private val isOffline: Boolean
    get() = online?.value == false

  private val ratingsConfigured = ratingRepositoryFactory != null
  private var ratingSubmitJob: Job? = null
  private var ratingReadJob: Job? = null
  private var ratingReadGeneration = 0L
  private var paymentReadJob: Job? = null

  private val session = SessionGuard(currentUserId)
  // One session-bound instance, as for ratings, shared by payment status and refunds (#407).
  private val paymentRepository = paymentRepositoryFactory?.invoke {
    if (session.hasEnded()) null else session.initialUserId
  }
  private val ratingRepository: RatingRepository by lazy {
    checkNotNull(ratingRepositoryFactory) { "Rating repository factory is required" }
        .invoke { if (session.hasEnded()) null else session.initialUserId }
  }

  init {
    viewModelScope.launch {
      sessionChanges.collect { userId ->
        if (session.hasEnded(userId)) {
          invalidateRatingRead()
          invalidatePaymentRead()
          mutableState.value = OrderDetailsUiState.SessionEnded
          mutableRating.value = RatingDialogState()
        }
      }
    }
    online?.let {
      viewModelScope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
        it.drop(1).collect { connected -> if (connected) load() else offline() }
      }
    }
    load()
  }

  private fun offline() {
    readJob?.cancel()
    invalidateRatingRead()
    invalidatePaymentRead()
    mutableRating.value = RatingDialogState()
    val current = mutableState.value as? OrderDetailsUiState.Content
    mutableState.value =
        if (endSessionIfChanged()) OrderDetailsUiState.SessionEnded
        else
            cachedDetails()?.copy(mutation = current?.mutation ?: OrderMutationState.Idle)
                ?: OrderDetailsUiState.OfflineUnavailable
  }

  private fun cachedDetails(): OrderDetailsUiState.Content? =
      cache?.get(detailsKey)?.let {
        OrderDetailsUiState.Content(
            it.value.first,
            it.value.second,
            session.initialUserId,
            live = false,
            cachedAt = it.confirmedAt,
        )
      }

  private fun rememberDetails(state: OrderDetailsUiState): OrderDetailsUiState {
    if (cache == null) return state
    return when {
      state is OrderDetailsUiState.Content ->
          if (cache.put(detailsKey, state.order to state.trip)) state
          else OrderDetailsUiState.SessionEnded
      state == OrderDetailsUiState.Error.OrderFailure(OrderError.Unknown) ||
          state == OrderDetailsUiState.Error.TripFailure(TripError.NetworkError) ||
          state == OrderDetailsUiState.Error.TripFailure(TripError.Unknown) ->
          cachedDetails() ?: state
      else -> {
        cache.remove(detailsKey)
        state
      }
    }
  }

  /**
   * Reloads order details and eligible submitted feedback without interrupting the rating dialog.
   */
  fun retry() {
    load()
  }

  private fun load() {
    if (isOffline) {
      offline()
      return
    }
    val content = mutableState.value as? OrderDetailsUiState.Content
    // Only a displayed dialog can hold a refresh to preserve its input.
    if (isLoading || session.ended || content?.isSaving == true) return
    if (content?.live == true && mutableRating.value.visible) return
    // Set before launching so rapid retries cannot start simultaneous loads.
    invalidateRatingRead()
    invalidatePaymentRead()
    mutableState.value = OrderDetailsUiState.Loading
    val job =
        viewModelScope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
          val state = fetchOrder()
          currentCoroutineContext().ensureActive()
          if (endSessionIfChanged()) return@launch
          mutableState.value = rememberDetails(state)
          if ((mutableState.value as? OrderDetailsUiState.Content)?.live == true) {
            loadPayment()
            loadSubmittedRating()
          }
        }
    readJob = job
    job.start()
  }

  private fun endSessionIfChanged(): Boolean {
    if (session.hasEnded()) {
      invalidateRatingRead()
      invalidatePaymentRead()
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

  private fun invalidatePaymentRead() {
    paymentReadJob?.cancel()
    paymentReadJob = null
  }

  /** Payment status must not delay the order actions that were already available on this screen. */
  private fun loadPayment() {
    val content = mutableState.value as? OrderDetailsUiState.Content ?: return
    val repository = paymentRepository ?: return
    val viewer = session.initialUserId
    if (viewer == null || (viewer != content.order.requesterId && viewer != content.trip.ownerId)) {
      return
    }
    if (endSessionIfChanged()) return
    paymentReadJob = viewModelScope.launch {
      val result = readPayment(repository)
      currentCoroutineContext().ensureActive()
      if (endSessionIfChanged()) return@launch
      val current = mutableState.value as? OrderDetailsUiState.Content ?: return@launch
      mutableState.value = current.copy(payment = result)
    }
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
   * Submits a confirmed cancellation: a pending order for its requester, or an accepted order for
   * the trip owner.
   *
   * The screen asks for confirmation before calling this action. Repeated taps while saving are
   * ignored. Only the repository result updates the displayed order; a recoverable failure keeps
   * the last confirmed content for retry, and a conflict displays the persisted winner. Results
   * arriving after the session ends are discarded.
   */
  fun cancel() =
      saveOrder(isAllowed = { it.canCancel || it.canCancelAccepted }) {
        if (it.canCancelAccepted) orderRepository.cancelAcceptedOrder(orderId)
        else orderRepository.cancelOrder(orderId)
      }

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
    if (
        endSessionIfChanged() ||
            isOffline ||
            (mutableState.value as? OrderDetailsUiState.Content)?.live != true
    )
        return
    val state = mutableRating.value
    if (!state.visible || ratingSubmitJob?.isActive == true || !state.valid || state.terminalError)
        return
    invalidateRatingRead()
    val submitting = state.copy(submitting = true, error = null)
    mutableRating.value = submitting
    val job =
        viewModelScope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
          val result =
              try {
                reconcileOrSubmit(NewRating(orderId, state.score, state.comment))
              } catch (_: Exception) {
                currentCoroutineContext().ensureActive()
                RatingResult.Error(RatingError.Unknown)
              }
          currentCoroutineContext().ensureActive()
          finishRatingSubmission(submitting, result)
        }
    ratingSubmitJob = job
    job.start()
  }

  private fun finishRatingSubmission(
      submitting: RatingDialogState,
      result: RatingResult<Rating>,
  ) {
    if (endSessionIfChanged()) return
    when (result) {
      is RatingResult.Success -> {
        invalidateRatingRead()
        publishSubmittedRating(result.data)
      }
      is RatingResult.Error -> {
        // Uncertain-write reconciliation already read and published any existing feedback.
        if (result.error == RatingError.AlreadyRated && !ratingUncertain) loadSubmittedRating()
      }
    }
    // A disconnect closes the dialog. The write still settles so uncertainty can be reconciled.
    if (isOffline || mutableRating.value !== submitting) return
    mutableRating.value =
        when (result) {
          is RatingResult.Success ->
              submitting.copy(visible = false, submitting = false, submitted = true)
          is RatingResult.Error ->
              submitting.copy(
                  visible = result.error != RatingError.AlreadyRated,
                  submitting = false,
                  error = result.error,
              )
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
    if (isOffline || !isAllowed(content) || content.isSaving || isLoading) return
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
        // Saving keeps the screen in Content; payment may have completed while the write was
        // active.
        cache?.remove(detailsKey)
        cache?.remove(historyKey(content.order.requesterId))
        val latest = mutableState.value as? OrderDetailsUiState.Content ?: return@launch
        mutableState.value = mutationResult(latest, result)
        if (result is OrderResult.Success && latest.live) onSuccess(result.data)
        if (!latest.live && !isOffline) load()
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
          OrderDetailsUiState.Content(
              order,
              result.data,
              session.initialUserId,
          )
      is TripResult.Error -> OrderDetailsUiState.Error.TripFailure(result.error)
    }
  }

  /** The repository re-checks the participant filtered by [loadPayment]. */
  private suspend fun readPayment(repository: PaymentRepository): PaymentResult<Payment?> {
    return try {
      repository.getPayment(orderId)
    } catch (_: Exception) {
      currentCoroutineContext().ensureActive()
      PaymentResult.Error(PaymentError.UNKNOWN)
    }
  }
}

internal const val ORDER_DETAILS_CACHE_PREFIX = "order-details/"

internal fun orderDetailsKey(id: String) =
    SessionCache.key<Pair<Order, Trip>>("$ORDER_DETAILS_CACHE_PREFIX$id")
