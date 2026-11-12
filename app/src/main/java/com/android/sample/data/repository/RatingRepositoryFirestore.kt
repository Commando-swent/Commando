package com.android.sample.data.repository

// AI assistance: OpenAI Codex.
import com.android.sample.model.*
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Immutable, server-confirmed feedback. Inject an identity invalidated when its session ends. */
class RatingRepositoryFirestore
internal constructor(
    private val source: RatingFirestoreDataSource,
    private val currentUserId: () -> String?,
) : RatingRepository {
  /** Uses server reads and transactions; UI code must supply a session-bound identity. */
  constructor(
      currentUserId: () -> String?,
      firestore: FirebaseFirestore = FirebaseFirestore.getInstance(),
  ) : this(FirebaseRatingFirestoreDataSource(firestore), currentUserId)

  /** Creates feedback and increments its summary atomically; uncertain outcomes require a read. */
  override suspend fun submitRating(input: NewRating): RatingResult<Rating> =
      session { uid, guard ->
        val comment = input.comment?.takeUnless(String::isBlank)
        requireRating(
            input.orderId.isDocumentId() &&
                input.score in RATING_SCORES &&
                (comment == null || comment.length <= MAX_RATING_COMMENT_LENGTH),
        )
        try {
          source.transaction { transaction ->
            val context = transaction.context(input.orderId, uid, submitting = true)
            requireRating(transaction.get(RATINGS, input.orderId) == null, RatingError.AlreadyRated)
            val summary = transaction.get(SUMMARIES, context.commandoId).summary(context.commandoId)
            requireRating(summary.first < Int.MAX_VALUE)
            guard()
            transaction.set(
                RATINGS,
                input.orderId,
                mapOf(
                    "orderId" to input.orderId,
                    "tripId" to context.tripId,
                    "requesterId" to context.requesterId,
                    "commandoId" to context.commandoId,
                    "score" to input.score,
                    "comment" to comment,
                    "createdAt" to FieldValue.serverTimestamp(),
                ),
            )
            transaction.set(
                SUMMARIES,
                context.commandoId,
                mapOf(
                    "ratingCount" to summary.first + 1,
                    "scoreTotal" to summary.second + input.score,
                    "lastOrderId" to input.orderId,
                ),
            )
          }
        } catch (failure: FirebaseFirestoreException) {
          if (failure.code != FirebaseFirestoreException.Code.PERMISSION_DENIED) throw failure
          reconcile(input.orderId, uid, guard, failure)
        }
        guard()
        source.get(RATINGS, input.orderId)?.rating() ?: throw RatingFailure(RatingError.Unknown)
      }

  /** Authorizes persisted participants before revealing feedback or its absence. */
  override suspend fun getRatingForOrder(orderId: String): RatingResult<Rating?> =
      session { uid, guard ->
        requireRating(orderId.isDocumentId())
        source.transaction { transaction ->
          val rating = transaction.readRating(orderId, uid)
          guard()
          rating
        }
      }

  /** Reconciles one denied write without retrying it or replacing unrelated read failures. */
  private suspend fun reconcile(
      orderId: String,
      uid: String,
      guard: () -> Unit,
      original: FirebaseFirestoreException,
  ): Nothing {
    guard()
    val existing =
        try {
          source.transaction { transaction ->
            transaction.readRating(orderId, uid, submitting = true)
          }
        } catch (cancelled: CancellationException) {
          throw cancelled
        } catch (_: Exception) {
          guard()
          throw original
        }
    guard()
    if (existing != null) throw RatingFailure(RatingError.AlreadyRated)
    throw original
  }

  /** Reads one authenticated summary, dividing the integral total without rounding. */
  override suspend fun getRatingSummary(commandoId: String): RatingResult<RatingSummary> =
      session { _, _ ->
        requireRating(commandoId.isDocumentId())
        val (count, total) = source.get(SUMMARIES, commandoId).summary(commandoId)
        RatingSummary(commandoId, count, if (count == 0) null else total.toDouble() / count)
      }

  private suspend fun <T> session(action: suspend (String, () -> Unit) -> T): RatingResult<T> {
    val context = currentCoroutineContext()
    context.ensureActive()
    val uid =
        currentUserId()?.takeIf(String::isNotBlank)
            ?: return RatingResult.Error(RatingError.NotAuthenticated)
    val guard = {
      context.ensureActive()
      requireRating(currentUserId() == uid, RatingError.NotAuthenticated)
    }
    return try {
      val result = action(uid, guard)
      guard()
      RatingResult.Success(result)
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (exception: Exception) {
      context.ensureActive()
      RatingResult.Error(
          when {
            currentUserId() != uid -> RatingError.NotAuthenticated
            exception is RatingFailure -> exception.error
            exception.cause is RatingFailure -> (exception.cause as RatingFailure).error
            else ->
                when (exception.toFirestoreFailure()) {
                  FirestoreFailure.UNAUTHENTICATED -> RatingError.NotAuthenticated
                  FirestoreFailure.PERMISSION_DENIED -> RatingError.PermissionDenied
                  FirestoreFailure.NOT_FOUND -> RatingError.NotFound
                  FirestoreFailure.INVALID_DATA -> RatingError.InvalidData
                  else -> RatingError.Unknown
                }
          }
      )
    }
  }
}
