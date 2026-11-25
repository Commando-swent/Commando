package com.commando.app.ui.orders

// AI assistance: Claude Code (checkout UI following Figma #283, #419).
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.commando.app.R
import com.commando.app.data.repository.PaymentError
import com.commando.app.model.PaymentMethod
import com.commando.app.model.PaymentStatus
import com.commando.app.model.itemCount
import com.commando.app.model.totalCents

object CheckoutTestTags {
  const val BACK = "checkout_back"
  const val TOTAL = "checkout_total"
  const val METHOD_TWINT = "checkout_method_twint"
  const val METHOD_CARD = "checkout_method_card"
  const val PRIMARY = "checkout_primary"
  const val PROCESSING = "checkout_processing"
  const val CONFIRMED = "checkout_confirmed"
  const val NOTICE = "checkout_notice"
  const val MESSAGE = "checkout_message"
  const val LOADING = "checkout_loading"

  fun method(method: PaymentMethod) =
      if (method == PaymentMethod.TWINT) METHOD_TWINT else METHOD_CARD
}

/**
 * Simulated requester checkout. The amount shown is the persisted order total; the caller owns the
 * repository calls, and [onBack] also leaves the confirmed receipt.
 */
@Composable
fun CheckoutScreen(
    state: CheckoutUiState,
    onBack: () -> Unit,
    onSelectMethod: (PaymentMethod) -> Unit,
    onPay: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
  Column(
      modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding()
  ) {
    CheckoutTopBar(onBack)
    when (state) {
      is CheckoutUiState.Content ->
          if (state.status == PaymentStatus.CONFIRMED) ConfirmedPayment(state, onBack)
          else PaymentForm(state, onSelectMethod, onPay, onRefresh)
      CheckoutUiState.Loading -> CheckoutLoading()
      CheckoutUiState.LoadFailed -> CheckoutMessage(R.string.payment_load_failed, onRefresh)
      CheckoutUiState.NotFound -> CheckoutMessage(R.string.payment_not_found)
      CheckoutUiState.Ineligible -> CheckoutMessage(R.string.payment_ineligible_message)
      CheckoutUiState.SessionEnded -> CheckoutMessage(R.string.payment_session_ended)
    }
  }
}

@Composable
private fun CheckoutTopBar(onBack: () -> Unit) {
  val colors = MaterialTheme.colorScheme
  Row(
      Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 24.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    IconButton(
        onClick = onBack,
        modifier =
            Modifier.size(48.dp)
                .background(colors.surfaceContainer, CircleShape)
                .testTag(CheckoutTestTags.BACK),
    ) {
      Icon(
          painterResource(R.drawable.ic_trip_arrow_left),
          stringResource(R.string.nav_back),
          Modifier.size(24.dp),
          tint = colors.onSurface,
      )
    }
    Text(
        stringResource(R.string.payment_title),
        Modifier.semantics { heading() },
        style = MaterialTheme.typography.titleMedium.copy(fontSize = 20.sp),
    )
  }
}

@Composable
private fun CheckoutLoading() {
  val loading = stringResource(R.string.payment_loading)
  Box(
      Modifier.fillMaxSize().testTag(CheckoutTestTags.LOADING).semantics {
        contentDescription = loading
      },
      contentAlignment = Alignment.Center,
  ) {
    CircularProgressIndicator()
  }
}

@Composable
private fun ColumnScope.PaymentForm(
    state: CheckoutUiState.Content,
    onSelectMethod: (PaymentMethod) -> Unit,
    onPay: () -> Unit,
    onRefresh: () -> Unit,
) {
  val colors = MaterialTheme.colorScheme
  Column(
      Modifier.weight(1f)
          .fillMaxWidth()
          .verticalScroll(rememberScrollState())
          .padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 16.dp),
      verticalArrangement = Arrangement.spacedBy(20.dp),
  ) {
    val notice = paymentNotice(state)
    if (notice != null)
        PaymentNotice(stringResource(notice.title), stringResource(notice.message), notice.isError)
    else {
      Text(
          stringResource(R.string.payment_heading),
          Modifier.semantics { heading() },
          style = MaterialTheme.typography.titleMedium.copy(fontSize = 24.sp, lineHeight = 35.sp),
      )
      Text(
          stringResource(R.string.payment_intro),
          color = colors.onSurfaceVariant,
          style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
      )
    }
    PaymentCard {
      Text(
          state.trip.store.name,
          style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
      )
      SecondaryText(
          pluralStringResource(
              R.plurals.payment_items,
              state.order.itemCount.toInt(),
              state.order.itemCount.toInt(),
          )
      )
      SecondaryText(stringResource(R.string.payment_handoff, state.trip.handoffLocation.name))
    }
    PaymentBreakdown(state.order, paid = false)
    Column(
        Modifier.selectableGroup(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      SectionLabel(stringResource(R.string.payment_method))
      PaymentMethod.entries.forEach { method ->
        PaymentMethodRow(
            method,
            selected = state.method == method,
            enabled = state.canPay && !state.methodLocked,
            onClick = { onSelectMethod(method) },
        )
      }
    }
    SecondaryText(
        stringResource(
            if (state.status == PaymentStatus.FAILED) R.string.payment_recovery
            else R.string.payment_note
        )
    )
  }
  val total = money(state.order.totalCents)
  PaymentBottomBar(
      label =
          when {
            state.needsReconciliation || state.methodLocked ->
                stringResource(R.string.payment_check_status)
            state.status == PaymentStatus.FAILED || state.error != null ->
                stringResource(R.string.payment_try_again)
            else -> stringResource(R.string.payment_pay, total)
          },
      // "Check payment status" only re-reads the stored payment; it never submits one.
      onClick = if (state.needsReconciliation || state.methodLocked) onRefresh else onPay,
      enabled = state.canPay || state.needsReconciliation,
      processing = state.processing,
      footer =
          stringResource(
              if (state.method == PaymentMethod.TWINT) R.string.payment_footer_twint
              else R.string.payment_footer_card
          ),
  )
}

/** The latest outcome needing attention; [isError] colours it as a failure. */
private data class Notice(
    @StringRes val title: Int,
    @StringRes val message: Int,
    val isError: Boolean,
)

private fun paymentNotice(state: CheckoutUiState.Content): Notice? =
    when {
      state.error == PaymentError.UNKNOWN ->
          Notice(R.string.payment_uncertain_title, R.string.payment_connection_message, false)
      state.error != null ->
          Notice(R.string.payment_failed_title, R.string.payment_request_failed, true)
      state.status == PaymentStatus.FAILED ->
          Notice(R.string.payment_failed_title, R.string.payment_failed_message, true)
      state.status == PaymentStatus.PENDING ->
          Notice(R.string.payment_pending_title, R.string.payment_pending_message, false)
      state.status == PaymentStatus.UNCERTAIN ->
          Notice(R.string.payment_uncertain_title, R.string.payment_uncertain_message, false)
      else -> null
    }

@Composable
private fun ColumnScope.ConfirmedPayment(state: CheckoutUiState.Content, onDone: () -> Unit) {
  val colors = MaterialTheme.colorScheme
  val total = money(state.order.totalCents)
  Column(
      Modifier.weight(1f)
          .fillMaxWidth()
          .verticalScroll(rememberScrollState())
          .padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 16.dp),
      verticalArrangement = Arrangement.spacedBy(24.dp),
  ) {
    Column(
        Modifier.fillMaxWidth().testTag(CheckoutTestTags.CONFIRMED).semantics(
            mergeDescendants = true
        ) {
          liveRegion = LiveRegionMode.Polite
        },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Box(
          Modifier.size(80.dp).background(colors.primaryContainer, CircleShape),
          contentAlignment = Alignment.Center,
      ) {
        Text(
            "✓",
            Modifier.clearAndSetSemantics {},
            color = colors.primary,
            fontSize = 36.sp,
        )
      }
      Text(
          stringResource(R.string.payment_confirmed_title),
          Modifier.semantics { heading() },
          style = MaterialTheme.typography.titleMedium.copy(fontSize = 24.sp),
      )
      Text(
          total,
          color = colors.primary,
          style = MaterialTheme.typography.titleMedium.copy(fontSize = 36.sp, lineHeight = 52.sp),
      )
      Text(
          stringResource(R.string.payment_confirmed_message),
          color = colors.onSurfaceVariant,
          textAlign = TextAlign.Center,
          style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
      )
    }
    PaymentBreakdown(state.order, paid = true)
    PaymentCard {
      AmountRow(stringResource(R.string.payment_paid_with), methodName(state.method))
      SecondaryText("${state.trip.store.name} · ${state.trip.handoffLocation.name}")
    }
  }
  PaymentBottomBar(
      label = stringResource(R.string.payment_view_order),
      onClick = onDone,
      enabled = true,
      processing = false,
      footer = stringResource(R.string.payment_confirmed_footer),
  )
}

@Composable
private fun CheckoutMessage(message: Int, onRetry: (() -> Unit)? = null) {
  Column(
      Modifier.fillMaxSize().padding(24.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
  ) {
    Text(
        stringResource(message),
        Modifier.fillMaxWidth().testTag(CheckoutTestTags.MESSAGE).semantics {
          liveRegion = LiveRegionMode.Polite
        },
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.titleMedium,
    )
    if (onRetry != null)
        Button(onRetry, Modifier.testTag(CheckoutTestTags.PRIMARY)) {
          Text(stringResource(R.string.payment_retry))
        }
  }
}
