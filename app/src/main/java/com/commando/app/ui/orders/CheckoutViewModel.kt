package com.commando.app.ui.orders

// AI assistance: Claude Code (checkout UI, #419).
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.commando.app.data.repository.CheckoutPaymentRepository
import com.commando.app.data.repository.OrderError
import com.commando.app.data.repository.OrderRepository
import com.commando.app.data.repository.OrderResult
import com.commando.app.data.repository.PaymentError
import com.commando.app.data.repository.PaymentResult
import com.commando.app.data.repository.TripRepository
import com.commando.app.data.repository.TripResult
import com.commando.app.model.Order
import com.commando.app.model.OrderStatus
import com.commando.app.model.Payment
import com.commando.app.model.PaymentMethod
import com.commando.app.model.PaymentStatus
import com.commando.app.model.Trip
import com.commando.app.ui.session.SessionGuard
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch

sealed interface CheckoutUiState {
  data object Loading : CheckoutUiState

  /**
   * A requester's persisted order, trip and payment. [error] is the latest request failure; it
   * never replaces the persisted [payment], so an uncertain result is never shown as confirmed.
   */
  data class Content(
      val order: Order,
      val trip: Trip,
      val payment: Payment?,
      val selectedMethod: PaymentMethod = payment?.method ?: PaymentMethod.TWINT,
      val processing: Boolean = false,
      val error: PaymentError? = null,
  ) : CheckoutUiState {
    val status: PaymentStatus?
      get() = payment?.status

    /** PENDING and UNCERTAIN retries reconcile the same operation, so they keep its method. */
    val methodLocked: Boolean
      get() = status == PaymentStatus.PENDING || status == PaymentStatus.UNCERTAIN

    val method: PaymentMethod
      get() = if (methodLocked) payment!!.method else selectedMethod

    /** An UNKNOWN failure may have committed server-side; re-read before offering another try. */
    val needsReconciliation: Boolean
      get() = error == PaymentError.UNKNOWN

    val canPay: Boolean
      get() =
          !processing &&
              order.status == OrderStatus.ACCEPTED &&
              status != PaymentStatus.CONFIRMED &&
              !needsReconciliation
  }

  /** The viewer is not the requester, or the order can no longer be paid. */
  data object Ineligible : CheckoutUiState

  data object NotFound : CheckoutUiState

  data object SessionEnded : CheckoutUiState

  data object LoadFailed : CheckoutUiState
}

/** Payment rejections that mean the stored order changed; checkout re-reads it. */
private val STALE_ORDER =
    setOf(PaymentError.INELIGIBLE, PaymentError.PERMISSION_DENIED, PaymentError.NOT_FOUND)

/**
 * Loads the persisted order total and payment for its requester and submits simulated payments.
 *
 * Amount, identity and eligibility are re-derived by the repository; this ViewModel only prevents
 * duplicate submissions while one is in flight and discards results once the session changes.
 */
class CheckoutViewModel(
    private val orderId: String,
    private val orderRepository: OrderRepository,
    private val tripRepository: TripRepository,
    paymentRepositoryFactory: (() -> String?) -> CheckoutPaymentRepository,
    currentUserId: () -> String?,
    sessionChanges: Flow<String?> = emptyFlow(),
) : ViewModel() {
  private val mutableState = MutableStateFlow<CheckoutUiState>(CheckoutUiState.Loading)
  val uiState: StateFlow<CheckoutUiState> = mutableState.asStateFlow()
  private val session = SessionGuard(currentUserId)
  // PaymentRepository contract: the identity becomes null for good once this session ends.
  private val payments = paymentRepositoryFactory {
    if (session.hasEnded()) null else session.initialUserId
  }
  private var loading = false

  init {
    viewModelScope.launch {
      sessionChanges.collect {
        if (session.hasEnded(it)) mutableState.value = CheckoutUiState.SessionEnded
      }
    }
    load()
  }

  /** Re-reads the persisted order and payment; ignored while a payment is processing. */
  fun refresh() {
    if ((mutableState.value as? CheckoutUiState.Content)?.processing == true) return
    load()
  }

  fun selectMethod(method: PaymentMethod) {
    if (endIfChanged()) return
    val content = mutableState.value as? CheckoutUiState.Content ?: return
    if (content.canPay && !content.methodLocked)
        mutableState.value = content.copy(selectedMethod = method, error = null)
  }

  /** Submits or retries once; taps while processing or after confirmation are ignored. */
  fun pay() {
    if (endIfChanged()) return
    val content = mutableState.value as? CheckoutUiState.Content ?: return
    if (!content.canPay) return
    mutableState.value = content.copy(processing = true, error = null)
    viewModelScope.launch {
      val result =
          guarded { payments.processPayment(orderId, content.method) }
              ?: PaymentResult.Error(PaymentError.UNKNOWN)
      currentCoroutineContext().ensureActive()
      if (endIfChanged()) return@launch
      // The stored order moved on (e.g. cancelled): show it instead of offering a dead retry.
      if (result is PaymentResult.Error && result.error in STALE_ORDER) return@launch load()
      mutableState.value =
          when (result) {
            is PaymentResult.Success ->
                content.copy(
                    payment = result.data,
                    selectedMethod = result.data.method,
                    processing = false,
                )
            is PaymentResult.Error ->
                if (result.error == PaymentError.NOT_AUTHENTICATED) CheckoutUiState.SessionEnded
                else content.copy(processing = false, error = result.error)
          }
    }
  }

  private fun load() {
    if (loading || endIfChanged()) return
    loading = true
    mutableState.value = CheckoutUiState.Loading
    viewModelScope.launch {
      try {
        val state = fetch()
        currentCoroutineContext().ensureActive()
        if (!endIfChanged()) mutableState.value = state
      } finally {
        loading = false
      }
    }
  }

  private suspend fun fetch(): CheckoutUiState {
    val order =
        when (val result = guarded { orderRepository.getOrderById(orderId) }) {
          is OrderResult.Success -> result.data
          is OrderResult.Error ->
              return when (result.error) {
                OrderError.NotFound -> CheckoutUiState.NotFound
                OrderError.NotAuthenticated -> CheckoutUiState.SessionEnded
                else -> CheckoutUiState.LoadFailed
              }
          null -> return CheckoutUiState.LoadFailed
        }
    if (order.requesterId != session.initialUserId) return CheckoutUiState.Ineligible
    val trip =
        when (val result = guarded { tripRepository.getTripById(order.tripId) }) {
          is TripResult.Success -> result.data
          else -> return CheckoutUiState.LoadFailed
        }
    val payment =
        when (val result = guarded { payments.getPayment(orderId) }) {
          is PaymentResult.Success -> result.data
          is PaymentResult.Error ->
              return if (result.error == PaymentError.NOT_AUTHENTICATED)
                  CheckoutUiState.SessionEnded
              else CheckoutUiState.LoadFailed
          null -> return CheckoutUiState.LoadFailed
        }
    // A confirmed payment stays viewable as a receipt after the order moves on.
    val payable = order.status == OrderStatus.ACCEPTED || payment?.status == PaymentStatus.CONFIRMED
    return if (payable) CheckoutUiState.Content(order, trip, payment)
    else CheckoutUiState.Ineligible
  }

  /** Converts unexpected repository exceptions to null without swallowing cancellation. */
  private suspend fun <T> guarded(read: suspend () -> T): T? =
      try {
        read()
      } catch (_: Exception) {
        currentCoroutineContext().ensureActive()
        null
      }

  private fun endIfChanged(): Boolean {
    if (!session.hasEnded()) return false
    mutableState.value = CheckoutUiState.SessionEnded
    return true
  }
}
