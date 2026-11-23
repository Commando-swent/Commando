package com.android.sample.emulator.firestore.security

import com.commando.app.data.repository.*
import com.commando.app.model.*
import com.commando.app.testing.PaymentFixtures
import com.google.firebase.Timestamp
import com.google.firebase.firestore.Source
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Production-rule tests use admin seeds only for captures supplied by the checkout dependency. */
@RunWith(RobolectricTestRunner::class)
class PaymentFirestoreSecurityRulesEmulatorTest {
  @Test
  fun concurrentRefundsPersistOneOperationAndReloadWithoutAnotherWrite() = runTest {
    withClients("owner" to true, "requester" to true) { (owner, requester) ->
      val order = seed(owner, requester)
      val start = CompletableDeferred<Unit>()
      val jobs =
          List(4) {
            async(Dispatchers.IO) {
              start.await()
              repo(requester).refundForCancellation(order.id)
            }
          }
      start.complete(Unit)
      val refunds = jobs.awaitAll().map { (it as PaymentResult.Success).data }
      assertEquals(1, refunds.toSet().size)
      assertEquals("refund-${order.id}", refunds.first().operationId)
      assertEquals(order.totalCents, refunds.first().amountCents)
      for (client in listOf(owner, requester)) {
        assertEquals(PaymentResult.Success(refunds.first()), repo(client).getRefund(order.id))
        assertEquals(
            order.totalCents,
            (repo(client).getPayment(order.id) as PaymentResult.Success).data!!.amountCents,
        )
      }
      val reference = requester.firestore.collection("refunds").document(order.id)
      val timestamp = reference.get(Source.SERVER).await().getTimestamp("updatedAt")
      assertEquals(
          PaymentResult.Success(refunds.first()),
          repo(requester).refundForCancellation(order.id),
      )
      assertEquals(timestamp, reference.get(Source.SERVER).await().getTimestamp("updatedAt"))
    }
  }

  @Test
  fun nonterminalOutcomesSurviveReloadAndBlockCancellationUntilConfirmed() = runTest {
    withClients("owner" to true, "requester" to true) { (owner, requester) ->
      for (status in RefundStatus.entries.filter { it != RefundStatus.CONFIRMED }) {
        val order = seed(owner, requester)
        val pending = repo(requester, status).refundForCancellation(order.id)
        assertEquals(status, (pending as PaymentResult.Success).data.status)
        assertEquals(pending, repo(requester).getRefund(order.id))
        val reference = requester.firestore.collection("orders").document(order.id)
        assertPermissionDenied { reference.update("status", "CANCELLED").await() }
        assertEquals(
            RefundStatus.CONFIRMED,
            (repo(requester).refundForCancellation(order.id) as PaymentResult.Success).data.status,
        )
        reference.update("status", "CANCELLED").await()
        assertEquals("CANCELLED", reference.get(Source.SERVER).await().getString("status"))
        assertTrue(repo(requester).refundForCancellation(order.id) is PaymentResult.Success)
      }
    }
  }

  @Test
  fun onlyParticipantsReadAndOnlyTheCancellationActorWrites() = runTest {
    withClients("owner" to true, "requester" to true, "other" to true, "out" to false) {
        (owner, requester, other, out) ->
      val pending = seed(owner, requester)
      assertEquals(
          PaymentResult.Error(PaymentError.INELIGIBLE),
          repo(owner).refundForCancellation(pending.id),
      )
      for (client in listOf(other, out)) for (collection in listOf("payments", "refunds")) {
        assertPermissionDenied {
          client.firestore.collection(collection).document(pending.id).get(Source.SERVER).await()
        }
      }
      assertEquals(
          PaymentResult.Error(PaymentError.PERMISSION_DENIED),
          repo(other).getPayment(pending.id),
      )
      assertEquals(
          PaymentResult.Error(PaymentError.NOT_AUTHENTICATED),
          repo(out).getRefund(pending.id),
      )
      val accepted = seed(owner, requester, OrderStatus.ACCEPTED)
      assertEquals(
          PaymentResult.Error(PaymentError.INELIGIBLE),
          repo(requester).refundForCancellation(accepted.id),
      )
      assertTrue(repo(owner).refundForCancellation(accepted.id) is PaymentResult.Success)
      for (client in listOf(owner, requester, other, out)) for (collection in
          listOf("payments", "refunds")) {
        assertPermissionDenied {
          client.firestore.collection(collection).get(Source.SERVER).await()
        }
        assertPermissionDenied {
          client.firestore.collection(collection).document(pending.id).delete().await()
        }
      }
    }
  }

  @Test
  fun rulesRejectForgedFieldsAndConfirmedOperationMutation() = runTest {
    withClients("owner" to true, "requester" to true, "other" to true) { (owner, requester, other)
      ->
      val order = seed(owner, requester)
      val data = refund(order).firestoreFields()
      val reference = requester.firestore.collection("refunds").document(order.id)
      for (invalid in
          listOf(
              data + ("amountCents" to order.totalCents + 1),
              data + ("amountCents" to order.totalCents.toDouble()),
              data + ("requesterId" to "forged"),
              data + ("requestedBy" to owner.auth.currentUser!!.uid),
              data + ("orderId" to "forged"),
              data + ("paymentId" to "forged"),
              data + ("operationId" to "new"),
              data + ("status" to "OTHER"),
              data + ("updatedAt" to Timestamp(0, 0)),
              data + ("extra" to true),
              data - "status",
          )) assertPermissionDenied(invalid.keys.toString()) { reference.set(invalid).await() }
      for (client in listOf(owner, other)) assertPermissionDenied {
        client.firestore
            .collection("refunds")
            .document(order.id)
            .set(data + ("requestedBy" to client.auth.currentUser!!.uid))
            .await()
      }
      for (status in
          OrderStatus.entries.filter { it !in listOf(OrderStatus.PENDING, OrderStatus.ACCEPTED) }) {
        updateDocumentAsOwner("orders/${order.id}", mapOf("status" to status.name))
        assertPermissionDenied(status.name) { reference.set(data).await() }
      }
      updateDocumentAsOwner("orders/${order.id}", mapOf("status" to "PENDING"))
      reference.set(data).await()
      for (change in
          listOf(mapOf("status" to "FAILED"), mapOf("amountCents" to 1), mapOf("extra" to true))) {
        assertPermissionDenied { reference.update(change).await() }
      }
      assertPermissionDenied {
        requester.firestore
            .collection("payments")
            .document(order.id)
            .update("status", "CONFIRMED")
            .await()
      }
    }
  }

  @Test
  fun malformedOrUncapturedPaymentsFailClosedWhileUnpaidCancellationIsPreserved() = runTest {
    withClients("owner" to true, "requester" to true) { (owner, requester) ->
      val order = seed(owner, requester)
      val payment = requester.firestore.collection("payments").document(order.id)
      val orderRef = requester.firestore.collection("orders").document(order.id)
      for (status in PaymentStatus.entries.filter { it != PaymentStatus.CONFIRMED }) {
        updateDocumentAsOwner("payments/${order.id}", mapOf("status" to status.name))
        assertEquals(
            PaymentResult.Error(PaymentError.NOT_CAPTURED),
            repo(requester).refundForCancellation(order.id),
        )
        if (status != PaymentStatus.FAILED)
            assertPermissionDenied { orderRef.update("status", "CANCELLED").await() }
      }
      for (fields in
          listOf(
              mapOf("amountCents" to 1),
              mapOf("amountCents" to 325.0),
              mapOf("status" to "OTHER"),
          )) {
        updateDocumentAsOwner(
            "payments/${order.id}",
            mapOf("amountCents" to order.totalCents, "status" to "CONFIRMED") + fields,
        )
        assertEquals(
            PaymentResult.Error(PaymentError.INVALID_DATA),
            repo(requester).getPayment(order.id),
        )
        assertPermissionDenied { orderRef.update("status", "CANCELLED").await() }
      }
      deleteDocumentAsOwner("payments/${order.id}")
      assertFalse(payment.get(Source.SERVER).await().exists())
      assertEquals(PaymentResult.Success(null), repo(requester).getPayment(order.id))
      orderRef.update("status", "CANCELLED").await()
    }
  }

  @Test
  fun refundReservationRejectsRacingHandoffAndEdits() = runTest {
    withClients("owner" to true, "requester" to true) { (owner, requester) ->
      val order = seed(owner, requester, OrderStatus.ACCEPTED)
      assertTrue(
          repo(owner, RefundStatus.PENDING).refundForCancellation(order.id) is PaymentResult.Success
      )
      assertPermissionDenied {
        owner.firestore
            .collection("orders")
            .document(order.id)
            .update("status", "READY_FOR_HANDOFF")
            .await()
      }
      val pending = seed(owner, requester)
      assertPermissionDenied {
        requester.firestore
            .collection("orders")
            .document(pending.id)
            .update("commissionCents", 76, "revision", 1)
            .await()
      }
      val unpaid = seed(owner, requester)
      deleteDocumentAsOwner("payments/${unpaid.id}")
      assertTrue(repo(requester).getRefund(unpaid.id) is PaymentResult.Success)
    }
  }

  @Test
  fun paidOwnerCancellationCommitsItsEventOnlyAfterConfirmedRefund() = runTest {
    withClients("owner" to true, "requester" to true) { (owner, requester) ->
      val order = seed(owner, requester, OrderStatus.ACCEPTED)
      val orders = OrderRepositoryFirestore({ owner.auth.currentUser?.uid }, owner.firestore)
      assertEquals(
          OrderResult.Error(OrderError.PermissionDenied),
          orders.cancelAcceptedOrder(order.id),
      )
      assertTrue(
          repo(owner, RefundStatus.UNCERTAIN).refundForCancellation(order.id)
              is PaymentResult.Success
      )
      assertEquals(
          OrderResult.Error(OrderError.PermissionDenied),
          orders.cancelAcceptedOrder(order.id),
      )
      assertTrue(repo(owner).refundForCancellation(order.id) is PaymentResult.Success)
      assertEquals(
          OrderStatus.CANCELLED,
          (orders.cancelAcceptedOrder(order.id) as OrderResult.Success).data.status,
      )
      val event =
          requester.firestore.collection("orderEvents").document("${order.id}_ORDER_CANCELLED")
      val timestamp = event.get(Source.SERVER).await().getTimestamp("occurredAt")
      assertNotNull(timestamp)
      assertTrue(orders.cancelAcceptedOrder(order.id) is OrderResult.Success)
      assertEquals(timestamp, event.get(Source.SERVER).await().getTimestamp("occurredAt"))
    }
  }

  private fun repo(
      client: EmulatorClient,
      status: RefundStatus = RefundStatus.CONFIRMED,
  ): PaymentRepository =
      if (status == RefundStatus.CONFIRMED)
          PaymentRepositoryFirestore({ client.auth.currentUser?.uid }, client.firestore)
      else
          TransactionalPaymentRepository(
              FirebasePaymentDataSource(client.firestore),
              { client.auth.currentUser?.uid },
          ) {
            status
          }

  private fun refund(order: Order) =
      PaymentFixtures.refund()
          .copy(
              operationId = "refund-${order.id}",
              orderId = order.id,
              paymentId = order.id,
              requesterId = order.requesterId,
              requestedBy = order.requesterId,
          )

  private fun seed(
      owner: EmulatorClient,
      requester: EmulatorClient,
      status: OrderStatus = OrderStatus.PENDING,
  ): Order {
    val order =
        PaymentFixtures.order.copy(
            id = UUID.randomUUID().toString(),
            tripId = UUID.randomUUID().toString(),
            requesterId = requester.auth.currentUser!!.uid,
            status = status,
        )
    createDocumentAsOwner(
        "trips",
        order.tripId,
        mapOf("ownerId" to owner.auth.currentUser!!.uid, "status" to "PUBLISHED"),
    )
    createDocumentAsOwner(
        "orders",
        order.id,
        OrderDraft(order.tripId, order.items, order.commissionCents)
            .firestoreFields(order.requesterId) +
            mapOf("status" to status.name, "createdAt" to Timestamp(0, 0)),
    )
    createDocumentAsOwner(
        "payments",
        order.id,
        mapOf(
            "orderId" to order.id,
            "requesterId" to order.requesterId,
            "amountCents" to order.totalCents,
            "method" to "TWINT",
            "status" to "CONFIRMED",
        ),
    )
    return order
  }
}
