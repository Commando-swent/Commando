package com.android.sample.data.repository

// AI assistance: OpenAI Codex.
import com.android.sample.model.MAX_RATING_COMMENT_LENGTH
import com.android.sample.model.NewRating
import com.android.sample.model.Order
import com.android.sample.model.OrderStatus
import com.android.sample.model.Rating
import com.android.sample.model.RatingSummary
import com.android.sample.model.Trip
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * In-memory feedback shared within one instance; never persists data or enforces Firebase rules.
 *
 * [currentUserId] must be session-bound and invalidated when that session ends. UID comparisons
 * alone cannot detect an account changing away and back between checks. Inject repositories for the
 * same session; their persisted order/trip identities determine authorization.
 *
 * Both submission and per-order reads depend on [TripRepository.getTripById] allowing the lookup. A
 * denied lookup fails closed, including for delivered orders on closed or completed trips. Success
 * with a fake trip repository does not prove production requester access: the Firestore
 * access/ownership strategy must be reviewed before implementing the production rating repository.
 *
 * One mutex serializes lookups and rating storage operations. It guarantees uniqueness only in this
 * instance, not atomicity across the injected repositories. [now] supplies creation timestamps.
 */
class FakeRatingRepository(
    private val currentUserId: () -> String?,
    private val orderRepository: OrderRepository,
    private val tripRepository: TripRepository,
    private val now: () -> Instant = Instant::now,
) : RatingRepository {
  private val mutex = Mutex()
  private val ratings = mutableMapOf<String, Rating>()

  /**
   * Validates and stores one delivered-order rating, normalizing absent comments before length
   * validation. Authorized duplicates fail without changing the original feedback or timestamp.
   */
  override suspend fun submitRating(input: NewRating): RatingResult<Rating> = withSession { uid ->
    val comment = input.comment?.takeUnless { it.isBlank() }
    if (input.score !in 1..5 || (comment != null && comment.length > MAX_RATING_COMMENT_LENGTH)) {
      return@withSession RatingResult.Error(RatingError.InvalidData)
    }
    withOrderTrip(input.orderId, uid, submitting = true) { order, trip ->
      if (ratings.containsKey(order.id)) {
        return@withOrderTrip RatingResult.Error(RatingError.AlreadyRated)
      }
      val rating =
          Rating(
              orderId = order.id,
              tripId = order.tripId,
              requesterId = order.requesterId,
              commandoId = trip.ownerId,
              score = input.score,
              comment = comment,
              createdAt = now(),
          )
      if (!sameSession(uid)) {
        return@withOrderTrip RatingResult.Error(RatingError.NotAuthenticated)
      }
      ratings[order.id] = rating
      RatingResult.Success(rating)
    }
  }

  /**
   * Resolves persisted participants before revealing feedback or its absence. Trip lookup denial is
   * propagated rather than bypassed using a previously stored rating's identities.
   */
  override suspend fun getRatingForOrder(orderId: String): RatingResult<Rating?> =
      withSession { uid ->
        withOrderTrip(orderId, uid) { order, _ -> RatingResult.Success(ratings[order.id]) }
      }

  /** Returns an authenticated aggregate from the same locked storage used for submissions. */
  override suspend fun getRatingSummary(commandoId: String): RatingResult<RatingSummary> =
      withSession { _ ->
        if (!commandoId.isDocumentId()) {
          return@withSession RatingResult.Error(RatingError.InvalidData)
        }
        val matching = ratings.values.filter { it.commandoId == commandoId }
        val average =
            if (matching.isEmpty()) null
            else matching.sumOf { it.score.toLong() }.toDouble() / matching.size
        RatingResult.Success(RatingSummary(commandoId, matching.size, average))
      }

  private suspend fun <T> withOrderTrip(
      orderId: String,
      uid: String,
      submitting: Boolean = false,
      action: suspend (Order, Trip) -> RatingResult<T>,
  ): RatingResult<T> {
    if (!orderId.isDocumentId()) return RatingResult.Error(RatingError.InvalidData)
    val orderResult = orderRepository.getOrderById(orderId)
    if (!sameSession(uid)) return RatingResult.Error(RatingError.NotAuthenticated)
    val order =
        when (orderResult) {
          is OrderResult.Success -> orderResult.data
          is OrderResult.Error -> return RatingResult.Error(orderResult.error.toRatingError())
        }
    if (order.id != orderId || !order.tripId.isDocumentId() || order.requesterId.isBlank()) {
      return RatingResult.Error(RatingError.InvalidData)
    }
    // Submission checks precede trip lookup; reads skip these eligibility checks.
    when {
      !submitting -> Unit
      order.requesterId != uid -> return RatingResult.Error(RatingError.PermissionDenied)
      order.status != OrderStatus.DELIVERED ->
          return RatingResult.Error(RatingError.OrderNotDelivered)
    }
    val tripResult = tripRepository.getTripById(order.tripId)
    if (!sameSession(uid)) return RatingResult.Error(RatingError.NotAuthenticated)
    val trip =
        when (tripResult) {
          is TripResult.Success -> tripResult.data
          is TripResult.Error -> return RatingResult.Error(tripResult.error.toRatingError())
        }
    if (trip.id != order.tripId || trip.ownerId.isBlank()) {
      return RatingResult.Error(RatingError.InvalidData)
    }
    if (uid != order.requesterId && uid != trip.ownerId) {
      return RatingResult.Error(RatingError.PermissionDenied)
    }
    if (submitting && order.requesterId == trip.ownerId) {
      return RatingResult.Error(RatingError.PermissionDenied)
    }
    return action(order, trip)
  }

  private suspend fun <T> withSession(
      action: suspend (String) -> RatingResult<T>
  ): RatingResult<T> {
    currentCoroutineContext().ensureActive()
    val uid =
        currentUserId()?.takeIf { it.isNotBlank() }
            ?: return RatingResult.Error(RatingError.NotAuthenticated)
    return try {
      mutex.withLock {
        if (!sameSession(uid)) return@withLock RatingResult.Error(RatingError.NotAuthenticated)
        val result = action(uid)
        if (sameSession(uid)) result else RatingResult.Error(RatingError.NotAuthenticated)
      }
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (_: Exception) {
      RatingResult.Error(
          if (sameSession(uid)) RatingError.Unknown else RatingError.NotAuthenticated
      )
    }
  }

  private suspend fun sameSession(uid: String): Boolean {
    currentCoroutineContext().ensureActive()
    return currentUserId() == uid
  }

  private fun OrderError.toRatingError(): RatingError =
      when (this) {
        OrderError.NotAuthenticated -> RatingError.NotAuthenticated
        OrderError.NotFound -> RatingError.NotFound
        OrderError.PermissionDenied -> RatingError.PermissionDenied
        OrderError.InvalidData -> RatingError.InvalidData
        is OrderError.AlreadyDecided,
        OrderError.Unknown -> RatingError.Unknown
      }

  private fun TripError.toRatingError(): RatingError =
      when (this) {
        TripError.NotFound -> RatingError.NotFound
        TripError.PermissionDenied -> RatingError.PermissionDenied
        TripError.InvalidData -> RatingError.InvalidData
        TripError.NetworkError,
        TripError.InvalidTransition,
        TripError.OrdersStillOpen,
        TripError.Unknown -> RatingError.Unknown
      }
}
