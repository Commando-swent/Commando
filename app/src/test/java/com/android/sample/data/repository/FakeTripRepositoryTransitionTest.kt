package com.android.sample.data.repository

// AI assistance: Claude Code.

import com.android.sample.data.repository.TripError.*
import com.android.sample.model.*
import com.android.sample.model.OrderStatus.ACCEPTED
import com.android.sample.model.OrderStatus.DELIVERED
import com.android.sample.model.OrderStatus.PENDING
import com.android.sample.model.OrderStatus.READY_FOR_HANDOFF
import com.android.sample.model.OrderStatus.REJECTED
import com.android.sample.model.TripStatus.CANCELLED
import com.android.sample.model.TripStatus.COMPLETED
import com.android.sample.model.TripStatus.IN_PROGRESS
import com.android.sample.model.TripStatus.PUBLISHED
import com.android.sample.model.TripTransition.*
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeTripRepositoryTransitionTest {
  private class Case(
      val transition: TripTransition,
      val status: TripStatus,
      val orders: List<OrderStatus> = emptyList(),
      val error: TripError? = null,
  )

  @Test
  fun transitions_succeedRepeatOrFailAccordingToStatusAndOrders() = runTest {
    val open = listOf(PENDING, ACCEPTED, READY_FOR_HANDOFF)
    val settled = listOf(REJECTED, OrderStatus.CANCELLED, DELIVERED)
    val cases =
        listOf(
            Case(CLOSE, PUBLISHED),
            Case(COMPLETE, IN_PROGRESS),
            Case(COMPLETE, IN_PROGRESS, settled),
            Case(CLOSE, IN_PROGRESS, open), // repeat
            Case(COMPLETE, COMPLETED, open), // repeat: orders are not consulted
            Case(COMPLETE, PUBLISHED, error = InvalidTransition),
            Case(CLOSE, COMPLETED, error = InvalidTransition),
            Case(CLOSE, CANCELLED, error = InvalidTransition),
            Case(COMPLETE, CANCELLED, error = InvalidTransition),
        ) + open.map { Case(COMPLETE, IN_PROGRESS, settled + it, OrdersStillOpen) }
    for (case in cases) {
      val after =
          if (case.error != null || case.status == case.transition.target) trip(case.status)
          else trip(case.transition.target, updatedAt = NOW)
      val expected = case.error?.let { TripResult.Error(it) } ?: TripResult.Success(after)
      val repository = repository(trip(case.status), case.orders)

      assertEquals(case.toString(), expected, repository.transitionTrip(ID, case.transition))
      assertEquals(after, repository.getTripById(ID).successData())
    }
  }

  @Test
  fun close_leavesDiscoveryButStaysInMyTrips() = runTest {
    val repository = repository(trip(PUBLISHED))
    assertEquals(1, repository.getUpcomingTrips().successData().size)
    val closed = repository.transitionTrip(ID, CLOSE).successData()
    assertTrue(repository.getUpcomingTrips().successData().isEmpty())
    assertEquals(listOf(closed), repository.getMyTrips().successData())
  }

  @Test
  fun otherFailures_areReportedAndLeaveTheTripUntouched() = runTest {
    val repository = repository(trip(PUBLISHED))
    val errors =
        listOf("", "a/b", ".", "..").associateWith { InvalidData } + ("missing" to NotFound)
    for ((id, error) in errors) {
      assertEquals(id, TripResult.Error(error), repository.transitionTrip(id, CLOSE))
    }
    val requester = repository(trip(PUBLISHED), user = "requester")
    assertEquals(TripResult.Error(PermissionDenied), requester.transitionTrip(ID, CLOSE))
    assertEquals(trip(PUBLISHED), requester.getTripById(ID).successData())
    assertEquals(trip(PUBLISHED), repository.getTripById(ID).successData())
    repository.forcedError = NetworkError
    assertEquals(TripResult.Error(NetworkError), repository.transitionTrip(ID, CLOSE))
  }

  private fun repository(
      trip: Trip,
      orders: List<OrderStatus> = emptyList(),
      user: String = "owner",
  ) = FakeTripRepository(user, { NOW }, listOf(trip), { orders })

  private fun trip(status: TripStatus, updatedAt: Instant = EARLIER) =
      Trip(ID, "owner", PLACE, LATER, PLACE, status, EARLIER, updatedAt)

  private companion object {
    const val ID = "trip-1"
    const val TRIP_LEAD_SECONDS = 3_600L
    val NOW: Instant = Instant.parse("2026-10-04T12:00:00Z")
    val EARLIER: Instant = NOW.minusSeconds(TRIP_LEAD_SECONDS)
    val LATER: Instant = NOW.plusSeconds(TRIP_LEAD_SECONDS)
    val PLACE = Location("Migros", 46.5191, 6.6332)
  }
}
