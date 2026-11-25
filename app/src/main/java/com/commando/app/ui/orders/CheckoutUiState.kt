package com.commando.app.ui.orders

// AI assistance: Claude Code (checkout UI, #419).
import com.commando.app.data.repository.PaymentError
import com.commando.app.model.Order
import com.commando.app.model.OrderStatus
import com.commando.app.model.Payment
import com.commando.app.model.PaymentMethod
import com.commando.app.model.PaymentStatus
import com.commando.app.model.Trip

/**
 * What the checkout screen renders. It depends only on persisted models, so the screen is built and
 * tested independently of the checkout ViewModel and navigation.
 */
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
      get() = payment?.takeIf { methodLocked }?.method ?: selectedMethod

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
