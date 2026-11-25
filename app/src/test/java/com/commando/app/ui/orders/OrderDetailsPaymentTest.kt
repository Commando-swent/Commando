package com.commando.app.ui.orders

// AI assistance: Claude Code (#419).
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.commando.app.data.repository.FakeOrderRepository
import com.commando.app.data.repository.FakePaymentRepository
import com.commando.app.data.repository.FakeTripRepository
import com.commando.app.data.repository.OrderRepository
import com.commando.app.data.repository.OrderResult
import com.commando.app.data.repository.PaymentError
import com.commando.app.data.repository.PaymentRepository
import com.commando.app.data.repository.PaymentResult
import com.commando.app.model.*
import com.commando.app.ui.orders.OrderPaymentFixture.order
import com.commando.app.ui.orders.OrderPaymentFixture.payment
import com.commando.app.ui.orders.OrderPaymentFixture.trip
import com.commando.app.ui.theme.SampleAppTheme
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
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

/** One requester's accepted order: 3 × CHF 2.30 + CHF 1.50 commission = CHF 8.40. */
internal object OrderPaymentFixture {
  private val time = Instant.parse("2026-11-20T12:00:00Z")
  val trip =
      Trip(
          "trip",
          "commando",
          Location("Migros EPFL", 46.52, 6.57),
          time,
          Location("Rolex Learning Center", 46.52, 6.57),
          TripStatus.PUBLISHED,
          time,
          time,
      )
  val order =
      Order(
          "order",
          trip.id,
          "requester",
          listOf(OrderItem("milk", "Milk", 230, 3)),
          150,
          OrderStatus.ACCEPTED,
          time,
      )

  fun payment(status: PaymentStatus, method: PaymentMethod = PaymentMethod.TWINT) =
      Payment(order.id, order.id, order.requesterId, order.totalCents, method, status)
}

/** Order Details reads the persisted payment through the shared fake payment repository. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OrderDetailsPaymentTest {
  private var user: String? = order.requesterId

  @Before fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

  @After fun tearDown() = Dispatchers.resetMain()

  private fun orders(stored: Order = order) =
      FakeOrderRepository(
          { user },
          listOf(stored),
          tripRepository = FakeTripRepository(trip.ownerId, initialTrips = listOf(trip)),
      )

  private fun fake(vararg payments: Payment, stored: Order = order) =
      FakePaymentRepository(
          { user },
          listOf(stored),
          mapOf(trip.id to trip.ownerId),
          payments.toList(),
      )

  private fun TestScope.loaded(
      payments: PaymentRepository?,
      orderRepository: OrderRepository = orders(),
  ): OrderDetailsUiState.Content {
    val vm =
        OrderDetailsViewModel(
            order.id,
            orderRepository,
            FakeTripRepository(trip.ownerId, initialTrips = listOf(trip)),
            { user },
            paymentRepositoryFactory =
                payments?.let { repository ->
                  { identity ->
                    paymentIdentity = identity
                    repository
                  }
                },
        )
    advanceUntilIdle()
    return vm.uiState.value as OrderDetailsUiState.Content
  }

  /** The identity Order Details handed to the payment repository factory. */
  private var paymentIdentity: (() -> String?)? = null
  private var reads = 0

  private fun countingReads() =
      object : PaymentRepository by fake() {
        override suspend fun getPayment(orderId: String): PaymentResult<Payment?> {
          reads++
          return PaymentResult.Success(null)
        }
      }

  /** Even if an order read leaked to another viewer, Order Details must not query the payment. */
  private fun leakyOrders() =
      object : OrderRepository by orders() {
        override suspend fun getOrderById(orderId: String) = OrderResult.Success(order)
      }

  @Test
  fun requesterReadsAnUnpaidAcceptedOrderAsUnpaid() = runTest {
    assertEquals(PaymentResult.Success(null), loaded(fake()).payment)
  }

  @Test fun requesterCanPayAnUnpaidAcceptedOrder() = runTest { assertTrue(loaded(fake()).canPay) }

  @Test
  fun everyPersistedPaymentStatusIsReloaded() = runTest {
    for (status in PaymentStatus.entries) {
      val stored = payment(status)
      assertEquals(status.name, PaymentResult.Success(stored), loaded(fake(stored)).payment)
    }
  }

  @Test
  fun onlyAConfirmedPaymentClosesThePayEntry() = runTest {
    for (status in PaymentStatus.entries) {
      val canPay = loaded(fake(payment(status))).canPay
      assertEquals(status.name, status != PaymentStatus.CONFIRMED, canPay)
    }
  }

  @Test
  fun paymentReadFailureIsUnavailableAndCannotBePaid() = runTest {
    val throwing =
        object : PaymentRepository by fake() {
          override suspend fun getPayment(orderId: String) = throw IllegalStateException("offline")
        }
    val content = loaded(throwing)
    assertEquals(PaymentResult.Error(PaymentError.UNKNOWN), content.payment)
    assertFalse(content.canPay)
  }

  @Test
  fun unconfiguredPaymentsHideTheStatusAndPayEntry() = runTest {
    val content = loaded(null)
    assertNull(content.payment)
    assertFalse(content.canPay)
  }

  @Test
  fun tripOwnerReadsThePaymentStatus() = runTest {
    user = trip.ownerId
    assertEquals(PaymentResult.Success(null), loaded(fake()).payment)
  }

  @Test
  fun tripOwnerCannotPayTheRequesterOrder() = runTest {
    user = trip.ownerId
    assertFalse(loaded(fake()).canPay)
  }

  @Test
  fun unrelatedViewerNeverReadsThePayment() = runTest {
    user = "stranger"
    val content = loaded(countingReads(), leakyOrders())
    assertNull(content.payment)
    assertFalse(content.canPay)
    assertEquals(0, reads)
  }

  @Test
  fun signedOutViewerNeverReadsThePayment() = runTest {
    user = null
    val content = loaded(countingReads(), leakyOrders())
    assertNull(content.payment)
    assertFalse(content.canPay)
    assertEquals(0, reads)
  }

  @Test
  fun paymentRepositoryIdentityEndsPermanentlyWithTheSession() = runTest {
    loaded(fake())
    assertEquals(order.requesterId, paymentIdentity?.invoke())
    user = "other"
    assertNull(paymentIdentity?.invoke())
    user = order.requesterId
    assertNull(paymentIdentity?.invoke())
  }

  @Test
  fun slowPaymentReadDoesNotBlockExistingOrderActions() = runTest {
    val pending = order.copy(status = OrderStatus.PENDING)
    val gate = CompletableDeferred<PaymentResult<Payment?>>()
    val delayed =
        object : PaymentRepository by fake(stored = pending) {
          override suspend fun getPayment(orderId: String) = gate.await()
        }
    val vm =
        OrderDetailsViewModel(
            order.id,
            orders(pending),
            FakeTripRepository(trip.ownerId, initialTrips = listOf(trip)),
            { user },
            paymentRepositoryFactory = { delayed },
        )

    runCurrent()

    val content = vm.uiState.value as OrderDetailsUiState.Content
    assertTrue(content.canCancel)
    assertNull(content.payment)

    gate.complete(PaymentResult.Success(null))
    advanceUntilIdle()
    assertEquals(
        PaymentResult.Success(null),
        (vm.uiState.value as OrderDetailsUiState.Content).payment,
    )
  }

  @Test
  fun paymentReadCompletingDuringCancellationIsPreserved() = runTest {
    val pending = order.copy(status = OrderStatus.PENDING)
    val paymentGate = CompletableDeferred<PaymentResult<Payment?>>()
    val cancellationGate = CompletableDeferred<OrderResult<Order>>()
    val delayedPayment =
        object : PaymentRepository by fake(stored = pending) {
          override suspend fun getPayment(orderId: String) = paymentGate.await()
        }
    val delayedOrder =
        object : OrderRepository by orders(pending) {
          override suspend fun cancelOrder(orderId: String) = cancellationGate.await()
        }
    val vm =
        OrderDetailsViewModel(
            order.id,
            delayedOrder,
            FakeTripRepository(trip.ownerId, initialTrips = listOf(trip)),
            { user },
            paymentRepositoryFactory = { delayedPayment },
        )
    runCurrent()

    vm.cancel()
    runCurrent()
    paymentGate.complete(PaymentResult.Success(null))
    runCurrent()

    cancellationGate.complete(OrderResult.Success(pending.copy(status = OrderStatus.CANCELLED)))
    advanceUntilIdle()

    val content = vm.uiState.value as OrderDetailsUiState.Content
    assertEquals(OrderStatus.CANCELLED, content.order.status)
    assertEquals(PaymentResult.Success(null), content.payment)
  }

  @Test
  fun latePaymentResultCannotRestoreContentAfterAccountChange() = runTest {
    val session = kotlinx.coroutines.flow.MutableStateFlow<String?>(order.requesterId)
    val gate = CompletableDeferred<PaymentResult<Payment?>>()
    val delayed =
        object : PaymentRepository by fake() {
          override suspend fun getPayment(orderId: String) =
              withContext(NonCancellable) { gate.await() }
        }
    val vm =
        OrderDetailsViewModel(
            order.id,
            orders(),
            FakeTripRepository(trip.ownerId, initialTrips = listOf(trip)),
            { session.value },
            session,
            paymentRepositoryFactory = { delayed },
        )
    runCurrent()
    assertTrue(vm.uiState.value is OrderDetailsUiState.Content)

    session.value = "other"
    runCurrent()
    assertEquals(OrderDetailsUiState.SessionEnded, vm.uiState.value)

    gate.complete(PaymentResult.Success(payment(PaymentStatus.CONFIRMED)))
    advanceUntilIdle()
    assertEquals(OrderDetailsUiState.SessionEnded, vm.uiState.value)
  }

  @Test
  fun accountChangeSeenOnlyWhenThePaymentReadReturnsDiscardsTheResult() = runTest {
    val gate = CompletableDeferred<PaymentResult<Payment?>>()
    val delayed =
        object : PaymentRepository by fake() {
          override suspend fun getPayment(orderId: String) = gate.await()
        }
    val vm =
        OrderDetailsViewModel(
            order.id,
            orders(),
            FakeTripRepository(trip.ownerId, initialTrips = listOf(trip)),
            { user },
            paymentRepositoryFactory = { delayed },
        )
    runCurrent()
    // No session event yet: only the identity check after the read can notice the change.
    user = "other"
    gate.complete(PaymentResult.Success(payment(PaymentStatus.CONFIRMED)))
    advanceUntilIdle()
    assertEquals(OrderDetailsUiState.SessionEnded, vm.uiState.value)
  }

  @Test
  fun pendingOrderCannotBePaid() = runTest {
    val pending = order.copy(status = OrderStatus.PENDING)
    assertFalse(loaded(fake(stored = pending), orders(pending)).canPay)
  }

  @Test
  fun blankViewerCannotPayAnOrderWithABlankRequester() {
    val blank = order.copy(requesterId = "")
    val content =
        OrderDetailsUiState.Content(
            blank,
            trip,
            viewerId = "",
            payment = PaymentResult.Success(null),
        )
    assertFalse(content.canPay)
  }

  @Test
  fun orderShownWithAnotherTripCannotBePaid() {
    val content =
        OrderDetailsUiState.Content(
            order.copy(tripId = "other-trip"),
            trip,
            order.requesterId,
            payment = PaymentResult.Success(null),
        )
    assertFalse(content.canPay)
  }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-rUS-w412dp-h917dp")
class OrderDetailsPaymentScreenTest {
  @get:Rule val compose = createComposeRule()
  private val events = mutableListOf<String>()
  private val requesterView = OrderDetailsUiState.Content(order, trip, order.requesterId)

  private fun render(
      content: OrderDetailsUiState.Content,
      onPay: (() -> Unit)? = { events += "pay" },
  ) = compose.setContent { SampleAppTheme { OrderDetailsScreen(content, {}, {}, onPay = onPay) } }

  private fun renderPayment(payment: PaymentResult<Payment?>) =
      render(requesterView.copy(payment = payment))

  private fun status() = compose.onNodeWithTag(OrderDetailsTestTags.PAYMENT_STATUS)

  private fun pay() = compose.onNodeWithTag(OrderDetailsTestTags.PAY)

  @Test
  fun missingPaymentReadHidesThePaymentCard() {
    render(requesterView)
    status().assertDoesNotExist()
  }

  @Test
  fun unpaidOrderIsShownAsNotPaid() {
    renderPayment(PaymentResult.Success(null))
    status().assertTextEquals("Not paid yet")
  }

  @Test
  fun unpaidOrderPayButtonShowsTheTotalAndOpensCheckout() {
    renderPayment(PaymentResult.Success(null))
    pay().performScrollTo().assertTextEquals("Pay CHF 8.40").performClick()
    assertEquals(listOf("pay"), events)
  }

  @Test
  fun payEntryIsHiddenWithoutACheckoutDestination() {
    render(requesterView.copy(payment = PaymentResult.Success(null)), onPay = null)
    status().assertTextEquals("Not paid yet")
    pay().assertDoesNotExist()
  }

  @Test
  fun pendingPaymentIsShownInProgress() {
    renderPayment(PaymentResult.Success(payment(PaymentStatus.PENDING)))
    status().assertTextEquals("Payment in progress")
  }

  @Test
  fun uncertainPaymentIsShownUnconfirmedWithViewPayment() {
    renderPayment(PaymentResult.Success(payment(PaymentStatus.UNCERTAIN)))
    status().assertTextEquals("Payment not confirmed yet")
    pay().assertTextEquals("View payment")
  }

  @Test
  fun failedPaymentIsShownUnsuccessfulAndCanBePaidAgain() {
    renderPayment(PaymentResult.Success(payment(PaymentStatus.FAILED)))
    status().assertTextEquals("Payment unsuccessful")
    pay().assertTextEquals("Pay CHF 8.40")
  }

  @Test
  fun confirmedPaymentNamesItsMethodAndHidesPay() {
    renderPayment(PaymentResult.Success(payment(PaymentStatus.CONFIRMED)))
    status().assertTextEquals("Paid with TWINT")
    pay().assertDoesNotExist()
  }

  @Test
  fun confirmedCardPaymentNamesTheCard() {
    renderPayment(PaymentResult.Success(payment(PaymentStatus.CONFIRMED, PaymentMethod.CARD)))
    status().assertTextEquals("Paid with Debit or credit card")
  }

  @Test
  fun payButtonIsDisabledWhileTheOrderIsSaving() {
    render(
        requesterView.copy(
            payment = PaymentResult.Success(null),
            mutation = OrderMutationState.Saving,
        )
    )
    pay().performScrollTo().assertIsNotEnabled()
  }

  @Test
  fun paymentReadFailureIsShownUnavailableWithoutPay() {
    renderPayment(PaymentResult.Error(PaymentError.UNKNOWN))
    status().assertTextEquals("Payment status unavailable")
    pay().assertDoesNotExist()
  }

  @Test
  fun tripOwnerSeesThePaymentStatusWithoutPay() {
    render(requesterView.copy(viewerId = trip.ownerId, payment = PaymentResult.Success(null)))
    status().assertExists()
    pay().assertDoesNotExist()
  }
}
