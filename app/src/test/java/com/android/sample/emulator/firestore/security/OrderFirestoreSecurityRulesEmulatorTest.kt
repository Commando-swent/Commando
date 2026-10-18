package com.android.sample.emulator.firestore.security

// AI assistance: OpenAI Codex.
import com.android.sample.data.repository.*
import com.android.sample.model.*
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.Source
import java.time.Instant
import java.util.UUID
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
