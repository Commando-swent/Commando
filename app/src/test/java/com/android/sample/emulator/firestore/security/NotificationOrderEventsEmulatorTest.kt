package com.android.sample.emulator.firestore.security

// AI assistance: OpenAI Codex.
import com.android.sample.data.repository.*
import com.android.sample.model.*
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.Source
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Real order operations and canonical events against production Auth/Firestore rules. */
@RunWith(RobolectricTestRunner::class)
class NotificationOrderEventsEmulatorTest {
  @Test
  fun creationAndBothDecisionsNotifyOnlyTheIntendedRecipientOnce() = runTest {
    withClients("owner" to true, "requester" to true, "other" to true) { (owner, requester, other)
      ->
      for (decision in OrderDecision.entries) {
        val order = place(owner, requester)
        val placed = inbox(owner).last { it.event.orderId == order.id }
        assertEquals(NotificationEventType.ORDER_PLACED, placed.event.type)
        assertEquals(order.createdAt, placed.event.occurredAt)
        assertTrue(inbox(requester).none { it.event.orderId == order.id })
        repeat(2) {
          assertTrue(orders(owner).decideOrder(order.id, decision) is OrderResult.Success)
        }
        val notification = inbox(requester).single { it.event.orderId == order.id }
        assertEquals("ORDER_${decision.targetStatus().name}", notification.event.type.name)
        assertEquals(requester.auth.currentUser!!.uid, notification.event.recipientId)
        assertTrue(inbox(other).isEmpty())
        // A fresh repository retries read-only; it neither recreates nor overwrites the event.
        val again = orders(owner).decideOrder(order.id, decision)
        assertTrue(again is OrderResult.Success)
        assertEquals(notification, inbox(requester).single { it.event.orderId == order.id })
      }
    }
  }

  @Test
  fun competingDecisionsPublishOnlyThePersistedWinner() = runTest {
    withClients("owner" to true, "requester" to true) { (owner, requester) ->
      val order = place(owner, requester)
      OrderDecision.entries
          .map { decision -> async { orders(owner).decideOrder(order.id, decision) } }
          .awaitAll()
      val stored = (orders(requester).getOrderById(order.id) as OrderResult.Success).data
      val notifications = inbox(requester)
      assertEquals(1, notifications.size)
      assertEquals("ORDER_${stored.status.name}", notifications.single().event.type.name)
    }
  }

  @Test
  fun orderWritesWithoutTheirEventsAreDeniedWithoutChangingTheOrder() = runTest {
    withClients("owner" to true, "requester" to true) { (owner, requester) ->
      val saved = place(owner, requester)
      val original =
          requester.firestore
              .collection("orders")
              .document(saved.id)
              .get(Source.SERVER)
              .await()
              .data!!
      assertPermissionDenied {
        requester.firestore
            .collection("orders")
            .document()
            .set(original + ("createdAt" to FieldValue.serverTimestamp()))
            .await()
      }
      val ref = owner.firestore.collection("orders").document(saved.id)
      assertPermissionDenied { ref.update("status", "ACCEPTED").await() }
      assertEquals("PENDING", ref.get(Source.SERVER).await().getString("status"))
      assertTrue(inbox(requester).isEmpty())
    }
  }

  @Test
  fun forgedEventRecipientsTypesTimesAndIdsRollBackTheWholeBatch() = runTest {
    withClients("owner" to true, "requester" to true, "other" to true) { (owner, requester, other)
      ->
      val saved = place(owner, requester)
      val source =
          requester.firestore
              .collection("orders")
              .document(saved.id)
              .get(Source.SERVER)
              .await()
              .data!!
      for (mutation in
          listOf(
              mapOf("recipientId" to other.auth.currentUser!!.uid),
              mapOf("type" to "ORDER_ACCEPTED"),
              mapOf("occurredAt" to Timestamp(0, 0)),
              mapOf("orderId" to saved.id),
              mapOf("extra" to true),
          )) {
        val ref = requester.firestore.collection("orders").document()
        val event =
            mapOf(
                "orderId" to ref.id,
                "recipientId" to owner.auth.currentUser!!.uid,
                "type" to "ORDER_PLACED",
                "occurredAt" to FieldValue.serverTimestamp(),
            )
        assertPermissionDenied {
          requester.firestore
              .runBatch {
                it.set(ref, source + ("createdAt" to FieldValue.serverTimestamp()))
                it.set(
                    requester.firestore
                        .collection("orderEvents")
                        .document("${ref.id}_ORDER_PLACED"),
                    event + mutation,
                )
              }
              .await()
        }
        assertFalse(ref.get(Source.SERVER).await().exists())
      }
      val event = owner.firestore.collection("orderEvents").document("${saved.id}_ORDER_PLACED")
      assertPermissionDenied { event.update("occurredAt", FieldValue.serverTimestamp()).await() }
      assertPermissionDenied { event.delete().await() }
      // Publishing a decision without actually transitioning the order is forbidden too.
      assertPermissionDenied {
        owner.firestore
            .collection("orderEvents")
            .document("${saved.id}_ORDER_ACCEPTED")
            .set(
                mapOf(
                    "orderId" to saved.id,
                    "recipientId" to requester.auth.currentUser!!.uid,
                    "type" to "ORDER_ACCEPTED",
                    "occurredAt" to FieldValue.serverTimestamp(),
                )
            )
            .await()
      }
    }
  }

  @Test
  fun preferencesFilterRealOrderEventsAndReadStatePersists() = runTest {
    withClients("owner" to true, "requester" to true) { (owner, requester) ->
      val repo = notifications(owner)
      repo.updatePreferences(NotificationPreferences(newOrders = false))
      val muted = place(owner, requester)
      assertTrue(inbox(owner).isEmpty())
      repo.updatePreferences(NotificationPreferences())
      assertTrue(inbox(owner).isEmpty())
      val visible = place(owner, requester)
      val notification = inbox(owner).single()
      assertEquals(visible.id, notification.event.orderId)
      assertNotEquals(muted.id, notification.event.orderId)
      assertEquals(OrderResult.Success(Unit), repo.markAsRead(notification.event.id))
      assertTrue(inbox(owner).single().isRead)
    }
  }

  private fun orders(client: EmulatorClient) =
      OrderRepositoryFirestore({ client.auth.currentUser?.uid }, client.firestore)

  private fun notifications(client: EmulatorClient) =
      NotificationRepositoryFirestore({ client.auth.currentUser?.uid }, client.firestore)

  private suspend fun inbox(client: EmulatorClient) =
      (notifications(client).getNotifications() as OrderResult.Success).data

  private suspend fun place(owner: EmulatorClient, requester: EmulatorClient): Order {
    val id = UUID.randomUUID().toString()
    val time = Instant.now()
    val location = mapOf("name" to "EPFL", "latitude" to 46.5, "longitude" to 6.5)
    owner.firestore
        .collection("trips")
        .document(id)
        .set(
            mapOf(
                "ownerId" to owner.auth.currentUser!!.uid,
                "store" to location,
                "handoffLocation" to location,
                "status" to "PUBLISHED",
                "scheduledAt" to Timestamp(time.plusSeconds(3600)),
                "createdAt" to Timestamp(time),
                "updatedAt" to Timestamp(time),
            )
        )
        .await()
    return (orders(requester).createOrder(OrderDraft(id, listOf(OrderItem("milk", "Milk", 220, 1))))
            as OrderResult.Success)
        .data
  }
}
