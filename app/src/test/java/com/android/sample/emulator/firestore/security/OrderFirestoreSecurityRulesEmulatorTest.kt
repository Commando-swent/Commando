package com.android.sample.emulator.firestore.security

// AI assistance: OpenAI Codex.
import com.android.sample.data.repository.*
import com.android.sample.model.*
import com.android.sample.model.OrderDecision.ACCEPT
import com.android.sample.model.OrderDecision.REJECT
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.Source
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Concrete adapter AND direct SDK access against production rules, never permissive rules. */
@RunWith(RobolectricTestRunner::class)
class OrderFirestoreSecurityRulesEmulatorTest {
  @Test
  fun selfOrdersAreRejectedByTheAdapterAndDirectWrites() = runTest {
    withClients("owner" to true, "requester" to true) { (owner, requester) ->
      val trip = publish(owner)
      val ownerId = owner.auth.currentUser!!.uid
      val ownerOrders = OrderRepositoryFirestore({ ownerId }, owner.firestore)
      val draft = OrderDraft(trip, listOf(OrderItem("milk", "Milk", 220, 1)))
      assertEquals(OrderResult.Error(OrderError.InvalidData), ownerOrders.createOrder(draft))
      val requesterOrders =
          OrderRepositoryFirestore({ requester.auth.currentUser?.uid }, requester.firestore)
      val saved = (requesterOrders.createOrder(draft) as OrderResult.Success).data
      val data =
          requester.firestore
              .collection("orders")
              .document(saved.id)
              .get(Source.SERVER)
              .await()
              .data!!
      // Bypass the Kotlin guard with an otherwise valid document belonging to the trip owner.
      assertPermissionDenied {
        owner.firestore
            .collection("orders")
            .add(
                data +
                    mapOf(
                        "requesterId" to ownerId,
                        "createdAt" to FieldValue.serverTimestamp(),
                    )
            )
            .await()
      }
      assertEquals(OrderResult.Success(emptyList<Order>()), ownerOrders.getMyOrders())
      assertEquals(OrderResult.Success(listOf(saved)), requesterOrders.getMyOrders())
    }
  }

  @Test
  fun persistentSnapshotRequesterQueriesAndOwnerDetailAccess() = runTest {
    withClients("requester" to true, "owner" to true, "other" to true, "signedOut" to false) {
        (requester, owner, other, signedOut) ->
      val trip = publish(owner)
      val uid = requester.auth.currentUser!!.uid
      val repository =
          OrderRepositoryFirestore({ requester.auth.currentUser?.uid }, requester.firestore)
      val draft = OrderDraft(trip, listOf(OrderItem("milk", "Milk", 220, 2)), 150)
      val saved = (repository.createOrder(draft) as OrderResult.Success).data
      assertEquals(draft.items, saved.items)
      assertEquals(150, saved.commissionCents)
      assertEquals(uid, saved.requesterId)
      assertEquals(OrderStatus.PENDING, saved.status)
      // New repository + explicit SERVER reads cannot pass solely from cached data.
      val fresh = OrderRepositoryFirestore({ uid }, requester.firestore)
      assertEquals(OrderResult.Success(saved), fresh.getOrderById(saved.id))
      assertEquals(OrderResult.Success(listOf(saved)), fresh.getMyOrders())
      assertFalse(
          requester.firestore
              .collection("orders")
              .document(saved.id)
              .get(Source.SERVER)
              .await()
              .metadata
              .isFromCache
      )
      val ownerRepository =
          OrderRepositoryFirestore({ owner.auth.currentUser?.uid }, owner.firestore)
      assertEquals(OrderResult.Success(saved), ownerRepository.getOrderById(saved.id))
      assertEquals(OrderResult.Success(emptyList<Order>()), ownerRepository.getMyOrders())
      for (client in listOf(other, signedOut)) {
        assertPermissionDenied {
          client.firestore.collection("orders").document(saved.id).get(Source.SERVER).await()
        }
      }
      // A requester filter is mandatory; trip-wide list access belongs to #147.
      for (client in listOf(requester, owner, other, signedOut)) {
        assertPermissionDenied { client.firestore.collection("orders").get(Source.SERVER).await() }
        if (client != owner)
            assertPermissionDenied {
              client.firestore
                  .collection("orders")
                  .document(saved.id)
                  .update("status", "ACCEPTED")
                  .await()
            }
        assertPermissionDenied {
          client.firestore.collection("orders").document(saved.id).delete().await()
        }
      }
      owner.firestore.collection("orders").document(saved.id).update("status", "ACCEPTED").await()
      assertEquals(
          OrderResult.Success(saved.copy(status = OrderStatus.ACCEPTED)),
          fresh.getOrderById(saved.id),
      )
      assertPermissionDenied {
        other.firestore
            .collection("orders")
            .whereEqualTo("requesterId", uid)
            .get(Source.SERVER)
            .await()
      }
      assertPermissionDenied {
        requester.firestore
            .collection("orders")
            .document(saved.id)
            .update("items", emptyList<Any>())
            .await()
      }
      assertEquals(
          OrderResult.Error(OrderError.NotFound),
          fresh.getOrderById("missing-${UUID.randomUUID()}"),
      )
    }
  }

  @Test
  fun directWritesRejectSpoofingStatusMalformedFieldsAndUnavailableTrips() = runTest {
    withClients("requester" to true, "owner" to true, "signedOut" to false) {
        (requester, owner, signedOut) ->
      val trip = publish(owner)
      val uid = requester.auth.currentUser!!.uid
      val data =
          mapOf(
              "tripId" to trip,
              "requesterId" to uid,
              "items" to
                  listOf(
                      mapOf(
                          "productId" to "milk",
                          "name" to "Milk",
                          "unitPriceCents" to 220,
                          "quantity" to 1,
                      )
                  ),
              "commissionCents" to 0,
              "status" to "PENDING",
              "createdAt" to FieldValue.serverTimestamp(),
          )
      val item = (data.getValue("items") as List<*>).single() as Map<*, *>
      val five = List(5) { item + ("productId" to "product-$it") }
      val invalidItems =
          listOf(
              listOf("malformed"),
              listOf(item - "name"),
              listOf(item + ("extra" to true)),
              listOf(item + ("productId" to " ")),
              listOf(item + ("name" to " ")),
              listOf(item + ("name" to "\u00a0\u2003")),
              listOf(item + ("productId" to 1)),
              listOf(item + ("name" to true)),
              listOf(item + ("quantity" to 0)),
              listOf(item + ("quantity" to -1)),
              listOf(item + ("quantity" to 1.5)),
              listOf(item + ("quantity" to Long.MAX_VALUE)),
              listOf(item + ("unitPriceCents" to -1)),
              listOf(item + ("unitPriceCents" to 1.5)),
              listOf(item + ("unitPriceCents" to Long.MAX_VALUE)),
              listOf(item + mapOf("unitPriceCents" to Long.MAX_VALUE, "quantity" to 2)),
              listOf(item + mapOf("unitPriceCents" to 0, "quantity" to 2147483648L)),
              listOf(item + mapOf("unitPriceCents" to Int.MAX_VALUE, "quantity" to 2)),
              listOf(item + ("unitPriceCents" to Int.MAX_VALUE), item + ("productId" to "other")),
              five.dropLast(1) + (item + ("quantity" to -1)),
              five.dropLast(1) + five.first(),
              five + (item + ("productId" to "sixth")),
          )
      for (items in invalidItems) assertPermissionDenied {
        requester.firestore.collection("orders").add(data + ("items" to items)).await()
      }
      assertPermissionDenied {
        requester.firestore
            .collection("orders")
            .add(
                data +
                    mapOf(
                        "items" to listOf(item + ("unitPriceCents" to Int.MAX_VALUE)),
                        "commissionCents" to 1,
                    )
            )
            .await()
      }
      // Exercise the last permitted index and the inclusive Int boundary with direct writes.
      requester.firestore.collection("orders").add(data + ("items" to five)).await()
      requester.firestore
          .collection("orders")
          .add(data + ("items" to listOf(item + ("unitPriceCents" to Int.MAX_VALUE))))
          .await()
      val malformed =
          listOf(
              data + ("requesterId" to "spoofed"),
              data + ("status" to "ACCEPTED"),
              data + ("commissionCents" to -1),
              data + ("commissionCents" to 1.5),
              data + ("commissionCents" to Long.MAX_VALUE),
              data + ("items" to emptyList<Any>()),
              data + ("createdAt" to Timestamp(0, 0)),
              data + ("extra" to true),
              data - "tripId",
              data + ("tripId" to "missing-${UUID.randomUUID()}"),
          )
      for (invalid in malformed) assertPermissionDenied {
        requester.firestore.collection("orders").add(invalid).await()
      }
      assertPermissionDenied { signedOut.firestore.collection("orders").add(data).await() }
      val ref = owner.firestore.collection("trips").document(trip)
      ref.update("status", "CANCELLED").await()
      assertPermissionDenied { requester.firestore.collection("orders").add(data).await() }
      ref.update(mapOf("status" to "PUBLISHED", "scheduledAt" to Timestamp(0, 0))).await()
      assertPermissionDenied { requester.firestore.collection("orders").add(data).await() }
    }
  }

  @Test
  fun ownerDecisionsPersistAndRetriesDoNotWriteAgainEvenForPastOrClosedTrips() = runTest {
    withClients("requester" to true, "owner" to true) { (requester, owner) ->
      assertTrue(owner.auth.currentUser!!.isAnonymous)
      for (decision in OrderDecision.entries) {
        val trip = publish(owner)
        val saved = createOrder(requester, trip)
        val tripRef = owner.firestore.collection("trips").document(trip)
        tripRef.update(mapOf("status" to "COMPLETED", "scheduledAt" to Timestamp(0, 0))).await()
        val target = decision.targetStatus()
        val expected = saved.copy(status = target)
        // Production rules deny final-order writes; the second call must be read-only.
        repeat(2) {
          assertEquals(OrderResult.Success(expected), orders(owner).decideOrder(saved.id, decision))
        }
        val opposite = if (decision == ACCEPT) REJECT else ACCEPT
        assertEquals(
            OrderResult.Error(OrderError.AlreadyDecided(expected)),
            orders(owner).decideOrder(saved.id, opposite),
        )
        assertEquals(OrderResult.Success(expected), orders(requester).getOrderById(saved.id))
        val ref = owner.firestore.collection("orders").document(saved.id)
        for (status in OrderStatus.entries) assertPermissionDenied("final order -> $status") {
          ref.update("status", status.name).await()
        }
        assertPermissionDenied { ref.delete().await() }
      }
    }
  }

  @Test
  fun directSdkDecisionsRequirePersistedOwnerAndOnlyStatusMayChange() = runTest {
    withClients("requester" to true, "owner" to true, "other" to true, "signedOut" to false) {
        (requester, owner, other, signedOut) ->
      val saved = createOrder(requester, publish(owner))
      for (client in listOf(requester, other, signedOut)) for (status in
          listOf("ACCEPTED", "REJECTED")) {
        assertPermissionDenied {
          client.firestore.collection("orders").document(saved.id).update("status", status).await()
        }
      }
      val ref = owner.firestore.collection("orders").document(saved.id)
      val original = ref.get(Source.SERVER).await().data!!
      val item = (original.getValue("items") as List<*>).single() as Map<*, *>
      val mutations =
          listOf(
              mapOf("items" to emptyList<Any>()),
              mapOf("items" to listOf(item + ("unitPriceCents" to 1))),
              mapOf("requesterId" to owner.auth.currentUser!!.uid),
              mapOf("tripId" to publish(owner)),
              mapOf("commissionCents" to 999),
              mapOf("createdAt" to Timestamp(0, 0)),
              mapOf("updatedAt" to FieldValue.serverTimestamp()),
              mapOf("extra" to true),
              mapOf("commissionCents" to FieldValue.delete()),
          )
      for (mutation in mutations) assertPermissionDenied("immutable fields $mutation") {
        ref.update(mutation + ("status" to "ACCEPTED")).await()
      }
      val attackTrip = publish(other)
      assertPermissionDenied("tripId owner impersonation") {
        val attack = other.firestore.collection("orders").document(saved.id)
        attack.update(mapOf("tripId" to attackTrip, "status" to "ACCEPTED")).await()
      }
      for (status in listOf("PENDING", "UNKNOWN")) assertPermissionDenied {
        ref.update("status", status).await()
      }
      assertPermissionDenied { ref.delete().await() }
      assertEquals(original, ref.get(Source.SERVER).await().data)
      ref.update("status", "REJECTED").await()
      assertEquals(original + ("status" to "REJECTED"), ref.get(Source.SERVER).await().data)
      for (client in listOf(requester, other, signedOut)) {
        val error =
            if (client == signedOut) OrderError.NotAuthenticated else OrderError.PermissionDenied
        for (decision in OrderDecision.entries) {
          assertEquals(OrderResult.Error(error), orders(client).decideOrder(saved.id, decision))
        }
      }
    }
  }

  @Test
  fun legacySelfOrdersRemainReadableButDenyAllDecisions() = runTest {
    withClients("requester" to true, "owner" to true) { (requester, owner) ->
      val original = createOrder(requester, publish(owner))
      val saved =
          original.copy(
              id = UUID.randomUUID().toString(),
              requesterId = owner.auth.currentUser!!.uid,
          )
      val fields =
          owner.firestore
              .collection("orders")
              .document(original.id)
              .get(Source.SERVER)
              .await()
              .data!!
      // Seed historical data through the emulator admin API: clients can no longer create it.
      createDocumentAsOwner("orders", saved.id, fields + ("requesterId" to saved.requesterId))
      val repo = orders(owner)
      val denied = OrderResult.Error(OrderError.PermissionDenied)
      val ref = owner.firestore.collection("orders").document(saved.id)
      for (decision in OrderDecision.entries) {
        assertEquals(denied, repo.decideOrder(saved.id, decision))
        assertPermissionDenied { ref.update("status", decision.targetStatus().name).await() }
        assertEquals(OrderResult.Success(saved), repo.getOrderById(saved.id))
      }
      val uid = owner.auth.currentUser!!.uid
      val authError = OrderResult.Error(OrderError.NotAuthenticated)
      val direct = FirebaseOrderFirestoreDataSource(owner.firestore)
      assertEquals(
          authError,
          OrderRepositoryFirestore(direct, { "spoofed" }).decideOrder(saved.id, ACCEPT),
      )
      val revoked = FirebaseOrderFirestoreDataSource(owner.firestore) { null }
      assertEquals(
          authError,
          OrderRepositoryFirestore(revoked, { uid }).decideOrder(saved.id, ACCEPT),
      )
      val active = OrderRepositoryFirestore(direct, { uid })
      assertEquals(
          OrderResult.Error(OrderError.NotFound),
          active.decideOrder("missing-order", ACCEPT),
      )
      owner.auth.signOut()
      assertEquals(authError, active.decideOrder(saved.id, ACCEPT))
    }
  }

  @Test
  fun separateRepositoriesResolveCompetingTransactionsWithoutOverwriting() = runTest {
    assertConcurrentDecisions(OrderDecision.entries.toList())
  }

  @Test
  fun separateRepositoriesResolveIdenticalTransactionsWithoutOverwriting() = runTest {
    assertConcurrentDecisions(listOf(ACCEPT, ACCEPT))
  }

  private suspend fun assertConcurrentDecisions(decisions: List<OrderDecision>) =
      kotlinx.coroutines.coroutineScope {
        withClients("requester" to true, "owner" to true) { (requester, owner) ->
          val saved = createOrder(requester, publish(owner))
          val gate = CompletableDeferred<Unit>()
          val calls = decisions.map { decision ->
            val repo = orders(owner)
            async(Dispatchers.Default) {
              gate.await()
              repo.decideOrder(saved.id, decision)
            }
          }
          gate.complete(Unit)
          val results = calls.awaitAll()
          val persisted = (orders(owner).getOrderById(saved.id) as OrderResult.Success).data
          assertTrue(decisions.any { it.targetStatus() == persisted.status })
          assertEquals(saved.copy(status = persisted.status), persisted)
          decisions.zip(results).forEach { (decision, result) ->
            val target = decision.targetStatus()
            assertEquals(
                if (persisted.status == target) OrderResult.Success(persisted)
                else OrderResult.Error(OrderError.AlreadyDecided(persisted)),
                result,
            )
          }
          assertEquals(OrderResult.Success(persisted), orders(requester).getOrderById(saved.id))
        }
      }

  private fun orders(client: EmulatorClient) =
      OrderRepositoryFirestore({ client.auth.currentUser?.uid }, client.firestore)

  private suspend fun createOrder(requester: EmulatorClient, trip: String) =
      (orders(requester)
              .createOrder(OrderDraft(trip, listOf(OrderItem("milk", "Milk", 220, 2)), 150))
              as OrderResult.Success)
          .data

  private suspend fun publish(owner: EmulatorClient): String {
    val time = Instant.now()
    val location = mapOf("name" to "EPFL", "latitude" to 46.5, "longitude" to 6.5)
    return owner.firestore
        .collection("trips")
        .add(
            mapOf(
                "ownerId" to owner.auth.currentUser!!.uid,
                "store" to location,
                "handoffLocation" to location,
                "status" to "PUBLISHED",
                "scheduledAt" to Timestamp(time.epochSecond + 3600, 0),
                "createdAt" to Timestamp(time.epochSecond, 0),
                "updatedAt" to Timestamp(time.epochSecond, 0),
            )
        )
        .await()
        .id
  }
}
