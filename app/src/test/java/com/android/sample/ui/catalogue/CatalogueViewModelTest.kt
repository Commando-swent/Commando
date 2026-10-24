package com.android.sample.ui.catalogue

import com.android.sample.data.repository.FakeOrderRepository
import com.android.sample.data.repository.FakeTripRepository
import com.android.sample.data.repository.ProductError
import com.android.sample.data.repository.ProductRepository
import com.android.sample.data.repository.ProductResult
import com.android.sample.data.repository.TripError
import com.android.sample.model.Location
import com.android.sample.model.OrderItem
import com.android.sample.model.Product
import com.android.sample.model.Trip
import com.android.sample.model.TripStatus
import com.android.sample.ui.orders.OrderDraftState
import com.android.sample.ui.orders.ReviewOrderViewModel
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Deterministic stub; records each requested chain and limit. */
internal class StubProductRepository(
    var result: ProductResult<List<Product>> = ProductResult.Success(emptyList())
) : ProductRepository {
  val requests = mutableListOf<Pair<String, Int>>()
  var gate: CompletableDeferred<Unit>? = null

  override suspend fun getProducts(storeId: String, limit: Int): ProductResult<List<Product>> {
    requests += storeId to limit
    gate?.await()
    return result
  }

  override suspend fun searchProducts(storeId: String, query: String, cursor: String?, limit: Int) =
      ProductResult.Error(ProductError.Unknown)
}

internal fun catalogueTrip(id: String, store: String) =
    Trip(
        id = id,
        ownerId = "owner",
        store = Location(store, 46.52, 6.57),
        scheduledAt = Instant.parse("2026-10-14T15:30:00Z"),
        handoffLocation = Location("EPFL", 46.52, 6.56),
        status = TripStatus.PUBLISHED,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
    )

@OptIn(ExperimentalCoroutinesApi::class)
class CatalogueViewModelTest {
  private val migrosTrip = catalogueTrip("trip-migros", "Migros EPFL")
  private val coopTrip = catalogueTrip("trip-coop", "Coop Lausanne")
  private val milk = Product("migros:1", "migros", "Oat drink", 220)
  private val bread = Product("migros:2", "migros", "Wholemeal bread", 340)
  private val coopMilk = Product("coop:1", "coop", "Oat drink", 210)
  private val trips =
      FakeTripRepository(
          "owner",
          initialTrips = listOf(migrosTrip, coopTrip, catalogueTrip("trip-volg", "Volg Ecublens")),
      )
  private val products = StubProductRepository(ProductResult.Success(listOf(milk, bread, coopMilk)))

  @Before
  fun setUp() {
    Dispatchers.setMain(StandardTestDispatcher())
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  private fun viewModel(
      tripId: String = migrosTrip.id,
      draft: OrderDraftState = OrderDraftState(tripId),
  ) = CatalogueViewModel(tripId, trips, products, draft)

  private fun loaded(vm: CatalogueViewModel) =
      (vm.uiState.value.content as CatalogueContent.Loaded).products

  @Test
  fun migrosTripLoadsAtMostFiftyMigrosProductsOnly() = runTest {
    val vm = viewModel()
    advanceUntilIdle()
    assertEquals(listOf("migros" to 50), products.requests)
    assertEquals(migrosTrip, vm.uiState.value.trip)
    assertEquals(listOf(milk, bread), loaded(vm))
  }

  @Test
  fun coopTripLoadsTheCoopCatalogue() = runTest {
    val vm = viewModel(coopTrip.id)
    advanceUntilIdle()
    assertEquals(listOf("coop" to 50), products.requests)
    assertEquals(listOf(coopMilk), loaded(vm))
  }

  @Test
  fun unsupportedStoreNeverFallsBackToAnotherCatalogue() = runTest {
    val vm = viewModel("trip-volg")
    advanceUntilIdle()
    assertEquals(CatalogueContent.UnsupportedStore("Volg Ecublens"), vm.uiState.value.content)
    assertTrue(products.requests.isEmpty())

    products.result = ProductResult.Error(ProductError.InvalidStore)
    val rejected = viewModel()
    advanceUntilIdle()
    assertEquals(CatalogueContent.UnsupportedStore("Migros EPFL"), rejected.uiState.value.content)
  }

  @Test
  fun emptyCatalogueIsDistinctFromPermissionAndNetworkFailures() = runTest {
    products.result = ProductResult.Success(emptyList())
    val empty = viewModel()
    advanceUntilIdle()
    assertEquals(CatalogueContent.Loaded(emptyList()), empty.uiState.value.content)

    listOf(ProductError.PermissionDenied, ProductError.NetworkError, ProductError.Unknown).forEach {
      products.result = ProductResult.Error(it)
      val failed = viewModel()
      advanceUntilIdle()
      assertEquals(CatalogueContent.ProductsFailed(it), failed.uiState.value.content)
    }
  }

  @Test
  fun missingTripIsReportedWithoutQueryingProducts() = runTest {
    val vm = viewModel("deleted-trip")
    advanceUntilIdle()
    assertEquals(CatalogueContent.TripFailed(TripError.NotFound), vm.uiState.value.content)
    assertTrue(products.requests.isEmpty())
  }

  @Test
  fun retryShowsLoadingIgnoresRepeatsAndRecovers() = runTest {
    products.result = ProductResult.Error(ProductError.NetworkError)
    val vm = viewModel()
    advanceUntilIdle()

    products.result = ProductResult.Success(listOf(milk))
    products.gate = CompletableDeferred()
    vm.retry()
    vm.retry()
    advanceUntilIdle()
    assertEquals(CatalogueContent.Loading, vm.uiState.value.content)
    assertEquals(2, products.requests.size)

    products.gate!!.complete(Unit)
    advanceUntilIdle()
    assertEquals(listOf(milk), loaded(vm))
  }

  @Test
  fun selectionWritesProductSnapshotsToTheSharedDraft() = runTest {
    val draft = OrderDraftState(migrosTrip.id)
    val vm = viewModel(draft = draft)
    advanceUntilIdle()

    vm.add(milk.id)
    vm.add(milk.id)
    vm.add(bread.id)
    vm.add(coopMilk.id) // Not in this trip's catalogue.
    advanceUntilIdle()
    assertEquals(
        listOf(
            OrderItem("migros:1", "Oat drink", 220, 2),
            OrderItem("migros:2", "Wholemeal bread", 340, 1),
        ),
        draft.draft.value.items,
    )
    assertEquals(migrosTrip.id, draft.draft.value.tripId)
    assertEquals(3L, vm.uiState.value.selectedCount)
    assertEquals(780, vm.uiState.value.estimatedCents)
    assertTrue(vm.uiState.value.canContinue)

    vm.increment(milk.id)
    vm.decrement(bread.id)
    vm.decrement("unknown")
    advanceUntilIdle()
    assertEquals(listOf(OrderItem("migros:1", "Oat drink", 220, 3)), draft.draft.value.items)
    repeat(3) { vm.decrement(milk.id) }
    advanceUntilIdle()
    assertTrue(draft.draft.value.items.isEmpty())
    assertFalse(vm.uiState.value.canContinue)
  }

  @Test
  fun searchFiltersByNameAndKeepsSelectedItems() = runTest {
    val vm = viewModel()
    advanceUntilIdle()
    vm.add(bread.id)
    vm.onQueryChange("  OAT ")
    advanceUntilIdle()
    assertEquals(listOf(milk), vm.uiState.value.visibleProducts)
    assertEquals(1, vm.uiState.value.quantityOf(bread.id))

    vm.onQueryChange("chocolat")
    assertTrue(vm.uiState.value.visibleProducts.isEmpty())
    vm.clearSearch()
    assertEquals(listOf(milk, bread), vm.uiState.value.visibleProducts)
    assertEquals(1, vm.uiState.value.quantityOf(bread.id))
  }

  @Test
  fun draftIsKeptForTheSameTripAndResetForAnotherTrip() = runTest {
    val draft = OrderDraftState(migrosTrip.id)
    draft.addItem(OrderItem(milk.id, milk.name, milk.priceCents, 2))
    viewModel(draft = draft)
    assertEquals(2, draft.draft.value.items.single().quantity)

    val coop = viewModel(coopTrip.id, draft)
    advanceUntilIdle()
    assertEquals(coopTrip.id, draft.draft.value.tripId)
    assertTrue(draft.draft.value.items.isEmpty())
    assertTrue(coop.uiState.value.items.isEmpty())
  }

  @Test
  fun reviewOrderSeesCatalogueSelectionAndClearsItAfterSubmit() = runTest {
    val draft = OrderDraftState(migrosTrip.id)
    val catalogue = viewModel(draft = draft)
    advanceUntilIdle()
    catalogue.add(milk.id)
    catalogue.increment(milk.id)

    val review = ReviewOrderViewModel(FakeOrderRepository({ "requester" }), draft)
    advanceUntilIdle()
    assertEquals(listOf(OrderItem(milk.id, milk.name, 220, 2)), review.uiState.value.draft.items)

    review.changeQuantity(milk.id, 1)
    advanceUntilIdle()
    assertEquals(3, catalogue.uiState.value.quantityOf(milk.id))

    review.submit()
    advanceUntilIdle()
    assertNotNull(review.uiState.value.order)
    assertTrue(catalogue.uiState.value.items.isEmpty())
  }
}
