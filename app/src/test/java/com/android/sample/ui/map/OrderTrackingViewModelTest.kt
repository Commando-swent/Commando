package com.android.sample.ui.map

// AI assistance: OpenAI Codex.
import androidx.lifecycle.ViewModelStore
import com.android.sample.MainDispatcherRule
import com.android.sample.data.repository.*
import com.android.sample.model.*
import java.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Selected-order authorization, retry, confirmation and session cancellation. */
@OptIn(ExperimentalCoroutinesApi::class)
class OrderTrackingViewModelTest {
  @get:Rule val main = MainDispatcherRule()
  private val identity = MutableStateFlow<String?>("viewer")
  private val now = Instant.parse("2026-11-12T12:00:00Z")
  private val trip =
      Trip(
          "trip",
          "owner",
          TripLocations.stores.first(),
          now,
          TripLocations.handoffPoints.first(),
          TripStatus.IN_PROGRESS,
          now,
          now,
      )
  private var order =
      Order(
          "order",
          trip.id,
          "viewer",
          listOf(OrderItem("milk", "Milk", 100, 1)),
          0,
          OrderStatus.ACCEPTED,
          now,
      )
  private var tripResult: TripResult<Trip> = TripResult.Success(trip)
  private val fixes = MutableStateFlow<CommandoPosition?>(null)
  private val fix = CommandoPosition(46.521, 6.566, 20.0, now)
  private var failure = false
  private var closeStream = false
  private val witnesses = mutableListOf<Pair<String, String?>>()
  private val positions =
      object : CommandoPositionRepository {
        override suspend fun publish(tripId: String, position: CommandoPosition) = Unit

        override suspend fun stopSharing() = Unit

        override fun observe(ownerId: String, orderId: String?) = flow {
          witnesses += ownerId to orderId
          check(!failure)
          if (closeStream) emit(fix) else emitAll(fixes)
        }
      }

  private suspend fun TestScope.withModel(block: suspend (OrderTrackingViewModel) -> Unit) {
    val store = ViewModelStore()
    val orders =
        object : OrderRepository by FakeOrderRepository({ identity.value }) {
          override suspend fun getOrderById(orderId: String) = OrderResult.Success(order)
        }
    val trips =
        object : TripRepository by FakeTripRepository("viewer") {
          override suspend fun getTripById(tripId: String) = tripResult
        }
    val model =
        OrderTrackingViewModel("order", orders, trips, positions, { identity.value }, identity)
    store.put("tracking", model)
    try {
      block(model)
    } finally {
      store.clear()
      runCurrent()
    }
  }

  @Test
  fun onlyTheSelectedOrderSuppliesTheOwnerAndAuthorizationWitness() = runTest {
    withModel { model ->
      assertTrue(witnesses.isEmpty())
      model.start()
      runCurrent()
      assertEquals(listOf("owner" to "order"), witnesses)
      assertEquals(OrderTrackingState.Position(null), model.uiState.value)
      fixes.value = fix
      runCurrent()
      assertEquals(OrderTrackingState.Position(fix), model.uiState.value)
      fixes.value = null
      runCurrent()
      assertEquals(OrderTrackingState.Position(null), model.uiState.value)
      fixes.value = fix
      runCurrent()
      assertEquals(OrderTrackingState.Position(fix), model.uiState.value)
      model.stop()
      runCurrent()
      assertEquals(0, fixes.subscriptionCount.value)
      assertEquals(OrderTrackingState.Loading, model.uiState.value)
    }
  }

  @Test
  fun failedTripFetchCanRecoverOnRetryWithoutRecreatingTheViewModel() = runTest {
    withModel { model ->
      tripResult = TripResult.Error(TripError.NetworkError)
      model.start()
      runCurrent()
      assertEquals(OrderTrackingState.Unavailable, model.uiState.value)
      assertTrue(witnesses.isEmpty())
      tripResult = TripResult.Success(trip)
      model.start()
      runCurrent()
      assertEquals(OrderTrackingState.Position(null), model.uiState.value)
    }
  }

  @Test
  fun observerFailureClearsTheMarkerAndRetrySubscribesAgain() = runTest {
    withModel { model ->
      failure = true
      model.start()
      runCurrent()
      assertEquals(OrderTrackingState.Unavailable, model.uiState.value)
      failure = false
      fixes.value = fix
      model.start()
      runCurrent()
      assertEquals(OrderTrackingState.Position(fix), model.uiState.value)
      assertEquals(2, witnesses.size)
    }
  }

  @Test
  fun foreignAndTerminalOrdersNeverStartPositionObservation() = runTest {
    withModel { model ->
      for (rejected in
          listOf(
              order.copy(requesterId = "other"),
              order.copy(status = OrderStatus.CANCELLED),
              order.copy(id = "wrong"),
          )) {
        order = rejected
        model.start()
        runCurrent()
        assertEquals(OrderTrackingState.Unavailable, model.uiState.value)
      }
      assertTrue(witnesses.isEmpty())
    }
  }

  @Test
  fun mismatchedBlankOrSelfOwnedTripNeverStartsPositionObservation() = runTest {
    withModel { model ->
      for (invalid in
          listOf(trip.copy(id = "wrong"), trip.copy(ownerId = ""), trip.copy(ownerId = "viewer"))) {
        tripResult = TripResult.Success(invalid)
        model.start()
        runCurrent()
        assertEquals(OrderTrackingState.Unavailable, model.uiState.value)
      }
      assertTrue(witnesses.isEmpty())
    }
  }

  @Test
  fun closingThePositionStreamClearsItsLastFix() = runTest {
    closeStream = true
    withModel { model ->
      model.start()
      runCurrent()
      assertEquals(OrderTrackingState.Unavailable, model.uiState.value)
    }
  }

  @Test
  fun logoutAndAccountSwitchClearTheMarkerAndPermanentlyEndTheOldSession() = runTest {
    for (next in listOf(null, "other")) {
      identity.value = "viewer"
      withModel { model ->
        fixes.value = fix
        model.start()
        runCurrent()
        identity.value = next
        runCurrent()
        assertEquals(OrderTrackingState.Loading, model.uiState.value)
        assertEquals(0, fixes.subscriptionCount.value)
        val count = witnesses.size
        identity.value = "viewer"
        model.start()
        runCurrent()
        assertEquals(count, witnesses.size)
      }
    }
  }
}
