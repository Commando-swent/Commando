package com.commando.app.ui.map

// AI assistance: OpenAI Codex.
import androidx.lifecycle.ViewModelStore
import com.android.sample.MainDispatcherRule
import com.commando.app.data.repository.*
import com.commando.app.data.repository.CommandoPositionRepository
import com.commando.app.data.repository.FakeCommandoPositionRepository
import com.commando.app.data.repository.FakeOrderRepository
import com.commando.app.data.repository.FakeTripRepository
import com.commando.app.data.repository.OrderError
import com.commando.app.data.repository.OrderRepository
import com.commando.app.data.repository.OrderResult
import com.commando.app.data.repository.TripError
import com.commando.app.data.repository.TripRepository
import com.commando.app.data.repository.TripResult
import com.commando.app.model.*
import com.commando.app.ui.trips.discoveryTrip
import java.time.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

/** Reactive mode changes, privacy, filtering, expiration and session-bound subscription cleanup. */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveMapViewModelTest {
  @get:Rule val main = MainDispatcherRule()
  private val store = ViewModelStore()
  private val now = Instant.parse("2026-11-17T10:00:00Z")
  private val identity = MutableStateFlow<String?>("viewer")
  private val a =
      discoveryTrip(
          "a",
          TripLocations.stores.first(),
          CAMPUS_DISCOVERY_CENTER,
          now.plusSeconds(3600),
          "alice",
          now,
      )
  private val b = a.copy(id = "b", ownerId = "bob", status = TripStatus.COMPLETED)
  private val feed = MutableStateFlow<TripResult<List<Trip>>?>(TripResult.Success(listOf(a, b)))
  private val orders = MutableStateFlow<OrderResult<List<Order>>?>(OrderResult.Success(emptyList()))
  private val point = MutableStateFlow<Location?>(null)
  private val criteria = MutableStateFlow(TripSearchCriteria())
  private val fixes =
      MutableStateFlow<CommandoPosition?>(CommandoPosition(46.521, 6.566, 20.0, now))
  private var failPosition = false
  private var failLookup = false
  private val observed = mutableListOf<String?>()
  private val repository =
      object : TripRepository by FakeTripRepository("viewer", initialTrips = listOf(a, b)) {
        override fun observeUpcomingTrips() = feed

        override suspend fun getTripById(tripId: String) =
            if (failLookup) TripResult.Error(TripError.NotFound)
            else
                TripResult.Success(
                    when (tripId) {
                      "a" -> a
                      "c" -> a.copy(id = "c")
                      else -> b
                    }
                )
      }
  private val positions =
      object : CommandoPositionRepository by FakeCommandoPositionRepository(identity) {
        override fun observe(ownerId: String, orderId: String?): Flow<CommandoPosition?> = flow {
          observed += orderId
          if (failPosition || orderId == "wrong") error("Unavailable")
          emitAll(fixes)
        }

        override fun observeDiscovery(ownerId: String, tripId: String) = observe(ownerId, null)
      }

  private fun model(
      clock: Clock = Clock.fixed(now, ZoneOffset.UTC),
      tripRepo: TripRepository = repository,
      positionRepo: CommandoPositionRepository = positions,
  ): LiveMapViewModel =
      LiveMapViewModel(
              object : OrderRepository by FakeOrderRepository({ identity.value }) {
                override fun observeMyOrders() = orders
              },
              tripRepo,
              positionRepo,
              { identity.value },
              identity,
              point,
              criteria,
              clock,
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

  private fun content(model: LiveMapViewModel): LiveMapState.Content {
    assertTrue(
        "Expected content, got ${model.uiState.value}",
        model.uiState.value is LiveMapState.Content,
    )
    return model.uiState.value as LiveMapState.Content
  }

  private fun order(id: String, tripId: String = "a", status: OrderStatus = OrderStatus.PENDING) =
      Order(
          id = id,
          requesterId = "viewer",
          tripId = tripId,
          items = listOf(OrderItem("milk", "Milk", 200, 1)),
          commissionCents = 0,
          status = status,
          createdAt = now,
      )

  @Test
  fun unknownAndFailedOrdersNeverEnterDiscoveryAndConfirmedFeedsRecover() = modelTest {
    orders.value = null
    val vm = model()
    runCurrent()
    assertEquals(LiveMapState.Loading, vm.uiState.value)
    assertTrue(observed.isEmpty())
    orders.value = OrderResult.Error(OrderError.Unknown)
    runCurrent()
    assertEquals(LiveMapState.Unavailable, vm.uiState.value)
    orders.value = OrderResult.Success(emptyList())
    runCurrent()
    assertEquals(listOf("alice"), content(vm).commandos.map { it.ownerId })
    feed.value = null
    runCurrent()
    assertEquals(LiveMapState.Loading, vm.uiState.value)
    feed.value = TripResult.Error(TripError.NetworkError)
    runCurrent()
    assertEquals(LiveMapState.Unavailable, vm.uiState.value)
    feed.value = TripResult.Success(emptyList())
    runCurrent()
    assertTrue(content(vm).commandos.isEmpty())
  }

  @Test
  fun activeStatusesGroupEveryLinkAndClosedTripsWhileTerminalOrdersRestoreDiscovery() = modelTest {
    val vm = model()
    orders.value = OrderResult.Success(listOf(order("one"), order("two"), order("three", "b")))
    feed.value = TripResult.Error(TripError.NetworkError)
    runCurrent()
    assertEquals(LiveMapMode.TRACKING, content(vm).mode)
    assertEquals(2, content(vm).commandos.size)
    assertEquals(listOf("one", "two"), content(vm).commandos.first().orders.map { it.id })
    fixes.value = null
    runCurrent()
    assertEquals(2, content(vm).commandos.size)
    assertTrue(content(vm).commandos.all { it.position == null })
    for (status in OrderStatus.entries) {
      orders.value = OrderResult.Success(listOf(order("one", status = status)))
      feed.value = TripResult.Success(listOf(a))
      runCurrent()
      assertEquals(
          status.name,
          if (status.isActiveForTracking()) LiveMapMode.TRACKING else LiveMapMode.DISCOVERY,
          content(vm).mode,
      )
    }
  }

  @Test
  fun discoveryReactsToFiltersPositionsAndTripRemoval() = modelTest {
    val vm = model()
    runCurrent()
    assertEquals(1, content(vm).commandos.size)
    criteria.value = TripSearchCriteria(store = StoreFilter.COOP)
    runCurrent()
    assertTrue(content(vm).commandos.isEmpty())
    criteria.value =
        TripSearchCriteria(
            period = DeparturePeriod.Custom(LocalDate.of(2026, 11, 20), LocalDate.of(2026, 11, 19))
        )
    runCurrent()
    assertEquals(CriteriaProblem.INVALID_RANGE, content(vm).criteriaProblem)
    criteria.value = TripSearchCriteria()
    point.value = Location("Far", 0.0, 0.0)
    runCurrent()
    assertTrue(content(vm).commandos.isEmpty())
    point.value = null
    fixes.value = fixes.value!!.copy(latitude = 0.0)
    runCurrent()
    assertTrue(content(vm).commandos.isEmpty())
    fixes.value = CommandoPosition(46.521, 6.566, 20.0, now)
    runCurrent()
    feed.value = TripResult.Success(listOf(a.copy(ownerId = "viewer")))
    runCurrent()
    assertTrue(content(vm).commandos.isEmpty())
  }

  @Test
  fun quietExpiryClearsCoordinatesButRetainsTrackingSummaries() = modelTest {
    val clock =
        object : Clock() {
          override fun getZone() = ZoneOffset.UTC

          override fun withZone(zone: ZoneId) = this

          override fun instant() = now.plusMillis(testScheduler.currentTime)
        }
    orders.value = OrderResult.Success(listOf(order("one")))
    val vm = model(clock)
    runCurrent()
    assertNotNull(content(vm).commandos.single().position)
    advanceTimeBy(120_000)
    runCurrent()
    assertNull(content(vm).commandos.single().position)
    assertEquals("one", content(vm).commandos.single().orders.single().id)
    for (bad in
        listOf(
            CommandoPosition(46.521, 6.566, 1.0, now.plusSeconds(1000)),
            CommandoPosition(91.0, 6.566, 1.0, clock.instant()),
        )) {
      fixes.value = bad
      runCurrent()
      assertNull(content(vm).commandos.single().position)
    }
  }

  @Test
  fun failedWitnessFallsBackAndRetryReplacesSubscriptions() = modelTest {
    orders.value = OrderResult.Success(listOf(order("wrong"), order("z", "c")))
    val vm = model()
    runCurrent()
    assertNotNull(content(vm).commandos.single().position)
    assertTrue(observed.containsAll(listOf("wrong", "z")))
    failPosition = true
    vm.start()
    runCurrent()
    assertTrue(content(vm).commandos.all { it.positionUnavailable })
    failPosition = false
    vm.start()
    runCurrent()
    assertNotNull(content(vm).commandos.last().position)
    vm.stop()
    runCurrent()
    assertEquals(0, orders.subscriptionCount.value)
    assertEquals(0, fixes.subscriptionCount.value)
    assertEquals(LiveMapState.Loading, vm.uiState.value)
  }

  @Test
  fun switchingSharedTripRetriesEarlierWitnessWithoutOrderChanges() = modelTest {
    val sharedTrip = MutableStateFlow("c")
    val attempts = mutableListOf<String?>()
    val switchingPositions =
        object : CommandoPositionRepository by positions {
          override fun observe(ownerId: String, orderId: String?) = flow {
            attempts += orderId
            emitAll(
                sharedTrip.map { tripId ->
                  check(tripId == if (orderId == "one") "a" else "c")
                  fixes.value
                }
            )
          }
        }
    orders.value = OrderResult.Success(listOf(order("one"), order("two", "c")))
    val vm = model(positionRepo = switchingPositions)
    runCurrent()
    assertEquals(listOf("one", "two"), attempts)
    assertNotNull(content(vm).commandos.single().position)

    sharedTrip.value = "a"
    runCurrent()
    assertNull(content(vm).commandos.single().position)
    advanceTimeBy(LiveMapViewModel.WITNESS_RETRY_MILLIS)
    runCurrent()
    val person = content(vm).commandos.single()
    assertNotNull(person.position)
    assertFalse(person.positionUnavailable)
    assertEquals(listOf("one", "two"), person.orders.map { it.id })
    assertEquals(listOf("one", "two", "one"), attempts)
    assertEquals(1, sharedTrip.subscriptionCount.value)
  }

  @Test
  fun exhaustedWitnessesRecoverAutomaticallyAndStopCancelsBackoff() = modelTest {
    orders.value = OrderResult.Success(listOf(order("one")))
    failPosition = true
    val vm = model()
    runCurrent()
    assertTrue(content(vm).commandos.single().positionUnavailable)
    assertEquals(listOf("one"), observed)
    advanceTimeBy(LiveMapViewModel.WITNESS_RETRY_MILLIS - 1)
    runCurrent()
    assertEquals(listOf("one"), observed)
    failPosition = false
    advanceTimeBy(1)
    runCurrent()
    assertNotNull(content(vm).commandos.single().position)
    assertFalse(content(vm).commandos.single().positionUnavailable)

    failPosition = true
    vm.start()
    runCurrent()
    vm.stop()
    runCurrent()
    val attemptsAtStop = observed.size
    advanceTimeBy(3 * LiveMapViewModel.WITNESS_RETRY_MILLIS)
    runCurrent()
    assertEquals(attemptsAtStop, observed.size)
    assertEquals(0, fixes.subscriptionCount.value)
    assertEquals(LiveMapState.Loading, vm.uiState.value)
  }

  @Test
  fun accountChangeClearsQuietListenersAndReturningAccountCannotRestart() = modelTest {
    val vm = model()
    runCurrent()
    identity.value = "other"
    runCurrent()
    identity.value = "viewer"
    vm.start()
    runCurrent()
    assertEquals(LiveMapState.Loading, vm.uiState.value)
    assertEquals(0, orders.subscriptionCount.value)
    assertEquals(0, feed.subscriptionCount.value)
    assertEquals(0, fixes.subscriptionCount.value)
  }

  @Test
  fun discoveryPositionFailureClearsMarkersAndRetryRecovers() = modelTest {
    failPosition = true
    val vm = model()
    runCurrent()
    assertEquals(LiveMapMode.DISCOVERY, content(vm).mode)
    assertTrue(content(vm).commandos.isEmpty())
    failPosition = false
    vm.start()
    runCurrent()
    assertEquals(listOf("alice"), content(vm).commandos.map { it.ownerId })
    assertNotNull(content(vm).commandos.single().position)
  }

  @Test
  fun lateTripLookupCannotRestoreCoordinatesAfterAnAccountChange() = modelTest {
    val reply = CompletableDeferred<TripResult<Trip>>()
    val delayed =
        object : TripRepository by repository {
          override suspend fun getTripById(tripId: String) =
              withContext(NonCancellable) { reply.await() }
        }
    orders.value = OrderResult.Success(listOf(order("one")))
    val vm = model(tripRepo = delayed)
    runCurrent()
    identity.value = "other"
    runCurrent()
    reply.complete(TripResult.Success(a))
    runCurrent()
    assertEquals(LiveMapState.Loading, vm.uiState.value)
    assertEquals(0, fixes.subscriptionCount.value)
    assertTrue(observed.isEmpty())
  }

  @Test
  fun foreignOrdersOrMissingAssociatedTripsFailClosed() = modelTest {
    orders.value = OrderResult.Success(listOf(order("one").copy(requesterId = "other")))
    val vm = model()
    runCurrent()
    assertEquals(LiveMapState.Unavailable, vm.uiState.value)
    orders.value = OrderResult.Success(listOf(order("one")))
    failLookup = true
    vm.start()
    runCurrent()
    assertEquals(LiveMapState.Unavailable, vm.uiState.value)
  }
}
