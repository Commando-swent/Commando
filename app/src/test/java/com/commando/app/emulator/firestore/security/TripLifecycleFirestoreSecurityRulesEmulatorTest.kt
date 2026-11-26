package com.commando.app.emulator.firestore.security

// AI assistance: Claude Code (trip cancellation: #449).
import com.commando.app.data.repository.*
import com.commando.app.data.repository.OrderError
import com.commando.app.data.repository.OrderRepositoryFirestore
import com.commando.app.data.repository.OrderResult
import com.commando.app.data.repository.ShoppingChecklistRepositoryFirestore
import com.commando.app.data.repository.TripError.*
import com.commando.app.data.repository.TripRepositoryFirestore
import com.commando.app.data.repository.TripResult
import com.commando.app.data.repository.successData
import com.commando.app.model.*
import com.commando.app.model.OrderDecision.ACCEPT
import com.commando.app.model.OrderDecision.REJECT
import com.commando.app.model.TripTransition.CANCEL
import com.commando.app.model.TripTransition.CLOSE
import com.commando.app.model.TripTransition.COMPLETE
import com.commando.app.model.authentication.AuthRepositoryFirebase
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

/** Trip close, complete (#233) and cancel (#449) against the production rules, for every actor. */
@RunWith(RobolectricTestRunner::class)
class TripLifecycleFirestoreSecurityRulesEmulatorTest {
  private val clients =
      arrayOf("owner" to true, "requester" to true, "outsider" to true, "signedOut" to false)

  @Test
  fun owner_closesThenCompletesOrCancelsAndIsDeniedEveryOtherChange() = runTest {
    val denied =
        listOf(
            "PUBLISHED" to move("COMPLETED"),
            "IN_PROGRESS" to move("PUBLISHED"),
            "IN_PROGRESS" to move("CANCELLED"),
            "COMPLETED" to move("IN_PROGRESS"),
            "COMPLETED" to move("PUBLISHED"),
            "COMPLETED" to move("CANCELLED"),
            "CANCELLED" to move("PUBLISHED"),
            "CANCELLED" to move("IN_PROGRESS"),
            "PUBLISHED" to mapOf("status" to "CANCELLED"),
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
      val cancelled = publish(owner, "PUBLISHED")
      tripRef(owner, cancelled).update(move("CANCELLED")).await()
      assertEquals("CANCELLED", statusOf(owner, cancelled))
      for ((from, change) in denied) {
        val seeded = publish(owner, from)
        assertPermissionDenied("$from -> $change") { tripRef(owner, seeded).update(change).await() }
        assertEquals(from, statusOf(owner, seeded))
      }
    }
  }

  @Test
  fun requesterOutsiderAndSignedOut_areDeniedEveryTransition() = runTest {
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
  fun endedTripsAreReadableByIdButNotListable() = runTest {
    withClients(*clients) { (owner, requester, outsider, signedOut) ->
      val trip = publishWithOrder(owner, requester)
      for (status in listOf("IN_PROGRESS", "COMPLETED", "CANCELLED")) {
        updateDocumentAsOwner("trips/$trip", mapOf("status" to status))
        // Order Details and My Orders read the trip of every past order by id.
        for (reader in listOf(owner, requester, outsider, signedOut)) {
          val get = suspend { tripRef(reader, trip).get(Source.SERVER).await() }
          if (reader != signedOut) assertTrue("$status get", get().exists())
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

  @Test
  fun repositoryFlow_cancelWaitsForOpenOrdersThenHidesTheTripAndBlocksNewOrders() = runTest {
    withClients("owner" to true, "requester" to true) { (owner, requester) ->
      val ownerTrips = trips(owner)
      val trip = publishWithOrder(owner, requester)
      val (order) = ordersOf(owner, trip)
      assertEquals(TripResult.Error(OrdersStillOpen), ownerTrips.transitionTrip(trip, CANCEL))
      assertEquals(TripStatus.PUBLISHED, ownerTrips.getTripById(trip).successData().status)
      assertTrue("cancel order", orders(requester).cancelOrder(order.id) is OrderResult.Success)

      val cancelled = ownerTrips.transitionTrip(trip, CANCEL).successData()

      assertEquals(TripStatus.CANCELLED, cancelled.status)
      assertEquals(TripResult.Success(cancelled), ownerTrips.transitionTrip(trip, CANCEL))
      assertEquals(TripResult.Error(InvalidTransition), ownerTrips.transitionTrip(trip, CLOSE))
      assertEquals(
          OrderResult.Error(OrderError.InvalidData),
          orders(requester).createOrder(OrderDraft(trip, listOf(ITEM))),
      )
      assertTrue(trips(requester).getUpcomingTrips().successData().none { it.id == trip })
    }
  }

  @Test
  fun rulesRefuseCancelOrCompleteWhileTheTripCountsAnOpenOrder() = runTest {
    withClients("owner" to true, "requester" to true) { (owner, requester) ->
      val trip = publishWithOrder(owner, requester)
      val (order) = ordersOf(owner, trip)
      assertEquals(1L, openOrderCount(owner, trip))
      val steps =
          listOf<suspend () -> Unit>(
              {}, // PENDING
              { orders(owner).decideOrder(order.id, ACCEPT, order.revision) },
              { orders(owner).markReadyForHandoff(order.id) },
          )
      // A direct write skips the repository's order check; the rules still refuse it.
      for (step in steps) {
        step()
        assertPermissionDenied("cancel") { tripRef(owner, trip).update(move("CANCELLED")).await() }
        updateDocumentAsOwner("trips/$trip", mapOf("status" to "IN_PROGRESS"))
        assertPermissionDenied("complete") {
          tripRef(owner, trip).update(move("COMPLETED")).await()
        }
        updateDocumentAsOwner("trips/$trip", mapOf("status" to "PUBLISHED"))
      }
      // Nobody can lower the counter without settling the order it names.
      for (actor in listOf(owner, requester)) {
        assertPermissionDenied("counter reset by ${actor.app.name}") {
          tripRef(actor, trip)
              .update(mapOf("openOrderCount" to 0, "counterOrderId" to order.id))
              .await()
        }
      }
      assertTrue(orders(requester).confirmReceipt(order.id) is OrderResult.Success)
      assertEquals(0L, openOrderCount(owner, trip))
      tripRef(owner, trip).update(move("CANCELLED")).await()
      assertEquals("CANCELLED", statusOf(owner, trip))
    }
  }

  @Test
  fun orderPlacedAfterTheOrderCheckStillBlocksCancellation() = runTest {
    withClients("owner" to true, "requester" to true) { (owner, requester) ->
      val trip = publish(owner, "PUBLISHED")
      val firestore = FirebaseTripFirestoreDataSource(owner.firestore)
      // The order arrives after the repository's order query, before its transaction.
      val racing =
          object : TripFirestoreDataSource by firestore {
            override suspend fun getOrderStatuses(tripId: String): List<String?> =
                firestore.getOrderStatuses(tripId).also {
                  val placed = orders(requester).createOrder(OrderDraft(trip, listOf(ITEM)))
                  assertTrue(placed is OrderResult.Success)
                }
          }
      val ownerTrips =
          TripRepositoryFirestore(racing, AuthRepositoryFirebase(owner.auth), Instant::now)

      assertEquals(TripResult.Error(OrdersStillOpen), ownerTrips.transitionTrip(trip, CANCEL))
      assertEquals("PUBLISHED", statusOf(owner, trip))
      assertEquals(1, ordersOf(owner, trip).size)
    }
  }

  @Test
  fun owner_keepsUsingTheShoppingChecklistAfterCloseAndComplete() = runTest {
    withClients("owner" to true) { (owner) ->
      val trip = publish(owner, "PUBLISHED")
      val checklist =
          ShoppingChecklistRepositoryFirestore({ owner.auth.currentUser?.uid }, owner.firestore)
      val item = ShoppingChecklistItem("milk", "Milk", requiredQuantity = 2, purchased = false)
      for (status in listOf("IN_PROGRESS", "COMPLETED")) {
        updateDocumentAsOwner("trips/$trip", mapOf("status" to status))
        val saved = checklist.setPurchased(trip, item, purchased = true)
        assertTrue("$status save", saved is OrderResult.Success)
        val progress = checklist.getPurchasedQuantities(trip) as OrderResult.Success
        assertEquals(status, mapOf("milk" to 2L), progress.data)
      }
    }
  }

  private fun move(status: String): Map<String, Any> =
      mapOf("status" to status, "updatedAt" to FieldValue.serverTimestamp())

  private fun tripRef(client: EmulatorClient, id: String) =
      client.firestore.collection("trips").document(id)

  private suspend fun statusOf(client: EmulatorClient, id: String) =
      tripRef(client, id).get(Source.SERVER).await().getString("status")

  private suspend fun openOrderCount(client: EmulatorClient, id: String) =
      tripRef(client, id).get(Source.SERVER).await().getLong("openOrderCount")

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
