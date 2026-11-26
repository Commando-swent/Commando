package com.commando.app.emulator.firestore.security

import com.commando.app.data.repository.*
import com.commando.app.data.repository.PaymentError.*
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

/** Production-rule tests seed captures as admin; operations use authenticated SDK clients. */
@RunWith(RobolectricTestRunner::class)
class PaymentFirestoreSecurityRulesEmulatorTest {
  private val denied = OrderResult.Error(OrderError.PermissionDenied)

  private fun failure(error: PaymentError) = PaymentResult.Error(error)

  private lateinit var owner: EmulatorClient
  private lateinit var requester: EmulatorClient
  private lateinit var order: Order

  @Test
  fun requesterPaymentsPersistOneDerivedAmountForBothMethods() {
    for (method in PaymentMethod.entries) withOrder(OrderStatus.ACCEPTED, captured = false) {
      val expected =
          Payment(
              order.id,
              order.id,
              order.requesterId,
              order.totalCents,
              method,
              PaymentStatus.CONFIRMED,
          )
      val start = CompletableDeferred<Unit>()
      val results =
          List(4) {
                async(Dispatchers.IO) {
                  start.await()
                  repo().processPayment(order.id, method)
                }
              }
              .also { start.complete(Unit) }
              .awaitAll()
      assertEquals(setOf(PaymentResult.Success(expected)), results.toSet())
      assertEquals(PaymentResult.Success(expected), repo(owner).getPayment(order.id))
      assertEquals(PaymentResult.Success(expected), repo().processPayment(order.id, method))
    }
  }

  @Test
  fun settledPaymentOutcomesReconcileAndOnlyFailedPaymentsRetry() {
    for (status in listOf(PaymentStatus.CONFIRMED, PaymentStatus.FAILED)) withOrder(
        OrderStatus.ACCEPTED,
        captured = false,
    ) {
      val first = paymentRepo(status).processPayment(order.id, PaymentMethod.TWINT)
      assertEquals(status, (first as PaymentResult.Success).data.status)
      val retry =
          paymentRepo(PaymentStatus.CONFIRMED).processPayment(order.id, PaymentMethod.CARD)
              as PaymentResult.Success
      if (status == PaymentStatus.FAILED) {
        assertEquals(PaymentMethod.CARD, retry.data.method)
        assertEquals(PaymentStatus.CONFIRMED, retry.data.status)
      } else {
        assertEquals(first.data, retry.data)
      }
    }
  }

  @Test
  fun clientsCannotCreateOrRetryIntoUnsettledPayments() =
      withOrder(OrderStatus.ACCEPTED, captured = false) {
        for (status in listOf("PENDING", "UNCERTAIN")) {
          assertPermissionDenied("create $status") {
            doc("payments").set(failedPayment() + ("status" to status)).await()
          }
        }
        doc("payments").set(failedPayment()).await()
        for (status in listOf("PENDING", "UNCERTAIN")) {
          assertPermissionDenied("retry $status") {
            doc("payments").update("status", status).await()
          }
        }
        assertTrue(
            repo().processPayment(order.id, PaymentMethod.CARD) is PaymentResult.Success,
        )
        assertEquals("CONFIRMED", doc("payments").get(Source.SERVER).await().getString("status"))
      }

  @Test
  fun serverRecordedUnsettledPaymentsAreReturnedWithoutReprocessing() {
    for (status in listOf(PaymentStatus.PENDING, PaymentStatus.UNCERTAIN)) withOrder(
        OrderStatus.ACCEPTED,
    ) {
      updateDocumentAsOwner("payments/${order.id}", mapOf("status" to status.name))
      val result = paymentRepo(PaymentStatus.CONFIRMED).processPayment(order.id, PaymentMethod.CARD)
      assertEquals(status, (result as PaymentResult.Success).data.status)
      assertEquals(status.name, doc("payments").get(Source.SERVER).await().getString("status"))
    }
  }

  @Test
  fun forgedPaymentFieldsAreDenied() =
      withOrder(OrderStatus.ACCEPTED, captured = false) {
        val valid = failedPayment()
        for ((field, value) in
            listOf(
                "orderId" to "forged",
                "requesterId" to "forged",
                "amountCents" to order.totalCents + 1,
                "amountCents" to order.totalCents.toDouble(),
                "method" to "CASH",
                "status" to "OTHER",
                "extra" to true,
            )) {
          assertPermissionDenied(field) { doc("payments").set(valid + (field to value)).await() }
        }
        assertPermissionDenied("missing status") { doc("payments").set(valid - "status").await() }
      }

  @Test
  fun tripOwnerAndOutsidersCannotWritePayments() =
      withOrder(OrderStatus.ACCEPTED, captured = false) {
        withClients("other" to true) { (other) ->
          for (client in listOf(owner, other)) assertPermissionDenied {
            doc("payments", client = client).set(failedPayment()).await()
          }
        }
      }

  @Test
  fun onlyAFailedPaymentCanBeRewritten() =
      withOrder(OrderStatus.ACCEPTED, captured = false) {
        doc("payments").set(failedPayment()).await()
        doc("payments").update(mapOf("method" to "CARD", "status" to "CONFIRMED")).await()
        assertPermissionDenied { doc("payments").update("status", "FAILED").await() }
      }

  @Test
  fun concurrentRefundsPersistOneOperationAndReloadWithoutAnotherWrite() = withOrder {
    val start = CompletableDeferred<Unit>()
    val jobs =
        List(4) {
          async(Dispatchers.IO) {
            start.await()
            repo().refundForCancellation(order.id)
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
    val timestamp = doc("refunds").get(Source.SERVER).await().getTimestamp("updatedAt")
    assertEquals(PaymentResult.Success(refunds.first()), repo().refundForCancellation(order.id))
    assertEquals(timestamp, doc("refunds").get(Source.SERVER).await().getTimestamp("updatedAt"))
  }

  @Test fun pendingRefundBlocksAcceptedCancellation() = acceptedCancellation(RefundStatus.PENDING)

  @Test fun failedRefundBlocksAcceptedCancellation() = acceptedCancellation(RefundStatus.FAILED)

  @Test
  fun uncertainRefundBlocksAcceptedCancellation() = acceptedCancellation(RefundStatus.UNCERTAIN)

  @Test
  fun confirmedRefundAllowsAtomicAcceptedCancellation() =
      acceptedCancellation(RefundStatus.CONFIRMED)

  // AI assistance: OpenAI Codex (#387): exercise all four writes with production rule budgets.
  @Test fun pendingRefundBlocksLateCancellation() = acceptedCancellation(RefundStatus.PENDING, true)

  @Test fun failedRefundBlocksLateCancellation() = acceptedCancellation(RefundStatus.FAILED, true)

  @Test
  fun uncertainRefundBlocksLateCancellation() = acceptedCancellation(RefundStatus.UNCERTAIN, true)

  @Test
  fun confirmedRefundAllowsFirstLateCounter() = acceptedCancellation(RefundStatus.CONFIRMED, true)

  @Test
  fun confirmedRefundAllowsExistingLateCounter() =
      acceptedCancellation(RefundStatus.CONFIRMED, true, true)

  @Test fun paidDeliveryAllowsFirstCounterWithinRuleBudget() = paidDelivery(false)

  @Test fun paidDeliveryAllowsExistingCounterWithinRuleBudget() = paidDelivery(true)

  private fun paidDelivery(existing: Boolean) =
      withOrder(OrderStatus.READY_FOR_HANDOFF) {
        val uid = owner.auth.currentUser!!.uid
        if (existing)
            createDocumentAsOwner(
                "reliabilitySummaries",
                uid,
                mapOf("completedCount" to 4L, "lateCancellationCount" to 2L),
            )
        val orders =
            OrderRepositoryFirestore({ requester.auth.currentUser?.uid }, requester.firestore)
        assertEquals(
            OrderResult.Success(order.copy(status = OrderStatus.DELIVERED)),
            orders.confirmReceipt(order.id),
        )
        assertEquals(
            mapOf(
                "completedCount" to if (existing) 5L else 1L,
                "lateCancellationCount" to if (existing) 2L else 0L,
            ),
            owner.firestore
                .collection("reliabilitySummaries")
                .document(uid)
                .get(Source.SERVER)
                .await()
                .data,
        )
      }

  private fun acceptedCancellation(
      status: RefundStatus,
      late: Boolean = false,
      existing: Boolean = false,
  ) =
      withOrder(OrderStatus.ACCEPTED) {
        val ownerId = owner.auth.currentUser!!.uid
        val summary = owner.firestore.collection("reliabilitySummaries").document(ownerId)
        if (late)
            createDocumentAsOwner(
                "orderEvents",
                "${order.id}_ORDER_ACCEPTED",
                mapOf(
                    "orderId" to order.id,
                    "recipientId" to order.requesterId,
                    "type" to "ORDER_ACCEPTED",
                    "occurredAt" to Timestamp(0, 0),
                ),
            )
        if (existing)
            createDocumentAsOwner(
                "reliabilitySummaries",
                ownerId,
                mapOf(
                    "completedCount" to 4L,
                    "lateCancellationCount" to 2L,
                ),
            )
        val refund = repo(owner, status).refundForCancellation(order.id)
        assertEquals(status, (refund as PaymentResult.Success).data.status)
        assertEquals(refund, repo().getRefund(order.id))
        val result = orders().cancelAcceptedOrder(order.id)
        val events = events().filter { it.getString("type") == "ORDER_CANCELLED" }
        if (status == RefundStatus.CONFIRMED) {
          assertEquals(OrderResult.Success(order.copy(status = OrderStatus.CANCELLED)), result)
          val event = events.single()
          assertEquals(order.requesterId, event.getString("recipientId"))
          assertEquals("ORDER_CANCELLED", event.getString("type"))
          assertNotNull(event.getTimestamp("occurredAt"))
        } else {
          assertEquals(denied, result)
          assertTrue(events.isEmpty())
        }
        assertEquals(
            if (status == RefundStatus.CONFIRMED) "CANCELLED" else "ACCEPTED",
            doc("orders").get(Source.SERVER).await().getString("status"),
        )
        val counted = late && status == RefundStatus.CONFIRMED
        assertEquals(
            if (existing || counted)
                mapOf(
                    "completedCount" to if (existing) 4L else 0L,
                    "lateCancellationCount" to (if (existing) 2L else 0L) + if (counted) 1L else 0L,
                )
            else null,
            summary.get(Source.SERVER).await().data,
        )
      }

  @Test
  fun pendingRefundSurvivesReloadAndRetryBeforeRequesterCancellation() = withOrder {
    val pending = repo(requester, RefundStatus.PENDING).refundForCancellation(order.id)
    assertEquals(RefundStatus.PENDING, (pending as PaymentResult.Success).data.status)
    assertEquals(pending, repo().getRefund(order.id))
    assertPermissionDenied { doc("orders").update("status", "CANCELLED").await() }
    assertEquals(
        RefundStatus.CONFIRMED,
        (repo().refundForCancellation(order.id) as PaymentResult.Success).data.status,
    )
    doc("orders").update("status", "CANCELLED").await()
    assertTrue(repo().refundForCancellation(order.id) is PaymentResult.Success)
  }

  @Test
  fun outsidersAndSignedOutClientsCannotReadPaymentsOrRefunds() = withOrder {
    withClients("other" to true, "out" to false) { clients ->
      for (client in clients) for (collection in listOf("payments", "refunds")) {
        assertPermissionDenied { doc(collection, client = client).get(Source.SERVER).await() }
      }
      assertEquals(
          failure(PERMISSION_DENIED),
          repo(clients[0]).getPayment(order.id),
      )
      assertEquals(
          failure(NOT_AUTHENTICATED),
          repo(clients[1]).getRefund(order.id),
      )
    }
  }

  @Test
  fun onlyTheRequesterCanRefundAPendingOrder() = withOrder {
    assertEquals(
        failure(INELIGIBLE),
        repo(owner).refundForCancellation(order.id),
    )
    assertTrue(repo().refundForCancellation(order.id) is PaymentResult.Success)
  }

  @Test
  fun requesterCannotRefundAnAcceptedOrder() =
      withOrder(OrderStatus.ACCEPTED) {
        assertEquals(
            failure(INELIGIBLE),
            repo().refundForCancellation(order.id),
        )
      }

  @Test
  fun collectionListingAndDeletionAreDenied() = withOrder {
    withClients("other" to true, "out" to false) { clients ->
      for (client in listOf(owner, requester) + clients) for (collection in
          listOf("payments", "refunds")) {
        assertPermissionDenied {
          client.firestore.collection(collection).get(Source.SERVER).await()
        }
        assertPermissionDenied { doc(collection, client = client).delete().await() }
      }
    }
  }

  @Test
  fun forgedRefundFieldsAreDenied() = withOrder {
    val data = refund().firestoreFields()
    for ((field, value) in
        listOf(
            "amountCents" to order.totalCents + 1,
            "amountCents" to order.totalCents.toDouble(),
            "requesterId" to "forged",
            "requestedBy" to owner.auth.currentUser!!.uid,
            "orderId" to "forged",
            "paymentId" to "forged",
            "operationId" to "new",
            "status" to "OTHER",
            "updatedAt" to Timestamp(0, 0),
            "extra" to true,
        )) {
      assertPermissionDenied(field) { doc("refunds").set(data + (field to value)).await() }
    }
    assertPermissionDenied("missing status") { doc("refunds").set(data - "status").await() }
  }

  @Test
  fun unauthorizedActorsCannotWriteRefundsDirectly() = withOrder {
    withClients("other" to true) { (other) ->
      for (client in listOf(owner, other)) assertPermissionDenied {
        doc("refunds", client = client)
            .set(refund().firestoreFields() + ("requestedBy" to client.auth.currentUser!!.uid))
            .await()
      }
    }
  }

  @Test
  fun terminalAndHandoffOrdersRejectNewRefunds() = withOrder {
    for (status in
        OrderStatus.entries.filter { it !in listOf(OrderStatus.PENDING, OrderStatus.ACCEPTED) }) {
      updateDocumentAsOwner("orders/${order.id}", mapOf("status" to status.name))
      assertPermissionDenied(status.name) { doc("refunds").set(refund().firestoreFields()).await() }
    }
  }

  @Test
  fun confirmedFinancialRecordsCannotBeMutated() = withOrder {
    doc("refunds").set(refund().firestoreFields()).await()
    for (change in
        listOf(mapOf("status" to "FAILED"), mapOf("amountCents" to 1), mapOf("extra" to true))) {
      assertPermissionDenied(change.toString()) { doc("refunds").update(change).await() }
    }
    assertPermissionDenied { doc("payments").update("status", "CONFIRMED").await() }
  }

  @Test
  fun uncapturedPaymentsCannotAuthorizeRefundsOrPrematureCancellation() = withOrder {
    for (status in PaymentStatus.entries.filter { it != PaymentStatus.CONFIRMED }) {
      updateDocumentAsOwner("payments/${order.id}", mapOf("status" to status.name))
      assertEquals(
          failure(NOT_CAPTURED),
          repo().refundForCancellation(order.id),
      )
      if (status != PaymentStatus.FAILED)
          assertPermissionDenied(status.name) {
            doc("orders").update("status", "CANCELLED").await()
          }
    }
  }

  @Test
  fun malformedPaymentRelationshipsFailClosed() = withOrder {
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
      assertEquals(failure(INVALID_DATA), repo().getPayment(order.id))
      assertPermissionDenied(fields.toString()) {
        doc("orders").update("status", "CANCELLED").await()
      }
    }
    updateDocumentAsOwner("trips/${order.tripId}", mapOf("ownerId" to false))
    assertEquals(failure(INVALID_DATA), repo().getPayment(order.id))
  }

  @Test
  fun missingPaymentPreservesUnpaidCancellation() = withOrder {
    assertEquals(
        failure(NOT_FOUND),
        repo().getPayment(UUID.randomUUID().toString()),
    )
    deleteDocumentAsOwner("payments/${order.id}")
    assertFalse(doc("payments").get(Source.SERVER).await().exists())
    assertEquals(PaymentResult.Success(null), repo().getPayment(order.id))
    doc("orders").update("status", "CANCELLED").await()
  }

  @Test fun pendingPaymentBlocksHandoff() = handoff(PaymentStatus.PENDING)

  @Test fun failedPaymentBlocksHandoff() = handoff(PaymentStatus.FAILED)

  @Test fun uncertainPaymentBlocksHandoff() = handoff(PaymentStatus.UNCERTAIN)

  @Test fun confirmedPaymentAllowsHandoff() = handoff(PaymentStatus.CONFIRMED)

  private fun handoff(status: PaymentStatus) =
      withOrder(OrderStatus.ACCEPTED) {
        updateDocumentAsOwner("payments/${order.id}", mapOf("status" to status.name))
        val result = orders().markReadyForHandoff(order.id)
        if (status == PaymentStatus.CONFIRMED) assertTrue(result is OrderResult.Success)
        else assertEquals(denied, result)
      }

  @Test
  fun refundReservationBlocksPendingAcceptance() = withOrder {
    val refund = repo(requester, RefundStatus.PENDING).refundForCancellation(order.id)
    assertEquals(RefundStatus.PENDING, (refund as PaymentResult.Success).data.status)
    assertEquals(denied, orders().decideOrder(order.id, OrderDecision.ACCEPT, 0))
    assertEquals("PENDING", doc("orders").get(Source.SERVER).await().getString("status"))
    assertTrue(events().isEmpty())
  }

  @Test
  fun paidPendingRejectionLeavesOrderAndEventUnchanged() = withOrder {
    assertEquals(
        denied,
        orders().decideOrder(order.id, OrderDecision.REJECT, 0),
    )
    assertEquals("PENDING", doc("orders").get(Source.SERVER).await().getString("status"))
    assertTrue(events().isEmpty())
    assertTrue(repo().refundForCancellation(order.id) is PaymentResult.Success)
  }

  @Test
  fun unpaidPendingRejectionRemainsAvailable() = withOrder {
    deleteDocumentAsOwner("payments/${order.id}")
    assertEquals(
        OrderResult.Success(order.copy(status = OrderStatus.REJECTED)),
        orders().decideOrder(order.id, OrderDecision.REJECT, 0),
    )
  }

  @Test
  fun refundAndHandoffRaceHasExactlyOneWinner() =
      withOrder(OrderStatus.ACCEPTED) {
        val start = CompletableDeferred<Unit>()
        val refund =
            async(Dispatchers.IO) {
              start.await()
              repo(owner, RefundStatus.PENDING).refundForCancellation(order.id)
            }
        val handoff =
            async(Dispatchers.IO) {
              start.await()
              orders().markReadyForHandoff(order.id)
            }
        start.complete(Unit)
        val refunded = refund.await() is PaymentResult.Success
        val handedOff = handoff.await() is OrderResult.Success
        assertTrue("Exactly one operation must commit", refunded xor handedOff)
        assertEquals(
            if (handedOff) "READY_FOR_HANDOFF" else "ACCEPTED",
            doc("orders").get(Source.SERVER).await().getString("status"),
        )
        assertEquals(refunded, doc("refunds").get(Source.SERVER).await().exists())
      }

  @Test
  fun capturedPendingOrderCannotBeEdited() = withOrder {
    assertPermissionDenied { doc("orders").update("commissionCents", 76, "revision", 1).await() }
  }

  private fun doc(collection: String, id: String = order.id, client: EmulatorClient = requester) =
      client.firestore.collection(collection).document(id)

  private suspend fun events() =
      requester.firestore
          .collection("orderEvents")
          .whereEqualTo("recipientId", order.requesterId)
          .get(Source.SERVER)
          .await()
          .documents

  private fun orders() = OrderRepositoryFirestore({ owner.auth.currentUser?.uid }, owner.firestore)

  private fun repo(
      client: EmulatorClient = requester,
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

  private fun failedPayment() =
      Payment(
              order.id,
              order.id,
              order.requesterId,
              order.totalCents,
              PaymentMethod.TWINT,
              PaymentStatus.FAILED,
          )
          .firestoreFields()

  private fun paymentRepo(status: PaymentStatus): PaymentRepository =
      TransactionalPaymentRepository(
          FirebasePaymentDataSource(requester.firestore),
          { requester.auth.currentUser?.uid },
          simulatePayment = { status },
      )

  private fun refund() =
      PaymentFixtures.refund()
          .copy(
              operationId = "refund-${order.id}",
              orderId = order.id,
              paymentId = order.id,
              requesterId = order.requesterId,
              requestedBy = order.requesterId,
          )

  private fun withOrder(
      status: OrderStatus = OrderStatus.PENDING,
      captured: Boolean = true,
      test: suspend CoroutineScope.() -> Unit,
  ) = runTest {
    withClients("owner" to true, "requester" to true) { clients ->
      owner = clients[0]
      requester = clients[1]
      order =
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
      if (captured)
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
      test()
    }
  }
}
