package com.android.sample.ui.navigation

// AI assistance: OpenAI Codex.
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.sample.data.repository.*
import com.android.sample.model.*
import com.android.sample.model.authentication.AuthUser
import com.android.sample.model.authentication.FakeAuthRepository
import com.android.sample.ui.auth.GoogleCredentialClient
import com.android.sample.ui.catalogue.CatalogueTestTags
import com.android.sample.ui.home.HomeTestTags
import com.android.sample.ui.orders.MyOrdersTestTags
import com.android.sample.ui.orders.ReviewOrderTags
import com.android.sample.ui.profile.ProfileEditTestTags
import com.android.sample.ui.profile.ProfileTestTags
import com.android.sample.ui.theme.SampleAppTheme
import com.android.sample.ui.trips.CreateTripScreenTestTags
import com.android.sample.ui.trips.MyTripsTestTags
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device-level UI journey through the real app graph, screens and ViewModels. Authentication, trips
 * and products use deterministic in-memory fixtures; Orders uses the app's default session fake.
 * This tests the demo user flow, not Firebase authentication or backend persistence.
 */
@RunWith(AndroidJUnit4::class)
class AppJourneyTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

  @Test
  fun signInOrderEditProfilePublishTripAndSignOut() {
    val alice = AuthUser("alice", displayName = "Alice", email = "alice@epfl.ch")
    val auth = FakeAuthRepository().apply { signInWithEmailResult = Result.success(alice) }
    val now = Instant.now()
    val trip =
        Trip(
            id = "trip / % 雪",
            ownerId = "commando",
            store = Location("Migros EPFL", 46.52, 6.57),
            scheduledAt = now.plusSeconds(3600),
            handoffLocation = Location("EPFL", 46.52, 6.56),
            status = TripStatus.PUBLISHED,
            createdAt = now,
            updatedAt = now,
        )
    val trips = FakeTripRepository(alice.uid, now = { now }, initialTrips = listOf(trip))
    val oat = Product("migros:oat", "migros", "Oat drink", 220)
    val products =
        object : ProductRepository {
          override suspend fun getProducts(storeId: String, limit: Int) =
              ProductResult.Success(listOf(oat).filter { it.storeId == storeId }.take(limit))
        }
    val google =
        object : GoogleCredentialClient {
          override suspend fun request(context: Context) = null

          override suspend fun clearSession() {}
        }
    compose.setContent {
      SampleAppTheme { CommandoApp(auth, trips, google, productRepository = products) }
    }

    // Start signed out and enter through the real login form.
    compose.onNodeWithTag(NavigationTestTags.LOGIN_SCREEN).assertIsDisplayed()
    compose.onNodeWithTag("auth_email").performTextInput("alice@epfl.ch")
    compose.onNodeWithTag("auth_password").performTextInput("demo-password")
    compose.onNodeWithTag("auth_submit").performScrollTo().performClick()
    compose.onNodeWithTag(NavigationTestTags.HOME_SCREEN).assertIsDisplayed()
    click(AppTestTags.TRIPS_BUTTON)
    click(NavigationTestTags.OPEN_MAP)
    compose.onNodeWithTag(NavigationTestTags.MAP_LIST).assertIsDisplayed().performClick()
    compose.onNodeWithText(trip.store.name).performClick()
    compose.onNodeWithText("Add items to this run").performScrollTo().performClick()

    click(CatalogueTestTags.add(oat.id))
    click(CatalogueTestTags.increase(oat.id))
    click(CatalogueTestTags.CONTINUE)
    compose.onNodeWithText(oat.name).assertIsDisplayed()
    // Back preserves the shared cart, including quantities.
    back()
    compose.onNodeWithTag(CatalogueTestTags.quantity(oat.id)).assertTextEquals("2")
    click(CatalogueTestTags.CONTINUE)
    compose.onNodeWithTag(ReviewOrderTags.TOTAL).performScrollTo().assertTextContains("CHF 4.40")
    click(ReviewOrderTags.SUBMIT)
    compose.onNodeWithText("Your order is sent").assertIsDisplayed()
    click(ReviewOrderTags.SUBMIT)

    // The default order store generates its own ID; select the actual saved row via the UI.
    val orderCard = hasTestTagPrefix(MyOrdersTestTags.ORDER_CARD_PREFIX)
    compose.onAllNodes(orderCard).assertCountEquals(1)
    compose.onNode(orderCard).performClick()
    compose.onNodeWithText("2 × ${oat.name}").performScrollTo().assertIsDisplayed()
    compose.onNodeWithText(trip.store.name).performScrollTo().assertIsDisplayed()
    back()
    compose.onAllNodes(orderCard).assertCountEquals(1)
    back()
    compose.onNodeWithTag(NavigationTestTags.HOME_SCREEN).assertIsDisplayed()

    click(AppTestTags.PROFILE_BUTTON)
    click(ProfileTestTags.EDIT)
    compose.onNodeWithTag(ProfileEditTestTags.FULL_NAME).performTextReplacement("Alice Updated")
    click(ProfileEditTestTags.SAVE)
    // Save presents feedback; Done returns to the shared profile state.
    click(ProfileEditTestTags.SAVE)
    compose.onNodeWithTag(ProfileTestTags.FULL_NAME).assertTextEquals("Alice Updated")
    click(AppTestTags.COMMANDO_MODE)
    click(AppTestTags.HOME_BUTTON)
    compose.onNodeWithTag(AppTestTags.COMMANDO_MODE).assertIsSelected()
    click(HomeTestTags.TRIP_ACTION)
    publishTrip()
    compose.waitUntil(timeoutMillis = 15_000) {
      compose.onAllNodesWithTag(HomeTestTags.CURRENT_TRIP).fetchSemanticsNodes().isNotEmpty()
    }
    compose.onNodeWithTag(NavigationTestTags.HOME_SCREEN).assertIsDisplayed()
    click(AppTestTags.TRIPS_BUTTON)
    val ownTripCard = hasTestTagPrefix(MyTripsTestTags.TRIP_CARD_PREFIX)
    compose.onAllNodes(ownTripCard).assertCountEquals(1)
    compose.onNode(ownTripCard).performClick()
    compose.onNodeWithText(TripLocations.stores[0].name).assertIsDisplayed()
    compose.onNodeWithText("Add items to this run").assertIsNotEnabled()
    back()
    compose.onNodeWithTag(MyTripsTestTags.SCREEN).assertIsDisplayed()
    click(AppTestTags.PROFILE_BUTTON)
    compose.onNodeWithTag(ProfileTestTags.FULL_NAME).assertTextEquals("Alice Updated")
    click(AppTestTags.REQUESTER_MODE)
    click(AppTestTags.ORDERS_BUTTON)
    compose.onAllNodes(orderCard).assertCountEquals(1)
    click(AppTestTags.PROFILE_BUTTON)
    click(NavigationTestTags.SIGN_OUT_BUTTON)
    compose.onNodeWithTag(NavigationTestTags.LOGIN_SCREEN).assertIsDisplayed()
    compose.onAllNodes(orderCard).assertCountEquals(0)
    compose.onNodeWithTag(NavigationTestTags.HOME_SCREEN).assertDoesNotExist()
  }

  private fun publishTrip() {
    compose.onNodeWithTag(CreateTripScreenTestTags.STORE_FIELD).performScrollTo().performClick()
    click(CreateTripScreenTestTags.locationOption(TripLocations.stores[0].name))
    compose.onNodeWithTag(CreateTripScreenTestTags.DATE_FIELD).performScrollTo().performClick()
    compose.onNodeWithContentDescription("Switch to text input mode").performClick()
    compose
        .onNode(hasSetTextAction())
        .performTextReplacement(
            LocalDate.now().plusDays(2).format(DateTimeFormatter.ofPattern("MMddyyyy"))
        )
    compose.onNodeWithText("OK").performClick()
    compose.onNodeWithTag(CreateTripScreenTestTags.TIME_FIELD).performScrollTo().performClick()
    compose.onNodeWithText("OK").performClick()
    compose
        .onNodeWithTag(CreateTripScreenTestTags.HANDOFF_LOCATION_FIELD)
        .performScrollTo()
        .performClick()
    click(CreateTripScreenTestTags.locationOption(TripLocations.handoffPoints[0].name))
    compose.onNodeWithTag(CreateTripScreenTestTags.PUBLISH_BUTTON).assertIsEnabled().performClick()
  }

  private fun click(tag: String) = compose.onNodeWithTag(tag).performClick()

  private fun back() = compose.runOnUiThread {
    compose.activity.onBackPressedDispatcher.onBackPressed()
  }

  private fun hasTestTagPrefix(prefix: String) =
      SemanticsMatcher("Test tag starts with $prefix") {
        it.config
            .getOrElse(androidx.compose.ui.semantics.SemanticsProperties.TestTag) { "" }
            .startsWith(prefix)
      }
}
