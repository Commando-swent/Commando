package com.android.sample.ui.catalogue

import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.android.sample.data.repository.FakeTripRepository
import com.android.sample.data.repository.ProductError
import com.android.sample.data.repository.ProductResult
import com.android.sample.model.FavouriteProducts
import com.android.sample.model.Product
import com.android.sample.model.Trip
import com.android.sample.ui.orders.OrderDraftState
import com.android.sample.ui.theme.SampleAppTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-rUS-w412dp-h917dp-mdpi")
class CatalogueFavouritesScreenTest {
  @get:Rule val compose = createComposeRule()
  private val milk = Product("migros:1", "migros", "Oat drink", 220)
  private val apple = Product("migros:2", "migros", "Apples", 300)
  private val products = StubProductRepository(ProductResult.Success(listOf(milk, apple)))
  private lateinit var viewModel: CatalogueViewModel

  private fun show(trip: Trip = catalogueTrip("trip-migros", "Migros EPFL")): OrderDraftState {
    val draft = OrderDraftState(trip.id)
    viewModel =
        CatalogueViewModel(
            trip.id,
            FakeTripRepository("owner", initialTrips = listOf(trip)),
            products,
            draft,
            searchDebounceMillis = 0,
        )
    compose.setContent {
      SampleAppTheme { CatalogueRoute(viewModel, onBack = {}, onContinue = {}) }
    }
    compose.waitForIdle()
    return draft
  }

  @Test
  fun heartsOptimisticallySaveAndOnlyRemovalOffersUndo() {
    show()
    compose.onNodeWithContentDescription("Add Apples to favourites").performClick()
    compose.onNodeWithText("Added to favourites").assertIsDisplayed()
    compose.onNodeWithText("Undo").assertDoesNotExist()
    compose.onNodeWithTag(CatalogueTestTags.TAB_FAVOURITES).assertTextContains("1")

    compose.onNodeWithContentDescription("Remove Apples from favourites").performClick()
    compose.onNodeWithText("Removed from favourites").assertIsDisplayed()
    compose.onNodeWithText("Undo").performClick()
    compose.waitForIdle()

    assertEquals(
        listOf(
            Triple("migros", apple.id, true),
            Triple("migros", apple.id, false),
            Triple("migros", apple.id, true),
        ),
        products.favouriteChanges,
    )
    compose.onNodeWithContentDescription("Remove Apples from favourites").assertIsDisplayed()
  }

  @Test
  fun failedChangesRollBackWithoutUndo() {
    products.favouriteResult = ProductResult.Error(ProductError.NetworkError)
    show()

    compose.onNodeWithContentDescription("Add Apples to favourites").performClick()
    compose.onNodeWithText("Couldn’t update your favourites. Try again.").assertIsDisplayed()
    compose.onNodeWithText("Undo").assertDoesNotExist()
    compose.onNodeWithContentDescription("Add Apples to favourites").assertIsDisplayed()
  }

  @Test
  fun failedLoadsRetryFromTheFavouritesTab() {
    products.favourites = ProductResult.Error(ProductError.NetworkError)
    show()
    compose.onNodeWithTag(CatalogueTestTags.TAB_FAVOURITES).performClick()
    compose.onNodeWithText("Couldn’t load your favourites").assertIsDisplayed()

    products.favourites = ProductResult.Success(FavouriteProducts(listOf(milk), emptyList()))
    compose.onNodeWithTag(CatalogueTestTags.MESSAGE_ACTION).performClick()
    compose.waitForIdle()
    compose.onNodeWithTag(CatalogueTestTags.row(milk.id)).assertIsDisplayed()
  }

  @Test
  fun favouritesReuseProductRowsAndTheSharedBasketWhileUnavailableItemsStayRemovable() {
    val yoghurt = Product("migros:7", "migros", "Yoghurt", 95)
    products.favourites =
        ProductResult.Success(FavouriteProducts(listOf(yoghurt), listOf("migros:missing")))
    val draft = show()
    compose.onNodeWithTag(CatalogueTestTags.TAB_FAVOURITES).performClick()
    compose.onNodeWithTag(CatalogueTestTags.TAB_FAVOURITES).assertIsSelected()
    compose.onNodeWithText("FAVOURITES · 1").assertIsDisplayed()
    compose.onNodeWithText("NOT AVAILABLE · 1").assertIsDisplayed()

    compose.onNodeWithTag(CatalogueTestTags.add(yoghurt.id)).performClick()
    compose.onNodeWithTag(CatalogueTestTags.TAB_CATALOGUE).performClick()
    compose.onNodeWithTag(CatalogueTestTags.TAB_FAVOURITES).performClick()
    compose.onNodeWithTag(CatalogueTestTags.quantity(yoghurt.id)).assertTextContains("1")
    assertEquals(1, draft.draft.value.items.single().quantity)

    compose
        .onNodeWithContentDescription("Remove Product no longer listed from favourites")
        .performClick()
    compose.onNodeWithTag(CatalogueTestTags.unavailable("migros:missing")).assertDoesNotExist()
  }

  @Test
  fun tabsAndHeartsExposeSelectionAnd48DpTargets() {
    show()
    compose.onNodeWithTag(CatalogueTestTags.TAB_CATALOGUE).assertIsSelected()
    compose.onNodeWithTag(CatalogueTestTags.TAB_FAVOURITES).performClick()
    compose.onNodeWithText("No favourites at Migros yet").assertIsDisplayed()
    compose.onNodeWithText("Browse catalogue").performClick()

    compose
        .onNodeWithTag(CatalogueTestTags.favourite(apple.id))
        .assertWidthIsEqualTo(48.dp)
        .assertHeightIsEqualTo(48.dp)
  }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fr-rCH-w412dp-h917dp-mdpi")
class CatalogueFavouritesFrenchScreenTest {
  @get:Rule val compose = createComposeRule()

  @Test
  fun favouritesAreLocalizedInFrench() {
    val milk = Product("coop:1", "coop", "Lait", 180)
    val products = StubProductRepository(ProductResult.Success(listOf(milk)))
    val trip = catalogueTrip("trip-coop", "Coop Lausanne")
    val viewModel =
        CatalogueViewModel(
            trip.id,
            FakeTripRepository("owner", initialTrips = listOf(trip)),
            products,
            OrderDraftState(trip.id),
            searchDebounceMillis = 0,
        )
    compose.setContent {
      SampleAppTheme { CatalogueRoute(viewModel, onBack = {}, onContinue = {}) }
    }

    compose.onNodeWithContentDescription("Ajouter Lait aux favoris").assertIsDisplayed()
    compose.onNodeWithTag(CatalogueTestTags.TAB_FAVOURITES).performClick()
    compose.onNodeWithText("Pas encore de favoris chez Coop").assertIsDisplayed()
  }
}
