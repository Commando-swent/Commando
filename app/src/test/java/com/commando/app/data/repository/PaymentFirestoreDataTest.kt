package com.commando.app.data.repository

import com.commando.app.model.*
import com.commando.app.testing.PaymentFixtures
import com.google.android.gms.tasks.Tasks
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Transaction
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class PaymentFirestoreDataTest {
  @Test
  fun defaultClientDoesNotReadAgainAfterTheSessionEnds() = runTest {
    mockkStatic(FirebaseFirestore::class)
    try {
      val firestore = mockk<FirebaseFirestore>()
      every { FirebaseFirestore.getInstance() } returns firestore
      every { firestore.runTransaction(any<Transaction.Function<Payment?>>()) } returns
          Tasks.forException(IllegalStateException())
      var uid: String? = "requester"
      val repository = PaymentRepositoryFirestore({ uid })
      assertEquals(PaymentResult.Error(PaymentError.UNKNOWN), repository.getPayment("order"))
      uid = null
      assertEquals(
          PaymentResult.Error(PaymentError.NOT_AUTHENTICATED),
          repository.getPayment("order"),
      )
      verify(exactly = 1) { firestore.runTransaction(any<Transaction.Function<Payment?>>()) }
    } finally {
      unmockkStatic(FirebaseFirestore::class)
    }
  }

  private val capture =
      mapOf<String, Any?>(
          "orderId" to "order",
          "requesterId" to "requester",
          "amountCents" to 325L,
          "method" to "TWINT",
          "status" to "CONFIRMED",
      )

  @Test
  fun paymentDecoderRejectsMissingAndMistypedFieldsWithoutCoercion() {
    for (name in capture.keys) for (fields in listOf(capture - name, capture + (name to false))) {
      assertInvalid { FirestoreDocument("order", fields).toPayment() }
    }
    for (fields in
        listOf(
            capture + ("amountCents" to 325.0),
            capture + ("status" to "NEW"),
            capture + ("method" to "NEW"),
        )) {
      assertInvalid { FirestoreDocument("order", fields).toPayment() }
    }
    assertEquals(PaymentFixtures.captured, FirestoreDocument("order", capture).toPayment())
  }

  @Test
  fun refundDecoderRejectsMissingFieldsAndUnknownOutcome() {
    val fields = PaymentFixtures.refund().firestoreFields()
    for (name in fields.keys - "updatedAt") assertInvalid {
      FirestoreDocument("order", fields - name).toRefund()
    }
    for (change in
        listOf("amountCents" to 325.0, "status" to "NEW", "requestedBy" to false)) assertInvalid {
      FirestoreDocument("order", fields + change).toRefund()
    }
    assertEquals(PaymentFixtures.refund(), FirestoreDocument("order", fields).toRefund())
  }

  @Test
  fun sdkFailuresRetainTypedErrorsRatherThanLookingLikeMissingPayments() = runTest {
    val errors =
        mapOf(
            FirebaseFirestoreException.Code.PERMISSION_DENIED to PaymentError.PERMISSION_DENIED,
            FirebaseFirestoreException.Code.UNAUTHENTICATED to PaymentError.NOT_AUTHENTICATED,
            FirebaseFirestoreException.Code.NOT_FOUND to PaymentError.NOT_FOUND,
            FirebaseFirestoreException.Code.INVALID_ARGUMENT to PaymentError.INVALID_DATA,
            FirebaseFirestoreException.Code.UNAVAILABLE to PaymentError.UNKNOWN,
        )
    for ((code, expected) in errors) {
      val firestore = mockk<FirebaseFirestore>()
      every { firestore.runTransaction(any<Transaction.Function<Unit>>()) } returns
          Tasks.forException(FirebaseFirestoreException("unavailable", code))
      val failure =
          try {
            FirebasePaymentDataSource(firestore).transaction<Unit> {
              fail("No read on transport failure")
            }
            error("Expected failure")
          } catch (failure: PaymentFailure) {
            failure
          }
      assertEquals(expected, failure.error)
    }
  }

  @Test
  fun cancelledSdkTaskPropagatesCancellation() = runTest {
    val firestore = mockk<FirebaseFirestore>()
    every { firestore.runTransaction(any<Transaction.Function<Unit>>()) } returns
        Tasks.forCanceled()
    try {
      FirebasePaymentDataSource(firestore).transaction<Unit> { fail("No read after cancellation") }
      fail("Cancellation must propagate")
    } catch (_: CancellationException) {
      /* Expected; never mapped to a successful payload. */
    }
  }

  private fun assertInvalid(action: () -> Unit) {
    try {
      action()
      fail("Expected invalid data")
    } catch (failure: PaymentFailure) {
      assertEquals(PaymentError.INVALID_DATA, failure.error)
    }
  }
}
