package com.android.sample.ui.navigation

// AI assistance: OpenAI Codex.
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.sample.data.repository.FakeTripRepository
import com.android.sample.data.repository.TripResult
import com.android.sample.model.TripLocations
import com.android.sample.model.authentication.AuthUser
import com.android.sample.model.authentication.FakeAuthRepository
import com.android.sample.ui.auth.GoogleCredentialClient
import com.android.sample.ui.home.HomeTestTags
import com.android.sample.ui.theme.SampleAppTheme
import com.android.sample.ui.trips.CreateTripScreenTestTags
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real dialogs exercise publication through the navigation root and back to Home. */
@RunWith(AndroidJUnit4::class)
class HomeTripPublicationTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

  @Test
  fun publishingFromHomeShowsTheStoredTripAndReopeningStartsAFreshForm() {
    val auth = FakeAuthRepository(AuthUser("alice"))
    val trips = FakeTripRepository("alice")
    val google =
        object : GoogleCredentialClient {
          override suspend fun request(context: Context) = null

          override suspend fun clearSession() {}
        }
    compose.setContent { SampleAppTheme { CommandoApp(auth, trips, google) } }
    compose.onNodeWithTag(AppTestTags.COMMANDO_MODE).performClick()
    compose.onNodeWithTag(HomeTestTags.TRIP_ACTION).performClick()
    field(CreateTripScreenTestTags.STORE_FIELD).performClick()
    compose
        .onNodeWithTag(CreateTripScreenTestTags.locationOption(TripLocations.stores[0].name))
        .performClick()
    field(CreateTripScreenTestTags.DATE_FIELD).performClick()
    compose.onNodeWithContentDescription("Switch to text input mode").performClick()
    compose
        .onNode(hasSetTextAction())
        .performTextReplacement(
            LocalDate.now().plusDays(2).format(DateTimeFormatter.ofPattern("MMddyyyy"))
        )
    compose.onNodeWithText("OK").performClick()
    field(CreateTripScreenTestTags.TIME_FIELD).performClick()
    compose.onNodeWithText("OK").performClick()
    field(CreateTripScreenTestTags.HANDOFF_LOCATION_FIELD).performClick()
    compose
        .onNodeWithTag(CreateTripScreenTestTags.locationOption(TripLocations.handoffPoints[0].name))
        .performClick()
    compose.onNodeWithTag(CreateTripScreenTestTags.PUBLISH_BUTTON).assertIsEnabled().performClick()
    compose.waitUntil(timeoutMillis = 15_000) {
      compose.onAllNodesWithTag(HomeTestTags.CURRENT_TRIP).fetchSemanticsNodes().isNotEmpty()
    }
    compose.onNodeWithTag(HomeTestTags.CURRENT_TRIP).assertIsDisplayed()
    compose.onNodeWithText(TripLocations.stores[0].name).assertIsDisplayed()
    compose.onNodeWithTag(AppTestTags.COMMANDO_MODE).assertIsSelected()
    compose.runOnIdle {
      val stored = runBlocking { (trips.getMyTrips() as TripResult.Success).data }
      assertEquals(1, stored.size)
      assertEquals("alice", stored.single().ownerId)
    }
    compose.onNodeWithTag(HomeTestTags.TRIP_ACTION).performClick()
    compose.onNodeWithTag(CreateTripScreenTestTags.STORE_FIELD).assertTextContains("Select a store")
    compose.onNodeWithTag(CreateTripScreenTestTags.PUBLISH_BUTTON).assertIsNotEnabled()
    compose.onNodeWithTag(CreateTripScreenTestTags.BACK_BUTTON).performClick()
    compose.onNodeWithTag(HomeTestTags.CURRENT_TRIP).assertIsDisplayed()
  }

  private fun field(tag: String) = compose.onNodeWithTag(tag).performScrollTo()
}
