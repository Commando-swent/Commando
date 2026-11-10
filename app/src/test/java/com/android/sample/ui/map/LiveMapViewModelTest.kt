package com.android.sample.ui.map

// AI assistance: OpenAI Codex.
import androidx.lifecycle.ViewModelStore
import com.android.sample.MainDispatcherRule
import com.android.sample.data.location.DeviceLocationResult
import com.android.sample.data.repository.*
import com.android.sample.model.*
import com.android.sample.ui.trips.discoveryTrip
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Tests mode transitions and membership using hot feeds; a refresh is never needed. */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveMapViewModelTest {
  @get:Rule val main = MainDispatcherRule()
  private val store = ViewModelStore()
  private val now = Instant.parse("2026-11-10T10:00:00Z")
  private val identity = MutableStateFlow<String?>("viewer")
  private val a = trip("a", "alice")
  private val b = trip("b", "bob")
  private val map = MutableStateFlow<TripMapUiState>(TripMapUiState.Content(listOf(a, b)))
  private val point = MutableStateFlow<Location?>(Location("", Double.NaN, Double.NaN))
  private val orders = MutableStateFlow<OrderResult<List<Order>>>(OrderResult.Success(emptyList()))
  private val fixes =
      MutableStateFlow(mapOf("alice" to fix(), "bob" to fix().copy(latitude = 46.54)))
  private val trips = FakeTripRepository("viewer", initialTrips = listOf(a, b), now = { now })
  private val orderRepo =
      object : OrderRepository by FakeOrderRepository({ identity.value }) {
        override fun observeMyOrders() = orders
      }

  private fun model(
      clock: Clock = Clock.fixed(now, ZoneOffset.UTC),
      repository: TripRepository = trips,
      positions: CommandoPositionRepository =
          FakeCommandoPositionRepository(identity, Clock.fixed(now, ZoneOffset.UTC), fixes),
  ): LiveMapViewModel =
      LiveMapViewModel(
              map,
              orderRepo,
              repository,
              positions,
              point,
              { identity.value },
              identity,
              clock,
              { 0L },
          )
          .also {
            store.put("live", it)
            it.start()
          }

  private fun modelTest(block: suspend TestScope.() -> Unit) = runTest {
    try {
      block()
    } finally {
      store.clear()
      runCurrent()
    }
  }

  @Test
  fun discoveryExcludesDistantAndOwnTrips() = modelTest {
    val model = model()
    runCurrent()
    assertEquals(LiveMapMode.DISCOVERY, content(model).mode)
    assertEquals(listOf("alice"), content(model).commandos.map { it.ownerId })
    map.value = TripMapUiState.Content(listOf(a.copy(ownerId = "viewer"), b))
    runCurrent()
    assertTrue(content(model).commandos.isEmpty())
  }

  @Test
  fun multipleOrdersGroupByCommandoAndTerminalTransitionsRestoreDiscovery() = modelTest {
    val model = model()
    orders.value = OrderResult.Success(listOf(order("a1", a), order("a2", a), order("b1", b)))
    runCurrent()
    assertEquals(LiveMapMode.TRACKING, content(model).mode)
    assertEquals(2, content(model).commandos.size)
    assertEquals(2, content(model).commandos.first { it.ownerId == "alice" }.orders.size)
    point.value = Location("Elsewhere", 0.0, 0.0)
    runCurrent()
    assertEquals(2, content(model).commandos.size)
    point.value = null
    orders.value = OrderResult.Success(listOf(order("b1", b)))
    runCurrent()
    assertEquals(listOf("bob"), content(model).commandos.map { it.ownerId })
    orders.value = OrderResult.Success(listOf(order("b1", b).copy(status = OrderStatus.DELIVERED)))
    runCurrent()
    assertEquals(LiveMapMode.DISCOVERY, content(model).mode)
  }

  @Test
  fun missingTrackingPositionsKeepTheOrderReachable() = modelTest {
    orders.value = OrderResult.Success(listOf(order("a1", a)))
    fixes.value = emptyMap()
    val model = model()
    runCurrent()
    val person = content(model).commandos.single()
    assertNull(person.position)
    assertEquals("a1", person.orders.single().id)
  }

  @Test
  fun unknownOrdersNeverBecomeDiscoveryAndQuietLogoutReleasesAllFeeds() = modelTest {
    orders.value = OrderResult.Error(OrderError.Unknown)
    val model = model()
    runCurrent()
    assertEquals(LiveMapState.Unavailable, model.uiState.value)
    orders.value = OrderResult.Success(listOf(order("a1", a)))
    runCurrent()
    assertEquals(1, fixes.subscriptionCount.value)
    identity.value = null
    runCurrent()
    assertEquals(LiveMapState.Loading, model.uiState.value)
    assertEquals(0, fixes.subscriptionCount.value)
    assertEquals(0, orders.subscriptionCount.value)
    assertEquals(0, map.subscriptionCount.value)
    model.start()
    runCurrent()
    assertEquals(0, orders.subscriptionCount.value)
  }

  @Test
  fun allCanonicalStatusesChooseTheExpectedMode() = modelTest {
    val model = model()
    for (status in OrderStatus.entries) {
      orders.value = OrderResult.Success(listOf(order("a1", a).copy(status = status)))
      runCurrent()
      val active =
          status in setOf(OrderStatus.PENDING, OrderStatus.ACCEPTED, OrderStatus.READY_FOR_HANDOFF)
      assertEquals(
          status.name,
          if (active) LiveMapMode.TRACKING else LiveMapMode.DISCOVERY,
          content(model).mode,
      )
    }
  }

  @Test
  fun trackingReadsAClosedTripOutsideTheDiscoveryFeed() = modelTest {
    // A closed trip may no longer be present in the discovery feed.
    val closed = a.copy(status = TripStatus.IN_PROGRESS)
    val repo = FakeTripRepository("viewer", initialTrips = listOf(closed), now = { now })
    orders.value = OrderResult.Success(listOf(order("a1", a)))
    map.value = TripMapUiState.Empty
    val model = model(repository = repo)
    runCurrent()
    assertEquals(LiveMapMode.TRACKING, content(model).mode)
    assertEquals(TripStatus.IN_PROGRESS, content(model).commandos.single().trips.single().status)
  }

  @Test
  fun quietExpiryRemovesMarkersWithoutBackendEvents() = modelTest {
    var time = now
    val clock =
        object : Clock() {
          override fun getZone() = ZoneOffset.UTC

          override fun withZone(zone: java.time.ZoneId): Clock = this

          override fun instant() = time
        }
    val model = model(clock)
    runCurrent()
    assertEquals(1, content(model).commandos.size)
    time = now.plus(CommandoPosition.MAX_AGE)
    advanceTimeBy(LiveMapViewModel.LOCAL_REFRESH_MILLIS)
    runCurrent()
    assertTrue(content(model).commandos.isEmpty())
  }

  @Test
  fun unavailableDiscoveryDoesNotDisplayOldPeopleAndCanRecover() = modelTest {
    val model = model()
    for (state in
        listOf(
            TripMapUiState.Loading,
            TripMapUiState.Error(TripError.Unknown),
            TripMapUiState.SessionEnded,
        )) {
      map.value = state
      runCurrent()
      assertEquals(
          if (state == TripMapUiState.Loading) LiveMapState.Loading else LiveMapState.Unavailable,
          model.uiState.value,
      )
    }
    map.value = TripMapUiState.Content(listOf(a))
    runCurrent()
    assertEquals(listOf("alice"), content(model).commandos.map { it.ownerId })
  }

  @Test
  fun unavailableOrderTripDoesNotRevealUnrelatedDiscoveryPeople() = modelTest {
    orders.value = OrderResult.Success(listOf(order("a1", a).copy(tripId = "missing")))
    val model = model()
    runCurrent()
    assertEquals(LiveMapState.Unavailable, model.uiState.value)
  }

  @Test
  fun failedPositionObservationKeepsTrackingAndForeignOrdersAreRejected() = modelTest {
    val failing =
        object : CommandoPositionRepository by FakeCommandoPositionRepository(identity) {
          override fun observe(ownerId: String, orderId: String?): Flow<CommandoPosition?> = flow {
            throw IllegalStateException()
          }
        }
    orders.value = OrderResult.Success(listOf(order("a1", a)))
    val model = model(positions = failing)
    runCurrent()
    assertNull(content(model).commandos.single().position)
    orders.value = OrderResult.Success(listOf(order("a1", a).copy(requesterId = "other")))
    runCurrent()
    assertEquals(LiveMapState.Unavailable, model.uiState.value)
  }

  @Test
  fun signedOutEntryNeverSubscribes() = modelTest {
    identity.value = null
    val model = model()
    runCurrent()
    assertEquals(LiveMapState.Loading, model.uiState.value)
    assertEquals(0, orders.subscriptionCount.value)
  }

  @Test
  fun devicePositionChangesDiscoveryWithoutChangingTheDeliveryFilter() = modelTest {
    point.value = CAMPUS_DISCOVERY_CENTER
    val model = model()
    runCurrent()
    model.updateLocation(
        MapLocationUiState.Resolved(
            DeviceLocationResult.Available(DeviceLocation(46.54, 6.566, 100f, 0))
        )
    )
    runCurrent()
    assertEquals(listOf("bob"), content(model).commandos.map { it.ownerId })
    assertEquals(CAMPUS_DISCOVERY_CENTER, point.value)
    model.start()
    runCurrent()
    assertEquals(listOf("bob"), content(model).commandos.map { it.ownerId })
    model.stop()
    runCurrent()
    assertEquals(0, fixes.subscriptionCount.value)
  }

  private fun content(model: LiveMapViewModel) = model.uiState.value as LiveMapState.Content

  private fun fix() = CommandoPosition(46.521, 6.566, 100.0, now)

  private fun trip(id: String, uid: String) =
      discoveryTrip(
          id,
          TripLocations.stores.first(),
          TripLocations.handoffPoints.first(),
          now.plusSeconds(3600),
          uid,
          now,
      )

  private fun order(id: String, trip: Trip) =
      Order(
          id,
          trip.id,
          "viewer",
          listOf(OrderItem("milk", "Milk", 200, 1)),
          0,
          OrderStatus.PENDING,
          now,
      )
}
