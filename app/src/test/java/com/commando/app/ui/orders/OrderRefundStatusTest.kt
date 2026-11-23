package com.commando.app.ui.orders

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.android.sample.R
import com.android.sample.ui.orders.money
import com.android.sample.ui.theme.SampleAppTheme
import com.commando.app.data.repository.PaymentError
import com.commando.app.model.PaymentStatus
import com.commando.app.model.RefundStatus
import com.commando.app.testing.PaymentFixtures
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OrderRefundStatusTest {
  @get:Rule val compose = createComposeRule()

  @Test
  fun displaysAllPersistedOutcomesAndDisablesRefreshWhileProcessing() {
    val state = mutableStateOf<RefundUiState>(RefundUiState.Loading)
    var refreshes = 0
    compose.setContent { SampleAppTheme { OrderRefundStatus(state.value, { refreshes++ }) } }
    compose.onNodeWithTag(OrderRefundTestTags.PROGRESS).assertExists()
    compose.onNodeWithTag(OrderRefundTestTags.REFRESH).assertIsNotEnabled()
    for ((status, message) in
        mapOf(
            RefundStatus.PENDING to R.string.refund_pending,
            RefundStatus.FAILED to R.string.refund_failed,
            RefundStatus.UNCERTAIN to R.string.refund_uncertain,
            RefundStatus.CONFIRMED to R.string.refund_confirmed,
            null to R.string.refund_not_requested,
        )) {
      compose.runOnIdle {
        state.value =
            RefundUiState.Content(PaymentFixtures.captured, status?.let(PaymentFixtures::refund))
      }
      val text =
          ApplicationProvider.getApplicationContext<Context>()
              .getString(message, money(PaymentFixtures.captured.amountCents))
      compose.onNodeWithText(text).assertExists()
      compose.onNodeWithTag(OrderRefundTestTags.REFRESH).assertIsEnabled()
    }
    compose.onNodeWithTag(OrderRefundTestTags.REFRESH).performClick()
    compose.runOnIdle {
      assertEquals(1, refreshes)
      state.value = RefundUiState.Content(PaymentFixtures.captured, null, processing = true)
    }
    compose.onNodeWithTag(OrderRefundTestTags.REFRESH).assertIsNotEnabled()
    compose.runOnIdle { state.value = RefundUiState.Hidden }
    compose.onNodeWithTag(OrderRefundTestTags.STATUS).assertDoesNotExist()
  }

  @Test
  @Config(qualifiers = "fr")
  fun errorsUseFrenchResourcesAndUnauthorizedContentOffersNoAction() {
    val state =
        mutableStateOf<RefundUiState>(RefundUiState.Unavailable(PaymentError.PERMISSION_DENIED))
    compose.setContent { SampleAppTheme { OrderRefundStatus(state.value, {}) } }
    compose
        .onNodeWithText(
            ApplicationProvider.getApplicationContext<Context>()
                .getString(R.string.refund_unavailable)
        )
        .assertExists()
    compose.onNodeWithTag(OrderRefundTestTags.REFRESH).assertDoesNotExist()
    compose.runOnIdle { state.value = RefundUiState.Unavailable(PaymentError.UNKNOWN) }
    compose.onNodeWithTag(OrderRefundTestTags.REFRESH).assertIsEnabled()
    for ((error, message) in
        mapOf(
            null to R.string.refund_payment_unconfirmed,
            PaymentError.UNKNOWN to R.string.refund_unavailable,
            PaymentError.NOT_CAPTURED to R.string.refund_payment_unconfirmed,
            PaymentError.INELIGIBLE to R.string.order_status_conflict,
        )) {
      compose.runOnIdle {
        state.value =
            RefundUiState.Content(
                PaymentFixtures.captured.copy(status = PaymentStatus.PENDING),
                null,
                error = error,
            )
      }
      compose
          .onNodeWithText(ApplicationProvider.getApplicationContext<Context>().getString(message))
          .assertExists()
    }
  }
}
