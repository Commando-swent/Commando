package com.android.sample.data.repository

import com.android.sample.model.Location
import com.android.sample.model.NewTrip
import com.android.sample.model.Trip
import com.android.sample.model.TripStatus
import java.time.Instant
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeTripRepositoryTest {

  private val currentTime = Instant.parse("2026-10-04T12:00:00Z")
  private val store = Location(name = "Migros", latitude = 46.5191, longitude = 6.6332)
  private val handoff = Location(name = "EPFL", latitude = 46.5188, longitude = 6.5665)

  @Test
  fun publishTrip_withValidData_createsAndStoresTrip() = runSuspend {
    val repository = createRepository()
    val newTrip = newTripAt(currentTime.plusSeconds(3_600))

    val result = repository.publishTrip(newTrip)

    assertTrue(result is TripResult.Success)
    val trip = (result as TripResult.Success).data
    assertTrue(trip.id.isNotBlank())
    assertEquals("current-user", trip.ownerId)
    assertEquals(store, trip.store)
    assertEquals(newTrip.scheduledAt, trip.scheduledAt)
    assertEquals(handoff, trip.handoffLocation)
    assertEquals(TripStatus.PUBLISHED, trip.status)
    assertEquals(currentTime, trip.createdAt)
    assertEquals(currentTime, trip.updatedAt)
    assertEquals(TripResult.Success(trip), repository.getTripById(trip.id))
  }

  @Test
  fun publishTrip_twice_generatesDifferentIds() = runSuspend {
    val repository = createRepository()

    val first = repository.publishTrip(newTripAt(currentTime.plusSeconds(3_600))).successData()
    val second = repository.publishTrip(newTripAt(currentTime.plusSeconds(7_200))).successData()

    assertNotEquals(first.id, second.id)
  }

  @Test
  fun publishTrip_withBlankNameInEitherLocation_returnsInvalidData() = runSuspend {
    val repository = createRepository()
    val validTrip = newTripAt(currentTime.plusSeconds(3_600))
    val invalidTrips =
        listOf(
            validTrip.copy(store = store.copy(name = "   ")),
            validTrip.copy(handoffLocation = handoff.copy(name = "   ")),
        )

    invalidTrips.forEach { invalidTrip ->
      assertEquals(
          TripResult.Error(TripError.InvalidData),
          repository.publishTrip(invalidTrip),
      )
    }
  }

  @Test
  fun publishTrip_withOutOfRangeCoordinateInEitherLocation_returnsInvalidData() = runSuspend {
    val repository = createRepository()
    val invalidLocations =
        listOf(
            store.copy(latitude = -90.1),
            store.copy(latitude = 90.1),
            store.copy(longitude = -180.1),
            store.copy(longitude = 180.1),
        )

    invalidLocations.forEach { invalidLocation ->
      val validTrip = newTripAt(currentTime.plusSeconds(3_600))
      val invalidTrips =
          listOf(
              validTrip.copy(store = invalidLocation),
              validTrip.copy(handoffLocation = invalidLocation),
          )

      invalidTrips.forEach { invalidTrip ->
        assertEquals(
            TripResult.Error(TripError.InvalidData),
            repository.publishTrip(invalidTrip),
        )
      }
    }
  }

  @Test
  fun publishTrip_withBoundaryCoordinatesInEitherLocation_succeeds() = runSuspend {
    val repository = createRepository()
    val boundaryLocations =
        listOf(
            Location(name = "Southwest boundary", latitude = -90.0, longitude = -180.0),
            Location(name = "Northeast boundary", latitude = 90.0, longitude = 180.0),
        )

    boundaryLocations.forEach { boundaryLocation ->
      val validTrip = newTripAt(currentTime.plusSeconds(3_600))
      assertTrue(
          repository.publishTrip(validTrip.copy(store = boundaryLocation)) is TripResult.Success
      )
      assertTrue(
          repository.publishTrip(validTrip.copy(handoffLocation = boundaryLocation))
              is TripResult.Success
      )
    }
  }

  @Test
  fun publishTrip_withNonFiniteCoordinateInEitherLocation_returnsInvalidData() = runSuspend {
    val repository = createRepository()
    val nonFiniteValues = listOf(Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)

    nonFiniteValues.forEach { nonFiniteValue ->
      val validTrip = newTripAt(currentTime.plusSeconds(3_600))
      val invalidTrips =
          listOf(
              validTrip.copy(store = store.copy(latitude = nonFiniteValue)),
              validTrip.copy(store = store.copy(longitude = nonFiniteValue)),
              validTrip.copy(handoffLocation = handoff.copy(latitude = nonFiniteValue)),
              validTrip.copy(handoffLocation = handoff.copy(longitude = nonFiniteValue)),
          )

      invalidTrips.forEach { invalidTrip ->
        assertEquals(
            TripResult.Error(TripError.InvalidData),
            repository.publishTrip(invalidTrip),
        )
      }
    }
  }

  @Test
  fun publishTrip_atScheduleBoundary_acceptsOnlyTimesAfterNow() = runSuspend {
    val repository = createRepository()

    listOf(currentTime.minusSeconds(1), currentTime).forEach { scheduledAt ->
      assertEquals(
          TripResult.Error(TripError.InvalidData),
          repository.publishTrip(newTripAt(scheduledAt)),
      )
    }

    assertTrue(repository.publishTrip(newTripAt(currentTime.plusNanos(1))) is TripResult.Success)
  }

  @Test
  fun publishTrip_readsClockOnceAndUsesThatInstantForBothTimestamps() = runSuspend {
    var clockReads = 0
    val repository =
        FakeTripRepository(
            currentUserId = "current-user",
            now = {
              val instant = currentTime.plusSeconds(clockReads.toLong())
              clockReads += 1
              instant
            },
        )

    val trip = repository.publishTrip(newTripAt(currentTime.plusSeconds(3_600))).successData()

    assertEquals(1, clockReads)
    assertEquals(currentTime, trip.createdAt)
    assertEquals(trip.createdAt, trip.updatedAt)
  }

  @Test
  fun getTripById_withExistingId_returnsTrip() = runSuspend {
    val existingTrip = trip(id = "existing", scheduledAt = currentTime.plusSeconds(3_600))
    val repository = createRepository(initialTrips = listOf(existingTrip))

    assertEquals(TripResult.Success(existingTrip), repository.getTripById(existingTrip.id))
  }

  @Test
  fun getTripById_withMissingId_returnsNotFound() = runSuspend {
    val repository = createRepository()

    assertEquals(
        TripResult.Error(TripError.NotFound),
        repository.getTripById("missing"),
    )
  }

  @Test
  fun getUpcomingTrips_returnsAllFuturePublishedTripsSortedByScheduledTime() = runSuspend {
    val later = trip(id = "later", scheduledAt = currentTime.plusSeconds(7_200))
    val earlier = trip(id = "earlier", scheduledAt = currentTime.plusSeconds(3_600))
    val repository = createRepository(initialTrips = listOf(later, earlier))

    assertEquals(
        TripResult.Success(listOf(earlier, later)),
        repository.getUpcomingTrips(),
    )
  }

  @Test
  fun getUpcomingTrips_excludesPastAndNonPublishedTrips() = runSuspend {
    val upcoming = trip(id = "upcoming", scheduledAt = currentTime.plusSeconds(3_600))
    val excludedTrips =
        listOf(
            trip(id = "past", scheduledAt = currentTime.minusSeconds(1)),
            trip(id = "now", scheduledAt = currentTime),
            trip(
                id = "in-progress",
                scheduledAt = currentTime.plusSeconds(3_600),
                status = TripStatus.IN_PROGRESS,
            ),
            trip(
                id = "completed",
                scheduledAt = currentTime.plusSeconds(3_600),
                status = TripStatus.COMPLETED,
            ),
            trip(
                id = "cancelled",
                scheduledAt = currentTime.plusSeconds(3_600),
                status = TripStatus.CANCELLED,
            ),
        )
    val repository = createRepository(initialTrips = excludedTrips + upcoming)

    assertEquals(
        TripResult.Success(listOf(upcoming)),
        repository.getUpcomingTrips(),
    )
  }

  @Test
  fun getUpcomingTrips_withNoMatches_returnsEmptyList() = runSuspend {
    val repository =
        createRepository(
            initialTrips = listOf(trip(id = "past", scheduledAt = currentTime.minusSeconds(1)))
        )

    assertEquals(TripResult.Success(emptyList<Trip>()), repository.getUpcomingTrips())
  }

  @Test
  fun getUpcomingTrips_readsClockOncePerOperation() = runSuspend {
    var clockReads = 0
    val repository =
        FakeTripRepository(
            currentUserId = "current-user",
            now = {
              clockReads += 1
              currentTime
            },
            initialTrips =
                listOf(
                    trip(id = "first", scheduledAt = currentTime.plusSeconds(1)),
                    trip(id = "second", scheduledAt = currentTime.plusSeconds(2)),
                ),
        )

    repository.getUpcomingTrips()

    assertEquals(1, clockReads)
  }

  @Test
  fun getMyTrips_filtersOnlyByOwnerAndPreservesAllDatesAndStatuses() = runSuspend {
    val mine =
        TripStatus.entries.mapIndexed { index, status ->
          trip(
              id = "mine-$status",
              ownerId = "current-user",
              scheduledAt = currentTime.minusSeconds((index + 1).toLong()),
              status = status,
          )
        }
    val someoneElses = trip(id = "theirs", ownerId = "another-user")
    val repository = createRepository(initialTrips = listOf(someoneElses) + mine)

    assertEquals(TripResult.Success(mine), repository.getMyTrips())
  }

  @Test
  fun configuredError_isReturnedByEveryOperationWithoutMutationAndCanBeCleared() = runSuspend {
    val repository = createRepository()
    val errors =
        listOf(
            TripError.NotFound,
            TripError.NetworkError,
            TripError.PermissionDenied,
            TripError.InvalidData,
            TripError.Unknown,
        )

    errors.forEach { error ->
      repository.forcedError = error
      val expected = TripResult.Error(error)
      assertEquals(expected, repository.publishTrip(newTripAt(currentTime.plusSeconds(3_600))))
      assertEquals(expected, repository.getTripById("missing"))
      assertEquals(expected, repository.getUpcomingTrips())
      assertEquals(expected, repository.getMyTrips())
    }

    repository.forcedError = null
    assertEquals(TripResult.Success(emptyList<Trip>()), repository.getUpcomingTrips())
    assertTrue(
        repository.publishTrip(newTripAt(currentTime.plusSeconds(3_600))) is TripResult.Success
    )
  }

  private fun createRepository() =
      FakeTripRepository(
          currentUserId = "current-user",
          now = { currentTime },
      )

  private fun createRepository(initialTrips: List<Trip>) =
      FakeTripRepository(
          currentUserId = "current-user",
          now = { currentTime },
          initialTrips = initialTrips,
      )

  private fun newTripAt(scheduledAt: Instant) =
      NewTrip(store = store, scheduledAt = scheduledAt, handoffLocation = handoff)

  private fun trip(
      id: String,
      ownerId: String = "current-user",
      scheduledAt: Instant = currentTime.plusSeconds(3_600),
      status: TripStatus = TripStatus.PUBLISHED,
  ) =
      Trip(
          id = id,
          ownerId = ownerId,
          store = store,
          scheduledAt = scheduledAt,
          handoffLocation = handoff,
          status = status,
          createdAt = currentTime.minusSeconds(3_600),
          updatedAt = currentTime.minusSeconds(3_600),
      )

  private fun <T> TripResult<T>.successData(): T =
      when (this) {
        is TripResult.Success -> data
        is TripResult.Error -> error("Expected success, got $error")
      }

  private fun runSuspend(block: suspend () -> Unit) {
    var outcome: Result<Unit>? = null
    block.startCoroutine(
        object : Continuation<Unit> {
          override val context = EmptyCoroutineContext

          override fun resumeWith(result: Result<Unit>) {
            outcome = result
          }
        }
    )
    checkNotNull(outcome).getOrThrow()
  }
}
