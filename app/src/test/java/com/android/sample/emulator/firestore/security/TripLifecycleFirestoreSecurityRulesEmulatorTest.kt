package com.android.sample.emulator.firestore.security

// AI assistance: Claude Code.

import com.android.sample.data.repository.*
import com.android.sample.data.repository.TripError.*
import com.android.sample.model.*
import com.android.sample.model.OrderDecision.ACCEPT
import com.android.sample.model.OrderDecision.REJECT
import com.android.sample.model.TripTransition.CLOSE
import com.android.sample.model.TripTransition.COMPLETE
import com.android.sample.model.authentication.AuthRepositoryFirebase
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.Source
import java.time.Instant
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Trip close and complete (#233) against the production rules, for every kind of actor. */
@RunWith(RobolectricTestRunner::class)
class TripLifecycleFirestoreSecurityRulesEmulatorTest {
  private val clients =
      arrayOf("owner" to true, "requester" to true, "outsider" to true, "signedOut" to false)

  @Test
  fun owner_closesThenCompletesAndIsDeniedEveryOtherChange() = runTest {
    val denied =
        listOf(
            "PUBLISHED" to move("COMPLETED"),
            "PUBLISHED" to move("CANCELLED"),
            "IN_PROGRESS" to move("PUBLISHED"),
            "IN_PROGRESS" to move("CANCELLED"),
            "COMPLETED" to move("IN_PROGRESS"),
            "COMPLETED" to move("PUBLISHED"),
            "PUBLISHED" to move("PUBLISHED"),
            "PUBLISHED" to mapOf("status" to "IN_PROGRESS"),
            "PUBLISHED" to move("IN_PROGRESS") + ("updatedAt" to Timestamp.now()),
            "PUBLISHED" to move("IN_PROGRESS") + ("scheduledAt" to Timestamp(0, 0)),
            "PUBLISHED" to move("IN_PROGRESS") + ("store" to mapOf("name" to "Coop")),
            "PUBLISHED" to move("IN_PROGRESS") + ("ownerId" to "someone-else"),
        )
    withClients("owner" to true) { (owner) ->
      val trip = publish(owner, "PUBLISHED")
      for (status in listOf("IN_PROGRESS", "COMPLETED")) {
        tripRef(owner, trip).update(move(status)).await()
        assertEquals(status, statusOf(owner, trip))
      }
      for ((from, change) in denied) {
        val seeded = publish(owner, from)
        assertPermissionDenied("$from -> $change") { tripRef(owner, seeded).update(change).await() }
        assertEquals(from, statusOf(owner, seeded))
      }
    }
  }

  @Test
  fun requesterOutsiderAndSignedOut_areDeniedBothTransitions() = runTest {
    withClients(*clients) { (owner, requester, outsider, signedOut) ->
      val trip = publishWithOrder(owner, requester)
      for (transition in TripTransition.entries) {
        updateDocumentAsOwner("trips/$trip", mapOf("status" to transition.source.name))
        for (actor in listOf(requester, outsider, signedOut)) {
          assertPermissionDenied("$transition by ${actor.app.name}") {
            tripRef(actor, trip).update(move(transition.target.name)).await()
          }
          assertEquals(transition.source.name, statusOf(owner, trip))
        }
      }
    }
  }

  @Test
  fun closedTripsAreReadableByIdNotListableAndCancelledTripsAreOwnerOnly() = runTest {
    withClients(*clients) { (owner, requester, outsider, signedOut) ->
      val trip = publishWithOrder(owner, requester)
      for (status in listOf("IN_PROGRESS", "COMPLETED", "CANCELLED")) {
        updateDocumentAsOwner("trips/$trip", mapOf("status" to status))
        val canGet =
            if (status == "CANCELLED") listOf(owner) else listOf(owner, requester, outsider)
        for (reader in listOf(owner, requester, outsider, signedOut)) {
          val get = suspend { tripRef(reader, trip).get(Source.SERVER).await() }
          if (reader in canGet) assertTrue("$status get", get().exists())
          else assertPermissionDenied("$status get by ${reader.app.name}") { get() }
        }
        for (reader in listOf(requester, outsider)) {
          assertPermissionDenied("$status list") {
            reader.firestore
                .collection("trips")
                .whereEqualTo("status", status)
                .get(Source.SERVER)
                .await()
          }
        }
        assertTrue(trips(owner).getMyTrips().successData().any { it.id == trip })
      }
    }
  }

  @Test
  fun repositoryFlow_closeBlocksNewOrdersAndCompleteWaitsForOpenOrders() = runTest {
    withClients("owner" to true, "requester" to true) { (owner, requester) ->
      val ownerTrips = trips(owner)
      val trip = publishWithOrder(owner, requester, listOf(150, 300))
      val (a, b) = ordersOf(owner, trip).sortedBy { it.commissionCents }
      for (transition in TripTransition.entries) {
        // The rules cannot read a missing document's owner; the repository must still say NotFound.
        val unknown = ownerTrips.transitionTrip("missing-trip", transition)
        assertEquals("$transition on unknown trip", TripResult.Error(NotFound), unknown)
      }
      val closed = ownerTrips.transitionTrip(trip, CLOSE).successData()

      assertEquals(TripStatus.IN_PROGRESS, closed.status)
      assertEquals(TripResult.Success(closed), ownerTrips.transitionTrip(trip, CLOSE))
      assertEquals(
          TripResult.Error(PermissionDenied),
          trips(requester).transitionTrip(trip, CLOSE),
      )
      assertEquals(
          OrderResult.Error(OrderError.InvalidData),
          orders(requester).createOrder(OrderDraft(trip, listOf(ITEM))),
      )
      assertTrue(trips(requester).getUpcomingTrips().successData().none { it.id == trip })
      assertEquals(TripResult.Error(OrdersStillOpen), ownerTrips.transitionTrip(trip, COMPLETE))

      // Orders of a closed trip can still be settled; each step must succeed on its own.
      assertTrue(
          "accept",
          orders(owner).decideOrder(a.id, ACCEPT, a.revision) is OrderResult.Success,
      )
      assertTrue("ready", orders(owner).markReadyForHandoff(a.id) is OrderResult.Success)
      assertTrue("receipt", orders(requester).confirmReceipt(a.id) is OrderResult.Success)
      assertTrue(
          "reject",
          orders(owner).decideOrder(b.id, REJECT, b.revision) is OrderResult.Success,
      )

      val completed = ownerTrips.transitionTrip(trip, COMPLETE).successData()
      assertEquals(TripStatus.COMPLETED, completed.status)
      assertEquals(TripResult.Error(InvalidTransition), ownerTrips.transitionTrip(trip, CLOSE))
    }
  }

  private fun move(status: String): Map<String, Any> =
      mapOf("status" to status, "updatedAt" to FieldValue.serverTimestamp())

  private fun tripRef(client: EmulatorClient, id: String) =
      client.firestore.collection("trips").document(id)

  private suspend fun statusOf(client: EmulatorClient, id: String) =
      tripRef(client, id).get(Source.SERVER).await().getString("status")

  private suspend fun ordersOf(owner: EmulatorClient, trip: String) =
      (orders(owner).getOrdersForTrip(trip) as OrderResult.Success).data

  private fun trips(client: EmulatorClient) =
      TripRepositoryFirestore(AuthRepositoryFirebase(client.auth), client.firestore)

  private fun orders(client: EmulatorClient) =
      OrderRepositoryFirestore({ client.auth.currentUser?.uid }, client.firestore)

  /** Publishes a trip through the repository, then seeds [status] as the admin (clients cannot). */
  private suspend fun publish(owner: EmulatorClient, status: String): String {
    val place = Location("EPFL", 46.5, 6.5)
    val id =
        trips(owner)
            .publishTrip(NewTrip(place, Instant.now().plusSeconds(TRIP_LEAD_SECONDS), place))
            .successData()
            .id
    if (status != "PUBLISHED") updateDocumentAsOwner("trips/$id", mapOf("status" to status))
    return id
  }

  /** Publishes a trip and has [requester] place one order per commission. */
  private suspend fun publishWithOrder(
      owner: EmulatorClient,
      requester: EmulatorClient,
      commissions: List<Int> = listOf(150),
  ): String {
    val trip = publish(owner, "PUBLISHED")
    commissions.forEach { orders(requester).createOrder(OrderDraft(trip, listOf(ITEM), it)) }
    return trip
  }

  private companion object {
    const val TRIP_LEAD_SECONDS = 3_600L
    val ITEM = OrderItem("milk", "Milk", 220, 2)
  }
}
