package com.android.sample.data.repository

// AI assistance: OpenAI Codex.
import com.android.sample.model.NewRating
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.FirebaseFirestoreException.Code
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class RatingRepositoryFirestoreTest {
  private var uid: String? = "requester"
  private val source = MemorySource()
  private val repository = RatingRepositoryFirestore(source) { uid }
  private val input = NewRating("order", 4)

  @Test
  fun failuresAndUncertainCommitsNeverOverwriteFeedback() = runTest {
    for ((exception, error) in
        listOf(
            IOException() to RatingError.Unknown,
            FirebaseFirestoreException("denied", Code.PERMISSION_DENIED) to
                RatingError.PermissionDenied,
        )) {
      source.before = { throw exception }
      val before = source.transactions
      assertEquals(RatingResult.Error(error), repository.submitRating(input))
      assertEquals(
          if (error == RatingError.PermissionDenied) 2 else 1,
          source.transactions - before,
      )
      assertFalse(source.documents.containsKey("ratings/order"))
    }
    source.before = {}
    source.failRead = true
    assertEquals(RatingResult.Error(RatingError.Unknown), repository.submitRating(input))
    source.failRead = false
    val stored = repository.getRatingForOrder(input.orderId)
    assertTrue(stored is RatingResult.Success)
    assertEquals(
        RatingResult.Error(RatingError.AlreadyRated),
        repository.submitRating(input.copy(score = 1)),
    )
    assertEquals(stored, repository.getRatingForOrder(input.orderId))
  }

  @Test
  fun cancellationAndChangedSessionsRejectSuspendedOperations() = runTest {
    for (cancel in listOf(false, true)) {
      uid = "requester"
      val entered = CompletableDeferred<Unit>()
      val release = CompletableDeferred<Unit>()
      source.before = {
        entered.complete(Unit)
        release.await()
      }
      val pending = async { repository.submitRating(input) }
      entered.await()
      if (cancel) {
        pending.cancelAndJoin()
        assertTrue(pending.isCancelled)
      } else {
        uid = null
        release.complete(Unit)
        assertEquals(RatingResult.Error(RatingError.NotAuthenticated), pending.await())
      }
      assertFalse(source.documents.containsKey("ratings/order"))
    }
    source.before = {}
    for (id in listOf(null, "other")) {
      uid = id
      assertTrue(repository.getRatingForOrder("order") is RatingResult.Error)
    }
  }

  @Test
  fun deniedCommitReconcilesOnlyValidMatchingFeedbackWithoutRetryingWrites() = runTest {
    denyCommit { pending -> source.documents.putAll(pending) }
    assertEquals(RatingResult.Error(RatingError.AlreadyRated), repository.submitRating(input))
    assertEquals(2, source.transactions)
    assertEquals(2, source.attemptedWrites)
    assertEquals(
        input.score,
        (repository.getRatingForOrder(input.orderId) as RatingResult.Success).data!!.score,
    )
  }

  @Test
  fun absentMalformedAndMismatchedRatingsPreservePermissionFailure() = runTest {
    val invalid =
        listOf<Map<String, Any?>?>(
            null,
            emptyMap(),
            mapOf("orderId" to "wrong"),
            mapOf("tripId" to "other"),
            mapOf("requesterId" to "other"),
            mapOf("commandoId" to "other"),
            mapOf("score" to 0),
            mapOf("createdAt" to null),
        )
    for (change in invalid) {
      source.documents.remove("ratings/order")
      denyCommit { pending ->
        if (change != null)
            source.documents["ratings/order"] =
                if (change.isEmpty()) emptyMap() else pending.getValue("ratings/order") + change
      }
      assertEquals(
          change.toString(),
          RatingResult.Error(RatingError.PermissionDenied),
          repository.submitRating(input),
      )
    }
  }

  @Test
  fun reconciliationRevalidatesEligibilityAndPreservesReadFailures() = runTest {
    val initial = source.documents.toMap()
    for (change in
        listOf<suspend () -> Unit>(
            { source.documents.remove("orders/order") },
            { source.documents.remove("trips/trip") },
            {
              source.documents["orders/order"] =
                  initial.getValue("orders/order") + ("status" to "ACCEPTED")
            },
            {
              source.documents["orders/order"] =
                  initial.getValue("orders/order") + ("requesterId" to "other")
            },
            { source.documents["trips/trip"] = mapOf("ownerId" to "requester") },
            { source.before = { throw IOException("reconciliation offline") } },
        )) {
      source.documents.clear()
      source.documents.putAll(initial)
      source.before = {}
      denyCommit { pending ->
        source.documents.putAll(pending)
        change()
      }
      assertEquals(RatingResult.Error(RatingError.PermissionDenied), repository.submitRating(input))
    }
  }

  @Test
  fun reconciliationPropagatesCancellationAndRejectsEndedOrChangedSessions() = runTest {
    for (ending in listOf("sign-out", "account-change", "cancel")) {
      uid = "requester"
      source.documents.remove("ratings/order")
      val entered = CompletableDeferred<Unit>()
      val release = CompletableDeferred<Unit>()
      denyCommit { pending ->
        source.documents.putAll(pending)
        source.before = {
          entered.complete(Unit)
          release.await()
        }
      }
      val submission = async { repository.submitRating(input) }
      entered.await()
      if (ending == "cancel") {
        submission.cancelAndJoin()
        assertTrue(submission.isCancelled)
      } else {
        uid = if (ending == "sign-out") null else "other"
        release.complete(Unit)
        assertEquals(RatingResult.Error(RatingError.NotAuthenticated), submission.await())
      }
      source.before = {}
    }
  }

  private fun denyCommit(competingChange: suspend (Map<String, Map<String, Any?>>) -> Unit) {
    source.after = { pending ->
      source.after = {}
      competingChange(pending)
      throw FirebaseFirestoreException("commit denied", Code.PERMISSION_DENIED)
    }
  }

  private class MemorySource : RatingFirestoreDataSource {
    var before: suspend () -> Unit = {}
    var failRead = false
    var after: suspend (Map<String, Map<String, Any?>>) -> Unit = {}
    var transactions = 0
    var attemptedWrites = 0
    val documents =
        mutableMapOf<String, Map<String, Any?>>(
            "orders/order" to
                mapOf("tripId" to "trip", "requesterId" to "requester", "status" to "DELIVERED"),
            "trips/trip" to mapOf("ownerId" to "commando"),
        )

    override suspend fun get(collection: String, id: String): FirestoreDocument? {
      if (failRead) throw IOException("confirmation lost")
      return documents["$collection/$id"]?.let { FirestoreDocument(id, it) }
    }

    override suspend fun <T> transaction(action: (RatingTransaction) -> T): T {
      transactions++
      before()
      val pending = documents.toMutableMap()
      val result = runCatching {
        action(
            object : RatingTransaction {
              override fun get(collection: String, id: String) =
                  pending["$collection/$id"]?.let { FirestoreDocument(id, it) }

              override fun set(collection: String, id: String, data: Map<String, Any?>) {
                attemptedWrites++
                pending["$collection/$id"] =
                    if (collection == "ratings") data + ("createdAt" to Timestamp(0, 0)) else data
              }
            }
        )
      }
          .getOrElse { throw if (it is CancellationException) it else RuntimeException(it) }
      after(pending)
      documents.putAll(pending)
      return result
    }
  }
}
