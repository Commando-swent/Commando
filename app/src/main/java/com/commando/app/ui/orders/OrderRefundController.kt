package com.commando.app.ui.orders

import com.commando.app.data.repository.*
import com.commando.app.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Persisted refund feedback is independent of the canonical order mutation result. */
sealed interface RefundUiState {
  data object Loading : RefundUiState

  data object Hidden : RefundUiState

  data class Unavailable(val error: PaymentError) : RefundUiState

  data class Content(
      val payment: Payment,
      val refund: Refund?,
      val processing: Boolean = false,
      val error: PaymentError? = null,
  ) : RefundUiState
}

/**
 * Coordinates refund reads/preflight for one Order Details destination and one permanent session.
 */
internal class OrderRefundController(
    private val orderId: String,
    private val repository: PaymentRepository,
    private val currentUserId: () -> String?,
    private val scope: CoroutineScope,
) {
  private val owner = currentUserId()
  private val mutableState = MutableStateFlow<RefundUiState>(RefundUiState.Loading)
  val state = mutableState.asStateFlow()
  private var read: Job? = null
  private var ended = false
  private var processing = false

  fun clear() {
    ended = true
    read?.cancel()
    mutableState.value = RefundUiState.Hidden
  }

  fun load() {
    if (processing || !active()) return
    read?.cancel()
    mutableState.value = RefundUiState.Loading
    read = scope.launch {
      val loaded = safely { snapshot() }
      currentCoroutineContext().ensureActive()
      if (active()) mutableState.value = loaded
    }
  }

  /**
   * Only confirmed refunds or authoritatively unpaid orders may reach the existing cancellation.
   */
  suspend fun beforeCancellation(): Boolean {
    if (processing || !active()) return false
    processing = true
    read?.cancel()
    mutableState.value = RefundUiState.Loading
    return try {
      val result = safely {
        when (val loaded = snapshot()) {
          is RefundUiState.Content ->
              if (loaded.payment.status != PaymentStatus.CONFIRMED)
                  loaded.copy(error = PaymentError.NOT_CAPTURED)
              else if (!active()) RefundUiState.Unavailable(PaymentError.NOT_AUTHENTICATED)
              else {
                mutableState.value = loaded.copy(processing = true)
                when (val refund = repository.refundForCancellation(orderId)) {
                  is PaymentResult.Success -> loaded.copy(refund = refund.data)
                  is PaymentResult.Error -> loaded.copy(error = refund.error)
                }
              }
          else -> loaded
        }
      }
      currentCoroutineContext().ensureActive()
      if (!active()) false
      else {
        mutableState.value = result
        result == RefundUiState.Hidden ||
            (result is RefundUiState.Content &&
                result.error == null &&
                result.refund?.status == RefundStatus.CONFIRMED)
      }
    } finally {
      processing = false
      val current = mutableState.value
      if (current is RefundUiState.Content && current.processing)
          mutableState.value = current.copy(processing = false)
    }
  }

  private suspend fun snapshot(): RefundUiState {
    val result = repository.getPayment(orderId)
    currentCoroutineContext().ensureActive()
    if (!active()) return RefundUiState.Hidden
    return when (result) {
      is PaymentResult.Error -> RefundUiState.Unavailable(result.error)
      is PaymentResult.Success -> {
        val payment = result.data
        if (payment == null || payment.status == PaymentStatus.FAILED) RefundUiState.Hidden
        else
            when (val refund = repository.getRefund(orderId)) {
              is PaymentResult.Error -> RefundUiState.Unavailable(refund.error)
              is PaymentResult.Success -> RefundUiState.Content(payment, refund.data)
            }
      }
    }
  }

  private suspend fun safely(action: suspend () -> RefundUiState): RefundUiState =
      try {
        action()
      } catch (failure: CancellationException) {
        throw failure
      } catch (_: Exception) {
        RefundUiState.Unavailable(PaymentError.UNKNOWN)
      }

  private fun active(): Boolean {
    if (owner == null || currentUserId() != owner) clear()
    return !ended
  }
}
