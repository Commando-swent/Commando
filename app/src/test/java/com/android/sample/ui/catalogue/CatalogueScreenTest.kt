package com.android.sample.ui.catalogue

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.android.sample.data.repository.FakeTripRepository
import com.android.sample.data.repository.ProductError
import com.android.sample.data.repository.ProductResult
import com.android.sample.model.MAX_ORDER_PRODUCTS
import com.android.sample.model.OrderItem
import com.android.sample.model.Product
import com.android.sample.model.Trip
import com.android.sample.ui.components.formatTripTime
import com.android.sample.ui.orders.OrderDraftState
import com.android.sample.ui.theme.SampleAppTheme
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-rUS-w412dp-h917dp-mdpi")
class CatalogueScreenTest {
  @get:Rule val compose = createComposeRule()
  private val trip = catalogueTrip("trip-1", "Migros EPFL")
  private val milk = Product("migros:1", "migros", "Oat drink", 220)
  private val bread = Product("migros:2", "migros", "Wholemeal bread", 340)
  private val products = StubProductRepository(ProductResult.Success(listOf(milk, bread)))
  private val draft = OrderDraftState(trip.id)
  private val continued = mutableListOf<Trip>()
  private var backs = 0

  private fun show(storeTrip: Trip = trip, fontScale: Float = 1f) {
    val trips = FakeTripRepository("owner", initialTrips = listOf(storeTrip))
    val vm = CatalogueViewModel(storeTrip.id, trips, products, draft, searchDebounceMillis = 0)
    compose.setContent {
      CompositionLocalProvider(
          LocalDensity provides Density(LocalDensity.current.density, fontScale)
      ) {
        SampleAppTheme { CatalogueRoute(vm, onBack = { backs++ }, onContinue = continued::add) }
      }
    }
  }

  @Test
  fun sixDifferentProductsExplainTheLimitAndRemovingOneRestoresContinue() {
    val selection =
        List(MAX_ORDER_PRODUCTS + 1) { Product("migros:$it", "migros", "Product $it", 100) }
    products.result = ProductResult.Success(selection)
    show()
    selection.forEach { product ->
      compose
          .onNodeWithTag(CatalogueTestTags.PRODUCT_LIST)
          .performScrollToNode(hasTestTag(CatalogueTestTags.row(product.id)))
      compose.onNodeWithTag(CatalogueTestTags.add(product.id)).performClick()
    }
    val message = "Maximum distinct products per order: 5. Remove a product to continue."
    compose.onNodeWithText(message).assertIsDisplayed()
    compose.onNodeWithTag(CatalogueTestTags.CONTINUE).assertIsNotEnabled().performClick()
    compose.runOnIdle {
      assertEquals(6, draft.draft.value.items.size)
      assertTrue(continued.isEmpty())
    }
    compose
        .onNodeWithTag(CatalogueTestTags.PRODUCT_LIST)
        .performScrollToNode(hasTestTag(CatalogueTestTags.row(selection.last().id)))
    compose.onNodeWithContentDescription("Remove Product 5 from order").performClick()
    compose.onNodeWithText(message).assertDoesNotExist()
    compose.onNodeWithTag(CatalogueTestTags.CONTINUE).assertIsEnabled().performClick()
    compose.runOnIdle { assertEquals(listOf(trip), continued) }
  }

  @Test
  fun cartStepperAndRemoveEditTheDraftAndDriveContinue() {
    show()
    compose.onNodeWithText("ALL PRODUCTS · 2").assertIsDisplayed()
    compose.onNodeWithTag(CatalogueTestTags.CONTINUE).assertIsNotEnabled()

    compose.onNodeWithContentDescription("Add Oat drink to order").performClick()
    compose.onNodeWithContentDescription("Remove Oat drink from order").assertIsDisplayed()
    compose.onNodeWithTag(CatalogueTestTags.increase(milk.id)).performClick()
    compose.onNodeWithTag(CatalogueTestTags.SUMMARY).assertTextContains("2 items")
    compose.onNodeWithTag(CatalogueTestTags.SUMMARY).assertTextContains("Est. CHF 4.40")
    compose.runOnIdle {
      assertEquals(listOf(OrderItem(milk.id, "Oat drink", 220, 2)), draft.draft.value.items)
    }

    compose.onNodeWithTag(CatalogueTestTags.CONTINUE).assertIsEnabled().performClick()
    compose.runOnIdle { assertEquals(listOf(trip), continued) }

    compose.onNodeWithContentDescription("Decrease Oat drink quantity").performClick()
    compose.onNodeWithContentDescription("Remove Oat drink from order").performClick()
    compose.onNodeWithContentDescription("Add Oat drink to order").assertIsDisplayed()
    compose.onNodeWithText("Select items to continue").assertIsNotEnabled()
    compose.runOnIdle { assertTrue(draft.draft.value.items.isEmpty()) }
  }

  @Test
  fun searchShowsResultsAndEmptyStateWhileKeepingSelection() {
    show()
    compose.onNodeWithContentDescription("Search products").assertIsDisplayed()
    compose.onNodeWithTag(CatalogueTestTags.add(bread.id)).performClick()
    compose.onNodeWithTag(CatalogueTestTags.SEARCH).performTextReplacement("oat")
    compose.onNodeWithText("1 RESULT FOR “OAT”").assertIsDisplayed()
    compose.onNodeWithTag(CatalogueTestTags.row(bread.id)).assertDoesNotExist()

    compose.onNodeWithTag(CatalogueTestTags.SEARCH).performTextReplacement("chocolat")
    compose.onNodeWithText("No products match “chocolat”").assertIsDisplayed()
    compose.onNodeWithTag(CatalogueTestTags.SUMMARY).assertTextContains("1 item")
    compose.onNodeWithText("Clear search").performClick()
    compose.onNodeWithTag(CatalogueTestTags.quantity(bread.id)).assertTextContains("1")

    compose.onNodeWithTag(CatalogueTestTags.SEARCH).performTextReplacement("bread")
    compose.onNodeWithTag(CatalogueTestTags.CLEAR_QUERY).performClick()
    compose.onNodeWithText("ALL PRODUCTS · 2").assertIsDisplayed()
    compose.onNodeWithTag(CatalogueTestTags.CLEAR_QUERY).assertDoesNotExist()
  }

  @Test
  fun scrollingToTheEndLoadsEachPageOnceAndAFailedPageWaitsForRetry() {
    products.result = ProductResult.Success(migrosProducts(0..120))
    products.pageError = ProductError.NetworkError
    show()
    val list = compose.onNodeWithTag(CatalogueTestTags.PRODUCT_LIST)
    compose.onNodeWithText("ALL PRODUCTS · 50+").assertIsDisplayed()
    assertEquals(1, products.requests.size)
    list.performScrollToNode(hasTestTag(CatalogueTestTags.add("migros:049")))
    compose.onNodeWithTag(CatalogueTestTags.add("migros:049")).performClick()

    // Reaching the end asked for page 2 once; scrolling back and forth does not retry it.
    list.performScrollToNode(hasTestTag(CatalogueTestTags.LOAD_MORE))
    compose
        .onNodeWithText("Couldn’t load more products.")
        .assertIsDisplayed()
        .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
    list.performScrollToNode(hasTestTag(CatalogueTestTags.row("migros:000")))
    list.performScrollToNode(hasTestTag(CatalogueTestTags.LOAD_MORE))
    assertEquals(listOf(null, "migros:049"), products.requests.map { it.cursor })
    compose.onNodeWithTag(CatalogueTestTags.quantity("migros:049")).assertTextContains("1")
    products.pageError = null
    compose.onNodeWithTag(CatalogueTestTags.LOAD_MORE).assertTextContains("Retry").performClick()

    list.performScrollToNode(hasTestTag(CatalogueTestTags.row("migros:099")))
    list.performScrollToNode(hasTestTag(CatalogueTestTags.row("migros:120")))
    compose.onNodeWithTag(CatalogueTestTags.LOAD_MORE).assertDoesNotExist()
    assertEquals(
        listOf(null, "migros:049", "migros:049", "migros:099"),
        products.requests.map { it.cursor },
    )
    compose.onNodeWithTag(CatalogueTestTags.SUMMARY).assertTextContains("1 item")
    list.performScrollToNode(hasTestTag(CatalogueTestTags.SEARCH))
    compose.onNodeWithText("ALL PRODUCTS · 121").assertIsDisplayed()
  }

  @Test
  fun aLoadingPageIsAnnouncedOnceToScreenReaders() {
    products.result = ProductResult.Success(migrosProducts(0..120))
    show()
    val held = CompletableDeferred<Unit>()
    products.gate = held
    compose
        .onNodeWithTag(CatalogueTestTags.PRODUCT_LIST)
        .performScrollToNode(hasTestTag(CatalogueTestTags.row("migros:049")))
    compose
        .onNodeWithContentDescription("Loading more products…")
        .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
    products.gate = null
    held.complete(Unit)
    compose.onNodeWithContentDescription("Loading more products…").assertDoesNotExist()
  }

  @Test
  fun aShortListNeverAsksForAnotherPage() {
    show()
    compose.onNodeWithText("ALL PRODUCTS · 2").assertIsDisplayed()
    assertEquals(listOf<String?>(null), products.requests.map { it.cursor })
  }

  @Test
  fun unsearchableQueriesAskToTypeMoreOrShortenInsteadOfReportingNoMatch() {
    show()
    compose.onNodeWithTag(CatalogueTestTags.add(milk.id)).performClick()
    products.result = ProductResult.Error(ProductError.InvalidQuery)
    compose.onNodeWithTag(CatalogueTestTags.SEARCH).performTextReplacement("o")
    compose.onNodeWithText("Keep typing to search").assertIsDisplayed()
    compose.onNodeWithText("Use at least 2 letters or digits", substring = true).assertIsDisplayed()
    compose.onNodeWithText("No products match", substring = true).assertDoesNotExist()
    compose.onNodeWithTag(CatalogueTestTags.SUMMARY).assertTextContains("1 item")

    // Too long to search: asking for more letters would be the wrong hint.
    compose.onNodeWithTag(CatalogueTestTags.SEARCH).performTextReplacement("o".repeat(101))
    compose.onNodeWithText("Shorten your search").assertIsDisplayed()
    compose.onNodeWithText("Use at most 100 characters", substring = true).assertIsDisplayed()
    compose.onNodeWithText("Keep typing to search").assertDoesNotExist()

    products.result = ProductResult.Success(listOf(milk, bread))
    compose.onNodeWithText("Clear search").performClick()
    compose.onNodeWithText("ALL PRODUCTS · 2").assertIsDisplayed()
  }

  @Test
  fun permissionFailureIsAnErrorWithRetryNotAnEmptyCatalogue() {
    products.result = ProductResult.Error(ProductError.PermissionDenied)
    show()
    compose.onNodeWithText("Couldn’t load the catalogue").assertIsDisplayed()
    compose.onNodeWithText("You don’t have access", substring = true).assertIsDisplayed()
    compose.onNodeWithText("No products yet").assertDoesNotExist()

    products.result = ProductResult.Success(emptyList())
    compose.onNodeWithText("Retry").performClick()
    compose.onNodeWithText("No products yet").assertIsDisplayed()
    compose.runOnIdle { assertEquals(2, products.requests.size) }
  }

  @Test
  fun unsupportedStoreExplainsWithoutRetry() {
    show(catalogueTrip("trip-1", "Volg Ecublens"))
    compose.onNodeWithText("Store not supported yet").assertIsDisplayed()
    compose.onNodeWithTag(CatalogueTestTags.MESSAGE_ACTION).assertDoesNotExist()
  }

  @Test
  @Config(qualifiers = "en-rUS-w640dp-h320dp-land-mdpi")
  fun roundButtonsKeep48dpTargetsAndStayReachableOnShortScreenWithLargeText() {
    show(fontScale = 2f)
    val list = compose.onNodeWithTag(CatalogueTestTags.PRODUCT_LIST)
    list.performScrollToNode(hasTestTag(CatalogueTestTags.add(milk.id)))
    compose
        .onNodeWithTag(CatalogueTestTags.add(milk.id))
        .assertIsDisplayed()
        .assert48dp()
        .performClick()
    compose.onNodeWithContentDescription("Remove Oat drink from order").assert48dp()
    compose.onNodeWithTag(CatalogueTestTags.increase(milk.id)).assert48dp()
    compose
        .onNodeWithTag(CatalogueTestTags.CONTINUE)
        .assertIsDisplayed()
        .assertIsEnabled()
        .assertHeightIsAtLeast(56.dp)

    list.performScrollToNode(hasTestTag(CatalogueTestTags.SEARCH))
    compose.onNodeWithTag(CatalogueTestTags.SEARCH).performTextReplacement("oat")
    compose
        .onNodeWithTag(CatalogueTestTags.CLEAR_QUERY)
        .assertIsDisplayed()
        .assert48dp()
        .performClick()
    compose.onNodeWithTag(CatalogueTestTags.CLEAR_QUERY).assertDoesNotExist()

    compose.onNodeWithTag(CatalogueTestTags.BACK).assertIsDisplayed().assert48dp().performClick()
    compose.runOnIdle { assertEquals(1, backs) }
  }

  @Test
  fun tripTimeUsesTodayTomorrowOrShortDateInTheTripZone() {
    val zurich = ZoneId.of("Europe/Zurich")
    val now = Instant.parse("2026-10-14T22:30:00Z") // 00:30 on 15 October in Zurich.
    fun format(at: String, locale: Locale = Locale.forLanguageTag("en-CH")) =
        formatTripTime(Instant.parse(at), now, zurich, locale, "Today", "Tomorrow")

    assertEquals("Today, 17:30", format("2026-10-15T15:30:00Z"))
    assertEquals("Tomorrow, 08:05", format("2026-10-16T06:05:00Z"))
    assertEquals("18 Oct, 17:30", format("2026-10-18T15:30:00Z"))
    assertEquals("Oct 18, 5:30 PM", format("2026-10-18T15:30:00Z", Locale.US))
  }

  private fun SemanticsNodeInteraction.assert48dp() =
      assertWidthIsEqualTo(48.dp).assertHeightIsEqualTo(48.dp)
}
