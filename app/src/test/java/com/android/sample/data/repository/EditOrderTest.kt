package com.android.sample.data.repository

// AI assistance: OpenAI Codex (#232).
import com.android.sample.model.*
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class EditOrderTest {
  private val now = Instant.parse("2026-11-08T10:00:00Z")
  private val location = Location("Migros", 46.5, 6.5)
  private val trip =
      Trip(
          "trip",
          "owner",
          location,
          now.plusSeconds(3600),
          location,
          TripStatus.PUBLISHED,
          now,
          now,
      )
  private val original =
      Order(
          "order",
          "trip",
          "requester",
          listOf(OrderItem("milk", "Milk", 220, 1)),
          100,
          OrderStatus.PENDING,
          now,
      )
  private val draft = OrderDraft("trip", listOf(OrderItem("bread", "Bread", 300, 2)), 150)
  private var uid: String? = "requester"

  private fun repository(order: Order = original, associated: Trip = trip) =
      FakeOrderRepository(
          { uid },
          listOf(order),
          { now },
          tripRepository = FakeTripRepository("owner", initialTrips = listOf(associated)),
      )

  @Test
  fun editPreservesIdentityAndRepeatsWithoutAnotherOrder() = runTest {
    val repo = repository()
    val updated = original.copy(items = draft.items, commissionCents = 150, revision = 1)
    repeat(2) { assertEquals(OrderResult.Success(updated), repo.editOrder("order", draft)) }
    assertEquals(750L, updated.totalCents)
    assertEquals(OrderResult.Success(listOf(updated)), repo.getMyOrders())
    uid = "owner"
    assertEquals(OrderResult.Success(listOf(updated)), repo.getOrdersForTrip("trip"))
  }

  @Test
  fun ownerMustReviewTheEditedRevisionBeforeEitherDecision() = runTest {
    for (decision in OrderDecision.entries) {
      uid = "requester"
      val repo = repository()
      val edited = (repo.editOrder("order", draft) as OrderResult.Success).data
      assertEquals(
          OrderResult.Error(OrderError.AlreadyDecided(edited)),
          repo.editOrder("order", draft.copy(commissionCents = 999)),
      )
      uid = "owner"
      assertEquals(
          OrderResult.Error(OrderError.AlreadyDecided(edited)),
          repo.decideOrder("order", decision, 0),
      )
      assertEquals(OrderResult.Success(edited), repo.getOrderById("order"))
      assertTrue(repo.decideOrder("order", decision, edited.revision) is OrderResult.Success)
    }
  }

  @Test
  fun unauthorizedAndInvalidEditsLeaveStoredOrderUntouched() = runTest {
    val repo = repository()
    for (user in listOf(null, "outsider", "owner")) {
      uid = user
      assertEquals(
          OrderResult.Error(
              if (user == null) OrderError.NotAuthenticated else OrderError.PermissionDenied
          ),
          repo.editOrder("order", draft),
      )
    }
    uid = "requester"
    for (invalid in
        listOf(
            draft.copy(tripId = "other"),
            draft.copy(items = emptyList()),
            draft.copy(commissionCents = -1),
            draft.copy(items = draft.items + draft.items),
        )) {
      assertEquals(OrderResult.Error(OrderError.InvalidData), repo.editOrder("order", invalid))
    }
    assertEquals(OrderResult.Error(OrderError.NotFound), repo.editOrder("missing", draft))
    assertEquals(OrderResult.Error(OrderError.InvalidData), repo.editOrder("a/b", draft))
    assertEquals(OrderResult.Success(original), repo.getOrderById("order"))
  }

  @Test
  fun finalOrdersAndClosedOrPastTripsCannotBeEdited() = runTest {
    for (status in OrderStatus.entries.filter { it != OrderStatus.PENDING }) {
      val stored = original.copy(status = status)
      assertEquals(
          OrderResult.Error(OrderError.AlreadyDecided(stored)),
          repository(stored).editOrder("order", draft),
      )
    }
    for (closed in
        listOf(
            trip.copy(status = TripStatus.IN_PROGRESS),
            trip.copy(status = TripStatus.COMPLETED),
            trip.copy(status = TripStatus.CANCELLED),
            trip.copy(scheduledAt = now),
        )) {
      assertEquals(
          OrderResult.Error(OrderError.InvalidData),
          repository(associated = closed).editOrder("order", draft),
      )
    }
  }

  @Test
  fun unavailableOrInconsistentTripLookupNeverChangesTheOrder() = runTest {
    val absent = FakeOrderRepository({ uid }, listOf(original), { now })
    assertEquals(OrderResult.Error(OrderError.InvalidData), absent.editOrder("order", draft))
    for (lookup in
        listOf<TripResult<Trip>>(
            TripResult.Error(TripError.NotFound),
            TripResult.Error(TripError.PermissionDenied),
            TripResult.Success(trip.copy(id = "other")),
            TripResult.Success(trip.copy(ownerId = "requester")),
        )) {
      val trips =
          object : TripRepository by FakeTripRepository("owner") {
            override suspend fun getTripById(tripId: String) = lookup
          }
      val repo = FakeOrderRepository({ uid }, listOf(original), { now }, tripRepository = trips)
      assertEquals(OrderResult.Error(OrderError.InvalidData), repo.editOrder("order", draft))
      assertEquals(OrderResult.Success(original), repo.getOrderById("order"))
    }
  }

  @Test
  fun tripReadFailuresPreserveStateAndCancellationIsRethrown() = runTest {
    for (changeAccount in listOf(false, true)) {
      uid = "requester"
      val trips =
          object : TripRepository by FakeTripRepository("owner") {
            override suspend fun getTripById(tripId: String): TripResult<Trip> {
              if (changeAccount) uid = "other"
              throw IllegalStateException("Trip unavailable")
            }
          }
      val repo = FakeOrderRepository({ uid }, listOf(original), { now }, tripRepository = trips)
      assertEquals(
          OrderResult.Error(if (changeAccount) OrderError.NotAuthenticated else OrderError.Unknown),
          repo.editOrder("order", draft),
      )
      uid = "requester"
      assertEquals(OrderResult.Success(original), repo.getOrderById("order"))
    }
    val cancelled = kotlinx.coroutines.CancellationException("Cancelled trip read")
    val trips =
        object : TripRepository by FakeTripRepository("owner") {
          override suspend fun getTripById(tripId: String): TripResult<Trip> = throw cancelled
        }
    val repo = FakeOrderRepository({ uid }, listOf(original), { now }, tripRepository = trips)
    assertSame(cancelled, runCatching { repo.editOrder("order", draft) }.exceptionOrNull())
    assertEquals(OrderResult.Success(original), repo.getOrderById("order"))
  }

  @Test
  fun changedSessionDuringTripReadCannotCommit() = runTest {
    val gate = CompletableDeferred<Unit>()
    val trips =
        object : TripRepository by FakeTripRepository("owner") {
          override suspend fun getTripById(tripId: String): TripResult<Trip> {
            gate.await()
            return TripResult.Success(trip)
          }
        }
    val repo = FakeOrderRepository({ uid }, listOf(original), { now }, tripRepository = trips)
    val result = async { repo.editOrder("order", draft) }
    runCurrent()
    uid = "other"
    gate.complete(Unit)
    assertEquals(OrderResult.Error(OrderError.NotAuthenticated), result.await())
    uid = "requester"
    assertEquals(OrderResult.Success(original), repo.getOrderById("order"))
  }
}
