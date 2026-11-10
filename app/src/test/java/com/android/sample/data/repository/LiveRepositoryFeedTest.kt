package com.android.sample.data.repository

// AI assistance: OpenAI Codex.
import com.android.sample.model.*
import com.android.sample.ui.trips.discoveryTrip
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

/** Mutating the shared fake repositories updates observers without calling refresh. */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveRepositoryFeedTest {
  private val now = Instant.parse("2026-11-10T10:00:00Z")

  @Test
  fun legacyAdaptersKeepOneShotObservationCompatibility() = runTest {
    val trips =
        object : TripRepository by FakeTripRepository("owner") {
          override fun observeUpcomingTrips() = super<TripRepository>.observeUpcomingTrips()

          override fun observeMyTrips() = super<TripRepository>.observeMyTrips()
        }
    val orders =
        object : OrderRepository by FakeOrderRepository({ "owner" }) {
          override fun observeMyOrders() = super<OrderRepository>.observeMyOrders()
        }
    assertEquals(trips.getUpcomingTrips(), trips.observeUpcomingTrips().first())
    assertEquals(trips.getMyTrips(), trips.observeMyTrips().first())
    assertEquals(orders.getMyOrders(), orders.observeMyOrders().first())
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
