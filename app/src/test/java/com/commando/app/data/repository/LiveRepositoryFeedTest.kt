package com.commando.app.data.repository

// AI assistance: OpenAI Codex.
import com.commando.app.model.*
import com.commando.app.model.authentication.AuthUser
import com.commando.app.model.authentication.FakeAuthRepository
import com.commando.app.ui.trips.discoveryTrip
import com.google.firebase.Timestamp
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
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
  fun quietServerTripFeedDropsDeparturesAndCancelsExpiryForUnconfirmedSnapshots() = runTest {
    val source = mockk<TripFirestoreDataSource>()
    val snapshots =
        MutableStateFlow<List<TripFirestoreDocument>?>(
            listOf(tripDocument("first", 1000), tripDocument("second", 2000))
        )
    every { source.observeUpcomingTrips(any(), any(), "viewer") } returns snapshots
    val repository =
        TripRepositoryFirestore(
            source,
            FakeAuthRepository(AuthUser("viewer")),
            { now.plusMillis(testScheduler.currentTime) },
        )
    val seen = mutableListOf<TripResult<List<Trip>>?>()
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
      repository.observeUpcomingTrips().collect { seen += it }
    }
    runCurrent()
    assertEquals(listOf("first", "second"), (seen.last() as TripResult.Success).data.map(Trip::id))
    advanceTimeBy(1000)
    runCurrent()
    assertEquals(listOf("second"), (seen.last() as TripResult.Success).data.map(Trip::id))
    snapshots.value = null
    runCurrent()
    val count = seen.size
    advanceTimeBy(1000)
    runCurrent()
    assertNull(seen.last())
    assertEquals(count, seen.size)
    // A later confirmed snapshot must be filtered against the current clock as well.
    snapshots.value = listOf(tripDocument("second", 2000), tripDocument("third", 3000))
    runCurrent()
    assertEquals(listOf("third"), (seen.last() as TripResult.Success).data.map(Trip::id))
    advanceTimeBy(1000)
    runCurrent()
    assertEquals(TripResult.Success(emptyList<Trip>()), seen.last())
  }

  @Test
  fun quietFakeTripFeedDropsADepartureWithoutAnyRepositoryMutation() = runTest {
    val repository =
        FakeTripRepository("owner", now = { now.plusMillis(testScheduler.currentTime) })
    repository.publishTrip(
        NewTrip(
            TripLocations.stores.first(),
            now.plusSeconds(1),
            TripLocations.handoffPoints.first(),
        )
    )
    val seen = mutableListOf<TripResult<List<Trip>>>()
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
      repository.observeUpcomingTrips().collect { seen += it }
    }
    runCurrent()
    assertEquals(1, (seen.last() as TripResult.Success).data.size)
    advanceTimeBy(1000)
    runCurrent()
    assertTrue((seen.last() as TripResult.Success).data.isEmpty())
  }

  private fun tripDocument(id: String, departureMillis: Long): TripFirestoreDocument {
    val location = mapOf("name" to "EPFL", "latitude" to 46.52, "longitude" to 6.56)
    return FirestoreDocument(
        id,
        mapOf(
            "ownerId" to "owner",
            "store" to location,
            "handoffLocation" to location,
            "status" to "PUBLISHED",
            "scheduledAt" to Timestamp(now.plusMillis(departureMillis)),
            "createdAt" to Timestamp(now),
            "updatedAt" to Timestamp(now),
        ),
    )
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
  fun serverFeedFailuresRemainErrorsAndNeverBecomeConfirmedEmptyLists() = runTest {
    for (error in listOf(IOException("offline"), IllegalStateException("unexpected"))) {
      val tripSource = mockk<TripFirestoreDataSource>()
      every { tripSource.observeUpcomingTrips(any(), any(), "viewer") } returns flow { throw error }
      val trips =
          TripRepositoryFirestore(tripSource, FakeAuthRepository(AuthUser("viewer")), { now })
      val expectedTrip = if (error is IOException) TripError.NetworkError else TripError.Unknown
      assertEquals(listOf(TripResult.Error(expectedTrip)), trips.observeUpcomingTrips().toList())
      val orderSource = mockk<OrderFirestoreDataSource>()
      every { orderSource.observeMine("viewer") } returns flow { throw error }
      val orders = OrderRepositoryFirestore(orderSource, { "viewer" })
      val expectedOrder = OrderError.Unknown
      assertEquals(listOf(OrderResult.Error(expectedOrder)), orders.observeMyOrders().toList())
    }
  }

  @Test
  fun liveFeedsPreserveCancellationAndFatalFailures() = runTest {
    for (error in listOf(CancellationException("cancelled"), AssertionError("fatal"))) {
      val tripSource = mockk<TripFirestoreDataSource>()
      every { tripSource.observeUpcomingTrips(any(), any(), "viewer") } returns flow { throw error }
      val trips =
          TripRepositoryFirestore(tripSource, FakeAuthRepository(AuthUser("viewer")), { now })
      val tripFailure = runCatching { trips.observeUpcomingTrips().toList() }.exceptionOrNull()
      assertEquals(error.javaClass, tripFailure?.javaClass)
      assertEquals(error.message, tripFailure?.message)
      val orderSource = mockk<OrderFirestoreDataSource>()
      every { orderSource.observeMine("viewer") } returns flow { throw error }
      val orders = OrderRepositoryFirestore(orderSource, { "viewer" })
      val orderFailure = runCatching { orders.observeMyOrders().toList() }.exceptionOrNull()
      assertEquals(error.javaClass, orderFailure?.javaClass)
      assertEquals(error.message, orderFailure?.message)
    }
  }

  @Test
  fun expiryRejectsClosedTripsAndReplacementSnapshotsCancelOldDeadlines() = runTest {
    val trip =
        discoveryTrip(
            "trip",
            TripLocations.stores.first(),
            TripLocations.handoffPoints.first(),
            now.plusMillis(1000),
            "owner",
            now,
        )
    val snapshots = MutableStateFlow<TripResult<List<Trip>>?>(TripResult.Success(listOf(trip)))
    val seen = mutableListOf<TripResult<List<Trip>>?>()
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
      snapshots
          .expireUpcomingTrips { now.plusMillis(testScheduler.currentTime) }
          .collect { seen += it }
    }
    runCurrent()
    snapshots.value =
        TripResult.Success(
            listOf(
                trip.copy(status = TripStatus.IN_PROGRESS),
                trip.copy(id = "later", scheduledAt = now.plusMillis(2000)),
            )
        )
    runCurrent()
    val count = seen.size
    advanceTimeBy(1000)
    runCurrent()
    assertEquals(count, seen.size)
    assertEquals(listOf("later"), (seen.last() as TripResult.Success).data.map(Trip::id))
    advanceTimeBy(1000)
    runCurrent()
    assertEquals(TripResult.Success(emptyList<Trip>()), seen.last())
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
