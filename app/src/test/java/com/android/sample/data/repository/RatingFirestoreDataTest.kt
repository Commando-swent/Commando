package com.android.sample.data.repository

// AI assistance: OpenAI Codex.
import com.android.sample.model.MAX_RATING_COMMENT_LENGTH
import com.google.firebase.Timestamp
import org.junit.Assert.*
import org.junit.Test

/** Foundation validation independent of Firebase initialization and write permissions. */
class RatingFirestoreDataTest {
  private val fields =
      mapOf<String, Any?>(
          "orderId" to "order",
          "tripId" to "trip",
          "requesterId" to "requester",
          "commandoId" to "commando",
          "score" to 4L,
          "comment" to null,
          "createdAt" to Timestamp(0, 0),
      )

  @Test
  fun feedbackPreservesTextAndRejectsMalformedFields() {
    for (score in RATING_SCORES) {
      val text = "x".repeat(MAX_RATING_COMMENT_LENGTH)
      val rating =
          FirestoreDocument("order", fields + mapOf("score" to score, "comment" to text)).rating()
      assertEquals(score, rating.score)
      assertEquals(text, rating.comment)
    }
    for (change in
        listOf(
            mapOf("score" to 0),
            mapOf("score" to 6),
            mapOf("score" to 2.5),
            mapOf("comment" to " "),
            mapOf("comment" to "x".repeat(MAX_RATING_COMMENT_LENGTH + 1)),
            mapOf("orderId" to "other"),
            mapOf("tripId" to "a/b"),
            mapOf("requesterId" to " "),
            mapOf("commandoId" to "requester"),
            mapOf("createdAt" to null),
        )) {
      assertError(RatingError.InvalidData) { FirestoreDocument("order", fields + change).rating() }
    }
  }

  @Test
  fun summariesRequireIntegralBoundedCountersAndHandleAbsence() {
    assertEquals(0 to 0L, (null as FirestoreDocument?).summary("commando"))
    val counters = mapOf("ratingCount" to 3L, "scoreTotal" to 10L, "lastOrderId" to "order")
    assertEquals(3 to 10L, FirestoreDocument("commando", counters).summary("commando"))
    for (change in
        listOf(
            mapOf("ratingCount" to 0),
            mapOf("ratingCount" to 2147483648L),
            mapOf("scoreTotal" to 2),
            mapOf("scoreTotal" to 16),
            mapOf("scoreTotal" to 10.0),
            mapOf("lastOrderId" to "a/b"),
        )) {
      assertError(RatingError.InvalidData) {
        FirestoreDocument("commando", counters + change).summary("commando")
      }
    }
  }

  @Test
  fun relationshipChecksPreserveErrorPrecedenceAndReadAuthorization() {
    val order = mapOf("tripId" to "trip", "requesterId" to "requester", "status" to "ACCEPTED")
    val documents =
        mutableMapOf<String, Map<String, Any?>>(
            "orders/order" to order,
            "trips/trip" to mapOf("ownerId" to "commando"),
        )
    val transaction =
        object : RatingTransaction {
          override fun get(collection: String, id: String) =
              documents["$collection/$id"]?.let { FirestoreDocument(id, it) }

          override fun set(collection: String, id: String, data: Map<String, Any?>) =
              error("Read-only foundation test")
        }
    assertError(RatingError.PermissionDenied) {
      transaction.context("order", "commando", submitting = true)
    }
    assertError(RatingError.OrderNotDelivered) {
      transaction.context("order", "requester", submitting = true)
    }
    assertError(RatingError.PermissionDenied) { transaction.readRating("order", "outsider") }
    assertNull(transaction.readRating("order", "commando"))
    documents["ratings/order"] = fields
    assertEquals("commando", transaction.readRating("order", "requester")!!.commandoId)
    documents["ratings/order"] = fields + ("commandoId" to "other")
    assertError(RatingError.InvalidData) { transaction.readRating("order", "requester") }
    documents.remove("trips/trip")
    assertError(RatingError.NotFound) { transaction.readRating("order", "requester") }
  }

  private fun assertError(expected: RatingError, action: () -> Unit) {
    assertEquals(expected, assertThrows(RatingFailure::class.java, action).error)
  }
}
