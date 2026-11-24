package com.android.sample.emulator.firestore.security

// AI assistance: OpenAI Codex.
import com.android.sample.model.authentication.AuthRepositoryFirebase
import com.commando.app.data.repository.*
import com.commando.app.model.*
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.Source
import java.time.Instant
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MeetupSecurityEmulatorTest {
  @Test
  fun firstRequesterDecisionWinsAndCannotRewriteTheProposal() = runTest {
    withClients("owner" to true, "requester" to true, "outsider" to true, "signedOut" to false) {
        (owner, requester, outsider, signedOut) ->
      val trips = TripRepositoryFirestore(AuthRepositoryFirebase(owner.auth), owner.firestore)
      val location = Location("Migros EPFL", 46.52, 6.57)
      val trip =
          (trips.publishTrip(NewTrip(location, Instant.now().plusSeconds(3600), location))
                  as TripResult.Success)
              .data
      val orders =
          OrderRepositoryFirestore({ requester.auth.currentUser?.uid }, requester.firestore)
      val order =
          (orders.createOrder(OrderDraft(trip.id, listOf(OrderItem("milk", "Milk", 220, 1)), 100))
                  as OrderResult.Success)
              .data
      val ownerOrders = OrderRepositoryFirestore({ owner.auth.currentUser?.uid }, owner.firestore)
      ownerOrders.decideOrder(order.id, OrderDecision.ACCEPT, order.revision)
      fun chat(client: EmulatorClient) =
          OrderChatRepositoryFirestore({ client.auth.currentUser?.uid }, client.firestore)
      val proposal = MeetupProposal("Library entrance", Instant.now().plusSeconds(7200))
      val ok = OrderResult.Success(Unit)
      assertEquals(
          OrderResult.Error(OrderError.NotAuthenticated),
          chat(signedOut).respondToMeetup(order.id, "meetup", MeetupResponse.ACCEPTED),
      )
      for ((id, message, response) in
          listOf(
              Triple("", "meetup", MeetupResponse.ACCEPTED),
              Triple(order.id, "bad/path", MeetupResponse.ACCEPTED),
              Triple(order.id, "meetup", MeetupResponse.PENDING),
          )) {
        assertEquals(
            OrderResult.Error(OrderError.InvalidData),
            chat(requester).respondToMeetup(id, message, response),
        )
      }
      assertEquals(
          OrderResult.Error(OrderError.InvalidData),
          chat(owner).proposeMeetup(order.id, "expired", proposal.copy(at = Instant.EPOCH)),
      )
      assertEquals(ok, chat(owner).proposeMeetup(order.id, "meetup", proposal))
      assertTrue(chat(requester).proposeMeetup(order.id, "forged", proposal) is OrderResult.Error)
      assertTrue(
          chat(owner).respondToMeetup(order.id, "meetup", MeetupResponse.ACCEPTED)
              is OrderResult.Error
      )
      assertTrue(
          chat(outsider).respondToMeetup(order.id, "meetup", MeetupResponse.ACCEPTED)
              is OrderResult.Error
      )
      val ref =
          requester.firestore
              .collection("orders")
              .document(order.id)
              .collection("messages")
              .document("meetup")
      assertPermissionDenied { ref.update("meetup.location", "Spoofed place").await() }
      assertPermissionDenied {
        ref.update(mapOf("meetup.response" to "ACCEPTED", "meetup.respondedAt" to Timestamp(0, 0)))
            .await()
      }
      val outcomes =
          listOf(MeetupResponse.ACCEPTED, MeetupResponse.DECLINED)
              .map { async { chat(requester).respondToMeetup(order.id, "meetup", it) } }
              .awaitAll()
      assertEquals(1, outcomes.count { it is OrderResult.Success })
      val decided =
          (chat(requester).observeChat(order.id).first() as OrderResult.Success)
              .data
              .messages
              .single()
              .meetup!!
      assertNotNull(decided.respondedAt)
      assertEquals(proposal.location, decided.location)
      assertEquals(proposal.at, decided.at)
      assertEquals(ok, chat(owner).proposeMeetup(order.id, "meetup", proposal))
      assertEquals(ok, chat(requester).respondToMeetup(order.id, "meetup", decided.response))
      assertPermissionDenied {
        ref.update(
                mapOf(
                    "meetup.response" to "PENDING",
                    "meetup.respondedAt" to FieldValue.serverTimestamp(),
                )
            )
            .await()
      }
      assertPermissionDenied { ref.delete().await() }
      val data = ref.get(Source.SERVER).await().data!!
      val fields = data.getValue("meetup") as Map<*, *>
      for (invalid in
          listOf(
              42,
              fields + ("extra" to true),
              fields - "location",
              fields + ("at" to "invalid"),
              fields - "response",
              fields + ("respondedAt" to "invalid"),
              fields + ("response" to "PENDING"),
          )) {
        createDocumentAsOwner(
            "orders/${order.id}/messages",
            "corrupt",
            data + ("meetup" to invalid),
        )
        assertTrue(chat(requester).observeChat(order.id).first() is OrderResult.Error)
        deleteDocumentAsOwner("orders/${order.id}/messages/corrupt")
      }
      assertEquals(ok, chat(owner).proposeMeetup(order.id, "pending", proposal))
      updateDocumentAsOwner("trips/${trip.id}", mapOf("status" to TripStatus.CANCELLED.name))
      assertEquals(
          OrderResult.Error(OrderError.PermissionDenied),
          chat(requester).respondToMeetup(order.id, "pending", MeetupResponse.ACCEPTED),
      )
      updateDocumentAsOwner("trips/${trip.id}", mapOf("status" to TripStatus.IN_PROGRESS.name))
      updateDocumentAsOwner("orders/${order.id}", mapOf("status" to OrderStatus.DELIVERED.name))
      assertEquals(
          OrderResult.Error(OrderError.PermissionDenied),
          chat(requester).respondToMeetup(order.id, "pending", MeetupResponse.ACCEPTED),
      )
      assertTrue(chat(owner).proposeMeetup(order.id, "late", proposal) is OrderResult.Error)
      assertEquals(ok, chat(requester).respondToMeetup(order.id, "meetup", decided.response))
      assertEquals(
          decided.response.name,
          ref.get(Source.SERVER).await().getString("meetup.response"),
      )
    }
  }
}
