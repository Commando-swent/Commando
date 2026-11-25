package com.commando.app.emulator.firestore.security

// AI assistance: OpenAI Codex.
import com.commando.app.data.repository.*
import com.commando.app.data.repository.CommandoPositionRepositoryFirestore
import com.commando.app.data.repository.OrderRepositoryFirestore
import com.commando.app.data.repository.OrderResult
import com.commando.app.data.repository.TripRepositoryFirestore
import com.commando.app.data.repository.TripResult
import com.commando.app.model.*
import com.commando.app.model.authentication.AuthUser
import com.commando.app.model.authentication.FakeAuthRepository
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

  @Test
  fun discoveryCanStartPrivateAndReactToOptInOptOutAndRenewedConsent() = runTest {
    withContext(Dispatchers.Default) {
      withClients("owner" to true, "viewer" to true) { (owner, viewer) ->
        val trip = publish(owner)
        val uid = owner.auth.currentUser!!.uid
        val publisher =
            CommandoPositionRepositoryFirestore({ owner.auth.currentUser?.uid }, owner.firestore)
        val reader =
            CommandoPositionRepositoryFirestore({ viewer.auth.currentUser?.uid }, viewer.firestore)
        fun fix() = CommandoPosition(46.52, 6.56, 20.0, Instant.now().minusSeconds(1))
        publisher.publish(trip.id, fix())
        withUpdates(reader.observeDiscovery(uid, trip.id)) { updates ->
          updates.await { it == null }
          publisher.publishForDiscovery(trip.id, fix())
          updates.await { it != null }
          publisher.publish(trip.id, fix())
          updates.await { it == null }
          publisher.publishForDiscovery(trip.id, fix())
          updates.await { it != null }
          publisher.stopSharing()
          updates.await { it == null }
        }
      }
    }
  }

  private fun OrderResult<List<Order>>?.hasOrder(id: String, status: OrderStatus) =
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
