package com.android.sample.emulator.firestore.security

// AI assistance: OpenAI Codex.
import com.android.sample.data.repository.*
import com.android.sample.model.*
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.Source
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Repository integration and direct malicious writes against production rules, never open rules.
 */
@RunWith(RobolectricTestRunner::class)
class RatingFirestoreSecurityRulesEmulatorTest {
  @Test
  fun persistenceConcurrencyPrivacyAndSummaries() = runTest {
    withClients("owner" to true, "requester" to true, "outsider" to true, "signedOut" to false) {
        (owner, requester, outsider, signedOut) ->
      val id = seed(owner, requester)
      val ownerId = owner.auth.currentUser!!.uid
      assertEquals(RatingResult.Success(null), repo(requester).getRatingForOrder(id))
      assertEquals(
          RatingResult.Success(RatingSummary(ownerId, 0, null)),
          repo(outsider).getRatingSummary(ownerId),
      )
      val start = CompletableDeferred<Unit>()
      val results =
          listOf(1, 5).map { score ->
            async(Dispatchers.IO) {
              start.await()
              repo(requester).submitRating(NewRating(id, score, " text "))
            }
          }
      start.complete(Unit)
      val outcomes = results.awaitAll()
      val saved = outcomes.filterIsInstance<RatingResult.Success<Rating>>().single().data
      assertEquals(1, outcomes.count { it == RatingResult.Error(RatingError.AlreadyRated) })
      assertEquals(" text ", saved.comment)
      for (client in listOf(owner, requester)) assertEquals(
          RatingResult.Success(saved),
          repo(client).getRatingForOrder(id),
      )
      val another = seed(owner, requester, "COMPLETED")
      assertTrue(
          repo(requester).submitRating(NewRating(another, 4, " ".repeat(251)))
              is RatingResult.Success
      )
      assertEquals(
          RatingResult.Success(RatingSummary(ownerId, 2, (saved.score + 4) / 2.0)),
          repo(outsider).getRatingSummary(ownerId),
      )
      val rating = requester.firestore.collection("ratings").document(id)
      for (client in listOf(outsider, signedOut)) assertPermissionDenied {
        client.firestore.collection("ratings").document(id).get(Source.SERVER).await()
      }
      for (client in listOf(owner, requester, outsider, signedOut)) {
        assertPermissionDenied {
          client.firestore.collection("ratings").document(id).update("score", 3).await()
        }
        assertPermissionDenied {
          client.firestore.collection("ratings").document(id).delete().await()
        }
        assertPermissionDenied { client.firestore.collection("ratings").get(Source.SERVER).await() }
      }
      assertPermissionDenied {
        signedOut.firestore
            .collection("ratingSummaries")
            .document(ownerId)
            .get(Source.SERVER)
            .await()
      }
      assertEquals(saved.score.toLong(), rating.get(Source.SERVER).await().getLong("score"))
    }
  }

  @Test
  fun rulesRejectActorsStatusesForgedFieldsAndUncoupledWrites() = runTest {
    withClients("owner" to true, "requester" to true, "outsider" to true, "signedOut" to false) {
        (owner, requester, outsider, signedOut) ->
      val id = seed(owner, requester)
      val ownerId = owner.auth.currentUser!!.uid
      val data = payload(id, ownerId, requester.auth.currentUser!!.uid)
      for (client in listOf(owner, outsider, signedOut)) assertPermissionDenied {
        write(client, id, data)
      }
      for (status in OrderStatus.entries.filter { it != OrderStatus.DELIVERED }) {
        updateDocumentAsOwner("orders/$id", mapOf("status" to status.name))
        assertPermissionDenied(status.name) { write(requester, id, data) }
      }
      updateDocumentAsOwner("orders/$id", mapOf("status" to "DELIVERED"))
      val invalid =
          listOf(
              data + ("score" to 0),
              data + ("score" to 6),
              data + ("score" to 2.5),
              data + ("comment" to "x".repeat(251)),
              data + ("comment" to "😀".repeat(126)),
              data + ("comment" to " \t\n"),
              data + ("comment" to 1),
              data + ("requesterId" to ownerId),
              data + ("commandoId" to "forged"),
              data + ("tripId" to "forged"),
              data + ("orderId" to "forged"),
              data + ("createdAt" to com.google.firebase.Timestamp(0, 0)),
              data + ("extra" to true),
              data - "comment",
          )
      for (fields in invalid) assertPermissionDenied(fields.toString()) {
        write(requester, id, fields, ownerId)
      }
      assertPermissionDenied {
        requester.firestore.collection("ratings").document(id).set(data).await()
      }
      assertPermissionDenied { write(requester, "wrong-$id", data) }
      val summary = requester.firestore.collection("ratingSummaries").document(ownerId)
      assertPermissionDenied {
        summary.set(mapOf("ratingCount" to 1, "scoreTotal" to 3, "lastOrderId" to id)).await()
      }
      assertPermissionDenied { write(requester, id, data, count = 2) }
      assertPermissionDenied { write(requester, id, data, total = 4) }
      write(requester, id, data)
      for (change in
          listOf(mapOf("ratingCount" to 2), mapOf("scoreTotal" to 4), mapOf("extra" to true))) {
        assertPermissionDenied { summary.update(change).await() }
      }
      assertPermissionDenied { summary.delete().await() }
      assertPermissionDenied { write(requester, id, data, count = 2, total = 6) }
      val self = seed(owner, owner)
      assertPermissionDenied { write(owner, self, payload(self, ownerId, ownerId)) }
    }
  }

  private fun repo(client: EmulatorClient) =
      RatingRepositoryFirestore({ client.auth.currentUser?.uid }, client.firestore)

  private fun seed(
      owner: EmulatorClient,
      requester: EmulatorClient,
      status: String = "IN_PROGRESS",
  ): String {
    val id = UUID.randomUUID().toString()
    createDocumentAsOwner(
        "trips",
        id,
        mapOf("ownerId" to owner.auth.currentUser!!.uid, "status" to status),
    )
    createDocumentAsOwner(
        "orders",
        id,
        mapOf(
            "tripId" to id,
            "requesterId" to requester.auth.currentUser!!.uid,
            "status" to "DELIVERED",
        ),
    )
    return id
  }

  private fun payload(id: String, owner: String, requester: String): Map<String, Any?> =
      mapOf(
          "orderId" to id,
          "tripId" to id,
          "requesterId" to requester,
          "commandoId" to owner,
          "score" to 3,
          "comment" to null,
          "createdAt" to FieldValue.serverTimestamp(),
      )

  private suspend fun write(
      client: EmulatorClient,
      id: String,
      data: Map<String, Any?>,
      owner: String = data.getValue("commandoId") as String,
      count: Int = 1,
      total: Int = 3,
  ) {
    client.firestore
        .batch()
        .apply {
          set(client.firestore.collection("ratings").document(id), data)
          set(
              client.firestore.collection("ratingSummaries").document(owner),
              mapOf(
                  "ratingCount" to count,
                  "scoreTotal" to total,
                  "lastOrderId" to id,
              ),
          )
        }
        .commit()
        .await()
  }
}
