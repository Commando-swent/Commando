package com.android.sample.ui.catalogue

import androidx.lifecycle.viewModelScope
import com.android.sample.MainDispatcherRule
import com.android.sample.data.repository.FakeTripRepository
import com.android.sample.data.repository.ProductError
import com.android.sample.data.repository.ProductResult
import com.android.sample.model.FavouriteProducts
import com.android.sample.model.Product
import com.android.sample.ui.orders.OrderDraftState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CatalogueFavouritesViewModelTest {
  private val migrosTrip = catalogueTrip("trip-migros", "Migros EPFL")
  private val coopTrip = catalogueTrip("trip-coop", "Coop Lausanne")
  private val volgTrip = catalogueTrip("trip-volg", "Volg Ecublens")
  private val milk = Product("migros:1", "migros", "Oat drink", 220)
  private val apple = Product("migros:2", "migros", "Apples", 300)
  private val coopMilk = Product("coop:1", "coop", "Coop milk", 180)
  private val trips =
      FakeTripRepository("owner", initialTrips = listOf(migrosTrip, coopTrip, volgTrip))
  private val products = StubProductRepository(ProductResult.Success(listOf(milk, apple, coopMilk)))

  @get:Rule val mainDispatcherRule = MainDispatcherRule()

  private fun viewModel(
      tripId: String = migrosTrip.id,
      draft: OrderDraftState = OrderDraftState(tripId),
  ) = CatalogueViewModel(tripId, trips, products, draft, searchDebounceMillis = 0)

  private fun CatalogueViewModel.favourites() = uiState.value.favourites as FavouritesContent.Loaded

  @Test
  fun favouritesLoadForTheTripsStoreAndUnsupportedStoresHideTheTab() = runTest {
    products.favourites =
        ProductResult.Success(
            FavouriteProducts(
                listOf(milk, coopMilk),
                listOf("migros:missing", "coop:missing"),
            )
        )
    val migros = viewModel()
    advanceUntilIdle()
    assertEquals(listOf(milk), migros.favourites().products)
    assertEquals(listOf("migros:missing"), migros.favourites().unavailableProductIds)
    assertEquals(listOf("migros"), products.favouriteRequests)

    products.favourites = ProductResult.Success(FavouriteProducts(listOf(coopMilk), emptyList()))
    val coop = viewModel(coopTrip.id)
    advanceUntilIdle()
    assertEquals(listOf(coopMilk), coop.favourites().products)
    assertEquals(listOf("migros", "coop"), products.favouriteRequests)

    val volg = viewModel(volgTrip.id)
    advanceUntilIdle()
    assertEquals(FavouritesContent.Unsupported, volg.uiState.value.favourites)
    assertFalse(volg.uiState.value.showsFavourites)
    assertEquals(2, products.favouriteRequests.size)
  }

  @Test
  fun aFailedFavouriteLoadCanBeRetriedOnce() = runTest {
    products.favourites = ProductResult.Error(ProductError.NetworkError)
    val vm = viewModel()
    advanceUntilIdle()
    assertEquals(FavouritesContent.Failed(ProductError.NetworkError), vm.uiState.value.favourites)

    products.favourites = ProductResult.Success(FavouriteProducts(listOf(milk), emptyList()))
    vm.retryFavourites()
    vm.retryFavourites()
    advanceUntilIdle()
    assertEquals(listOf(milk), vm.favourites().products)
    assertEquals(listOf("migros", "migros"), products.favouriteRequests)
  }

  @Test
  fun thrownRepositoryErrorsFailTheLoadAndRollBackChanges() = runTest {
    products.favouriteThrown = IllegalStateException("offline")
    val vm = viewModel()
    advanceUntilIdle()
    assertEquals(FavouritesContent.Failed(ProductError.Unknown), vm.uiState.value.favourites)
    vm.add(milk.id) // The basket still works from the catalogue.
    runCurrent()
    assertEquals(1, vm.uiState.value.quantityOf(milk.id))

    products.favouriteThrown = null
    products.favourites = ProductResult.Success(FavouriteProducts(listOf(milk), emptyList()))
    vm.retryFavourites()
    advanceUntilIdle()
    vm.retryFavourites() // Loaded: nothing to retry.
    assertEquals(listOf("migros", "migros"), products.favouriteRequests)

    products.favouriteThrown = IllegalStateException("offline")
    vm.toggleFavourite(milk.id)
    advanceUntilIdle()
    assertEquals(listOf(milk), vm.favourites().products)
    assertEquals(FavouriteNotice.Failed(milk.id), vm.uiState.value.favouriteNotice)
  }

  @Test
  fun cancelledFavouriteReadsAndWritesAreNotReportedAsFailures() = runTest {
    products.favouriteGate = CompletableDeferred()
    val loading = viewModel()
    runCurrent()
    loading.viewModelScope.cancel()
    products.favouriteGate!!.complete(Unit)
    advanceUntilIdle()
    assertEquals(FavouritesContent.Loading, loading.uiState.value.favourites)

    products.favouriteGate = null
    val saving = viewModel()
    advanceUntilIdle()
    products.favouriteGate = CompletableDeferred()
    saving.toggleFavourite(milk.id)
    runCurrent()
    saving.viewModelScope.cancel()
    products.favouriteGate!!.complete(Unit)
    advanceUntilIdle()
    assertNull(saving.uiState.value.favouriteNotice)
    assertEquals(setOf(milk.id), saving.uiState.value.pendingFavourites)
  }

  @Test
  fun aStoreRejectedByTheFavouritesReadHidesTheTabAndIgnoresHearts() = runTest {
    products.favourites = ProductResult.Error(ProductError.InvalidStore)
    val vm = viewModel()
    advanceUntilIdle()
    assertEquals(FavouritesContent.Unsupported, vm.uiState.value.favourites)

    vm.selectTab(CatalogueTab.FAVOURITES)
    vm.toggleFavourite(milk.id)
    vm.retryFavourites()
    advanceUntilIdle()
    assertEquals(CatalogueTab.CATALOGUE, vm.uiState.value.tab)
    assertEquals(emptyList<Triple<String, String, Boolean>>(), products.favouriteChanges)
    assertEquals(listOf("migros"), products.favouriteRequests)
  }

  @Test
  fun undoIgnoresStaleNoticesAndRollsBackWhenItFails() = runTest {
    products.favourites = ProductResult.Success(FavouriteProducts(listOf(milk), emptyList()))
    val vm = viewModel()
    advanceUntilIdle()
    vm.toggleFavourite("migros:unknown") // Neither loaded nor favourite: nothing to save.
    vm.toggleFavourite(milk.id)
    advanceUntilIdle()
    val removed = FavouriteNotice.Removed(milk.id)
    assertEquals(removed, vm.uiState.value.favouriteNotice)

    vm.undoFavourite(FavouriteNotice.Added(milk.id))
    vm.undoFavourite(FavouriteNotice.Removed(apple.id))
    advanceUntilIdle()
    assertEquals(listOf(Triple("migros", milk.id, false)), products.favouriteChanges)

    products.favouriteResult = ProductResult.Error(ProductError.NetworkError)
    vm.undoFavourite(removed)
    advanceUntilIdle()
    assertEquals(emptyList<Product>(), vm.favourites().products)
    val failed = FavouriteNotice.Failed(milk.id)
    assertEquals(failed, vm.uiState.value.favouriteNotice)

    vm.dismissFavouriteNotice(removed)
    assertEquals(failed, vm.uiState.value.favouriteNotice)
    vm.dismissFavouriteNotice(failed)
    assertNull(vm.uiState.value.favouriteNotice)
  }

  @Test
  fun optimisticChangesIgnoreRepeatedTapsAndSaveDifferentProductsIndependently() = runTest {
    val vm = viewModel()
    advanceUntilIdle()
    products.favouriteGate = CompletableDeferred()

    vm.toggleFavourite(apple.id)
    vm.toggleFavourite(apple.id)
    vm.toggleFavourite(milk.id)
    assertEquals(setOf(apple.id, milk.id), vm.uiState.value.pendingFavourites)
    assertEquals(listOf(milk, apple), vm.favourites().products)
    runCurrent()
    assertEquals(
        listOf(Triple("migros", apple.id, true), Triple("migros", milk.id, true)),
        products.favouriteChanges,
    )

    products.favouriteGate!!.complete(Unit)
    advanceUntilIdle()
    assertEquals(emptySet<String>(), vm.uiState.value.pendingFavourites)
    assertEquals(listOf(milk, apple), vm.favourites().products)
  }

  @Test
  fun failedAddsAndRemovalsRollBackAndOnlyReportFailure() = runTest {
    products.favourites = ProductResult.Success(FavouriteProducts(listOf(milk), emptyList()))
    val vm = viewModel()
    advanceUntilIdle()
    products.favouriteResult = ProductResult.Error(ProductError.NetworkError)

    vm.toggleFavourite(milk.id)
    advanceUntilIdle()
    assertEquals(listOf(milk), vm.favourites().products)
    assertEquals(FavouriteNotice.Failed(milk.id), vm.uiState.value.favouriteNotice)

    vm.toggleFavourite(apple.id)
    advanceUntilIdle()
    assertEquals(listOf(milk), vm.favourites().products)
    assertEquals(FavouriteNotice.Failed(apple.id), vm.uiState.value.favouriteNotice)
  }

  @Test
  fun aFailedRemovalKeepsItsNoticeWhenAnOverlappingAddSucceeds() = runTest {
    products.favourites = ProductResult.Success(FavouriteProducts(listOf(milk), emptyList()))
    val vm = viewModel()
    advanceUntilIdle()
    products.favouriteGate = CompletableDeferred()
    products.favouriteResults[milk.id] = ProductResult.Error(ProductError.NetworkError)

    vm.toggleFavourite(milk.id) // Removal: fails.
    vm.toggleFavourite(apple.id) // Add: succeeds after the failure, before the UI shows it.
    runCurrent()
    products.favouriteGate!!.complete(Unit)
    advanceUntilIdle()
    assertEquals(listOf(milk, apple), vm.favourites().products)
    val failed = FavouriteNotice.Failed(milk.id)
    assertEquals(failed, vm.uiState.value.favouriteNotice)

    products.favouriteGate = null
    vm.toggleFavourite(apple.id) // A later change still keeps the unshown failure.
    advanceUntilIdle()
    assertEquals(failed, vm.uiState.value.favouriteNotice)
    vm.dismissFavouriteNotice(failed)
    assertNull(vm.uiState.value.favouriteNotice)
  }

  @Test
  fun addingFromEachTabUsesThePriceThatTabShows() = runTest {
    val favouriteMilk = milk.copy(priceCents = 220)
    val catalogueMilk = milk.copy(priceCents = 300)
    products.result = ProductResult.Success(listOf(catalogueMilk))
    products.favourites =
        ProductResult.Success(FavouriteProducts(listOf(favouriteMilk), emptyList()))
    val draft = OrderDraftState(migrosTrip.id)
    val vm = viewModel(draft = draft)
    advanceUntilIdle()

    vm.selectTab(CatalogueTab.FAVOURITES)
    vm.add(milk.id)
    assertEquals(220, draft.draft.value.items.single().unitPriceCents)

    vm.decrement(milk.id)
    vm.selectTab(CatalogueTab.CATALOGUE)
    vm.add(milk.id)
    assertEquals(300, draft.draft.value.items.single().unitPriceCents)
  }

  @Test
  fun successfulRemovalsCanUndoAvailableAndUnavailableFavourites() = runTest {
    products.favourites =
        ProductResult.Success(FavouriteProducts(listOf(milk), listOf("migros:missing")))
    val vm = viewModel()
    advanceUntilIdle()

    vm.toggleFavourite(milk.id)
    advanceUntilIdle()
    val milkNotice = vm.uiState.value.favouriteNotice!!
    vm.undoFavourite(milkNotice)
    advanceUntilIdle()
    assertEquals(listOf(milk), vm.favourites().products)
    assertNull(vm.uiState.value.favouriteNotice)

    vm.toggleFavourite("migros:missing")
    advanceUntilIdle()
    val missingNotice = vm.uiState.value.favouriteNotice!!
    vm.undoFavourite(missingNotice)
    advanceUntilIdle()
    assertEquals(listOf("migros:missing"), vm.favourites().unavailableProductIds)
    assertEquals(
        listOf(
            Triple("migros", milk.id, false),
            Triple("migros", milk.id, true),
            Triple("migros", "migros:missing", false),
            Triple("migros", "migros:missing", true),
        ),
        products.favouriteChanges,
    )
  }

  @Test
  fun favouritesUseTheSharedBasketAcrossTabsAndCatalogueFilters() = runTest {
    val yoghurt = Product("migros:9", "migros", "Yoghurt", 95)
    products.favourites = ProductResult.Success(FavouriteProducts(listOf(yoghurt), emptyList()))
    val draft = OrderDraftState(migrosTrip.id)
    val vm = viewModel(draft = draft)
    advanceUntilIdle()

    vm.selectTab(CatalogueTab.FAVOURITES)
    vm.add(yoghurt.id)
    vm.increment(yoghurt.id)
    vm.selectTab(CatalogueTab.CATALOGUE)
    vm.selectCategory("Dairy")
    advanceUntilIdle()

    assertTrue(vm.uiState.value.isFavourite(yoghurt.id))
    assertEquals(2, vm.uiState.value.quantityOf(yoghurt.id))
    assertEquals(2, draft.draft.value.items.single().quantity)
  }
}
