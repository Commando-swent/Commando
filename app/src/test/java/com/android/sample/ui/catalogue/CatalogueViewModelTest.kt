package com.android.sample.ui.catalogue

import com.android.sample.data.repository.FakeOrderRepository
import com.android.sample.data.repository.FakeTripRepository
import com.android.sample.data.repository.MAX_SEARCH_QUERY_LENGTH
import com.android.sample.data.repository.ProductError
import com.android.sample.data.repository.ProductRepository
import com.android.sample.data.repository.ProductResult
import com.android.sample.data.repository.TripError
import com.android.sample.model.Location
import com.android.sample.model.OrderItem
import com.android.sample.model.Product
import com.android.sample.model.ProductPage
import com.android.sample.model.Trip
import com.android.sample.model.TripStatus
import com.android.sample.ui.orders.OrderDraftState
import com.android.sample.ui.orders.ReviewOrderViewModel
import java.time.Instant
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Deterministic search over [result], ignoring the store so chain filtering can be tested. Records
 * each request; a request waits for the [gate] set when it was made, even if cancelled, like a data
 * source that cannot be interrupted. [pageError] fails every page after the first.
 */
internal class StubProductRepository(
    var result: ProductResult<List<Product>> = ProductResult.Success(emptyList())
) : ProductRepository {
  data class Request(val storeId: String, val query: String, val cursor: String?, val limit: Int)

  val requests = mutableListOf<Request>()
  var gate: CompletableDeferred<Unit>? = null
  var pageError: ProductError? = null
  var thrown: Exception? = null

  override suspend fun getProducts(storeId: String, limit: Int) = result

  override suspend fun searchProducts(
      storeId: String,
      query: String,
      cursor: String?,
      limit: Int,
  ): ProductResult<ProductPage> {
    requests += Request(storeId, query, cursor, limit)
    thrown?.let { throw it }
    gate?.let { held -> suspendCoroutine { done -> held.invokeOnCompletion { done.resume(Unit) } } }
    val all =
        when (val current = result) {
          is ProductResult.Error -> return current
          is ProductResult.Success -> current.data
        }
    if (cursor != null)
        pageError?.let {
          return ProductResult.Error(it)
        }
    val words = query.lowercase().split(' ').filter { it.isNotBlank() }
    val matches = all.filter { product -> words.all { product.name.lowercase().contains(it) } }
    val start = cursor?.let { id -> matches.indexOfFirst { it.id == id } + 1 } ?: 0
    val page = matches.drop(start).take(limit)
    return ProductResult.Success(ProductPage(page, page.takeIf { it.size == limit }?.last()?.id))
  }
}

internal fun migrosProducts(range: IntRange) = range.map {
  Product("migros:%03d".format(it), "migros", "Product $it", 100)
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
  private val otherMigrosTrip = catalogueTrip("trip-migros-2", "Migros Renens")
  private val milk = Product("migros:1", "migros", "Oat drink", 220)
  private val bread = Product("migros:2", "migros", "Wholemeal bread", 340)
  private val coopMilk = Product("coop:1", "coop", "Oat drink", 210)
  private val trips =
      FakeTripRepository(
          "owner",
          initialTrips =
              listOf(
                  migrosTrip,
                  coopTrip,
                  otherMigrosTrip,
                  catalogueTrip("trip-volg", "Volg Ecublens"),
              ),
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
      searchDebounceMillis: Long = SEARCH_DEBOUNCE_MILLIS,
  ) = CatalogueViewModel(tripId, trips, products, draft, searchDebounceMillis)

  private fun content(vm: CatalogueViewModel) = vm.uiState.value.content as CatalogueContent.Loaded

  private fun loaded(vm: CatalogueViewModel) = content(vm).products

  private fun request(query: String = "", cursor: String? = null, store: String = "migros") =
      StubProductRepository.Request(store, query, cursor, 50)

  @Test
  fun migrosTripSearchesTheFirstMigrosPageWithABlankQuery() = runTest {
    val vm = viewModel()
    advanceUntilIdle()
    assertEquals(listOf(request()), products.requests)
    assertEquals(migrosTrip, vm.uiState.value.trip)
    assertEquals(listOf(milk, bread), loaded(vm))
  }

  @Test
  fun coopTripLoadsTheCoopCatalogue() = runTest {
    val vm = viewModel(coopTrip.id)
    advanceUntilIdle()
    assertEquals(listOf(request(store = "coop")), products.requests)
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
  fun typingIsDebouncedIntoOneServerSearchThatKeepsSelectedItems() = runTest {
    val vm = viewModel()
    advanceUntilIdle()
    vm.add(bread.id)
    val all = vm.uiState.value.content
    listOf("o", "oa", "OAT").forEach {
      vm.onQueryChange(it)
      advanceTimeBy(SEARCH_DEBOUNCE_MILLIS - 1)
    }
    // Typing never flickers to the skeleton: earlier results stay until the search is sent, and
    // they are not extended with another page meanwhile.
    assertEquals(all, vm.uiState.value.content)
    vm.loadMore()
    assertEquals(1, products.requests.size)

    val sent = CompletableDeferred<Unit>()
    products.gate = sent
    advanceTimeBy(2)
    runCurrent()
    assertEquals(CatalogueContent.Loading, vm.uiState.value.content)
    products.gate = null
    sent.complete(Unit)
    runCurrent()
    assertEquals(request("OAT"), products.requests.last())
    assertEquals(CatalogueContent.Loaded(listOf(milk), "OAT"), vm.uiState.value.content)
    assertEquals(1, vm.uiState.value.quantityOf(bread.id))

    vm.onQueryChange(" OAT ") // Same search: no new request.
    vm.clearSearch()
    advanceUntilIdle()
    assertEquals(listOf(request(), request("OAT"), request()), products.requests)
    assertEquals(listOf(milk, bread), loaded(vm))
    assertEquals(340, vm.uiState.value.estimatedCents)
  }

  @Test
  fun failedSearchCanBeEditedOrRetriedAndUnsearchableQueriesAskForMoreLetters() = runTest {
    val vm = viewModel(searchDebounceMillis = 0)
    advanceUntilIdle()
    products.result = ProductResult.Error(ProductError.NetworkError)
    vm.onQueryChange("oat")
    advanceUntilIdle()
    assertEquals(
        CatalogueContent.ProductsFailed(ProductError.NetworkError),
        vm.uiState.value.content,
    )
    assertTrue(vm.uiState.value.canSearch)

    products.result = ProductResult.Success(listOf(milk, bread))
    vm.retry()
    advanceUntilIdle()
    assertEquals(request("oat"), products.requests.last())
    assertEquals(listOf(milk), loaded(vm))

    products.result = ProductResult.Error(ProductError.InvalidQuery)
    vm.onQueryChange("a")
    advanceUntilIdle()
    assertEquals(CatalogueContent.UnsearchableQuery("a"), vm.uiState.value.content)
    assertFalse((vm.uiState.value.content as CatalogueContent.UnsearchableQuery).isTooLong)
    assertTrue(vm.uiState.value.canSearch)

    vm.onQueryChange("a".repeat(MAX_SEARCH_QUERY_LENGTH + 1))
    advanceUntilIdle()
    assertTrue((vm.uiState.value.content as CatalogueContent.UnsearchableQuery).isTooLong)
  }

  @Test
  fun newSearchDropsAPendingPageAndItsLoadingRows() = runTest {
    products.result = ProductResult.Success(migrosProducts(0..99))
    val vm = viewModel()
    advanceUntilIdle()
    val held = CompletableDeferred<Unit>()
    products.gate = held
    vm.loadMore()
    runCurrent()
    assertTrue(content(vm).isLoadingMore)

    vm.onQueryChange("product 99")
    assertFalse(content(vm).isLoadingMore) // The page is cancelled; no stuck loading rows.
    products.gate = null
    held.complete(Unit)
    advanceUntilIdle()
    assertEquals("product 99", content(vm).query)
    assertEquals(migrosProducts(99..99), loaded(vm))
  }

  @Test
  fun loadMoreAppendsTheNextPageOnceWithoutDuplicates() = runTest {
    // The second page repeats a product of the first one and is short, so it is the last.
    val catalogue = migrosProducts(0..49) + migrosProducts(0..0) + migrosProducts(50..50)
    products.result = ProductResult.Success(catalogue)
    val vm = viewModel()
    advanceUntilIdle()
    assertEquals(50, loaded(vm).size)
    assertTrue(content(vm).canLoadMore)

    vm.add("migros:005")
    vm.loadMore()
    vm.loadMore()
    assertTrue(content(vm).isLoadingMore)
    assertEquals(50, loaded(vm).size) // Loaded products stay visible.
    advanceUntilIdle()
    assertEquals(listOf(request(), request(cursor = "migros:049")), products.requests)
    assertEquals(migrosProducts(0..50), loaded(vm))
    assertNull(content(vm).nextCursor)

    vm.add("migros:050")
    vm.loadMore()
    advanceUntilIdle()
    assertEquals(2, products.requests.size)
    assertEquals(2L, vm.uiState.value.selectedCount)
    assertEquals(200, vm.uiState.value.estimatedCents)
  }

  @Test
  fun failedPageKeepsProductsRetriesItsCursorAndAFullLastPageEndsWithAnEmptyOne() = runTest {
    products.result = ProductResult.Success(migrosProducts(0..99))
    val vm = viewModel()
    advanceUntilIdle()
    products.pageError = ProductError.NetworkError
    vm.loadMore()
    advanceUntilIdle()
    assertEquals(50, loaded(vm).size)
    assertEquals(ProductError.NetworkError, content(vm).loadMoreError)

    products.pageError = null
    vm.loadMore()
    advanceUntilIdle()
    assertEquals(request(cursor = "migros:049"), products.requests.last())
    assertNull(content(vm).loadMoreError)
    assertEquals("migros:099", content(vm).nextCursor)
    vm.loadMore()
    advanceUntilIdle()
    assertEquals(migrosProducts(0..99), loaded(vm))
    assertFalse(content(vm).canLoadMore)
    assertEquals(4, products.requests.size)
  }

  @Test
  fun unexpectedRepositoryExceptionsBecomeUnknownErrorsAndRetryKeepsLoadedProducts() = runTest {
    products.result = ProductResult.Success(migrosProducts(0..60))
    val vm = viewModel()
    advanceUntilIdle()
    products.thrown = IllegalStateException("boom")
    vm.loadMore()
    advanceUntilIdle()
    assertEquals(ProductError.Unknown, content(vm).loadMoreError)
    // A failed later page is retried with loadMore(); retry() never drops the loaded products.
    vm.retry()
    advanceUntilIdle()
    assertEquals(migrosProducts(0..49), loaded(vm))
    assertEquals(ProductError.Unknown, content(vm).loadMoreError)
    assertEquals(2, products.requests.size)

    vm.onQueryChange("oat")
    advanceUntilIdle()
    assertEquals(CatalogueContent.ProductsFailed(ProductError.Unknown), vm.uiState.value.content)
  }

  @Test
  fun lateResultsOfAnOlderQueryOrPageNeverReplaceTheCurrentOnes() = runTest {
    products.result = ProductResult.Success(migrosProducts(0..99))
    val vm = viewModel(searchDebounceMillis = 0)
    advanceUntilIdle()
    val slow = CompletableDeferred<Unit>()
    products.gate = slow
    vm.loadMore()
    advanceUntilIdle()
    vm.onQueryChange("product 1") // Its first page is held too.
    advanceUntilIdle()
    products.gate = null
    vm.onQueryChange("product 99")
    advanceUntilIdle()
    slow.complete(Unit) // Both older requests return late.
    advanceUntilIdle()
    assertEquals(request("product 99"), products.requests.last())
    assertEquals(CatalogueContent.Loaded(migrosProducts(99..99), "product 99"), content(vm))
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
  fun twoTripsAtTheSameStoreNeverShareABasketOrLateResults() = runTest {
    val draft = OrderDraftState(migrosTrip.id)
    val first = viewModel(draft = draft)
    advanceUntilIdle()
    first.add(milk.id)
    first.increment(milk.id)
    val late = CompletableDeferred<Unit>()
    products.gate = late
    first.onQueryChange("bread") // Still pending when the requester switches trips.
    advanceUntilIdle()

    products.gate = null
    val second = viewModel(otherMigrosTrip.id, draft)
    advanceUntilIdle()
    assertEquals(request(), products.requests.last())
    assertTrue(second.uiState.value.items.isEmpty())
    first.add(bread.id) // The previous trip's screen can no longer edit the draft.
    second.add(bread.id)
    late.complete(Unit)
    advanceUntilIdle()
    assertEquals(otherMigrosTrip.id, draft.draft.value.tripId)
    assertEquals(listOf(OrderItem(bread.id, bread.name, 340, 1)), draft.draft.value.items)
    assertTrue(first.uiState.value.items.isEmpty())
    assertEquals(listOf(milk, bread), loaded(second))
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
