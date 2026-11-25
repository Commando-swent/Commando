package com.commando.app.ui.orders

// AI assistance: Claude Code (#419).
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.commando.app.data.repository.PaymentError
import com.commando.app.model.*
import com.commando.app.ui.orders.OrderPaymentFixture.order
import com.commando.app.ui.orders.OrderPaymentFixture.payment
import com.commando.app.ui.orders.OrderPaymentFixture.trip
import com.commando.app.ui.theme.SampleAppTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-rUS-w412dp-h917dp")
class CheckoutScreenTest {
  @get:Rule val compose = createComposeRule()
  private val events = mutableListOf<String>()

  private fun show(state: CheckoutUiState) {
    val current = mutableStateOf(state)
    compose.setContent {
      SampleAppTheme {
        CheckoutScreen(
            current.value,
            onBack = { events += "back" },
            onSelectMethod = { events += it.name },
            onPay = { events += "pay" },
            onRefresh = { events += "refresh" },
        )
      }
    }
  }

  private fun showUnpaid() = show(CheckoutUiState.Content(order, trip, null))

  @Test
  fun unpaidOrderShowsThePersistedPriceBreakdown() {
    showUnpaid()
    compose.onNodeWithText("Pay your commando").assertIsDisplayed()
    compose.onNodeWithText("Products · 3 items").assertIsDisplayed()
    compose.onNodeWithText("CHF 6.90").assertIsDisplayed()
    compose.onNodeWithText("CHF 1.50").assertIsDisplayed()
    compose.onNodeWithTag(CheckoutTestTags.TOTAL).assert(hasText("CHF 8.40", substring = true))
  }

  @Test
  fun twintIsTheDefaultRadioChoiceAndExplainsAppConfirmation() {
    showUnpaid()
    compose
        .onNodeWithTag(CheckoutTestTags.METHOD_TWINT)
        .assertIsSelected()
        .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
    compose.onNodeWithTag(CheckoutTestTags.METHOD_CARD).assertIsNotSelected()
    compose.onNodeWithText("You’ll confirm the payment in your TWINT app.").assertExists()
  }

  @Test
  fun selectingCardReportsTheChosenMethod() {
    showUnpaid()
    compose.onNodeWithTag(CheckoutTestTags.METHOD_CARD).performClick()
    assertEquals(listOf("CARD"), events)
  }

  @Test
  fun payButtonShowsTheTotalAndSubmitsThePayment() {
    showUnpaid()
    compose.onNodeWithTag(CheckoutTestTags.PRIMARY).assertTextEquals("Pay CHF 8.40").performClick()
    assertEquals(listOf("pay"), events)
  }

  @Test
  fun backLeavesCheckout() {
    showUnpaid()
    compose.onNodeWithTag(CheckoutTestTags.BACK).performClick()
    assertEquals(listOf("back"), events)
  }

  @Test
  fun cardSelectionExplainsThatNoCardDetailsAreRequested() {
    show(CheckoutUiState.Content(order, trip, null, PaymentMethod.CARD))
    compose.onNodeWithTag(CheckoutTestTags.METHOD_CARD).assertIsSelected()
    compose
        .onNodeWithText("Simulated card payment: no card details are requested or stored.")
        .assertExists()
  }

  @Test
  fun processingDisablesEverySubmission() {
    show(CheckoutUiState.Content(order, trip, null, processing = true))
    compose.onNodeWithTag(CheckoutTestTags.PROCESSING).assertExists()
    compose.onNodeWithTag(CheckoutTestTags.PRIMARY).assertIsNotEnabled()
    compose.onNodeWithTag(CheckoutTestTags.METHOD_CARD).assertIsNotEnabled()
  }

  @Test
  fun confirmedPaymentShowsReceiptAndReturnsToTheOrder() {
    show(CheckoutUiState.Content(order, trip, payment(PaymentStatus.CONFIRMED)))
    compose.onNodeWithTag(CheckoutTestTags.CONFIRMED).assert(hasText("Payment confirmed", true))
    compose.onNodeWithText("Total paid").assertExists()
    compose.onNodeWithText("TWINT").assertExists()
    compose.onNodeWithTag(CheckoutTestTags.METHOD_TWINT).assertDoesNotExist()
    compose.onNodeWithTag(CheckoutTestTags.PRIMARY).assertTextEquals("View my order").performClick()
    assertEquals(listOf("back"), events)
  }

  @Test
  fun failedPaymentOffersRetryAndAnotherMethod() {
    show(CheckoutUiState.Content(order, trip, payment(PaymentStatus.FAILED)))
    compose
        .onNodeWithTag(CheckoutTestTags.NOTICE)
        .assert(hasText("Payment couldn’t be completed", true))
    compose.onNodeWithTag(CheckoutTestTags.METHOD_CARD).assertIsEnabled()
    compose.onNodeWithTag(CheckoutTestTags.PRIMARY).assertTextEquals("Try again").performClick()
    assertEquals(listOf("pay"), events)
  }

  @Test
  fun uncertainPaymentReconcilesWithTheLockedMethod() {
    show(CheckoutUiState.Content(order, trip, payment(PaymentStatus.UNCERTAIN, PaymentMethod.CARD)))
    compose
        .onNodeWithTag(CheckoutTestTags.NOTICE)
        .assert(hasText("Payment not confirmed yet", true))
    compose.onNodeWithTag(CheckoutTestTags.METHOD_CARD).assertIsSelected().assertIsNotEnabled()
    compose.onNodeWithText("Payment confirmed").assertDoesNotExist()
    compose
        .onNodeWithTag(CheckoutTestTags.PRIMARY)
        .assertTextEquals("Check payment status")
        .performClick()
    assertEquals(listOf("pay"), events)
  }

  @Test
  fun pendingPaymentIsShownAsInProgress() {
    show(CheckoutUiState.Content(order, trip, payment(PaymentStatus.PENDING)))
    compose.onNodeWithTag(CheckoutTestTags.NOTICE).assert(hasText("Payment in progress", true))
  }

  @Test
  fun lostResponseReadsTheStatusInsteadOfPayingAgain() {
    show(CheckoutUiState.Content(order, trip, null, error = PaymentError.UNKNOWN))
    compose
        .onNodeWithText(
            "We couldn’t reach the payment service. Check the payment status before trying again."
        )
        .assertExists()
    compose
        .onNodeWithTag(CheckoutTestTags.PRIMARY)
        .assertTextEquals("Check payment status")
        .performClick()
    assertEquals(listOf("refresh"), events)
  }

  @Test
  fun rejectedRequestShowsAnErrorAndAllowsRetry() {
    show(CheckoutUiState.Content(order, trip, null, error = PaymentError.INVALID_DATA))
    compose.onNodeWithText("This payment couldn’t be processed. Try again later.").assertExists()
    compose.onNodeWithTag(CheckoutTestTags.PRIMARY).assertTextEquals("Try again")
  }

  @Test
  fun loadingIsAnnouncedToAccessibilityServices() {
    show(CheckoutUiState.Loading)
    compose.onNodeWithContentDescription("Loading payment…").assertExists()
  }

  @Test
  fun loadFailureOffersARetryThatRefreshes() {
    show(CheckoutUiState.LoadFailed)
    compose.onNodeWithTag(CheckoutTestTags.PRIMARY).performClick()
    assertEquals(listOf("refresh"), events)
  }

  @Test
  fun missingOrderExplainsItIsNoLongerAvailableWithoutAction() {
    show(CheckoutUiState.NotFound)
    compose
        .onNodeWithTag(CheckoutTestTags.MESSAGE)
        .assertTextEquals("This order is no longer available.")
    compose.onNodeWithTag(CheckoutTestTags.PRIMARY).assertDoesNotExist()
  }

  @Test
  fun ineligibleOrderExplainsWhoCanPayWithoutAction() {
    show(CheckoutUiState.Ineligible)
    compose
        .onNodeWithTag(CheckoutTestTags.MESSAGE)
        .assertTextEquals(
            "This order can’t be paid. Only an accepted order can be paid by its requester."
        )
    compose.onNodeWithTag(CheckoutTestTags.PRIMARY).assertDoesNotExist()
  }

  @Test
  fun endedSessionAsksToReopenTheOrderWithoutAction() {
    show(CheckoutUiState.SessionEnded)
    compose
        .onNodeWithTag(CheckoutTestTags.MESSAGE)
        .assertTextEquals("Your session changed. Open the order again to continue.")
    compose.onNodeWithTag(CheckoutTestTags.PRIMARY).assertDoesNotExist()
  }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fr-rFR-w412dp-h917dp")
class CheckoutScreenFrenchTest {
  @get:Rule val compose = createComposeRule()

  @Test
  fun checkoutIsLocalizedInFrench() {
    // One item keeps the French singular plural form covered.
    val single = order.copy(items = listOf(OrderItem("milk", "Milk", 100, 1)), commissionCents = 50)
    compose.setContent {
      SampleAppTheme { CheckoutScreen(CheckoutUiState.Content(single, trip, null), {}, {}, {}, {}) }
    }
    compose.onNodeWithText("Payez votre commando").assertExists()
    compose.onNodeWithText("Produits · 1 article").assertExists()
    compose.onNodeWithTag(CheckoutTestTags.PRIMARY).assertTextEquals("Payer CHF 1.50")
  }
}
