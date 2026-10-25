package com.android.sample.emulator.firestore.security

// AI assistance: OpenAI Codex.
import androidx.lifecycle.ViewModelStore
import com.android.sample.data.repository.*
import com.android.sample.emulator.auth.FirebaseAuthEmulator.runTest
import com.android.sample.model.*
import com.android.sample.model.authentication.AuthRepositoryFirebase
import com.android.sample.ui.orders.*
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class PersistentRequesterFlowEmulatorTest {
  @Test
  fun saveListDetailsFreshSessionAndAccountIsolation() = runTest {
    Dispatchers.setMain(UnconfinedTestDispatcher())
    val store = ViewModelStore()
    try {
      withClients("requester" to false, "fresh" to false, "owner" to true) { (first, fresh, owner)
        ->
        val email = "orders-${UUID.randomUUID()}@example.test"
        val password = "emulator-password"
        first.auth.createUserWithEmailAndPassword(email, password).await()
        val auth = AuthRepositoryFirebase(first.auth)
        val uid = auth.currentUser!!.uid
        val ownerTrips =
            TripRepositoryFirestore(AuthRepositoryFirebase(owner.auth), owner.firestore)
        val location = Location("Migros EPFL", 46.52, 6.57)
        val trip =
            (ownerTrips.publishTrip(NewTrip(location, Instant.now().plusSeconds(3600), location))
                    as TripResult.Success)
                .data
        val trips = TripRepositoryFirestore(auth, first.firestore)
        val session =
            SessionOrderViewModel(uid, auth) { OrderRepositoryFirestore(it, first.firestore) }
        store.put("session", session)
        assertFalse(session.isDemo)
        val draft = session.draftFor(trip.id)
        draft.addItem(OrderItem("milk", "Milk", 220, 2))
        draft.updateCommission(150)
        // Exercise a real adapter/rules failure before a successful retry of the same draft.
        val tripRef = owner.firestore.collection("trips").document(trip.id)
        tripRef.update("status", "CANCELLED").await()
        val failed = ReviewOrderViewModel(session.repository, draft)
        store.put("failed", failed)
        failed.submit()
        failed.uiState.first { it.error != null }
        assertEquals(2, draft.draft.value.items.single().quantity)
        tripRef.update("status", "PUBLISHED").await()
        val review = failed
        review.submit()
        review.submit()
        val saved = review.uiState.first { it.order != null }.order!!
        assertTrue(draft.draft.value.items.isEmpty())
        assertEquals(150, saved.commissionCents)
        assertEquals(1, (session.repository.getMyOrders() as OrderResult.Success).data.size)
        val list = MyOrdersViewModel(session.repository, trips, uid)
        store.put("list", list)
        assertEquals(
            saved,
            (list.uiState.first { it is MyOrdersUiState.Content } as MyOrdersUiState.Content)
                .rows
                .single()
                .order,
        )
        val storedTrip = (trips.getTripById(trip.id) as TripResult.Success).data
        val details = OrderDetailsViewModel(saved.id, session.repository, trips)
        store.put("details", details)
        assertEquals(
            OrderDetailsUiState.Content(saved, storedTrip),
            details.uiState.first { it !is OrderDetailsUiState.Loading },
        )
        store.clear()
        assertEquals(
            OrderResult.Error(OrderError.NotAuthenticated),
            session.repository.getMyOrders(),
        )
        // A second FirebaseApp/client has no first-client cache or ViewModel/session state.
        fresh.auth.signInWithEmailAndPassword(email, password).await()
        val reloadedAuth = AuthRepositoryFirebase(fresh.auth)
        val reloaded =
            SessionOrderViewModel(uid, reloadedAuth) {
              OrderRepositoryFirestore(it, fresh.firestore)
            }
        store.put("newSession", reloaded)
        assertEquals(OrderResult.Success(listOf(saved)), reloaded.repository.getMyOrders())
        assertEquals(OrderResult.Success(saved), reloaded.repository.getOrderById(saved.id))
        fresh.auth.signOut()
        fresh.auth.signInAnonymously().await()
        assertEquals(
            OrderResult.Error(OrderError.NotAuthenticated),
            reloaded.repository.getMyOrders(),
        )
        val other =
            SessionOrderViewModel(reloadedAuth.currentUser!!.uid, reloadedAuth) {
              OrderRepositoryFirestore(it, fresh.firestore)
            }
        store.put("other", other)
        assertEquals(OrderResult.Success(emptyList<Order>()), other.repository.getMyOrders())
        assertEquals(
            OrderResult.Error(OrderError.PermissionDenied),
            other.repository.getOrderById(saved.id),
        )
      }
    } finally {
      store.clear()
      Dispatchers.resetMain()
    }
  }
}
