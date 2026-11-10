package com.android.sample.emulator.firestore.security

// AI assistance: OpenAI Codex.
import com.android.sample.data.repository.*
import com.android.sample.model.*
import com.android.sample.model.authentication.AuthUser
import com.android.sample.model.authentication.FakeAuthRepository
import java.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Real cross-client query events use the canonical repositories and production security rules. */
@RunWith(RobolectricTestRunner::class)
class LiveDiscoveryFeedsEmulatorTest {
  @Test
  fun aPublishedThenClosedTripUpdatesAnAlreadyOpenDiscoveryFeed() = runTest {
    withContext(Dispatchers.Default) {
      withClients("owner" to true, "viewer" to true) { (owner, viewer) ->
        val source = trips(viewer)
        val ready = CompletableDeferred<Unit>()
        val updates = Channel<TripResult<List<Trip>>>(Channel.UNLIMITED)
        val observation = launch {
          source.observeUpcomingTrips().collect {
            updates.send(it)
            if (it is TripResult.Success) ready.complete(Unit)
          }
        }
        try {
          withTimeout(10_000) { ready.await() }
          val created = publish(owner)
          withTimeout(10_000) {
            updates.receiveAsFlow().filterIsInstance<TripResult.Success<List<Trip>>>().first {
                result ->
              result.data.any { it.id == created.id }
            }
          }
          assertTrue(
              trips(owner).transitionTrip(created.id, TripTransition.CLOSE) is TripResult.Success
          )
          withTimeout(10_000) {
            updates.receiveAsFlow().filterIsInstance<TripResult.Success<List<Trip>>>().first {
                result ->
              result.data.none { it.id == created.id }
            }
          }
        } finally {
          observation.cancelAndJoin()
          updates.close()
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
        val ready = CompletableDeferred<Unit>()
        val updates = Channel<OrderResult<List<Order>>>(Channel.UNLIMITED)
        val observation = launch {
          repo.observeMyOrders().collect {
            updates.send(it)
            if (it is OrderResult.Success) ready.complete(Unit)
          }
        }
        try {
          withTimeout(10_000) { ready.await() }
          val placed =
              (repo.createOrder(OrderDraft(trip.id, listOf(OrderItem("milk", "Milk", 220, 1))))
                      as OrderResult.Success)
                  .data
          withTimeout(10_000) {
            updates.receiveAsFlow().filterIsInstance<OrderResult.Success<List<Order>>>().first {
                result ->
              result.data.any { it.id == placed.id && it.status == OrderStatus.PENDING }
            }
          }
          assertTrue(repo.cancelOrder(placed.id) is OrderResult.Success)
          withTimeout(10_000) {
            updates.receiveAsFlow().filterIsInstance<OrderResult.Success<List<Order>>>().first {
                result ->
              result.data.any { it.id == placed.id && it.status == OrderStatus.CANCELLED }
            }
          }
        } finally {
          observation.cancelAndJoin()
          updates.close()
        }
      }
    }
  }

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
