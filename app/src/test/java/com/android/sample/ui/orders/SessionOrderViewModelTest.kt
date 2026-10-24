package com.android.sample.ui.orders

// AI assistance: OpenAI Codex.
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import com.android.sample.data.repository.*
import com.android.sample.model.*
import com.android.sample.model.authentication.AuthUser
import com.android.sample.model.authentication.FakeAuthRepository
import com.android.sample.ui.catalogue.CatalogueViewModel
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionOrderViewModelTest {
  private val store = ViewModelStore()
  private val auth = FakeAuthRepository(AuthUser("alice"))
  private val time = Instant.parse("2030-01-01T12:00:00Z")
  private val trip =
      Trip(
          "trip / % 雪",
          "owner",
          Location("Migros EPFL", 46.5, 6.5),
          time,
          Location("EPFL", 46.5, 6.5),
          TripStatus.PUBLISHED,
          time,
          time,
      )
  private val trips = FakeTripRepository("alice", initialTrips = listOf(trip))
  private val products =
      object : ProductRepository {
        override suspend fun getProducts(storeId: String, limit: Int) =
            ProductResult.Success(listOf(Product("milk", "migros", "Milk", 220)))

        override suspend fun searchProducts(
            storeId: String,
            query: String,
            cursor: String?,
            limit: Int,
        ) = ProductResult.Error(ProductError.Unknown)
      }

  @Before
  fun setUp() {
    Dispatchers.setMain(StandardTestDispatcher())
  }

  @After
  fun tearDown() {
    store.clear()
    Dispatchers.resetMain()
  }

  private fun <T : ViewModel> keep(key: String, model: T): T = model.also { store.put(key, it) }

  private fun session() =
      keep(
          "session",
          SessionOrderViewModel("alice", auth) {
            FakeOrderRepository(it, now = { time }, newId = { "order / % 雪" })
          },
      )

  @Test
  fun catalogueReviewListAndDetailsUseOneDraftAndOneSavedOrder() = runTest {
    val session = session()
    val draft = session.draftFor(trip.id)
    val catalogue = keep("catalogue", CatalogueViewModel(trip.id, trips, products, draft))
    runCurrent()
    catalogue.add("milk")
    runCurrent()
    val review = keep("review", ReviewOrderViewModel(session.repository, session.draftFor(trip.id)))
    runCurrent()
    review.changeQuantity("milk", 1)
    runCurrent()
    assertEquals(2, catalogue.uiState.value.quantityOf("milk"))
    assertSame(draft, session.draftFor(trip.id))
    val list = keep("list", MyOrdersViewModel(session.repository, trips, "alice"))
    runCurrent()
    assertEquals(MyOrdersUiState.Empty, list.uiState.value)
    review.submit()
    review.submit()
    runCurrent()
    val saved = requireNotNull(review.uiState.value.order)
    assertTrue(draft.draft.value.items.isEmpty())
    assertEquals(trip.id, saved.tripId)
    assertEquals(2, saved.items.single().quantity)
    list.retry()
    runCurrent()
    val row = (list.uiState.value as MyOrdersUiState.Content).rows.single()
    assertEquals(saved, row.order)
    val details = keep("details", OrderDetailsViewModel(row.order.id, session.repository, trips))
    runCurrent()
    assertEquals(OrderDetailsUiState.Content(saved, trip), details.uiState.value)
  }

  @Test
  fun switchingTripClearsDraftAndOldScreensCannotEditOrSubmitIt() = runTest {
    val session = session()
    val draft = session.draftFor(trip.id)
    val catalogue = keep("catalogue", CatalogueViewModel(trip.id, trips, products, draft))
    runCurrent()
    catalogue.add("milk")
    val review = keep("review", ReviewOrderViewModel(session.repository, draft))
    runCurrent()
    assertSame(draft, session.draftFor("another-trip"))
    catalogue.add("milk")
    review.submit()
    runCurrent()
    assertTrue(draft.draft.value.items.isEmpty())
    assertNull(review.uiState.value.order)
    assertEquals(OrderResult.Success(emptyList<Order>()), session.repository.getMyOrders())
  }

  @Test
  fun endingSessionClearsDraftAndRevokesOldRepositoryEvenAfterSameAccountReturns() = runTest {
    val first = session()
    val draft = first.draftFor(trip.id)
    draft.addItem(OrderItem("milk", "Milk", 220, 1))
    first.repository.createOrder(draft.draft.value)
    store.clear()
    assertTrue(draft.draft.value.items.isEmpty())
    assertEquals(OrderResult.Error(OrderError.NotAuthenticated), first.repository.getMyOrders())
    assertThrows(IllegalStateException::class.java) { first.draftFor(trip.id) }
    val next = session()
    assertNotSame(first.repository, next.repository)
    assertEquals(OrderResult.Success(emptyList<Order>()), next.repository.getMyOrders())
  }

  @Test
  fun changedAccountIsRejectedBeforeTheOldEntryIsDisposed() = runTest {
    val first = session()
    auth.signInWithEmailResult = Result.success(AuthUser("bob"))
    auth.signInWithEmail("", "")
    assertEquals(OrderResult.Error(OrderError.NotAuthenticated), first.repository.getMyOrders())
    assertThrows(IllegalStateException::class.java) { first.draftFor(trip.id) }
  }
}
