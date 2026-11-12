package com.android.sample.ui.catalogue

// AI assistance: OpenAI Codex and Claude Code.
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.android.sample.data.repository.FakeTripRepository
import com.android.sample.data.repository.ProductResult
import com.android.sample.model.Product
import com.android.sample.model.Trip
import com.android.sample.ui.orders.OrderDraftState
import com.android.sample.ui.theme.SampleAppTheme
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-rUS-w412dp-h917dp-mdpi")
class CatalogueCategoryScreenTest {
  @get:Rule val compose = createComposeRule()
  private val dairy = "Produits laitiers, œufs & plats préparés frais"
  private val fruit = "Fruits & légumes"
  private val milk = Product("migros:1", "migros", "Oat drink", 220, category = dairy)
  private val apple = Product("migros:2", "migros", "Apples", 300, category = fruit)
  private val card = Product("migros:3", "migros", "Birthday card", 490, category = OTHER_CATEGORY)
  private val products =
      StubProductRepository(ProductResult.Success(listOf(milk, apple, card))).apply {
        categories = ProductResult.Success(listOf(OTHER_CATEGORY, dairy, "Rayon inconnu", fruit))
      }
  private lateinit var viewModel: CatalogueViewModel

  private fun show(trip: Trip = catalogueTrip("trip-1", "Migros EPFL")) {
    viewModel =
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
    compose.waitForIdle()
  }

  @Test
  fun categoryChipsShowLocalizedLabelsAndFilterOnTheStoredValue() {
    show()
    compose.onNodeWithTag(CatalogueTestTags.CATEGORY_ALL).assertIsSelected()
    compose.onNodeWithTag(CatalogueTestTags.category(fruit)).assertTextContains("Fruit & veg")
    // Unknown metadata remains browsable until the next app release adds a label.
    compose.onNodeWithTag(CatalogueTestTags.category("Rayon inconnu")).assertIsDisplayed()

    // The 36 dp chip keeps a 48 dp touch target: a tap 5 dp above it still selects it.
    compose.onNodeWithTag(CatalogueTestTags.category(fruit)).performTouchInput {
      click(Offset(centerX, -5.dp.toPx()))
    }
    compose.waitForIdle()
    assertEquals(fruit, products.requests.last().category)
    compose.onNodeWithText("FRUIT & VEG · 1").assertIsDisplayed()
    compose.onNodeWithTag(CatalogueTestTags.row(apple.id)).assertIsDisplayed()
    compose.onNodeWithTag(CatalogueTestTags.row(milk.id)).assertDoesNotExist()

    compose.onNodeWithTag(CatalogueTestTags.category(fruit)).performClick()
    compose.waitForIdle()
    assertEquals(null, products.requests.last().category)
    compose.onNodeWithTag(CatalogueTestTags.row(milk.id)).assertIsDisplayed()
  }

  @Test
  fun otherExplainsItsProductsAndAnEmptyCategoryOffersEveryCategory() {
    show()
    compose.runOnIdle {
      viewModel.add(card.id)
      viewModel.increment(card.id)
    }
    compose.onNodeWithTag(CatalogueTestTags.category(OTHER_CATEGORY)).performClick()
    compose.waitForIdle()
    compose.onNodeWithText("OTHER · 1").assertIsDisplayed()
    compose.onNodeWithText("They also stay under All", substring = true).assertIsDisplayed()

    compose.onNodeWithTag(CatalogueTestTags.SEARCH).performTextReplacement("chocolat")
    compose.waitForIdle()
    compose.onNodeWithText("No Other products match “chocolat”").assertIsDisplayed()
    val layouts = mutableListOf<TextLayoutResult>()
    compose.onNodeWithText("Search all categories").performSemanticsAction(
        SemanticsActions.GetTextLayoutResult
    ) {
      it(layouts)
    }
    assertEquals(1, layouts.single().lineCount)
    compose.onNodeWithText("Search all categories").performClick()
    compose.waitForIdle()
    assertEquals(
        StubProductRepository.Request("migros", "chocolat", null, 50, null),
        products.requests.last(),
    )
    // Widening the search keeps the basket, even for products no longer listed.
    assertEquals(2, viewModel.uiState.value.quantityOf(card.id))
  }

  @Test
  fun theChipRowScrollsToTheSelectedCategory() {
    products.categories = ProductResult.Success(ALL_DEPARTMENTS + OTHER_CATEGORY)
    show()
    compose.onNodeWithTag(CatalogueTestTags.category(OTHER_CATEGORY)).assertIsNotDisplayed()

    compose.runOnIdle { viewModel.selectCategory(OTHER_CATEGORY) }
    compose.waitForIdle()
    compose.onNodeWithTag(CatalogueTestTags.category(OTHER_CATEGORY)).assertIsDisplayed()
    compose.onNodeWithTag(CatalogueTestTags.CATEGORY_ALL).assertIsNotDisplayed()

    compose.runOnIdle { viewModel.selectCategory(null) }
    compose.waitForIdle()
    compose.onNodeWithTag(CatalogueTestTags.CATEGORY_ALL).assertIsDisplayed()
  }

  @Test
  fun unsupportedStoresShowNoCategoryChips() {
    show(catalogueTrip("trip-volg", "Volg Ecublens"))
    compose.onNodeWithTag(CatalogueTestTags.CATEGORY_CHIPS).assertDoesNotExist()
  }

  @Test
  fun categoriesFollowFigmaThenUnknownByLabelWithOtherLast() {
    val garden = "Jardin & extérieur"
    assertEquals(
        listOf(fruit, dairy, garden, "Aardvark", "Zebra", OTHER_CATEGORY),
        orderedCategories(
            listOf(OTHER_CATEGORY, "Zebra", garden, dairy, "Aardvark", fruit),
            Locale.ENGLISH,
        ) {
          it
        },
    )
    ALL_DEPARTMENTS.forEach { assertNotNull(it, categoryLabelRes(it)) }
    assertNotNull(categoryLabelRes(OTHER_CATEGORY))
  }
}

private val ALL_DEPARTMENTS =
    listOf(
        "Fruits & légumes",
        "Produits laitiers, œufs & plats préparés frais",
        "Boulangerie, pâtisserie & petit déjeuner",
        "Viandes & poissons",
        "Pâtes, condiments & conserves",
        "Snacks & confiseries",
        "Boissons, café & thé",
        "Vins, bières & spiritueux",
        "Surgelés",
        "Bébé et enfant",
        "Animaux",
        "Ménage & nettoyage",
        "Beauté & santé",
        "Maison & cuisine",
        "Mode & accessoires",
        "Papeterie, bureau & accessoires électroniques",
        "Jardin & extérieur",
        "Jeux, loisirs & cadeaux",
    )

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fr-rCH-w412dp-h917dp-mdpi")
class CatalogueCategoryFrenchScreenTest {
  @get:Rule val compose = createComposeRule()

  @Test
  fun coopCategoriesAreShownInFrench() {
    val dairy = "Produits laitiers, œufs & plats préparés frais"
    val products =
        StubProductRepository(
                ProductResult.Success(
                    listOf(Product("coop:1", "coop", "Lait", 180, category = dairy))
                )
            )
            .apply { categories = ProductResult.Success(listOf(dairy, OTHER_CATEGORY)) }
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

    compose.onNodeWithTag(CatalogueTestTags.CATEGORY_ALL).assertTextContains("Tout")
    compose.onNodeWithTag(CatalogueTestTags.category(dairy)).assertTextContains("Laitages & œufs")
    compose.onNodeWithTag(CatalogueTestTags.category(OTHER_CATEGORY)).assertTextContains("Autres")
  }
}
