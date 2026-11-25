package com.commando.app.data.repository

import com.commando.app.model.*
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await

/** Server transactions reuse the shared policy; [currentUserId] must be bound to one session. */
class PaymentRepositoryFirestore(
    currentUserId: () -> String?,
    firestore: FirebaseFirestore = FirebaseFirestore.getInstance(),
) :
    PaymentRepository by TransactionalPaymentRepository(
        FirebasePaymentDataSource(firestore),
        currentUserId,
    )

/** Reads authoritative relationships and stages payment/refund writes in one server transaction. */
internal class FirebasePaymentDataSource(private val firestore: FirebaseFirestore) :
    PaymentDataSource {
  override suspend fun <T> transaction(action: (PaymentTransaction) -> T): T =
      try {
        firestore
            .runTransaction { transaction ->
              fun read(collection: String, id: String): FirestoreDocument? =
                  transaction.get(firestore.collection(collection).document(id)).let {
                    if (it.exists()) it.toFirestoreDocument() else null
                  }
              action(
                  object : PaymentTransaction {
                    override fun read(orderId: String): PaymentContext {
                      val raw =
                          read("orders", orderId) ?: return PaymentContext(null, null, null, null)
                      val order = raw.toOrder() ?: throw PaymentFailure(PaymentError.INVALID_DATA)
                      return PaymentContext(
                          order,
                          read("trips", order.tripId)?.data?.paymentField<String>("ownerId"),
                          read("payments", orderId)?.toPayment(),
                          read("refunds", orderId)?.toRefund(),
                      )
                    }

                    override fun save(payment: Payment) {
                      transaction.set(
                          firestore.collection("payments").document(payment.orderId),
                          payment.firestoreFields(),
                      )
                    }

                    override fun save(refund: Refund) {
                      transaction.set(
                          firestore.collection("refunds").document(refund.orderId),
                          refund.firestoreFields(),
                      )
                    }
                  }
              )
            }
            .await()
      } catch (failure: CancellationException) {
        throw failure
      } catch (failure: PaymentFailure) {
        throw failure
      } catch (failure: Exception) {
        throw PaymentFailure(
            when (failure.toFirestoreFailure()) {
              FirestoreFailure.PERMISSION_DENIED -> PaymentError.PERMISSION_DENIED
              FirestoreFailure.UNAUTHENTICATED -> PaymentError.NOT_AUTHENTICATED
              FirestoreFailure.NOT_FOUND -> PaymentError.NOT_FOUND
              FirestoreFailure.INVALID_DATA -> PaymentError.INVALID_DATA
              else -> PaymentError.UNKNOWN
            }
        )
      }
}

/** Strict field reads reject coerced money and unknown enum values instead of repairing records. */
private inline fun <reified T> Map<String, Any?>.paymentField(name: String): T =
    field<T>(name) ?: throw PaymentFailure(PaymentError.INVALID_DATA)

private inline fun <reified T : Enum<T>> Map<String, Any?>.paymentEnum(name: String): T =
    enumValues<T>().firstOrNull { it.name == paymentField<String>(name) }
        ?: throw PaymentFailure(PaymentError.INVALID_DATA)

internal fun FirestoreDocument.toPayment() =
    Payment(
        id,
        data.paymentField("orderId"),
        data.paymentField("requesterId"),
        data.paymentField("amountCents"),
        data.paymentEnum("method"),
        data.paymentEnum("status"),
    )

internal fun Payment.firestoreFields(): Map<String, Any> =
    mapOf(
        "orderId" to orderId,
        "requesterId" to requesterId,
        "amountCents" to amountCents,
        "method" to method.name,
        "status" to status.name,
    )

internal fun FirestoreDocument.toRefund(): Refund {
  data.paymentField<Timestamp>("updatedAt")
  return Refund(
      data.paymentField("operationId"),
      data.paymentField("orderId"),
      data.paymentField("paymentId"),
      data.paymentField("requesterId"),
      data.paymentField("requestedBy"),
      data.paymentField("amountCents"),
      data.paymentEnum("status"),
  )
}

internal fun Refund.firestoreFields(): Map<String, Any> =
    mapOf(
        "operationId" to operationId,
        "orderId" to orderId,
        "paymentId" to paymentId,
        "requesterId" to requesterId,
        "requestedBy" to requestedBy,
        "amountCents" to amountCents,
        "status" to status.name,
        "updatedAt" to FieldValue.serverTimestamp(),
    )
