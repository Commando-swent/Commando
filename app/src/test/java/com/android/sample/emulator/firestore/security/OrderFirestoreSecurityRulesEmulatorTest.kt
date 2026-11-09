package com.android.sample.emulator.firestore.security

// AI assistance: OpenAI Codex; trip-owner query tests (#147): Claude Code.
import com.android.sample.data.repository.*
import com.android.sample.model.*
import com.android.sample.model.OrderDecision.ACCEPT
import com.android.sample.model.OrderDecision.REJECT
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentReference
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
  fun requesterEditsAreAtomicValidatedAndDoNotChangeIdentity() = runTest {
    withClients("owner" to true, "requester" to true, "outsider" to true, "anonymous" to false) {
        (owner, requester, outsider, anonymous) ->
      val tripId = publish(owner)
      val repo = OrderRepositoryFirestore({ requester.auth.currentUser?.uid }, requester.firestore)
      val draft = OrderDraft(tripId, listOf(OrderItem("milk", "Milk", 220, 1)), 100)
      val saved = (repo.createOrder(draft) as OrderResult.Success).data
      for (client in listOf(owner, outsider, anonymous)) assertPermissionDenied {
        client.firestore
            .collection("orders")
            .document(saved.id)
            .update(mapOf("commissionCents" to 200, "revision" to 1))
            .await()
      }
      val ref = requester.firestore.collection("orders").document(saved.id)
      for ((field, value) in
          listOf(
              "tripId" to "other",
              "requesterId" to owner.auth.currentUser!!.uid,
              "createdAt" to FieldValue.serverTimestamp(),
              "status" to "ACCEPTED",
              "extra" to true,
              "items" to emptyList<Any>(),
              "commissionCents" to -1,
              "commissionCents" to 2147483648L,
          )) {
        assertPermissionDenied { ref.update(mapOf(field to value, "revision" to 1)).await() }
      }
      val edited =
          draft.copy(items = listOf(OrderItem("bread", "Bread", 300, 2)), commissionCents = 150)
      val expected = saved.copy(items = edited.items, commissionCents = 150, revision = 1)
      assertEquals(OrderResult.Success(expected), repo.editOrder(saved.id, edited))
      assertEquals(OrderResult.Success(expected), repo.editOrder(saved.id, edited))
      assertEquals(OrderResult.Success(listOf(expected)), repo.getMyOrders())
      val ownerRepo = OrderRepositoryFirestore({ owner.auth.currentUser?.uid }, owner.firestore)
      assertEquals(OrderResult.Success(listOf(expected)), ownerRepo.getOrdersForTrip(tripId))
      assertEquals(OrderResult.Success(expected), repo.getOrderById(saved.id))
      // Closing the trip independently blocks raw requester edits, not just the Kotlin guard.
      owner.firestore.collection("trips").document(tripId).update("status", "CANCELLED").await()
      assertPermissionDenied {
        ref.update(mapOf("commissionCents" to 200, "revision" to 2)).await()
      }
      assertEquals(OrderResult.Error(OrderError.InvalidData), repo.editOrder(saved.id, draft))
    }
  }

  @Test
  fun editsRejectWrongTripsAndInvalidHistoricalEligibility() = runTest {
    withClients("owner" to true, "requester" to true) { (owner, requester) ->
      val tripId = publish(owner)
      val repo = OrderRepositoryFirestore({ requester.auth.currentUser?.uid }, requester.firestore)
      val draft = OrderDraft(tripId, listOf(OrderItem("milk", "Milk", 220, 1)), 100)
      val saved = (repo.createOrder(draft) as OrderResult.Success).data
      val tripRef = owner.firestore.collection("trips").document(tripId)
      val fields = tripRef.get(Source.SERVER).await().data!!
      assertEquals(
          OrderResult.Error(OrderError.InvalidData),
          repo.editOrder(saved.id, draft.copy(tripId = "different-trip")),
      )
      // Historical malformed trips remain readable when PUBLISHED; eligibility must still fail.
      for (trip in
          listOf(
              fields - "scheduledAt",
              fields + ("scheduledAt" to Timestamp(Instant.now().minusSeconds(60))),
              fields + ("ownerId" to requester.auth.currentUser!!.uid),
          )) {
        deleteDocumentAsOwner("trips/$tripId")
        createDocumentAsOwner("trips", tripId, trip)
        assertEquals(OrderResult.Error(OrderError.InvalidData), repo.editOrder(saved.id, draft))
        assertEquals(OrderResult.Success(saved), repo.getOrderById(saved.id))
      }
      val direct = FirebaseOrderFirestoreDataSource(requester.firestore) { null }
      assertEquals(
          OrderResult.Error(OrderError.NotAuthenticated),
          OrderRepositoryFirestore(direct, { requester.auth.currentUser?.uid })
              .editOrder(saved.id, draft),
      )
    }
  }

  @Test
  fun revisionsProtectOwnerDecisionsAndSerializeCompetingEdits() = runTest {
    withClients("owner" to true, "requester" to true) { (owner, requester) ->
      val tripId = publish(owner)
      val repo = orders(requester)
      val ownerRepo = orders(owner)
      val draft = OrderDraft(tripId, listOf(OrderItem("milk", "Milk", 220, 1)), 100)
      for (decision in OrderDecision.entries) {
        val saved = (repo.createOrder(draft) as OrderResult.Success).data
        val first =
            async(Dispatchers.IO) { repo.editOrder(saved.id, draft.copy(commissionCents = 300)) }
        val second =
            async(Dispatchers.IO) { repo.editOrder(saved.id, draft.copy(commissionCents = 8000)) }
        val results = listOf(first.await(), second.await())
        assertEquals(1, results.count { it is OrderResult.Success })
        val edited = (repo.getOrderById(saved.id) as OrderResult.Success).data
        assertEquals(1, edited.revision)
        assertTrue(results.contains(OrderResult.Error(OrderError.AlreadyDecided(edited))))
        assertEquals(
            OrderResult.Error(OrderError.AlreadyDecided(edited)),
            ownerRepo.decideOrder(saved.id, decision, saved.revision),
        )
        val ref = requester.firestore.collection("orders").document(saved.id)
        for (revision in listOf(1, 3, -1)) assertPermissionDenied {
          ref.update(mapOf("commissionCents" to 500, "revision" to revision)).await()
        }
        assertTrue(
            ownerRepo.decideOrder(saved.id, decision, edited.revision) is OrderResult.Success
        )
      }
    }
  }

  @Test
  fun editRacesWithAcceptRejectAndCancelPreserveTheCommittedWinner() = runTest {
    withClients("owner" to true, "requester" to true) { (owner, requester) ->
      val tripId = publish(owner)
      val repo = OrderRepositoryFirestore({ requester.auth.currentUser?.uid }, requester.firestore)
      val ownerRepo = OrderRepositoryFirestore({ owner.auth.currentUser?.uid }, owner.firestore)
      val draft = OrderDraft(tripId, listOf(OrderItem("milk", "Milk", 220, 1)), 100)
      for (target in listOf(OrderStatus.ACCEPTED, OrderStatus.REJECTED, OrderStatus.CANCELLED)) {
        val saved = (repo.createOrder(draft) as OrderResult.Success).data
        val edited = draft.copy(commissionCents = 250)
        val result = async(Dispatchers.IO) { repo.editOrder(saved.id, edited) }
        val transition =
            async(Dispatchers.IO) {
              when (target) {
                OrderStatus.ACCEPTED -> ownerRepo.decideOrder(saved.id, ACCEPT)
                OrderStatus.REJECTED -> ownerRepo.decideOrder(saved.id, REJECT)
                else -> repo.cancelOrder(saved.id)
              }
            }
        val edit = result.await()
        val decision = transition.await()
        val stored = (repo.getOrderById(saved.id) as OrderResult.Success).data
        if (decision is OrderResult.Success) assertEquals(target, stored.status)
        else {
          assertEquals(OrderResult.Error(OrderError.AlreadyDecided(stored)), decision)
          assertEquals(OrderStatus.PENDING, stored.status)
        }
        assertEquals(saved.tripId, stored.tripId)
        assertEquals(saved.createdAt, stored.createdAt)
        assertTrue(
            edit is OrderResult.Success ||
                (edit is OrderResult.Error && edit.error is OrderError.AlreadyDecided)
        )
        if (edit is OrderResult.Success) assertEquals(250, stored.commissionCents)
        else assertEquals(100, stored.commissionCents)
        if (stored.status != OrderStatus.PENDING)
            assertEquals(
                OrderResult.Error(OrderError.AlreadyDecided(stored)),
                repo.editOrder(saved.id, edited),
            )
      }
    }
  }

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
        writeOrder(
            owner,
            data +
                mapOf(
                    "requesterId" to ownerId,
                    "createdAt" to FieldValue.serverTimestamp(),
                ),
            ownerId,
        )
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
      // Lists must filter by requester or owned trip; an unfiltered list is denied to everyone.
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
      writeDecision(owner, saved.id, "ACCEPTED")
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
        writeOrder(requester, data + ("items" to items), owner.auth.currentUser!!.uid)
      }
      assertPermissionDenied {
        writeOrder(
            requester,
            data +
                mapOf(
                    "items" to listOf(item + ("unitPriceCents" to Int.MAX_VALUE)),
                    "commissionCents" to 1,
                ),
            owner.auth.currentUser!!.uid,
        )
      }
      // Exercise the last permitted index and the inclusive Int boundary with direct writes.
      writeOrder(requester, data + ("items" to five), owner.auth.currentUser!!.uid)
      writeOrder(
          requester,
          data + ("items" to listOf(item + ("unitPriceCents" to Int.MAX_VALUE))),
          owner.auth.currentUser!!.uid,
      )
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
        writeOrder(requester, invalid, owner.auth.currentUser!!.uid)
      }
      assertPermissionDenied { writeOrder(signedOut, data, owner.auth.currentUser!!.uid) }
      val ref = owner.firestore.collection("trips").document(trip)
      ref.update("status", "CANCELLED").await()
      assertPermissionDenied { writeOrder(requester, data, owner.auth.currentUser!!.uid) }
      ref.update(mapOf("status" to "PUBLISHED", "scheduledAt" to Timestamp(0, 0))).await()
      assertPermissionDenied { writeOrder(requester, data, owner.auth.currentUser!!.uid) }
    }
  }

  @Test
  fun tripOwnerListsOnlyTheirTripsOrdersAndOthersAreDenied() = runTest {
    withClients("requester" to true, "owner" to true, "other" to true, "signedOut" to false) {
        (requester, owner, other, signedOut) ->
      val trip = publish(owner)
      val otherTrip = publish(other)
      val requesterOrders =
          OrderRepositoryFirestore({ requester.auth.currentUser?.uid }, requester.firestore)
      val items = listOf(OrderItem("milk", "Milk", 220, 2))
      val drafts = listOf(150, 300).map { OrderDraft(trip, items, it) }
      val saved = drafts.map { (requesterOrders.createOrder(it) as OrderResult.Success).data }
      requesterOrders.createOrder(OrderDraft(otherTrip, items, 150))
      // A fresh owner repository reads through the production rules from the server.
      val ownerOrders = OrderRepositoryFirestore({ owner.auth.currentUser?.uid }, owner.firestore)
      assertEquals(
          OrderResult.Success(saved.sortedWith(NEWEST_ORDER_FIRST)),
          ownerOrders.getOrdersForTrip(trip),
      )
      assertEquals(
          OrderResult.Error(OrderError.PermissionDenied),
          ownerOrders.getOrdersForTrip(otherTrip),
      )
      for (client in listOf(requester, other)) {
        assertEquals(
            OrderResult.Error(OrderError.PermissionDenied),
            OrderRepositoryFirestore({ client.auth.currentUser?.uid }, client.firestore)
                .getOrdersForTrip(trip),
        )
      }
      // Direct SDK queries cannot bypass the repository's owner check.
      for (client in listOf(requester, other, signedOut)) assertPermissionDenied {
        client.firestore
            .collection("orders")
            .whereEqualTo("tripId", trip)
            .get(Source.SERVER)
            .await()
      }
      assertPermissionDenied {
        owner.firestore
            .collection("orders")
            .whereEqualTo("tripId", otherTrip)
            .get(Source.SERVER)
            .await()
      }
    }
  }

  @Test
  fun ownerDecisionsPersistAndRetriesDoNotWriteAgainEvenForPastOrClosedTrips() = runTest {
    withClients("requester" to true, "owner" to true) { (requester, owner) ->
      assertTrue(owner.auth.currentUser!!.isAnonymous)
      for (decision in OrderDecision.entries) {
        val trip = publish(owner)
        val saved = createOrder(requester, trip)
        assertEquals(
            OrderResult.Error(OrderError.AlreadyDecided(saved)),
            orders(owner).markReadyForHandoff(saved.id),
        )
        val tripRef = owner.firestore.collection("trips").document(trip)
        tripRef.update(mapOf("status" to "COMPLETED", "scheduledAt" to Timestamp(0, 0))).await()
        val target = expectedDecisionStatus(decision)
        val expected = saved.copy(status = target)
        // Repeated decision writes are denied; the second call must be read-only.
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
        for (status in
            OrderStatus.entries.filterNot {
              target == OrderStatus.ACCEPTED && it == OrderStatus.READY_FOR_HANDOFF
            }) assertPermissionDenied("invalid order -> $status") {
          ref.update("status", status.name).await()
        }
        if (decision == ACCEPT) {
          val ready = saved.copy(status = OrderStatus.READY_FOR_HANDOFF)
          repeat(2) {
            assertEquals(OrderResult.Success(ready), orders(owner).markReadyForHandoff(saved.id))
          }
          assertEquals(OrderResult.Success(ready), orders(requester).getOrderById(saved.id))
          for (status in OrderStatus.entries) assertPermissionDenied {
            ref.update("status", status.name).await()
          }
        }
        assertPermissionDenied { ref.delete().await() }
      }
    }
  }

  @Test
  fun requesterCancellationPersistsAndIsIdempotent() = runTest {
    withClients("requester" to true, "owner" to true, "other" to true) { (requester, owner, other)
      ->
      val saved = createOrder(requester, publish(owner))
      val expected = saved.copy(status = OrderStatus.CANCELLED)
      repeat(2) {
        assertEquals(OrderResult.Success(expected), orders(requester).cancelOrder(saved.id))
      }
      assertEquals(OrderResult.Success(expected), orders(owner).getOrderById(saved.id))
      for (client in listOf(owner, other)) {
        assertEquals(
            OrderResult.Error(OrderError.PermissionDenied),
            orders(client).cancelOrder(saved.id),
        )
      }
    }
  }

  @Test
  fun cancellationRejectsMissingOrMalformedStoredOrdersWithoutWriting() = runTest {
    withClients("requester" to true, "owner" to true) { (requester, owner) ->
      val saved = createOrder(requester, publish(owner))
      val repository = orders(requester)
      assertEquals(OrderResult.Error(OrderError.NotFound), repository.cancelOrder("missing"))
      val malformed = "malformed-${saved.id}"
      val fields =
          requester.firestore
              .collection("orders")
              .document(saved.id)
              .get(Source.SERVER)
              .await()
              .data!! + ("status" to "UNKNOWN_STATUS")
      createDocumentAsOwner("orders", malformed, fields)
      assertEquals(OrderResult.Error(OrderError.InvalidData), repository.cancelOrder(malformed))
      assertEquals(
          fields,
          requester.firestore
              .collection("orders")
              .document(malformed)
              .get(Source.SERVER)
              .await()
              .data,
      )
    }
  }

  @Test
  fun directCancellationRequiresRequesterAndPreservesImmutableFields() = runTest {
    withClients("requester" to true, "owner" to true, "other" to true, "signedOut" to false) {
        (requester, owner, other, signedOut) ->
      val saved = createOrder(requester, publish(owner))
      val ref = requester.firestore.collection("orders").document(saved.id)
      val original = ref.get(Source.SERVER).await().data!!
      for (client in listOf(owner, other, signedOut)) {
        assertPermissionDenied {
          client.firestore
              .collection("orders")
              .document(saved.id)
              .update("status", "CANCELLED")
              .await()
        }
      }
      for (mutation in
          listOf(
              mapOf("commissionCents" to 999),
              mapOf("items" to emptyList<Any>()),
              mapOf("requesterId" to owner.auth.currentUser!!.uid),
              mapOf("tripId" to publish(owner)),
              mapOf("createdAt" to Timestamp(0, 0)),
              mapOf("extra" to true),
          )) {
        assertPermissionDenied { ref.update(mutation + ("status" to "CANCELLED")).await() }
      }
      assertEquals(original, ref.get(Source.SERVER).await().data)
      ref.update("status", "CANCELLED").await()
      assertEquals(original + ("status" to "CANCELLED"), ref.get(Source.SERVER).await().data)
      for (client in listOf(requester, owner)) for (status in OrderStatus.entries) {
        assertPermissionDenied {
          client.firestore
              .collection("orders")
              .document(saved.id)
              .update("status", status.name)
              .await()
        }
      }
    }
  }

  @Test
  fun requesterCannotCancelAcceptedOrRejectedOrdersThroughDirectSdk() = runTest {
    withClients("requester" to true, "owner" to true) { (requester, owner) ->
      for (decision in OrderDecision.entries) {
        val saved = createOrder(requester, publish(owner))
        val decided = (orders(owner).decideOrder(saved.id, decision) as OrderResult.Success).data
        assertPermissionDenied {
          requester.firestore
              .collection("orders")
              .document(saved.id)
              .update("status", "CANCELLED")
              .await()
        }
        assertEquals(
            OrderResult.Error(OrderError.AlreadyDecided(decided)),
            orders(requester).cancelOrder(saved.id),
        )
        assertEquals(OrderResult.Success(decided), orders(requester).getOrderById(saved.id))
      }
    }
  }

  @Test
  fun cancellationAndOwnerDecisionCannotOverwriteEachOther() = runTest {
    withClients("requester" to true, "owner" to true) { (requester, owner) ->
      repeat(6) {
        val saved = createOrder(requester, publish(owner))
        val gate = CompletableDeferred<Unit>()
        val cancel =
            async(Dispatchers.Default) {
              gate.await()
              orders(requester).cancelOrder(saved.id)
            }
        val accept =
            async(Dispatchers.Default) {
              gate.await()
              orders(owner).decideOrder(saved.id, ACCEPT)
            }
        gate.complete(Unit)
        val results = awaitAll(cancel, accept)
        val persisted = (orders(requester).getOrderById(saved.id) as OrderResult.Success).data
        assertTrue(persisted.status in listOf(OrderStatus.CANCELLED, OrderStatus.ACCEPTED))
        assertEquals(1, results.count { it is OrderResult.Success })
        results.forEach { result ->
          val snapshot =
              when (result) {
                is OrderResult.Success -> result.data
                is OrderResult.Error -> (result.error as OrderError.AlreadyDecided).persistedOrder
              }
          assertEquals(persisted, snapshot)
        }
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
          writeDecision(client, saved.id, status, requester.auth.currentUser!!.uid)
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
        writeDecision(owner, saved.id, "ACCEPTED", mutation = mutation)
      }
      val attackTrip = publish(other)
      assertPermissionDenied("tripId owner impersonation") {
        val attack = other.firestore.collection("orders").document(saved.id)
        writeDecision(
            other,
            attack.id,
            "ACCEPTED",
            requester.auth.currentUser!!.uid,
            mapOf("tripId" to attackTrip),
        )
      }
      for (status in listOf("PENDING", "UNKNOWN")) assertPermissionDenied {
        ref.update("status", status).await()
      }
      assertPermissionDenied { ref.delete().await() }
      assertEquals(original, ref.get(Source.SERVER).await().data)
      writeDecision(owner, saved.id, "REJECTED")
      assertEquals(original + ("status" to "REJECTED"), ref.get(Source.SERVER).await().data)
      assertEquals(
          OrderResult.Error(OrderError.AlreadyDecided(saved.copy(status = OrderStatus.REJECTED))),
          orders(owner).markReadyForHandoff(saved.id),
      )
      for (client in listOf(requester, other, signedOut)) {
        val error =
            if (client == signedOut) OrderError.NotAuthenticated else OrderError.PermissionDenied
        for (decision in OrderDecision.entries) {
          assertEquals(OrderResult.Error(error), orders(client).decideOrder(saved.id, decision))
        }
        assertEquals(OrderResult.Error(error), orders(client).markReadyForHandoff(saved.id))
      }
      val accepted = createOrder(requester, publish(owner))
      val readyRef = owner.firestore.collection("orders").document(accepted.id)
      writeDecision(owner, accepted.id, "ACCEPTED")
      for (mutation in mutations) assertPermissionDenied {
        readyRef.update(mutation + ("status" to "READY_FOR_HANDOFF")).await()
      }
      for (client in listOf(requester, other, signedOut)) {
        assertPermissionDenied {
          client.firestore
              .collection("orders")
              .document(accepted.id)
              .update("status", "READY_FOR_HANDOFF")
              .await()
        }
      }
      assertEquals(
          OrderResult.Success(accepted.copy(status = OrderStatus.ACCEPTED)),
          orders(owner).getOrderById(accepted.id),
      )
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
      assertEquals(denied, repo.markReadyForHandoff(saved.id))
      for (decision in OrderDecision.entries) {
        assertEquals(denied, repo.decideOrder(saved.id, decision))
        assertPermissionDenied {
          ref.update("status", expectedDecisionStatus(decision).name).await()
        }
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
  fun separateRepositoriesResolveRepeatedOperationsAndStaleDecisionsWithoutOverwriting() = runTest {
    assertConcurrentDecisions(listOf(ACCEPT, ACCEPT))
    assertConcurrentDecisions(listOf(null, null))
    assertConcurrentDecisions(listOf(null, REJECT))
  }

  private suspend fun assertConcurrentDecisions(decisions: List<OrderDecision?>) =
      kotlinx.coroutines.coroutineScope {
        withClients("requester" to true, "owner" to true) { (requester, owner) ->
          var saved = createOrder(requester, publish(owner))
          if (null in decisions) {
            saved = (orders(owner).decideOrder(saved.id, ACCEPT) as OrderResult.Success).data
          }
          val gate = CompletableDeferred<Unit>()
          val calls = decisions.map { decision ->
            val repo = orders(owner)
            async(Dispatchers.Default) {
              gate.await()
              if (decision == null) repo.markReadyForHandoff(saved.id)
              else repo.decideOrder(saved.id, decision)
            }
          }
          gate.complete(Unit)
          val results = calls.awaitAll()
          val persisted = (orders(owner).getOrderById(saved.id) as OrderResult.Success).data
          assertTrue(
              decisions.any {
                (it?.let(::expectedDecisionStatus) ?: OrderStatus.READY_FOR_HANDOFF) ==
                    persisted.status
              }
          )
          assertEquals(saved.copy(status = persisted.status), persisted)
          decisions.zip(results).forEach { (decision, result) ->
            if (null in decisions && decision != null) {
              // A stale decision can observe ACCEPTED before readiness commits, or READY after it.
              assertTrue(
                  result == OrderResult.Error(OrderError.AlreadyDecided(saved)) ||
                      result == OrderResult.Error(OrderError.AlreadyDecided(persisted))
              )
              return@forEach
            }
            val target = decision?.let(::expectedDecisionStatus) ?: OrderStatus.READY_FOR_HANDOFF
            assertEquals(
                if (persisted.status == target) OrderResult.Success(persisted)
                else OrderResult.Error(OrderError.AlreadyDecided(persisted)),
                result,
            )
          }
          assertEquals(OrderResult.Success(persisted), orders(requester).getOrderById(saved.id))
        }
      }

  @Test
  fun receiptRequiresRequesterAndReadyStatusAndPreservesEveryOtherField() = runTest {
    withClients("requester" to true, "owner" to true, "other" to true, "signedOut" to false) {
        (requester, owner, other, signedOut) ->
      for (status in listOf(OrderStatus.PENDING, OrderStatus.ACCEPTED, OrderStatus.REJECTED)) {
        val saved = createOrder(requester, publish(owner))
        val ref = requester.firestore.collection("orders").document(saved.id)
        if (status != OrderStatus.PENDING) {
          orders(owner)
              .decideOrder(saved.id, if (status == OrderStatus.ACCEPTED) ACCEPT else REJECT)
        }
        assertEquals(
            OrderResult.Error(OrderError.AlreadyDecided(saved.copy(status = status))),
            orders(requester).confirmReceipt(saved.id),
        )
        assertPermissionDenied { ref.update("status", "DELIVERED").await() }
      }
      val saved = createOrder(requester, publish(owner))
      orders(owner).decideOrder(saved.id, ACCEPT)
      orders(owner).markReadyForHandoff(saved.id)
      val ref = requester.firestore.collection("orders").document(saved.id)
      val original = ref.get(Source.SERVER).await().data!!
      for (client in listOf(owner, other, signedOut)) {
        assertEquals(
            OrderResult.Error(
                if (client == signedOut) OrderError.NotAuthenticated
                else OrderError.PermissionDenied
            ),
            orders(client).confirmReceipt(saved.id),
        )
        assertPermissionDenied {
          client.firestore
              .collection("orders")
              .document(saved.id)
              .update("status", "DELIVERED")
              .await()
        }
      }
      for (mutation in
          listOf(
              mapOf("requesterId" to other.auth.currentUser!!.uid),
              mapOf("tripId" to "other"),
              mapOf("items" to emptyList<Any>()),
              mapOf("commissionCents" to 999),
              mapOf("createdAt" to Timestamp(0, 0)),
              mapOf("extra" to true),
              mapOf("commissionCents" to FieldValue.delete()),
          )) assertPermissionDenied { ref.update(mutation + ("status" to "DELIVERED")).await() }
      val expected = OrderResult.Success(saved.copy(status = OrderStatus.DELIVERED))
      val gate = CompletableDeferred<Unit>()
      val calls =
          List(2) {
            async(Dispatchers.Default) {
              gate.await()
              orders(requester).confirmReceipt(saved.id)
            }
          }
      gate.complete(Unit)
      assertEquals(listOf(expected, expected), calls.awaitAll())
      assertEquals(expected, orders(requester).confirmReceipt(saved.id))
      assertEquals(original + ("status" to "DELIVERED"), ref.get(Source.SERVER).await().data)
      assertEquals(expected, orders(requester).getOrderById(saved.id))
      for (status in OrderStatus.entries) assertPermissionDenied {
        ref.update("status", status.name).await()
      }
      for (client in listOf(owner, other)) assertEquals(
          OrderResult.Error(OrderError.PermissionDenied),
          orders(client).confirmReceipt(saved.id),
      )
      val direct = createOrder(requester, publish(owner))
      orders(owner).decideOrder(direct.id, ACCEPT)
      orders(owner).markReadyForHandoff(direct.id)
      requester.firestore
          .collection("orders")
          .document(direct.id)
          .update("status", "DELIVERED")
          .await()
      assertEquals(
          OrderResult.Success(direct.copy(status = OrderStatus.DELIVERED)),
          orders(requester).confirmReceipt(direct.id),
      )
      val uid = requester.auth.currentUser!!.uid
      assertEquals(
          OrderResult.Error(OrderError.NotAuthenticated),
          OrderRepositoryFirestore(
                  FirebaseOrderFirestoreDataSource(requester.firestore) { null },
                  { uid },
              )
              .confirmReceipt(saved.id),
      )
    }
  }

  private suspend fun writeOrder(
      client: EmulatorClient,
      data: Map<String, Any?>,
      recipient: String,
  ): DocumentReference {
    val ref = client.firestore.collection("orders").document()
    client.firestore
        .runBatch {
          it.set(ref, data)
          it.set(
              client.firestore.collection("orderEvents").document("${ref.id}_ORDER_PLACED"),
              event(ref.id, recipient, "ORDER_PLACED"),
          )
        }
        .await()
    return ref
  }

  private suspend fun writeDecision(
      client: EmulatorClient,
      id: String,
      status: String,
      recipient: String? = null,
      mutation: Map<String, Any> = emptyMap(),
  ) {
    val ref = client.firestore.collection("orders").document(id)
    val requester = recipient ?: ref.get(Source.SERVER).await().getString("requesterId")!!
    client.firestore
        .runBatch {
          it.update(ref, mutation + ("status" to status))
          it.set(
              client.firestore.collection("orderEvents").document("${id}_ORDER_$status"),
              event(id, requester, "ORDER_$status"),
          )
        }
        .await()
  }

  private fun event(id: String, recipient: String, type: String) =
      mapOf(
          "orderId" to id,
          "recipientId" to recipient,
          "type" to type,
          "occurredAt" to FieldValue.serverTimestamp(),
      )

  private fun expectedDecisionStatus(decision: OrderDecision): OrderStatus =
      when (decision) {
        ACCEPT -> OrderStatus.ACCEPTED
        REJECT -> OrderStatus.REJECTED
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
