package com.android.sample.emulator.firestore.security

// AI assistance: OpenAI Codex.
import com.android.sample.data.repository.*
import com.android.sample.model.*
import com.android.sample.model.authentication.AuthUser
import com.android.sample.model.authentication.FakeAuthRepository
import java.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Real cross-client events use production repositories/rules and an already-open observation. */
@RunWith(RobolectricTestRunner::class)
class LiveDiscoveryFeedsEmulatorTest {
  @Test
  fun aPublishedThenClosedTripUpdatesAnAlreadyOpenDiscoveryFeed() = runTest {
    withContext(Dispatchers.Default) {
      withClients("owner" to true, "viewer" to true) { (owner, viewer) ->
        withUpdates(trips(viewer).observeUpcomingTrips()) { updates ->
          updates.await { it is TripResult.Success }
          val created = publish(owner)
          updates.await {
            it is TripResult.Success && it.data.any { trip -> trip.id == created.id }
          }
          assertTrue(
              trips(owner).transitionTrip(created.id, TripTransition.CLOSE) is TripResult.Success
          )
          updates.await {
            it is TripResult.Success && it.data.none { trip -> trip.id == created.id }
          }
        }
      }
    }
  }

  @Test
  fun placingAndCancellingAnOrderUpdatesTheRequestersLiveFeed() = runTest {
    withContext(Dispatchers.Default) {
      withClients("owner" to true, "requester" to true) { (owner, requester) ->
        val trip = publish(owner)
        val repo =
            OrderRepositoryFirestore({ requester.auth.currentUser?.uid }, requester.firestore)
        withUpdates(repo.observeMyOrders()) { updates ->
          updates.await { it is OrderResult.Success }
          val draft = OrderDraft(trip.id, listOf(OrderItem("milk", "Milk", 220, 1)))
          val placed = (repo.createOrder(draft) as OrderResult.Success).data
          updates.await { it.hasOrder(placed.id, OrderStatus.PENDING) }
          assertTrue(repo.cancelOrder(placed.id) is OrderResult.Success)
          updates.await { it.hasOrder(placed.id, OrderStatus.CANCELLED) }
        }
      }
    }
  }

  private fun OrderResult<List<Order>>.hasOrder(id: String, status: OrderStatus) =
      this is OrderResult.Success && data.any { it.id == id && it.status == status }

  private suspend fun <T> withUpdates(flow: Flow<T>, block: suspend (ReceiveChannel<T>) -> Unit) =
      coroutineScope {
        val updates = flow.produceIn(this)
        try {
          block(updates)
        } finally {
          updates.cancel()
        }
      }

  private suspend fun <T> ReceiveChannel<T>.await(predicate: (T) -> Boolean) =
      withTimeout(10_000) { receiveAsFlow().first(predicate) }

  @Test
  fun ownedTripFeedRetainsClosedTripsForForegroundSharing() = runTest {
    withContext(Dispatchers.Default) {
      withClients("owner" to true) { (owner) ->
        val created = publish(owner)
        val repo = trips(owner)
        assertTrue(repo.transitionTrip(created.id, TripTransition.CLOSE) is TripResult.Success)
        val mine =
            withTimeout(10_000) {
              repo.observeMyTrips().filterIsInstance<TripResult.Success<List<Trip>>>().first()
            }
        assertEquals(TripStatus.IN_PROGRESS, mine.data.single { it.id == created.id }.status)
      }
    }
  }

  private fun trips(client: EmulatorClient) =
      TripRepositoryFirestore(
          FakeAuthRepository(AuthUser(client.auth.currentUser!!.uid)),
          client.firestore,
      )

  private suspend fun publish(owner: EmulatorClient): Trip {
    val draft =
        NewTrip(
            TripLocations.stores.first(),
            Instant.now().plusSeconds(3600),
            TripLocations.handoffPoints.first(),
        )
    return (trips(owner).publishTrip(draft) as TripResult.Success).data
  }
}
