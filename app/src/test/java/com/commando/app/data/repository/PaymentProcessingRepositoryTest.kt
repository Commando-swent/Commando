package com.commando.app.data.repository

import com.commando.app.model.Order
import com.commando.app.model.OrderStatus
import com.commando.app.model.Payment
import com.commando.app.model.PaymentMethod
import com.commando.app.model.PaymentStatus
import com.commando.app.model.totalCents
import com.commando.app.testing.PaymentFixtures
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/** Payment creation, idempotency and retry policy shared by fake and Firestore adapters. */
class PaymentProcessingRepositoryTest {
  private val order = PaymentFixtures.order.copy(status = OrderStatus.ACCEPTED)
  private val owners = mapOf(order.tripId to PaymentFixtures.OWNER)
  private var uid: String? = order.requesterId

  private fun repository(
      storedOrder: Order = order,
      initial: Payment? = null,
      simulate: (Payment) -> PaymentStatus = { PaymentStatus.CONFIRMED },
  ) =
      FakePaymentRepository(
          { uid },
          listOf(storedOrder),
          owners,
          listOfNotNull(initial),
          simulatePayment = simulate,
      )

  @Test
  fun twintAndCardUseThePersistedTotalAndSurviveReload() = runTest {
    for (method in PaymentMethod.entries) {
      val repo = FakePaymentRepository({ uid }, listOf(order), owners)
      val expected = payment(method, PaymentStatus.CONFIRMED)
      repo.processPayment(order.id, method).assertData(expected)
      repo.getPayment(order.id).assertData(expected)
    }
  }

  @Test
  fun tripOwnerAndOutsidersCannotPay() = runTest {
    for (viewer in listOf(PaymentFixtures.OWNER, "outsider")) {
      uid = viewer
      repository()
          .processPayment(order.id, PaymentMethod.TWINT)
          .assertError(PaymentError.PERMISSION_DENIED)
    }
  }

  @Test
  fun signedOutCallerCannotPay() = runTest {
    uid = null
    repository()
        .processPayment(order.id, PaymentMethod.TWINT)
        .assertError(PaymentError.NOT_AUTHENTICATED)
  }

  @Test
  fun orderThatIsNotAcceptedIsIneligibleForPayment() = runTest {
    repository(storedOrder = order.copy(status = OrderStatus.PENDING))
        .processPayment(order.id, PaymentMethod.TWINT)
        .assertError(PaymentError.INELIGIBLE)
  }

  @Test
  fun missingOrderCannotBePaid() = runTest {
    FakePaymentRepository({ uid }, emptyList(), emptyMap())
        .processPayment(order.id, PaymentMethod.TWINT)
        .assertError(PaymentError.NOT_FOUND)
  }

  @Test
  fun confirmedPendingAndUncertainPaymentsAreNeverReprocessed() = runTest {
    for (status in
        listOf(PaymentStatus.CONFIRMED, PaymentStatus.PENDING, PaymentStatus.UNCERTAIN)) {
      val stored = payment(PaymentMethod.TWINT, status)
      repository(initial = stored) { error("Stored $status payment must be reconciled") }
          .processPayment(order.id, PaymentMethod.CARD)
          .assertData(stored)
    }
  }

  @Test
  fun unsettledSimulationOutcomesAreNeverPersisted() = runTest {
    for (status in listOf(PaymentStatus.PENDING, PaymentStatus.UNCERTAIN)) {
      val repo = repository { status }
      repo.processPayment(order.id, PaymentMethod.TWINT).assertError(PaymentError.UNKNOWN)
      repo.getPayment(order.id).assertData(null)
    }
  }

  @Test
  fun failedPaymentRetriesTheSameIdentityAndMayChangeMethod() = runTest {
    val failed = payment(PaymentMethod.TWINT, PaymentStatus.FAILED)
    val repo =
        repository(initial = failed) { attempted ->
          assertEquals(payment(PaymentMethod.CARD, PaymentStatus.PENDING), attempted)
          PaymentStatus.CONFIRMED
        }
    val confirmed = payment(PaymentMethod.CARD, PaymentStatus.CONFIRMED)
    repo.processPayment(order.id, PaymentMethod.CARD).assertData(confirmed)
    repo.getPayment(order.id).assertData(confirmed)
  }

  @Test
  fun simultaneousSubmissionsProcessOnlyOnce() = runTest {
    val simulations = AtomicInteger()
    val repo = repository {
      simulations.incrementAndGet()
      PaymentStatus.CONFIRMED
    }
    val results =
        List(20) {
              async(Dispatchers.Default) { repo.processPayment(order.id, PaymentMethod.TWINT) }
            }
            .awaitAll()
    assertEquals(listOf(PaymentResult.Success(payment())), results.distinct())
    assertEquals(1, simulations.get())
  }

  @Test
  fun failedOutcomeCanBeRetriedWithoutCreatingAnotherPayment() = runTest {
    var outcome = PaymentStatus.FAILED
    val repo = repository { outcome }
    repo
        .processPayment(order.id, PaymentMethod.TWINT)
        .assertData(payment(status = PaymentStatus.FAILED))
    outcome = PaymentStatus.CONFIRMED
    repo.processPayment(order.id, PaymentMethod.TWINT).assertData(payment())
    repo.getPayment(order.id).assertData(payment())
  }

  @Test
  fun sessionChangeBeforeCommitRollsBackThePayment() = runTest {
    val source = FakePaymentDataSource(listOf(order), owners, emptyList())
    val repository =
        TransactionalPaymentRepository(
            source,
            { uid },
            simulatePayment = {
              uid = null
              PaymentStatus.CONFIRMED
            },
        )
    repository
        .processPayment(order.id, PaymentMethod.TWINT)
        .assertError(PaymentError.NOT_AUTHENTICATED)
    val nextSession = TransactionalPaymentRepository(source, { order.requesterId })
    nextSession.getPayment(order.id).assertData(null)
  }

  @Test
  fun lostAcknowledgementReconcilesTheCommittedPaymentWithoutReprocessing() = runTest {
    val source = FakePaymentDataSource(listOf(order), owners, emptyList())
    var loseResponse = true
    val transport =
        object : PaymentDataSource {
          override suspend fun <T> transaction(action: (PaymentTransaction) -> T): T {
            val result = source.transaction(action)
            if (loseResponse) {
              loseResponse = false
              throw IOException("Response lost after commit")
            }
            return result
          }
        }
    var simulations = 0
    val repository =
        TransactionalPaymentRepository(
            transport,
            { uid },
            simulatePayment = {
              simulations++
              PaymentStatus.CONFIRMED
            },
        )
    repository.processPayment(order.id, PaymentMethod.TWINT).assertError(PaymentError.UNKNOWN)
    repository.processPayment(order.id, PaymentMethod.TWINT).assertData(payment())
    assertEquals(1, simulations)
  }

  @Test
  fun cancellationPropagatesWithoutPublishingAPayment() = runTest {
    val repo = repository { throw CancellationException("Cancelled") }
    try {
      repo.processPayment(order.id, PaymentMethod.TWINT)
      fail("Cancellation must propagate")
    } catch (expected: CancellationException) {
      assertEquals("Cancelled", expected.message)
    }
    repo.getPayment(order.id).assertData(null)
  }

  private fun payment(
      method: PaymentMethod = PaymentMethod.TWINT,
      status: PaymentStatus = PaymentStatus.CONFIRMED,
  ) = Payment(order.id, order.id, order.requesterId, order.totalCents, method, status)

  private fun PaymentResult<*>.assertError(expected: PaymentError) =
      assertEquals(PaymentResult.Error(expected), this)

  private fun PaymentResult<*>.assertData(expected: Any?) =
      assertEquals(PaymentResult.Success(expected), this)
}
