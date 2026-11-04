package com.android.sample.ui.catalogue

import android.graphics.Bitmap
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
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import coil3.request.ImageResult
import coil3.request.SuccessResult
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
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

  /** URLs the fake image loader was asked for; it never touches the network. */
  private val imageRequests = mutableListOf<String>()
  private var imagesFail = false

  @Before
  fun installFakeImageLoader() {
    val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    val fake =
        object : Interceptor {
          override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
            synchronized(imageRequests) { imageRequests += chain.request.data.toString() }
            return if (imagesFail)
                ErrorResult(null, chain.request, IllegalStateException("offline"))
            else
                SuccessResult(
                    Bitmap.createBitmap(4, 8, Bitmap.Config.ARGB_8888).asImage(),
                    chain.request,
                    DataSource.MEMORY,
                )
          }
        }
    SingletonImageLoader.setUnsafe(ImageLoader.Builder(context).components { add(fake) }.build())
  }

  @After fun resetImageLoader() = SingletonImageLoader.reset()

  private fun imageWasRequested(url: String) =
      compose.waitUntil(IMAGE_TIMEOUT_MILLIS) {
        synchronized(imageRequests) { url in imageRequests }
      }

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
  fun sameNamedProductsShowTheirSize() {
    val small = Product("migros:10", "migros", "M-Budget Lait", 80, sizeText = "100g")
    val pack = Product("migros:11", "migros", "M-Budget Lait", 110, sizeText = "3 x 100g")
    products.result = ProductResult.Success(listOf(small, pack, bread))
    show()
    compose.onNodeWithText("100g · CHF 0.80").assertIsDisplayed()
    compose.onNodeWithText("3 x 100g · CHF 1.10").assertIsDisplayed()
    compose.onNodeWithText("CHF 3.40").assertIsDisplayed() // No size: the price alone.
    assertEquals(listOf<String?>(null), products.requests.map { it.cursor })
  }

  @Test
  fun productsWithAnImageShowItOverThePlaceholder() {
    val photo = Product("migros:12", "migros", "M-Budget Lait", 80, imageUrl = MIGROS_IMAGE)
    products.result = ProductResult.Success(listOf(photo, bread))
    show()
    fun imageIn(productId: String) =
        compose.onNode(
            hasTestTag(CatalogueTestTags.PRODUCT_IMAGE) and
                hasAnyAncestor(hasTestTag(CatalogueTestTags.row(productId))),
            useUnmergedTree = true,
        )
    imageIn(photo.id).assertExists()
    imageIn(bread.id).assertDoesNotExist()
    imageWasRequested(productImageUrl(MIGROS_IMAGE, THUMBNAIL_PX))
    // Without a photo the placeholder is not a button: there is nothing to preview.
    compose.onNodeWithContentDescription("View photo of Wholemeal bread").assertDoesNotExist()
  }

  @Test
  fun tappingAPhotoPreviewsItWithoutChangingTheCartOrSearch() {
    val photo = Product("migros:12", "migros", "M-Budget Lait", 80, imageUrl = MIGROS_IMAGE)
    products.result = ProductResult.Success(listOf(photo, bread))
    show()
    compose.onNodeWithTag(CatalogueTestTags.add(bread.id)).performClick()
    compose.onNodeWithTag(CatalogueTestTags.SEARCH).performTextReplacement("lait")
    compose.onNodeWithText("1 RESULT FOR “LAIT”").assertIsDisplayed()

    compose
        .onNodeWithContentDescription("View photo of M-Budget Lait")
        .assertIsDisplayed()
        .assertWidthIsEqualTo(56.dp)
        .assertHeightIsEqualTo(56.dp)
        .performClick()
    compose.onNodeWithTag(CatalogueTestTags.IMAGE_PREVIEW).assertIsDisplayed()
    compose.onNodeWithContentDescription("Photo of M-Budget Lait").assertExists()
    imageWasRequested(productImageUrl(MIGROS_IMAGE, PREVIEW_PX))
    compose.waitForIdle()
    compose.onNodeWithText("Couldn’t load the photo.").assertDoesNotExist()
    compose.onNodeWithTag(CatalogueTestTags.IMAGE_PREVIEW_CLOSE).assert48dp().performClick()

    compose.onNodeWithTag(CatalogueTestTags.IMAGE_PREVIEW).assertDoesNotExist()
    compose.onNodeWithTag(CatalogueTestTags.SEARCH).assertTextContains("lait")
    compose.onNodeWithText("1 RESULT FOR “LAIT”").assertIsDisplayed()
    compose.onNodeWithTag(CatalogueTestTags.add(photo.id)).assertIsDisplayed()
    compose.runOnIdle {
      assertEquals(listOf(OrderItem(bread.id, "Wholemeal bread", 340, 1)), draft.draft.value.items)
    }
  }

  @Test
  fun aPhotoThatFailsKeepsThePlaceholderAndThePreviewExplainsIt() {
    imagesFail = true
    val photo = Product("migros:12", "migros", "M-Budget Lait", 80, imageUrl = MIGROS_IMAGE)
    products.result = ProductResult.Success(listOf(photo))
    show()
    imageWasRequested(productImageUrl(MIGROS_IMAGE, THUMBNAIL_PX))
    compose.onNodeWithContentDescription("View photo of M-Budget Lait").performClick()
    compose.waitUntil(IMAGE_TIMEOUT_MILLIS) {
      compose.onAllNodesWithText("Couldn’t load the photo.").fetchSemanticsNodes().isNotEmpty()
    }
    compose.onNodeWithText("Couldn’t load the photo.").assertIsDisplayed()
    compose.onNodeWithTag(CatalogueTestTags.IMAGE_PREVIEW_CLOSE).performClick()
    compose.onNodeWithTag(CatalogueTestTags.IMAGE_PREVIEW).assertDoesNotExist()
  }

  @Test
  fun migrosImagesUseTheRequestedRenditionAndOtherUrlsAreKept() {
    assertEquals(
        "https://image.migros.ch/d/mo-custom/v-w-200-h-200/o-af-1-t.clr-fff/abc/m-budget-lait.jpg",
        productImageUrl(MIGROS_IMAGE, THUMBNAIL_PX),
    )
    assertEquals(
        "https://image.migros.ch/d/mo-custom/v-w-800-h-800/x.jpg",
        productImageUrl("https://image.migros.ch/d/{stack}/x.jpg", PREVIEW_PX),
    )
    val other = "https://example.com/products/bread.jpg"
    assertEquals(other, productImageUrl(other, PREVIEW_PX))
  }

  @Test
  fun coopTripSearchesSelectsAndShowsCoopPhotosAndSizes() {
    val coopTrip = catalogueTrip("trip-coop", "Coop Lausanne")
    val avocado =
        Product("coop:1", "coop", "Bio Avocado", 195, imageUrl = COOP_IMAGE, sizeText = "1 Stück")
    val oat = Product("coop:2", "coop", "Oat drink", 210)
    products.result = ProductResult.Success(listOf(avocado, oat, milk))
    show(coopTrip)
    compose.onNodeWithText("ALL PRODUCTS · 2").assertIsDisplayed()
    compose.onNodeWithTag(CatalogueTestTags.row(milk.id)).assertDoesNotExist()
    compose.onNodeWithText("1 Stück · CHF 1.95").assertIsDisplayed()
    // Coop URLs have no Migros rendition placeholder, so they are loaded as imported.
    imageWasRequested(COOP_IMAGE)

    compose.onNodeWithTag(CatalogueTestTags.add(oat.id)).performClick()
    compose.onNodeWithTag(CatalogueTestTags.SEARCH).performTextReplacement("avocado")
    compose.onNodeWithText("1 RESULT FOR “AVOCADO”").assertIsDisplayed()
    compose.onNodeWithTag(CatalogueTestTags.add(avocado.id)).performClick()
    compose.onNodeWithTag(CatalogueTestTags.increase(avocado.id)).performClick()
    compose.onNodeWithTag(CatalogueTestTags.increase(avocado.id)).performClick()
    compose.onNodeWithContentDescription("Decrease Bio Avocado quantity").performClick()
    compose.onNodeWithTag(CatalogueTestTags.SUMMARY).assertTextContains("Est. CHF 6.00")
    compose.runOnIdle {
      assertEquals(listOf("coop"), products.requests.map { it.storeId }.distinct())
      assertEquals(coopTrip.id, draft.draft.value.tripId)
      assertEquals(
          listOf(
              OrderItem(oat.id, "Oat drink", 210, 1),
              OrderItem(avocado.id, "Bio Avocado", 195, 2),
          ),
          draft.draft.value.items,
      )
    }
    compose.onNodeWithTag(CatalogueTestTags.CONTINUE).performClick()
    compose.runOnIdle { assertEquals(listOf(coopTrip), continued) }
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

private const val MIGROS_IMAGE =
    "https://image.migros.ch/d/%7Bstack%7D/o-af-1-t.clr-fff/abc/m-budget-lait.jpg"

private const val COOP_IMAGE =
    "https://www.coop.ch/img/produkte/310_310/RGB/3465933_001.jpg?_=1710535453816"

private const val IMAGE_TIMEOUT_MILLIS = 5_000L
