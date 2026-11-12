package com.android.sample.ui.catalogue

// AI assistance: OpenAI Codex and Claude Code.
import com.android.sample.MainDispatcherRule
import com.android.sample.data.repository.FakeTripRepository
import com.android.sample.data.repository.ProductError
import com.android.sample.data.repository.ProductResult
import com.android.sample.model.Product
import com.android.sample.ui.orders.OrderDraftState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/** Category metadata, filtering, cancellation and pagination for Migros and Coop trips. */
@OptIn(ExperimentalCoroutinesApi::class)
class CatalogueCategoryViewModelTest {
  @get:Rule val mainDispatcherRule = MainDispatcherRule()
  private val migrosTrip = catalogueTrip("trip-migros", "Migros EPFL")
  private val coopTrip = catalogueTrip("trip-coop", "Coop Lausanne")
  private val trips = FakeTripRepository("owner", initialTrips = listOf(migrosTrip, coopTrip))
  private val dairy = "Produits laitiers, œufs & plats préparés frais"
  private val fruit = "Fruits & légumes"
  private val milk = Product("migros:1", "migros", "Oat drink", 220, category = dairy)
  private val apple = Product("migros:2", "migros", "Apples", 300, category = fruit)
  private val coopMilk = Product("coop:1", "coop", "Coop milk", 180, category = dairy)
  private val products = StubProductRepository(ProductResult.Success(listOf(milk, apple, coopMilk)))

  private fun viewModel(
      tripId: String = migrosTrip.id,
      draft: OrderDraftState = OrderDraftState(tripId),
  ) = CatalogueViewModel(tripId, trips, products, draft, searchDebounceMillis = 0)

  private fun CatalogueViewModel.loaded() = uiState.value.content as CatalogueContent.Loaded

  @Test
  fun migrosAndCoopTripsLoadTheirOwnStoredCategoriesOnce() = runTest {
    products.categories = ProductResult.Success(listOf(fruit, dairy, OTHER_CATEGORY))
    val migros = viewModel()
    advanceUntilIdle()
    assertEquals(listOf(fruit, dairy, OTHER_CATEGORY), migros.uiState.value.categories)

    val coop = viewModel(coopTrip.id)
    advanceUntilIdle()
    assertEquals(listOf("migros", "coop"), products.categoryRequests)
    assertEquals(listOf(fruit, dairy, OTHER_CATEGORY), coop.uiState.value.categories)

    migros.onQueryChange("oat")
    advanceUntilIdle()
    assertEquals(2, products.categoryRequests.size)
  }

  @Test
  fun failedCategoriesLeaveTheCatalogueUsableAndRetryAfterAWorkingSearch() = runTest {
    products.categories = ProductResult.Error(ProductError.NetworkError)
    val viewModel = viewModel()
    advanceUntilIdle()
    assertEquals(emptyList<String>(), viewModel.uiState.value.categories)
    assertEquals(listOf(milk, apple), viewModel.loaded().products)

    products.categories = ProductResult.Success(listOf(fruit))
    viewModel.onQueryChange("apples")
    advanceUntilIdle()
    assertEquals(listOf("migros", "migros"), products.categoryRequests)
    assertEquals(listOf(fruit), viewModel.uiState.value.categories)
  }

  @Test
  fun retryAfterAnOfflineStartLoadsTheCatalogueAndCategories() = runTest {
    products.result = ProductResult.Error(ProductError.NetworkError)
    products.categories = ProductResult.Error(ProductError.NetworkError)
    val viewModel = viewModel()
    advanceUntilIdle()
    assertEquals(
        CatalogueContent.ProductsFailed(ProductError.NetworkError),
        viewModel.uiState.value.content,
    )

    products.result = ProductResult.Success(listOf(milk, apple))
    products.categories = ProductResult.Success(listOf(dairy, fruit))
    viewModel.retry()
    advanceUntilIdle()
    assertEquals(listOf(milk, apple), viewModel.loaded().products)
    assertEquals(listOf(dairy, fruit), viewModel.uiState.value.categories)
  }

  @Test
  fun onlyTheLastOfRapidCategoryAndSearchChangesIsShown() = runTest {
    val viewModel = viewModel()
    advanceUntilIdle()
    products.gate = CompletableDeferred()
    viewModel.selectCategory(dairy)
    runCurrent()
    viewModel.selectCategory(fruit)
    runCurrent()
    viewModel.onQueryChange("app")
    runCurrent()
    products.gate!!.complete(Unit)
    advanceUntilIdle()

    assertEquals(
        StubProductRepository.Request("migros", "app", null, 50, fruit),
        products.requests.last(),
    )
    assertEquals(fruit, viewModel.loaded().category)
    assertEquals(listOf(apple), viewModel.loaded().products)
  }

  @Test
  fun selectedCategoryStaysOnPaginationAndSearchUntilCleared() = runTest {
    products.result =
        ProductResult.Success(migrosProducts(0..49).map { it.copy(category = dairy) } + milk)
    val viewModel = viewModel()
    advanceUntilIdle()

    viewModel.selectCategory(dairy)
    advanceUntilIdle()
    assertEquals(dairy, products.requests.last().category)
    assertEquals(50, viewModel.loaded().products.size)

    viewModel.loadMore()
    advanceUntilIdle()
    assertEquals(dairy, products.requests.last().category)
    assertEquals("migros:049", products.requests.last().cursor)
    viewModel.onQueryChange("oat")
    advanceUntilIdle()
    assertEquals(
        StubProductRepository.Request("migros", "oat", null, 50, dairy),
        products.requests.last(),
    )
    assertEquals(listOf(milk), viewModel.loaded().products)

    val sent = products.requests.size
    viewModel.selectCategory(dairy)
    advanceUntilIdle()
    assertEquals(sent, products.requests.size)
    viewModel.selectCategory(null)
    advanceUntilIdle()
    assertEquals(
        StubProductRepository.Request("migros", "oat", null, 50, null),
        products.requests.last(),
    )
    assertNull(viewModel.loaded().category)
  }

  @Test
  fun coopCategoryResultsNeverShowMigrosProducts() = runTest {
    products.categories = ProductResult.Success(listOf(dairy))
    val viewModel = viewModel(coopTrip.id)
    advanceUntilIdle()
    viewModel.selectCategory(dairy)
    advanceUntilIdle()

    assertEquals(
        StubProductRepository.Request("coop", "", null, 50, dairy),
        products.requests.last(),
    )
    // The stub ignores the store; the ViewModel still rejects the Migros product.
    assertEquals(listOf(coopMilk), viewModel.loaded().products)
  }

  @Test
  fun basketQuantitiesSurviveCategoryFilteringAndSearchingAllCategories() = runTest {
    val draft = OrderDraftState(migrosTrip.id)
    val viewModel = viewModel(draft = draft)
    advanceUntilIdle()
    viewModel.add(milk.id)
    viewModel.increment(milk.id)
    viewModel.increment(milk.id)
    viewModel.add(apple.id)
    viewModel.increment(apple.id)
    val basket = draft.draft.value.items
    assertEquals(listOf(milk.id to 3, apple.id to 2), basket.map { it.productId to it.quantity })

    viewModel.selectCategory(fruit)
    advanceUntilIdle()
    assertEquals(listOf(apple), viewModel.loaded().products)

    viewModel.onQueryChange("oat")
    advanceUntilIdle()
    assertEquals(emptyList<Product>(), viewModel.loaded().products)
    assertEquals(fruit, viewModel.loaded().category)

    // "Search all categories" clears only the category filter.
    viewModel.selectCategory(null)
    advanceUntilIdle()
    assertEquals(
        StubProductRepository.Request("migros", "oat", null, 50, null),
        products.requests.last(),
    )
    assertNull(viewModel.loaded().category)
    assertEquals(listOf(milk), viewModel.loaded().products)
    assertEquals(basket, draft.draft.value.items)
    assertEquals(3, viewModel.uiState.value.quantityOf(milk.id))
    assertEquals(2, viewModel.uiState.value.quantityOf(apple.id))
  }
}
