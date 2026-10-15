package com.android.sample.ui.navigation

// AI assistance: Claude; review and regression tests: OpenAI Codex.
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.credentials.Credential
import com.android.sample.data.repository.*
import com.android.sample.model.*
import com.android.sample.model.authentication.AuthUser
import com.android.sample.model.authentication.FakeAuthRepository
import com.android.sample.ui.auth.GoogleCredentialClient
import com.android.sample.ui.catalogue.CatalogueTestTags
import com.android.sample.ui.catalogue.StubProductRepository
import com.android.sample.ui.orders.MyOrdersTestTags
import com.android.sample.ui.orders.ReviewOrderTags
import com.android.sample.ui.theme.SampleAppTheme
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Requester journey from an available trip to a submitted order. The Catalogue and Review share one
 * draft and one session order store; fixtures are deterministic and stay in memory.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w412dp-h917dp-mdpi")
class CatalogueNavigationTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
  private val alice = AuthUser("alice", displayName = "Alice", email = "alice@example.com")
  private val auth = FakeAuthRepository(alice)
  private val time = Instant.parse("2030-01-01T12:00:00Z")
  private val migrosTrip = publishedTrip("trip / % 雪", "Migros EPFL", 3600)
  private val coopTrip = publishedTrip("trip-coop", "Coop Lausanne", 7200)
  private val oat = Product("migros:oat", "migros", "Oat drink", 220)
  private val bread = Product("migros:bread", "migros", "Wholemeal bread", 340)
  private val coopMilk = Product("coop:milk", "coop", "Coop milk", 180)
  private val trips =
      FakeTripRepository("alice", now = { time }, initialTrips = listOf(migrosTrip, coopTrip))
  // The ViewModel keeps only the chain of the selected trip, so one stub serves both catalogues.
  private val products = StubProductRepository(ProductResult.Success(listOf(oat, bread, coopMilk)))
  private val credentials =
      object : GoogleCredentialClient {
        override suspend fun request(context: Context): Credential? = null

        override suspend fun clearSession() {}
      }
  private var factoryCalls = 0
  private var createdOrders = 0
  private val factory: (() -> String?) -> OrderRepository = { userId ->
    factoryCalls++
    FakeOrderRepository(userId, now = { time }, newId = { "order-${++createdOrders}" })
  }

  private fun publishedTrip(id: String, store: String, inSeconds: Long) =
      Trip(
          id = id,
          ownerId = "commando",
          store = Location(store, 46.52, 6.57),
          scheduledAt = time.plusSeconds(inSeconds),
          handoffLocation = Location("EPFL", 46.52, 6.56),
          status = TripStatus.PUBLISHED,
          createdAt = time,
          updatedAt = time,
      )

  private lateinit var content: @Composable () -> Unit

  private fun show() {
    content = {
      SampleAppTheme {
        CommandoApp(
            auth,
            trips,
            credentials,
            productRepository = products,
            orderRepositoryFactory = factory,
        )
      }
    }
    compose.setContent(content)
  }

  private fun recreate() {
    compose.activityRule.scenario.recreate()
    compose.activityRule.scenario.onActivity { it.setContent(content = content) }
  }

  private fun click(tag: String) = compose.onNodeWithTag(tag).performClick()

  private fun back() = compose.runOnIdle {
    compose.activity.onBackPressedDispatcher.onBackPressed()
  }

  /** Home to Catalogue through the list and Trip Details, the way a requester reaches it. */
  private fun openCatalogue(trip: Trip) {
    click(AppTestTags.TRIPS_BUTTON)
    compose.onNodeWithText(trip.store.name).performClick()
    compose.onNodeWithText("Add items to this run").performScrollTo().performClick()
  }

  @Test
  fun requesterSelectsProductsReviewsThemAndSubmitsTheOrderOnce() {
    show()
    openCatalogue(migrosTrip)
    compose.onNodeWithTag(CatalogueTestTags.CONTINUE).assertIsNotEnabled()
    click(CatalogueTestTags.add(oat.id))
    click(CatalogueTestTags.increase(oat.id))
    click(CatalogueTestTags.add(bread.id))
    compose
        .onNodeWithTag(CatalogueTestTags.SUMMARY)
        .assertTextContains("CHF 7.80", substring = true)
    click(CatalogueTestTags.CONTINUE)
    // Review opens on the same draft, priced by the catalogue, with the trip it belongs to.
    compose.onNodeWithText(migrosTrip.store.name).assertIsDisplayed()
    compose.onNodeWithText(oat.name).assertIsDisplayed()
    compose.onNodeWithText(bread.name).assertIsDisplayed()
    compose.onNodeWithTag(ReviewOrderTags.COMMISSION).performScrollTo()
    compose.onNodeWithTag(ReviewOrderTags.COMMISSION).performTextReplacement("1,50")
    compose
        .onNodeWithTag(ReviewOrderTags.TOTAL)
        .performScrollTo()
        .assertTextContains("CHF 9.30", substring = true)
    click(ReviewOrderTags.SUBMIT)
    compose.onNodeWithText("Your order is sent").assertIsDisplayed()
    compose.onNodeWithTag(ReviewOrderTags.ERROR).assertDoesNotExist()
    click(ReviewOrderTags.SUBMIT)
    compose.onNodeWithTag(MyOrdersTestTags.ORDER_CARD_PREFIX + "order-1").assertIsDisplayed()
    click(MyOrdersTestTags.ORDER_CARD_PREFIX + "order-1")
    compose.onNodeWithText("2 × ${oat.name}").performScrollTo().assertIsDisplayed()
    compose.onNodeWithText("1 × ${bread.name}").performScrollTo().assertIsDisplayed()
    compose.onNodeWithText("CHF 1.50").performScrollTo().assertIsDisplayed()
    back()
    compose.onNodeWithTag(MyOrdersTestTags.ORDER_CARD_PREFIX + "order-1").assertIsDisplayed()
    // The submitted draft's screens left the back stack, so Back cannot resubmit the order.
    back()
    compose.onNodeWithTag(NavigationTestTags.HOME_SCREEN).assertIsDisplayed()
    compose.runOnIdle {
      assertEquals(1, factoryCalls)
      assertEquals(1, createdOrders)
    }
  }

  @Test
  fun recreationRetainsDraftCommissionAndConfirmationWithoutResubmission() {
    show()
    openCatalogue(migrosTrip)
    click(CatalogueTestTags.add(oat.id))
    recreate()
    compose.onNodeWithTag(CatalogueTestTags.quantity(oat.id)).assertTextEquals("1")
    click(CatalogueTestTags.CONTINUE)
    compose
        .onNodeWithTag(ReviewOrderTags.COMMISSION)
        .performScrollTo()
        .performTextReplacement("1.50")
    recreate()
    compose.onNodeWithTag(ReviewOrderTags.COMMISSION).assertTextContains("1.50")
    click(ReviewOrderTags.SUBMIT)
    compose.onNodeWithText("Your order is sent").assertIsDisplayed()
    recreate()
    compose.onNodeWithText("Your order is sent").assertIsDisplayed()
    // System Back from confirmation must also remove Catalogue and Review.
    back()
    compose.onNodeWithTag(MyOrdersTestTags.ORDER_CARD_PREFIX + "order-1").assertIsDisplayed()
    back()
    compose.onNodeWithTag(NavigationTestTags.HOME_SCREEN).assertIsDisplayed()
    openCatalogue(migrosTrip)
    compose.onNodeWithTag(CatalogueTestTags.CONTINUE).assertIsNotEnabled()
    compose.runOnIdle {
      assertEquals(1, factoryCalls)
      assertEquals(1, createdOrders)
    }
  }

  @Test
  fun toolbarBackFromConfirmationAlsoLeavesTheSubmittedFlow() {
    show()
    openCatalogue(migrosTrip)
    click(CatalogueTestTags.add(oat.id))
    click(CatalogueTestTags.CONTINUE)
    click(ReviewOrderTags.SUBMIT)
    compose.onNodeWithContentDescription("Back").performClick()
    compose.onNodeWithTag(MyOrdersTestTags.ORDER_CARD_PREFIX + "order-1").assertIsDisplayed()
    back()
    compose.onNodeWithTag(NavigationTestTags.HOME_SCREEN).assertIsDisplayed()
  }

  @Test
  fun backAndAddProductsBothReturnToTheCatalogueWithTheSameDraft() {
    show()
    openCatalogue(migrosTrip)
    click(CatalogueTestTags.add(oat.id))
    click(CatalogueTestTags.CONTINUE)
    back()
    // The Catalogue entry waited behind Review, so the selection is still the same draft.
    compose.onNodeWithTag(CatalogueTestTags.quantity(oat.id)).assertTextEquals("1")
    click(CatalogueTestTags.CONTINUE)
    // Emptying the draft in Review turns its action into Add products.
    compose.onNodeWithTag("decrease_${oat.id}").performScrollTo().performClick()
    compose.onNodeWithText("Your order is empty").assertIsDisplayed()
    click(ReviewOrderTags.SUBMIT)
    compose.onNodeWithTag(CatalogueTestTags.CONTINUE).assertIsNotEnabled()
    compose.onNodeWithTag(CatalogueTestTags.SUMMARY).assertDoesNotExist()
    compose.runOnIdle { assertEquals(0, createdOrders) }
  }

  @Test
  fun anotherTripStartsAnEmptyDraftAndKeepsItsOwnChainProducts() {
    show()
    openCatalogue(migrosTrip)
    click(CatalogueTestTags.add(oat.id))
    click(CatalogueTestTags.BACK)
    back()
    compose.onNodeWithText(coopTrip.store.name).performClick()
    compose.onNodeWithText("Add items to this run").performScrollTo().performClick()
    compose.onNodeWithTag(CatalogueTestTags.SUMMARY).assertDoesNotExist()
    compose.onNodeWithText(oat.name).assertDoesNotExist()
    click(CatalogueTestTags.add(coopMilk.id))
    click(CatalogueTestTags.CONTINUE)
    compose.onNodeWithText(coopMilk.name).assertIsDisplayed()
    compose.onNodeWithText(oat.name).assertDoesNotExist()
  }

  @Test
  fun reviewReportsAFailedTripLookupInsteadOfShowingAnOrderWithoutItsTrip() {
    show()
    openCatalogue(migrosTrip)
    click(CatalogueTestTags.add(oat.id))
    compose.runOnIdle { trips.forcedError = TripError.NetworkError }
    click(CatalogueTestTags.CONTINUE)
    compose.onNodeWithText("Something went wrong while loading this trip.").assertIsDisplayed()
    compose.onNodeWithTag(ReviewOrderTags.SUBMIT).assertDoesNotExist()
    back()
    // The draft survives the failed lookup, so nothing selected is lost.
    compose.onNodeWithTag(CatalogueTestTags.quantity(oat.id)).assertTextEquals("1")
  }

  @Test
  fun signOutDiscardsTheDraftAndTheSessionOrderStore() = runTest {
    show()
    openCatalogue(migrosTrip)
    click(CatalogueTestTags.add(oat.id))
    click(CatalogueTestTags.CONTINUE)
    click(ReviewOrderTags.SUBMIT)
    compose.onNodeWithText("Your order is sent").assertIsDisplayed()
    compose.runOnIdle { auth.signOut() }
    compose.onNodeWithTag(NavigationTestTags.LOGIN_SCREEN).assertIsDisplayed()
    auth.signInWithEmailResult = Result.success(alice)
    auth.signInWithEmail("", "")
    click(AppTestTags.ORDERS_BUTTON)
    compose.onNodeWithTag(MyOrdersTestTags.EMPTY).assertIsDisplayed()
    openCatalogue(migrosTrip)
    compose.onNodeWithTag(CatalogueTestTags.SUMMARY).assertDoesNotExist()
    compose.onNodeWithTag(CatalogueTestTags.CONTINUE).assertIsNotEnabled()
    compose.runOnIdle { assertEquals(2, factoryCalls) }
  }
}
