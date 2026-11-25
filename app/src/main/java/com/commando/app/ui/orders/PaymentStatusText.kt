package com.commando.app.ui.orders

// AI assistance: Claude Code (#419).
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.commando.app.R
import com.commando.app.model.Payment
import com.commando.app.model.PaymentMethod
import com.commando.app.model.PaymentStatus

@Composable
internal fun methodName(method: PaymentMethod): String =
    stringResource(
        if (method == PaymentMethod.TWINT) R.string.payment_twint else R.string.payment_card
    )

/** Persisted payment status for Order Details; a failed read is never shown as unpaid. */
@Composable
internal fun paymentStatusText(payment: Payment?, readFailed: Boolean): String =
    when {
      readFailed -> stringResource(R.string.payment_status_unavailable)
      payment == null -> stringResource(R.string.payment_status_unpaid)
      else ->
          when (payment.status) {
            PaymentStatus.PENDING -> stringResource(R.string.payment_status_pending)
            PaymentStatus.CONFIRMED ->
                stringResource(R.string.payment_status_confirmed, methodName(payment.method))
            PaymentStatus.FAILED -> stringResource(R.string.payment_status_failed)
            PaymentStatus.UNCERTAIN -> stringResource(R.string.payment_status_uncertain)
          }
    }
