package com.android.sample.data.repository

// AI assistance: OpenAI Codex.
import com.android.sample.model.*
import com.android.sample.model.OrderStatus.DELIVERED
import com.android.sample.model.TripStatus.PUBLISHED
import java.time.Instant
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart.UNDISPATCHED
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class FakeRatingRepositoryTest {
  private val time = Instant.parse("2026-11-12T12:00:00Z")
  private var userId: String? = "requester"
  private val place = Location("Store", 46.5, 6.6)
  private val item = OrderItem("milk", "Milk", 100, 1)
  private val scheduledAt = time.plusSeconds(3600)
  private val invalidIds = listOf("", " \t", "a/b", ".", "..")

  @Test
  fun deliveredOrdersDeriveIdentitiesAndAcceptBoundaryScoresWithoutTripCompletion() = runTest {
    for (status in TripStatus.entries) {
      for (score in listOf(1, 5)) {
        val f = Fixture(trips = listOf(trip().copy(status = status)))
        val expected = Rating("order", "trip", "requester", "commando", score, " text ", time)
        assertEquals(RatingResult.Success(expected), f.submit(score, " text "))
        assertEquals(RatingResult.Success(expected), f.read())
      }
    }
  }

  @Test
  fun commentsNormalizeBeforeLengthValidationAndPreserveNonblankText() = runTest {
    for (comment in listOf(null, "", " \t\n", " ".repeat(251), "x".repeat(250))) {
      val f = Fixture()
      val saved = f.submit(3, comment).data()
      assertEquals(comment?.takeUnless { it.isBlank() }, saved.comment)
    }
    val f = Fixture()
    assertEquals(failure(RatingError.InvalidData), f.submit(3, "x".repeat(251)))
    assertEquals(RatingResult.Success(null), f.read())
  }

  @Test
  fun invalidScoresIdsAndUndeliveredOrdersNeverInsert() = runTest {
    val f = Fixture()
    for (score in listOf(Int.MIN_VALUE, -1, 0, 6, Int.MAX_VALUE)) {
      assertEquals(score.toString(), failure(RatingError.InvalidData), f.submit(score))
    }
    for (id in invalidIds) {
      assertEquals(id, failure(RatingError.InvalidData), f.submit(id = id))
      assertEquals(id, failure(RatingError.InvalidData), f.rating.getRatingForOrder(id))
      assertEquals(id, failure(RatingError.InvalidData), f.rating.getRatingSummary(id))
    }
    for (status in OrderStatus.entries - OrderStatus.DELIVERED) {
      val pending = Fixture(listOf(order().copy(status = status)))
      assertEquals(status.name, failure(RatingError.OrderNotDelivered), pending.submit(3))
      assertEquals(RatingResult.Success(null), pending.read())
    }
    assertEquals(RatingResult.Success(null), f.read())
  }

  @Test
  fun missingSessionsRejectEveryOperationAndOtherUsersCannotSubmit() = runTest {
    val f = Fixture()
    for (missing in listOf(null, "", "  ")) {
      userId = missing
      assertEquals(failure(RatingError.NotAuthenticated), f.submit(3))
      assertEquals(failure(RatingError.NotAuthenticated), f.read())
      assertEquals(failure(RatingError.NotAuthenticated), f.rating.getRatingSummary("commando"))
    }
    f.orderResult = OrderResult.Success(order())
    f.tripResult = TripResult.Success(trip())
    for (other in listOf("stranger", "commando")) {
      userId = other
      assertEquals(failure(RatingError.PermissionDenied), f.submit(3))
    }
    userId = "requester"
    assertEquals(RatingResult.Success(null), f.read())
    val self = Fixture(trips = listOf(trip().copy(ownerId = "requester")))
    assertEquals(failure(RatingError.PermissionDenied), self.submit(3))
    assertEquals(RatingResult.Success(null), self.read())
  }

  @Test
  fun missingAndInconsistentDependenciesFailClosed() = runTest {
    for (f in listOf(Fixture(orders = emptyList()), Fixture(trips = emptyList()))) {
      assertEquals(failure(RatingError.NotFound), f.submit(3))
      assertEquals(failure(RatingError.NotFound), f.read())
    }
    for (bad in
        listOf(
            order().copy(id = "wrong"),
            order().copy(tripId = "a/b"),
            order().copy(requesterId = " "),
        )) {
      val f = Fixture()
      f.orderResult = OrderResult.Success(bad)
      assertEquals(failure(RatingError.InvalidData), f.submit(3))
      assertEquals(failure(RatingError.InvalidData), f.read())
    }
    for (bad in listOf(trip().copy(id = "wrong"), trip().copy(ownerId = " "))) {
      val f = Fixture()
      f.tripResult = TripResult.Success(bad)
      assertEquals(failure(RatingError.InvalidData), f.submit(3))
      assertEquals(failure(RatingError.InvalidData), f.read())
    }
  }

  @Test
  fun duplicatesValidateAndAuthorizeBeforeReportingConflictAndPreserveOriginal() = runTest {
    var clockReads = 0
    val f = Fixture(now = { time.plusSeconds(clockReads++.toLong()) })
    val original = f.submit(1, "original").data()
    for (input in listOf(NewRating("order", 1, "original"), NewRating("order", 5, "changed"))) {
      assertEquals(failure(RatingError.AlreadyRated), f.rating.submitRating(input))
    }
    assertEquals(failure(RatingError.InvalidData), f.submit(0))
    assertEquals(failure(RatingError.InvalidData), f.submit(3, "x".repeat(251)))
    userId = "commando"
    assertEquals(failure(RatingError.PermissionDenied), f.submit(5))
    userId = "requester"
    assertEquals(1, clockReads)
    assertEquals(RatingResult.Success(original), f.read())
    assertEquals(RatingSummary("commando", 1, 1.0), f.summary())
  }

  @Test
  fun overlappingSubmissionsStoreExactlyOneWinner() =
      runTest(timeout = 5.seconds) {
        val f = Fixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        // Only the first lookup waits, so a missing mutex would let the competitor finish.
        f.beforeOrder = { if (entered.complete(Unit)) release.await() }
        val first = async { f.submit(1) }
        entered.await()
        assertFalse(first.isCompleted)
        val second = async(start = UNDISPATCHED) { f.submit(5) }
        assertFalse(second.isCompleted)
        val cancelled = async(start = UNDISPATCHED) { f.submit(3) }
        cancelled.cancelAndJoin()
        assertTrue(cancelled.isCancelled)
        release.complete(Unit)
        val results = listOf(first.await(), second.await())
        val winner = results.filterIsInstance<RatingResult.Success<Rating>>().single().data
        assertEquals(1, results.count { it == failure(RatingError.AlreadyRated) })
        assertEquals(RatingResult.Success(winner), f.read())
        assertEquals(RatingSummary("commando", 1, winner.score.toDouble()), f.summary())
      }

  @Test
  fun participantReadsAllowAbsenceAndStrangersCannotDiscoverRatings() = runTest {
    val f = Fixture()
    for (rated in listOf(false, true)) {
      userId = "requester"
      val expected = if (rated) f.submit(4).data() else null
      for (participant in listOf("requester", "commando")) {
        userId = participant
        assertEquals(RatingResult.Success(expected), f.read())
      }
      userId = "stranger"
      // Bypass dependency authorization to exercise the rating repository's own read guard.
      f.orderResult = OrderResult.Success(order())
      assertEquals(failure(RatingError.PermissionDenied), f.read())
    }
  }

  @Test
  fun summariesCombineTripsWithoutRoundingAndIsolateCommandos() = runTest {
    val orders = (1..4).map { order().copy(id = "order-$it", tripId = "trip-$it") }
    val trips =
        (1..4).map { trip().copy(id = "trip-$it", ownerId = if (it == 4) "other" else "commando") }
    val f = Fixture(orders, trips)
    assertEquals(RatingSummary("commando", 0, null), f.summary())
    for ((index, score) in listOf(1, 4, 5, 2).withIndex()) {
      f.submit(score, id = "order-${index + 1}").data()
      if (index == 0) assertEquals(RatingSummary("commando", 1, 1.0), f.summary())
    }
    userId = "stranger"
    val summary = f.summary()
    assertEquals("commando", summary.commandoId)
    assertEquals(3, summary.ratingCount)
    assertEquals(10.0 / 3, summary.averageScore!!, 1e-12)
    assertEquals(RatingSummary("other", 1, 2.0), f.rating.getRatingSummary("other").data())
    assertEquals(RatingSummary("unrated", 0, null), f.rating.getRatingSummary("unrated").data())
  }

  @Test
  fun dependencyErrorsMapWithoutInsertion() = runTest {
    val orderErrors =
        listOf(
            OrderError.NotAuthenticated to RatingError.NotAuthenticated,
            OrderError.NotFound to RatingError.NotFound,
            OrderError.PermissionDenied to RatingError.PermissionDenied,
            OrderError.InvalidData to RatingError.InvalidData,
            OrderError.AlreadyDecided(order()) to RatingError.Unknown,
            OrderError.Unknown to RatingError.Unknown,
        )
    val tripErrors =
        listOf(
            TripError.NotFound to RatingError.NotFound,
            TripError.PermissionDenied to RatingError.PermissionDenied,
            TripError.InvalidData to RatingError.InvalidData,
            TripError.NetworkError to RatingError.Unknown,
            TripError.InvalidTransition to RatingError.Unknown,
            TripError.OrdersStillOpen to RatingError.Unknown,
            TripError.Unknown to RatingError.Unknown,
        )
    val f = Fixture()
    for ((error, expected) in orderErrors) {
      f.orderResult = OrderResult.Error(error)
      assertEquals(error.toString(), failure(expected), f.submit(3))
    }
    f.orderResult = null
    for ((error, expected) in tripErrors) {
      f.tripResult = TripResult.Error(error)
      assertEquals(error.toString(), failure(expected), f.submit(3))
    }
    f.tripResult = null
    assertEquals(RatingResult.Success(null), f.read())
  }

  @Test
  fun unexpectedLookupExceptionsReleaseStorageLock() = runTest {
    for (atTrip in listOf(false, true)) {
      val f = Fixture()
      val throwing: suspend () -> Unit = { error("dependency failed") }
      if (atTrip) f.beforeTrip = throwing else f.beforeOrder = throwing
      assertEquals(failure(RatingError.Unknown), f.submit(3))
      f.beforeOrder = {}
      f.beforeTrip = {}
      assertEquals(RatingResult.Success(null), f.read())
      assertTrue(f.submit(3) is RatingResult.Success)
    }
  }

  @Test
  fun sessionChangesAndCancellationPreventStaleResults() =
      runTest(timeout = 5.seconds) {
        for (atTrip in listOf(false, true)) {
          for (reading in listOf(false, true)) {
            for (ending in listOf("sign-out", "account-change", "cancellation")) {
              userId = "requester"
              val f = Fixture()
              val stored = if (reading) f.submit(4).data() else null
              val entered = CompletableDeferred<Unit>()
              val release = CompletableDeferred<Unit>()
              val suspendLookup: suspend () -> Unit = {
                entered.complete(Unit)
                release.await()
              }
              if (atTrip) f.beforeTrip = suspendLookup else f.beforeOrder = suspendLookup
              val pending = async { if (reading) f.read() else f.submit(3) }
              entered.await()
              assertFalse(pending.isCompleted)
              if (ending == "cancellation") {
                pending.cancelAndJoin()
                assertTrue(pending.isCancelled)
              } else {
                userId = if (ending == "sign-out") null else "another-account"
                release.complete(Unit)
                assertEquals(failure(RatingError.NotAuthenticated), pending.await())
              }
              userId = "requester"
              f.beforeOrder = {}
              f.beforeTrip = {}
              assertEquals(RatingResult.Success(stored), f.read())
              assertEquals(if (reading) 1 else 0, f.summary().ratingCount)
            }
          }
        }
      }

  private inner class Fixture(
      orders: List<Order> = listOf(order()),
      trips: List<Trip> = listOf(trip()),
      now: () -> Instant = { time },
  ) {
    var beforeOrder: suspend () -> Unit = {}
    var beforeTrip: suspend () -> Unit = {}
    var orderResult: OrderResult<Order>? = null
    var tripResult: TripResult<Trip>? = null
    private val tripSource = FakeTripRepository("commando", { time }, trips)
    private val orderSource = FakeOrderRepository({ userId }, orders, tripRepository = tripSource)
    private val orderRepository =
        object : OrderRepository by orderSource {
          override suspend fun getOrderById(orderId: String): OrderResult<Order> {
            beforeOrder()
            return orderResult ?: orderSource.getOrderById(orderId)
          }
        }
    private val tripRepository =
        object : TripRepository by tripSource {
          override suspend fun getTripById(tripId: String): TripResult<Trip> {
            beforeTrip()
            return tripResult ?: tripSource.getTripById(tripId)
          }
        }
    val rating = FakeRatingRepository({ userId }, orderRepository, tripRepository, now)

    suspend fun read() = rating.getRatingForOrder("order")

    suspend fun summary() = rating.getRatingSummary("commando").data()

    suspend fun submit(score: Int = 3, comment: String? = null, id: String = "order") =
        rating.submitRating(NewRating(id, score, comment))
  }

  private fun order() = Order("order", "trip", "requester", listOf(item), 0, DELIVERED, time)

  private fun trip() = Trip("trip", "commando", place, scheduledAt, place, PUBLISHED, time, time)

  private fun failure(error: RatingError) = RatingResult.Error(error)

  private fun <T> RatingResult<T>.data(): T = (this as RatingResult.Success<T>).data
}
