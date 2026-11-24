package com.commando.app.ui.orders

// AI assistance: Claude Code (#419).
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.commando.app.data.repository.FakeOrderRepository
import com.commando.app.data.repository.FakePaymentRepository
import com.commando.app.data.repository.FakeTripRepository
import com.commando.app.data.repository.PaymentError
import com.commando.app.data.repository.PaymentRepository
import com.commando.app.data.repository.PaymentResult
import com.commando.app.model.*
import com.commando.app.ui.orders.CheckoutFixture.order
import com.commando.app.ui.orders.CheckoutFixture.payment
import com.commando.app.ui.orders.CheckoutFixture.trip
import com.commando.app.ui.theme.SampleAppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Order Details reads the persisted payment through the shared fake payment repository. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OrderDetailsPaymentTest {
  private var user: String? = order.requesterId

  @Before fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

  @After fun tearDown() = Dispatchers.resetMain()

  private fun viewModel(payments: PaymentRepository?, stored: Order = order) =
      OrderDetailsViewModel(
          order.id,
          FakeOrderRepository({ user }, listOf(stored)),
          FakeTripRepository(trip.ownerId, initialTrips = listOf(trip)),
          { user },
          paymentRepositoryFactory = payments?.let { repository -> { repository } },
      )

  private fun fake(vararg payments: Payment, stored: Order = order) =
      FakePaymentRepository(
          { user },
          listOf(stored),
          mapOf(trip.id to trip.ownerId),
          payments.toList(),
      )

  private fun OrderDetailsViewModel.content() = uiState.value as OrderDetailsUiState.Content

  @Test
  fun unpaidAcceptedOrderOffersCheckoutToItsRequester() = runTest {
    val vm = viewModel(fake())
    advanceUntilIdle()
    assertEquals(PaymentResult.Success(null), vm.content().payment)
    assertTrue(vm.content().canPay)
  }

  @Test
  fun persistedStatusIsReloadedAndConfirmedPaymentClosesCheckout() = runTest {
    for (status in PaymentStatus.entries) {
      val vm = viewModel(fake(payment(status)))
      advanceUntilIdle()
      assertEquals(PaymentResult.Success(payment(status)), vm.content().payment)
      assertEquals(status.name, status != PaymentStatus.CONFIRMED, vm.content().canPay)
    }
  }

  @Test
  fun failedReadsFailClosedAndPaymentsAreOptional() = runTest {
    val throwing =
        object : PaymentRepository by fake() {
          override suspend fun getPayment(orderId: String) = throw IllegalStateException("offline")
        }
    val failed = viewModel(throwing)
    advanceUntilIdle()
    assertEquals(PaymentResult.Error(PaymentError.UNKNOWN), failed.content().payment)
    assertFalse(failed.content().canPay)

    val unconfigured = viewModel(null)
    advanceUntilIdle()
    assertNull(unconfigured.content().payment)
    assertFalse(unconfigured.content().canPay)
  }

  @Test
  fun ownerSeesStatusButCannotPayAndPendingOrdersCannotBePaid() = runTest {
    user = trip.ownerId
    val owner =
        OrderDetailsViewModel(
            order.id,
            FakeOrderRepository(
                { user },
                listOf(order),
                tripRepository = FakeTripRepository(trip.ownerId, initialTrips = listOf(trip)),
            ),
            FakeTripRepository(trip.ownerId, initialTrips = listOf(trip)),
            { user },
            paymentRepositoryFactory = { fake() },
        )
    advanceUntilIdle()
    assertEquals(PaymentResult.Success(null), owner.content().payment)
    assertFalse(owner.content().canPay)

    user = order.requesterId
    val pending = order.copy(status = OrderStatus.PENDING)
    val vm = viewModel(fake(stored = pending), pending)
    advanceUntilIdle()
    assertFalse(vm.content().canPay)
  }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-rUS-w412dp-h917dp")
class OrderDetailsPaymentScreenTest {
  @get:Rule val compose = createComposeRule()
  private val events = mutableListOf<String>()

  @Test
  fun orderDetailsShowsPersistedStatusAndOffersCheckoutOnlyWhenEligible() {
    val content = mutableStateOf(OrderDetailsUiState.Content(order, trip, order.requesterId))
    compose.setContent {
      SampleAppTheme { OrderDetailsScreen(content.value, {}, {}, onPay = { events += "pay" }) }
    }
    compose.onNodeWithTag(OrderDetailsTestTags.PAYMENT_STATUS).assertDoesNotExist()
    content.value = content.value.copy(payment = PaymentResult.Success(null))
    compose.onNodeWithTag(OrderDetailsTestTags.PAYMENT_STATUS).assertTextEquals("Not paid yet")
    compose
        .onNodeWithTag(OrderDetailsTestTags.PAY)
        .performScrollTo()
        .assertTextEquals("Pay CHF 8.40")
        .performClick()
    assertEquals(listOf("pay"), events)

    content.value =
        content.value.copy(payment = PaymentResult.Success(payment(PaymentStatus.UNCERTAIN)))
    compose
        .onNodeWithTag(OrderDetailsTestTags.PAYMENT_STATUS)
        .assertTextEquals("Payment not confirmed yet")
    compose.onNodeWithTag(OrderDetailsTestTags.PAY).assertTextEquals("View payment")

    mapOf(
            PaymentStatus.PENDING to "Payment in progress",
            PaymentStatus.FAILED to "Payment unsuccessful",
        )
        .forEach { (status, text) ->
          content.value = content.value.copy(payment = PaymentResult.Success(payment(status)))
          compose.onNodeWithTag(OrderDetailsTestTags.PAYMENT_STATUS).assertTextEquals(text)
        }

    content.value =
        content.value.copy(payment = PaymentResult.Success(payment(PaymentStatus.CONFIRMED)))
    compose.onNodeWithTag(OrderDetailsTestTags.PAYMENT_STATUS).assertTextEquals("Paid with TWINT")
    compose.onNodeWithTag(OrderDetailsTestTags.PAY).assertDoesNotExist()

    content.value = content.value.copy(payment = PaymentResult.Error(PaymentError.UNKNOWN))
    compose
        .onNodeWithTag(OrderDetailsTestTags.PAYMENT_STATUS)
        .assertTextEquals("Payment status unavailable")
    compose.onNodeWithTag(OrderDetailsTestTags.PAY).assertDoesNotExist()

    content.value =
        content.value.copy(viewerId = trip.ownerId, payment = PaymentResult.Success(null))
    compose.onNodeWithTag(OrderDetailsTestTags.PAYMENT_STATUS).assertExists()
    compose.onNodeWithTag(OrderDetailsTestTags.PAY).assertDoesNotExist()
  }
}
