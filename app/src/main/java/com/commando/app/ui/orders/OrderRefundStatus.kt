package com.commando.app.ui.orders

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.android.sample.R
import com.android.sample.ui.orders.money
import com.commando.app.data.repository.PaymentError
import com.commando.app.model.PaymentStatus
import com.commando.app.model.RefundStatus

/** Language-independent selectors for persisted refund feedback and status reconciliation. */
object OrderRefundTestTags {
  const val STATUS = "order_refund_status"
  const val REFRESH = "order_refund_refresh"
  const val PROGRESS = "order_refund_progress"
}

/** Displays persisted outcomes; [onRefresh] reloads status without issuing another refund. */
@Composable
fun OrderRefundStatus(state: RefundUiState, onRefresh: () -> Unit, modifier: Modifier = Modifier) {
  val busy = state == RefundUiState.Loading || (state is RefundUiState.Content && state.processing)
  val message =
      when (state) {
        RefundUiState.Loading -> R.string.refund_loading
        is RefundUiState.Unavailable -> R.string.refund_unavailable
        is RefundUiState.Content -> state.message()
        RefundUiState.Hidden -> return
      }
  Card(modifier.fillMaxWidth().testTag(OrderRefundTestTags.STATUS)) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text(stringResource(R.string.refund_title), Modifier.semantics { heading() })
      val amount = (state as? RefundUiState.Content)?.payment?.amountCents
      Text(
          stringResource(message, amount?.let(::money).orEmpty()),
          Modifier.semantics { liveRegion = LiveRegionMode.Polite },
      )
      if (busy)
          LinearProgressIndicator(Modifier.fillMaxWidth().testTag(OrderRefundTestTags.PROGRESS))
      if (state !is RefundUiState.Unavailable || state.error == PaymentError.UNKNOWN)
          TextButton(
              onRefresh,
              enabled = !busy,
              modifier = Modifier.testTag(OrderRefundTestTags.REFRESH),
          ) {
            Text(stringResource(R.string.refund_refresh))
          }
    }
  }
}

private fun RefundUiState.Content.message(): Int =
    when {
      processing -> R.string.refund_processing
      error == PaymentError.NOT_CAPTURED -> R.string.refund_payment_unconfirmed
      error == PaymentError.INELIGIBLE -> R.string.order_status_conflict
      error != null -> R.string.refund_unavailable
      payment.status != PaymentStatus.CONFIRMED -> R.string.refund_payment_unconfirmed
      refund == null -> R.string.refund_not_requested
      refund.status == RefundStatus.CONFIRMED -> R.string.refund_confirmed
      refund.status == RefundStatus.PENDING -> R.string.refund_pending
      refund.status == RefundStatus.FAILED -> R.string.refund_failed
      else -> R.string.refund_uncertain
    }
