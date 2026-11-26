package com.commando.app.data.repository

// AI assistance: OpenAI Codex authored targeted #387 adapter failure regressions for human review.
import com.commando.app.model.OrderDraft
import com.commando.app.model.OrderStatus
import com.commando.app.testing.PaymentFixtures
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.*
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Script SDK outcomes to reach reconciliation failures without emulator timing or network races.
 */
@RunWith(RobolectricTestRunner::class)
class OrderTransitionReconciliationTest {
  private val order = PaymentFixtures.order
  private val firestore = mockk<FirebaseFirestore>(relaxed = true)
  private val transaction = mockk<Transaction>()
  private val orderRef = mockk<DocumentReference>()
  private val tripRef = mockk<DocumentReference>()
  private var actor = order.requesterId
  private val source = FirebaseOrderFirestoreDataSource(firestore) { actor }
  private val denied =
      FirebaseFirestoreException(
          "initial denial",
          FirebaseFirestoreException.Code.PERMISSION_DENIED,
      )
  private val offline =
      FirebaseFirestoreException("offline", FirebaseFirestoreException.Code.UNAVAILABLE)

  @Before
  fun setup() {
    mockkStatic(FirebaseAuth::class)
    val auth = mockk<FirebaseAuth>()
    every { FirebaseAuth.getInstance(any()) } returns auth
    every { auth.currentUser } returns mockk { every { uid } answers { actor } }
    every { firestore.collection("orders").document(order.id) } returns orderRef
    every { firestore.collection("trips").document(order.tripId) } returns tripRef
    every { orderRef.firestore } returns firestore
    every { firestore.runTransaction(any<Transaction.Function<OrderFirestoreDocument>>()) } answers
        {
          try {
            Tasks.forResult(
                firstArg<Transaction.Function<OrderFirestoreDocument>>().apply(transaction)
            )
          } catch (error: Exception) {
            Tasks.forException(error)
          }
        }
  }

  @After fun teardown() = unmockkStatic(FirebaseAuth::class)

  @Test
  fun missingTripOwnerAbortsDeliveryBeforeAnyWrite() = runTest {
    val stored = document(OrderStatus.READY_FOR_HANDOFF)
    every { transaction.get(orderRef) } returns
        mockk {
          every { exists() } returns true
          every { id } returns stored.id
          every { data } returns stored.data
        }
    every { transaction.get(tripRef) } returns mockk { every { getString("ownerId") } returns null }

    val failure = runCatching { source.confirmReceipt(order.id, actor) }.exceptionOrNull()

    assertTrue(failure is FirebaseFirestoreException)
    assertEquals(
        FirebaseFirestoreException.Code.INVALID_ARGUMENT,
        (failure as FirebaseFirestoreException).code,
    )
    verify(exactly = 1) {
      transaction.get(orderRef)
      transaction.get(tripRef)
    }
    confirmVerified(transaction)
    verifyAttempts(1)
  }

  @Test
  fun lateAlternativeNonPermissionFailurePropagatesWithoutAnotherReadBack() = runTest {
    actor = PaymentFixtures.OWNER
    respond(
        Tasks.forException(denied),
        Tasks.forResult(document(OrderStatus.ACCEPTED)),
        Tasks.forException(offline),
    )

    assertSame(offline, runCatching { source.cancelAccepted(order.id, actor) }.exceptionOrNull())
    verifyAttempts(3)
  }

  @Test
  fun secondDenialReturnsACompetingPersistedTransition() = runTest {
    actor = PaymentFixtures.OWNER
    val persisted = document(OrderStatus.READY_FOR_HANDOFF)
    val secondDenial =
        FirebaseFirestoreException("late denial", FirebaseFirestoreException.Code.PERMISSION_DENIED)
    respond(
        Tasks.forException(denied),
        Tasks.forResult(document(OrderStatus.ACCEPTED)),
        Tasks.forException(secondDenial),
        Tasks.forResult(persisted),
    )

    assertEquals(persisted, source.cancelAccepted(order.id, actor))
    verifyAttempts(4)
  }

  private fun document(status: OrderStatus) =
      OrderFirestoreDocument(
          order.id,
          OrderDraft(order.tripId, order.items, order.commissionCents)
              .firestoreFields(order.requesterId) +
              mapOf("createdAt" to Timestamp(order.createdAt), "status" to status.name),
      )

  private fun respond(vararg responses: Task<OrderFirestoreDocument>) {
    every {
      firestore.runTransaction(any<Transaction.Function<OrderFirestoreDocument>>())
    } returnsMany responses.toList()
  }

  private fun verifyAttempts(count: Int) {
    verify(exactly = count) {
      firestore.runTransaction(any<Transaction.Function<OrderFirestoreDocument>>())
    }
  }
}
