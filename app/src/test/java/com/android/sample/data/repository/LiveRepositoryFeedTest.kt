package com.android.sample.data.repository

// AI assistance: OpenAI Codex.
import com.android.sample.model.authentication.AuthUser
import com.android.sample.model.authentication.FakeAuthRepository
import com.android.sample.ui.trips.discoveryTrip
import com.commando.app.model.*
import com.google.firebase.Timestamp
import io.mockk.every
import io.mockk.mockk
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

/** Mutating the shared fake repositories updates observers without calling refresh. */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveRepositoryFeedTest {
  private val now = Instant.parse("2026-11-10T10:00:00Z")

  @Test
  fun confirmedOrderFeedRejectsInvalidForeignAndPreviousSessionSnapshots() = runTest {
    var uid: String? = "viewer"
    val updates = MutableStateFlow<List<OrderFirestoreDocument>?>(null)
    val source = mockk<OrderFirestoreDataSource>()
    every { source.observeMine("viewer") } returns updates
    val repository = OrderRepositoryFirestore(source, { uid })
    val seen = mutableListOf<OrderResult<List<Order>>?>()
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
      repository.observeMyOrders().collect { seen += it }
    }
    assertNull(seen.last())
    updates.value = listOf(FirestoreDocument("invalid", emptyMap()))
    runCurrent()
    assertEquals(OrderResult.Error(OrderError.InvalidData), seen.last())
    val draft = OrderDraft("trip", listOf(OrderItem("milk", "Milk", 200, 1)))
    val document =
        FirestoreDocument(
            "owned",
            draft.firestoreFields("viewer") + ("createdAt" to Timestamp(now)),
        )
    updates.value = listOf(document.copy(data = document.data + ("requesterId" to "other")))
    runCurrent()
    assertEquals(OrderResult.Error(OrderError.PermissionDenied), seen.last())
    updates.value = listOf(document)
    runCurrent()
    assertEquals("owned", (seen.last() as OrderResult.Success).data.single().id)
    uid = "other"
    updates.value = emptyList()
    runCurrent()
    assertEquals(OrderResult.Error(OrderError.NotAuthenticated), seen.last())
  }

  @Test
  fun tripFeedRejectsMalformedDataAndLateSnapshotsAfterSignOut() = runTest {
    val auth = FakeAuthRepository(AuthUser("viewer"))
    val updates = MutableStateFlow<List<TripFirestoreDocument>?>(null)
    val source = mockk<TripFirestoreDataSource>()
    every { source.observeUpcomingTrips(any(), any(), "viewer") } returns updates
    val repository = TripRepositoryFirestore(source, auth, { now })
    val seen = mutableListOf<TripResult<List<Trip>>?>()
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
      repository.observeUpcomingTrips().collect { seen += it }
    }
    assertNull(seen.last())
    updates.value = listOf(FirestoreDocument("invalid", emptyMap()))
    runCurrent()
    assertEquals(TripResult.Error(TripError.InvalidData), seen.last())
    updates.value = emptyList()
    runCurrent()
    assertEquals(TripResult.Success(emptyList<Trip>()), seen.last())
    auth.signOut()
    updates.value = listOf(FirestoreDocument("late", emptyMap()))
    runCurrent()
    assertEquals(TripResult.Error(TripError.PermissionDenied), seen.last())
  }

  @Test
  fun fakeOrderFeedCannotSwitchToAnotherAccountOnItsNextUpdate() = runTest {
    var uid: String? = "viewer"
    val repository = FakeOrderRepository({ uid })
    val seen = mutableListOf<OrderResult<List<Order>>>()
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
      repository.observeMyOrders().collect { seen += it }
    }
    uid = "other"
    repository.forcedError = OrderError.Unknown
    runCurrent()
    assertEquals(OrderResult.Error(OrderError.NotAuthenticated), seen.last())
  }

  @Test
  fun unsupportedLiveAdaptersFailClosedInsteadOfReturningOneShotEmptyLists() = runTest {
    val trips =
        object : TripRepository by FakeTripRepository("owner") {
          override fun observeUpcomingTrips() = super<TripRepository>.observeUpcomingTrips()
        }
    val orders =
        object : OrderRepository by FakeOrderRepository({ "owner" }) {
          override fun observeMyOrders() = super<OrderRepository>.observeMyOrders()
        }
    val positions =
        object :
            CommandoPositionRepository by FakeCommandoPositionRepository(
                MutableStateFlow("owner")
            ) {
          override fun observeDiscovery(ownerId: String, tripId: String) =
              super<CommandoPositionRepository>.observeDiscovery(ownerId, tripId)

          override suspend fun publishForDiscovery(tripId: String, position: CommandoPosition) =
              super<CommandoPositionRepository>.publishForDiscovery(tripId, position)
        }
    assertTrue(
        runCatching { trips.observeUpcomingTrips().toList() }.exceptionOrNull()
            is IllegalStateException
    )
    assertTrue(
        runCatching { orders.observeMyOrders().toList() }.exceptionOrNull() is IllegalStateException
    )
    assertTrue(
        runCatching { positions.observeDiscovery("owner", "trip").toList() }.exceptionOrNull()
            is IllegalStateException
    )
    assertTrue(
        runCatching { positions.publishForDiscovery("trip", CommandoPosition(0.0, 0.0, 1.0, now)) }
            .exceptionOrNull() is IllegalStateException
    )
    val tripSource =
        object : TripFirestoreDataSource by mockk() {
          override fun observeUpcomingTrips(status: String, scheduledAfter: Instant, uid: String) =
              super<TripFirestoreDataSource>.observeUpcomingTrips(status, scheduledAfter, uid)
        }
    val orderSource =
        object : OrderFirestoreDataSource by mockk() {
          override fun observeMine(uid: String) = super<OrderFirestoreDataSource>.observeMine(uid)
        }
    assertEquals(
        listOf(TripResult.Error(TripError.Unknown)),
        TripRepositoryFirestore(tripSource, FakeAuthRepository(AuthUser("owner")), { now })
            .observeUpcomingTrips()
            .toList(),
    )
    assertEquals(
        listOf(OrderResult.Error(OrderError.Unknown)),
        OrderRepositoryFirestore(orderSource, { "owner" }).observeMyOrders().toList(),
    )
  }

  @Test
  fun unauthenticatedServerFeedsNeverReturnAConfirmedEmptyList() = runTest {
    val trips =
        TripRepositoryFirestore(mockk<TripFirestoreDataSource>(), FakeAuthRepository(), { now })
    val orders = OrderRepositoryFirestore(mockk<OrderFirestoreDataSource>(), { null })
    assertEquals(
        listOf(TripResult.Error(TripError.PermissionDenied)),
        trips.observeUpcomingTrips().toList(),
    )
    assertEquals(
        listOf(OrderResult.Error(OrderError.NotAuthenticated)),
        orders.observeMyOrders().toList(),
    )
  }

  @Test
  fun discoveryIsExplicitAndOrdinaryPublicationRevokesIt() = runTest {
    val repo =
        FakeCommandoPositionRepository(MutableStateFlow("owner"), Clock.fixed(now, ZoneOffset.UTC))
    val fix = CommandoPosition(46.52, 6.56, 10.0, now)
    repo.publish("trip", fix)
    assertNull(repo.observeDiscovery("owner", "trip").first())
    repo.publishForDiscovery("trip", fix)
    assertEquals(fix, repo.observeDiscovery("owner", "trip").first())
    assertNull(repo.observeDiscovery("owner", "other-trip").first())
    for (invalid in listOf(fix.copy(latitude = 91.0), fix.copy(capturedAt = now.minusMillis(1)))) {
      assertTrue(runCatching { repo.publish("trip", invalid) }.isFailure)
      assertEquals(fix, repo.observeDiscovery("owner", "trip").first())
    }
    repo.publish("trip", fix)
    assertNull(repo.observeDiscovery("owner", "trip").first())
  }

  @Test
  fun publishingAndClosingUpdateTheSameUpcomingFeed() = runTest {
    val trips = FakeTripRepository("owner", now = { now })
    val updates = mutableListOf<TripResult<List<Trip>>>()
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
      trips.observeUpcomingTrips().collect { updates += it }
    }
    val created =
        (trips.publishTrip(
                NewTrip(
                    TripLocations.stores.first(),
                    now.plusSeconds(3600),
                    TripLocations.handoffPoints.first(),
                )
            ) as TripResult.Success)
            .data
    runCurrent()
    assertEquals(created.id, (updates.last() as TripResult.Success).data.single().id)
    trips.transitionTrip(created.id, TripTransition.CLOSE)
    runCurrent()
    assertTrue((updates.last() as TripResult.Success).data.isEmpty())
  }

  @Test
  fun placementEditAndCancellationUpdateTheRequesterOrderFeed() = runTest {
    val trip =
        discoveryTrip(
            "trip",
            TripLocations.stores.first(),
            TripLocations.handoffPoints.first(),
            now.plusSeconds(3600),
            "commando",
            now,
        )
    val trips = FakeTripRepository("requester", now = { now }, initialTrips = listOf(trip))
    val orders = FakeOrderRepository({ "requester" }, now = { now }, tripRepository = trips)
    val updates = mutableListOf<OrderResult<List<Order>>>()
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
      orders.observeMyOrders().collect { updates += it }
    }
    val draft = OrderDraft(trip.id, listOf(OrderItem("milk", "Milk", 200, 1)))
    val placed = (orders.createOrder(draft) as OrderResult.Success).data
    runCurrent()
    assertEquals(placed.id, (updates.last() as OrderResult.Success).data.single().id)
    orders.editOrder(
        placed.id,
        draft.copy(items = listOf(draft.items.single().copy(quantity = 2))),
        0,
    )
    runCurrent()
    assertEquals(2, (updates.last() as OrderResult.Success).data.single().items.single().quantity)
    orders.cancelOrder(placed.id)
    runCurrent()
    assertEquals(
        OrderStatus.CANCELLED,
        (updates.last() as OrderResult.Success).data.single().status,
    )
  }
}
