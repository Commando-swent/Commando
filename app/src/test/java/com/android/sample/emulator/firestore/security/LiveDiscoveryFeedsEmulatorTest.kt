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
          val placed =
              (repo.createOrder(OrderDraft(trip.id, listOf(OrderItem("milk", "Milk", 220, 1))))
                      as OrderResult.Success)
                  .data
          updates.await {
            it is OrderResult.Success &&
                it.data.any { order ->
                  order.id == placed.id && order.status == OrderStatus.PENDING
                }
          }
          assertTrue(repo.cancelOrder(placed.id) is OrderResult.Success)
          updates.await {
            it is OrderResult.Success &&
                it.data.any { order ->
                  order.id == placed.id && order.status == OrderStatus.CANCELLED
                }
          }
        }
      }
    }
  }

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

  private suspend fun publish(owner: EmulatorClient) =
      (trips(owner)
              .publishTrip(
                  NewTrip(
                      TripLocations.stores.first(),
                      Instant.now().plusSeconds(3600),
                      TripLocations.handoffPoints.first(),
                  )
              ) as TripResult.Success)
          .data
}
