package com.commando.app.ui.orders

import androidx.lifecycle.viewModelScope
import com.android.sample.MainDispatcherRule
import com.android.sample.ui.orders.*
import com.commando.app.data.repository.*
import com.commando.app.model.*
import com.commando.app.testing.PaymentFixtures
import io.mockk.*
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OrderRefundCancellationTest {
  @get:Rule val main = MainDispatcherRule()

  @Test
  fun bothCancellationActorsWaitForConfirmationAndRepeatedTapsDoNotDuplicate() = runTest {
    for (accepted in listOf(false, true)) {
      val order =
          PaymentFixtures.order.copy(
              status = if (accepted) OrderStatus.ACCEPTED else OrderStatus.PENDING
          )
      val uid = if (accepted) PaymentFixtures.OWNER else order.requesterId
      val payments = payments(order, uid) { RefundStatus.CONFIRMED }
      val orders = orders(order)
      coEvery {
        if (accepted) orders.cancelAcceptedOrder(order.id) else orders.cancelOrder(order.id)
      } coAnswers
          {
            assertEquals(
                RefundStatus.CONFIRMED,
                (payments.getRefund(order.id) as PaymentResult.Success).data!!.status,
            )
            OrderResult.Success(order.copy(status = OrderStatus.CANCELLED))
          }
      val vm = model(order, uid, orders, payments)
      advanceUntilIdle()
      vm.cancel()
      vm.cancel()
      advanceUntilIdle()
      assertEquals(
          OrderStatus.CANCELLED,
          (vm.uiState.value as OrderDetailsUiState.Content).order.status,
      )
      coVerify(exactly = if (accepted) 0 else 1) { orders.cancelOrder(order.id) }
      coVerify(exactly = if (accepted) 1 else 0) { orders.cancelAcceptedOrder(order.id) }
      vm.viewModelScope.cancel()
    }
  }

  @Test
  fun unconfirmedOutcomesKeepTheOrderActiveUntilRetryConfirms() = runTest {
    for (status in RefundStatus.entries.filter { it != RefundStatus.CONFIRMED }) {
      val order = PaymentFixtures.order
      var outcome = status
      val payments = payments(order, order.requesterId) { outcome }
      val orders = orders(order)
      val vm = model(order, order.requesterId, orders, payments)
      advanceUntilIdle()
      vm.cancel()
      advanceUntilIdle()
      assertEquals(order, (vm.uiState.value as OrderDetailsUiState.Content).order)
      vm.refreshRefund()
      advanceUntilIdle()
      coVerify(exactly = 0) { orders.cancelOrder(any()) }
      outcome = RefundStatus.CONFIRMED
      vm.cancel()
      advanceUntilIdle()
      coVerify(exactly = 1) { orders.cancelOrder(order.id) }
      vm.viewModelScope.cancel()
    }
  }

  private fun payments(order: Order, uid: String, outcome: (Refund) -> RefundStatus) =
      FakePaymentRepository(
          { uid },
          listOf(order),
          mapOf(order.tripId to PaymentFixtures.OWNER),
          listOf(PaymentFixtures.captured),
          simulateRefund = outcome,
      )

  private fun orders(order: Order) =
      mockk<OrderRepository> {
        coEvery { getOrderById(order.id) } returns OrderResult.Success(order)
        coEvery { cancelOrder(order.id) } returns
            OrderResult.Success(order.copy(status = OrderStatus.CANCELLED))
      }

  private fun model(
      order: Order,
      uid: String,
      orders: OrderRepository,
      payments: PaymentRepository,
  ): OrderDetailsViewModel {
    val trip =
        mockk<Trip> {
          every { id } returns order.tripId
          every { ownerId } returns PaymentFixtures.OWNER
          every { status } returns TripStatus.PUBLISHED
        }
    val trips =
        mockk<TripRepository> {
          coEvery { getTripById(order.tripId) } returns TripResult.Success(trip)
        }
    return OrderDetailsViewModel(
        order.id,
        orders,
        trips,
        { uid },
        paymentRepositoryFactory = { payments },
    )
  }
}
