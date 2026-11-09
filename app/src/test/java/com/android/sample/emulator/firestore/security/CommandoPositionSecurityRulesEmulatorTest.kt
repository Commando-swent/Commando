package com.android.sample.emulator.firestore.security

// AI assistance: OpenAI Codex.
import com.android.sample.data.repository.CommandoPositionRepositoryFirestore
import com.android.sample.model.CommandoPosition
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.Source
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Direct SDK attacks and real repository listeners against the production rules. */
@RunWith(RobolectricTestRunner::class)
class CommandoPositionSecurityRulesEmulatorTest {
  @Test
  fun ownerPublishesReplacesAndDeletesOnePositionWhileOutsidersCannotWrite() = runTest {
    withClients("owner" to true, "other" to true, "out" to false) { (owner, other, out) ->
      val trip = trip(owner)
      val uid = owner.auth.currentUser!!.uid
      val repo = repo(owner)
      val first = fix()
      repo.publish(trip, first)
      val ref = owner.firestore.collection("commandoPositions").document(uid)
      assertEquals(first.latitude, ref.get(Source.SERVER).await().getDouble("latitude"))
      val replacement = first.copy(latitude = 46.53, capturedAt = fix().capturedAt)
      repo.publish(trip, replacement)
      assertEquals(replacement.latitude, ref.get(Source.SERVER).await().getDouble("latitude"))
      for (client in listOf(other, out)) {
        val foreign = client.firestore.collection("commandoPositions").document(uid)
        assertPermissionDenied { foreign.delete().await() }
        assertPermissionDenied { foreign.set(data(trip)).await() }
      }
      assertPermissionDenied {
        out.firestore.collection("commandoPositions").document(uid).get(Source.SERVER).await()
      }
      assertPermissionDenied {
        owner.firestore.collection("commandoPositions").get(Source.SERVER).await()
      }
      repo.stopSharing()
      assertFalse(ref.get(Source.SERVER).await().exists())
    }
  }

  @Test
  fun onlyFreshValidOwnerTripWritesWithServerTimestampAreAccepted() = runTest {
    withClients("owner" to true, "other" to true) { (owner, other) ->
      val own = trip(owner)
      val foreign = trip(other)
      val ref =
          owner.firestore.collection("commandoPositions").document(owner.auth.currentUser!!.uid)
      for (change in
          listOf(
              mapOf("latitude" to 91.0),
              mapOf("longitude" to Double.NaN),
              mapOf("accuracyMeters" to -1.0),
              mapOf("accuracyMeters" to Double.POSITIVE_INFINITY),
              mapOf("capturedAt" to Timestamp(Instant.now().minusSeconds(121))),
              mapOf("capturedAt" to Timestamp(Instant.now().plusSeconds(60))),
              mapOf("updatedAt" to Timestamp(0, 0)),
              mapOf("tripId" to foreign),
              mapOf("tripId" to "missing"),
              mapOf("extra" to true),
          )) {
        assertPermissionDenied { ref.set(data(own) + change).await() }
      }
      repo(owner).publish(own, fix())
      assertTrue(ref.get(Source.SERVER).await().exists())
    }
  }

  @Test
  fun discoveryRequiresAnOpenFutureTripAndAnOrderWitnessAllowsClosedTripTracking() = runTest {
    withClients("owner" to true, "requester" to true, "outsider" to true) {
        (owner, requester, outsider) ->
      val trip = trip(owner)
      val uid = owner.auth.currentUser!!.uid
      repo(owner).publish(trip, fix())
      val position = requester.firestore.collection("commandoPositions").document(uid)
      assertTrue(position.get(Source.SERVER).await().exists())
      val orderId = UUID.randomUUID().toString()
      createDocumentAsOwner(
          "orders",
          orderId,
          mapOf(
              "requesterId" to requester.auth.currentUser!!.uid,
              "tripId" to trip,
              "status" to "PENDING",
          ),
      )
      owner.firestore.collection("trips").document(trip).update("status", "IN_PROGRESS").await()
      assertPermissionDenied { position.get(Source.SERVER).await() }
      assertPermissionDenied { repo(outsider).observe(uid, orderId).first() }
      val received = repo(requester).observe(uid, orderId).filterNotNull().first()
      assertEquals(46.52, received.latitude, 0.0)
      for (status in listOf("PENDING", "ACCEPTED", "READY_FOR_HANDOFF")) {
        replaceOrder(orderId, requester, trip, status)
        assertTrue(position.get(Source.SERVER).await().exists())
      }
      for (status in listOf("REJECTED", "CANCELLED", "DELIVERED")) {
        replaceOrder(orderId, requester, trip, status)
        assertPermissionDenied { position.get(Source.SERVER).await() }
      }
      val pointer = position.collection("viewers").document(requester.auth.currentUser!!.uid)
      assertPermissionDenied { pointer.set(mapOf("orderId" to orderId)).await() }
      assertPermissionDenied {
        outsider.firestore
            .collection("commandoPositions")
            .document(uid)
            .collection("viewers")
            .document(requester.auth.currentUser!!.uid)
            .get(Source.SERVER)
            .await()
      }
    }
  }

  @Test
  fun liveObservationReceivesServerFix() = runTest {
    withClients("owner" to true, "viewer" to true) { (owner, viewer) ->
      val trip = trip(owner)
      val uid = owner.auth.currentUser!!.uid
      val next = async { repo(viewer).observe(uid).filterNotNull().first() }
      val position = fix()
      repo(owner).publish(trip, position)
      assertEquals(position, next.await())
    }
  }

  private fun repo(client: EmulatorClient) =
      CommandoPositionRepositoryFirestore({ client.auth.currentUser?.uid }, client.firestore)

  private fun fix() =
      CommandoPosition(
          46.52,
          6.56,
          30.0,
          // A received fix predates publication; do not assume identical clocks across JVMs.
          Instant.now().minusSeconds(1).let { it.minusNanos((it.nano % 1000).toLong()) },
      )

  private fun data(tripId: String) =
      mapOf(
          "tripId" to tripId,
          "latitude" to 46.52,
          "longitude" to 6.56,
          "accuracyMeters" to 30.0,
          "capturedAt" to Timestamp(Instant.now()),
          "updatedAt" to FieldValue.serverTimestamp(),
      )

  private fun replaceOrder(id: String, requester: EmulatorClient, trip: String, status: String) {
    deleteDocumentAsOwner("orders/$id")
    createDocumentAsOwner(
        "orders",
        id,
        mapOf(
            "requesterId" to requester.auth.currentUser!!.uid,
            "tripId" to trip,
            "status" to status,
        ),
    )
  }

  private suspend fun trip(owner: EmulatorClient): String {
    val now = Instant.now()
    val location = mapOf("name" to "EPFL", "latitude" to 46.52, "longitude" to 6.56)
    return owner.firestore
        .collection("trips")
        .add(
            mapOf(
                "ownerId" to owner.auth.currentUser!!.uid,
                "store" to location,
                "handoffLocation" to location,
                "status" to "PUBLISHED",
                "scheduledAt" to Timestamp(now.plusSeconds(3600)),
                "createdAt" to Timestamp(now),
                "updatedAt" to Timestamp(now),
            )
        )
        .await()
        .id
  }
}
