package com.android.sample.emulator.firestore.security

// AI assistance: OpenAI Codex.
import com.commando.app.data.repository.*
import com.commando.app.model.*
import com.commando.app.model.MeetupResponse.ACCEPTED
import com.commando.app.model.authentication.AuthRepositoryFirebase
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.Source
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit.MICROS
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
      val tripInput = NewTrip(location, Instant.now().plusSeconds(3600), location)
      val trip = (trips.publishTrip(tripInput) as TripResult.Success).data
      val orders =
          OrderRepositoryFirestore({ requester.auth.currentUser?.uid }, requester.firestore)
      val draft = OrderDraft(trip.id, listOf(OrderItem("milk", "Milk", 220, 1)), 100)
      val order = (orders.createOrder(draft) as OrderResult.Success).data
      val ownerOrders = OrderRepositoryFirestore({ owner.auth.currentUser?.uid }, owner.firestore)
      ownerOrders.decideOrder(order.id, OrderDecision.ACCEPT, order.revision)
      fun chat(client: EmulatorClient) =
          OrderChatRepositoryFirestore({ client.auth.currentUser?.uid }, client.firestore)
      val at = Instant.ofEpochSecond(Instant.now().epochSecond + 7200, 123456789)
      val proposal = MeetupProposal("Library entrance", at)
      val ok = OrderResult.Success(Unit)
      assertEquals(
          OrderResult.Error(OrderError.NotAuthenticated),
          chat(signedOut).respondToMeetup(order.id, "meetup", ACCEPTED),
      )
      for ((id, message, response) in
          listOf(
              Triple("", "meetup", ACCEPTED),
              Triple(order.id, "bad/path", ACCEPTED),
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
      assertTrue(chat(owner).respondToMeetup(order.id, "meetup", ACCEPTED) is OrderResult.Error)
      assertTrue(chat(outsider).respondToMeetup(order.id, "meetup", ACCEPTED) is OrderResult.Error)
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
          listOf(ACCEPTED, MeetupResponse.DECLINED)
              .map { async { chat(requester).respondToMeetup(order.id, "meetup", it) } }
              .awaitAll()
      assertEquals(1, outcomes.count { it is OrderResult.Success })
      val history = chat(requester).observeChat(order.id).first()
      val decided = (history as OrderResult.Success).data.messages.single().meetup!!
      assertNotNull(decided.respondedAt)
      assertEquals(proposal.location, decided.location)
      assertEquals(proposal.at.truncatedTo(MICROS), decided.at)
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
          chat(requester).respondToMeetup(order.id, "pending", ACCEPTED),
      )
      updateDocumentAsOwner("trips/${trip.id}", mapOf("status" to TripStatus.IN_PROGRESS.name))
      updateDocumentAsOwner("orders/${order.id}", mapOf("status" to OrderStatus.DELIVERED.name))
      assertEquals(
          OrderResult.Error(OrderError.PermissionDenied),
          chat(requester).respondToMeetup(order.id, "pending", ACCEPTED),
      )
      assertTrue(chat(owner).proposeMeetup(order.id, "late", proposal) is OrderResult.Error)
      assertEquals(ok, chat(requester).respondToMeetup(order.id, "meetup", decided.response))
      assertEquals(
          decided.response.name,
          ref.get(Source.SERVER).await().getString("meetup.response"),
      )
    }
  }

  @Test
  fun missingProposalReturnsNotFoundForTheRequester() = runTest {
    withAcceptedOrder { _, requester, orderId ->
      val repo =
          OrderChatRepositoryFirestore({ requester.auth.currentUser?.uid }, requester.firestore)
      assertEquals(
          OrderResult.Error(OrderError.NotFound),
          repo.respondToMeetup(orderId, "missing", ACCEPTED),
      )
    }
  }

  @Test
  fun missingOrderReturnsNotFound() = runTest {
    withClients("requester" to true) { (requester) ->
      val repo =
          OrderChatRepositoryFirestore({ requester.auth.currentUser?.uid }, requester.firestore)
      assertEquals(
          OrderResult.Error(OrderError.NotFound),
          repo.respondToMeetup("missing", "proposal", ACCEPTED),
      )
    }
  }

  @Test
  fun injectedClockRejectsExpiredCreationAndResponse() = runTest {
    withAcceptedOrder { owner, requester, orderId ->
      val at = Instant.now().plusSeconds(3600)
      val proposal = MeetupProposal("Library", at)
      val clock = Clock.fixed(at.plusSeconds(1), ZoneOffset.UTC)
      val ownerRepo = OrderChatRepositoryFirestore({ owner.auth.currentUser?.uid }, owner.firestore)
      val expiredOwner =
          OrderChatRepositoryFirestore({ owner.auth.currentUser?.uid }, owner.firestore, clock)
      assertEquals(
          OrderResult.Error(OrderError.InvalidData),
          expiredOwner.proposeMeetup(orderId, "expired", proposal),
      )
      assertEquals(OrderResult.Success(Unit), ownerRepo.proposeMeetup(orderId, "meeting", proposal))
      val requesterRepo =
          OrderChatRepositoryFirestore(
              { requester.auth.currentUser?.uid },
              requester.firestore,
              clock,
          )
      assertEquals(
          OrderResult.Error(OrderError.PermissionDenied),
          requesterRepo.respondToMeetup(orderId, "meeting", ACCEPTED),
      )
      val history = (ownerRepo.observeChat(orderId).first() as OrderResult.Success).data
      assertEquals(MeetupResponse.PENDING, history.messages.single().meetup!!.response)
    }
  }

  private suspend fun withAcceptedOrder(
      test: suspend (EmulatorClient, EmulatorClient, String) -> Unit
  ) {
    withClients("owner" to true, "requester" to true) { (owner, requester) ->
      val id = owner.app.name
      createDocumentAsOwner(
          "trips",
          id,
          mapOf("ownerId" to owner.auth.currentUser!!.uid, "status" to TripStatus.PUBLISHED.name),
      )
      createDocumentAsOwner(
          "orders",
          id,
          mapOf(
              "tripId" to id,
              "requesterId" to requester.auth.currentUser!!.uid,
              "status" to OrderStatus.ACCEPTED.name,
          ),
      )
      test(owner, requester, id)
    }
  }
}
