package com.android.sample.ui.navigation

// AI assistance: OpenAI Codex.
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.android.sample.data.repository.FakeTripRepository
import com.android.sample.model.Location
import com.android.sample.model.Trip
import com.android.sample.model.TripStatus
import com.android.sample.model.authentication.AuthUser
import com.android.sample.model.authentication.FakeAuthRepository
import com.android.sample.ui.auth.GoogleCredentialClient
import com.android.sample.ui.home.HomeTestTags
import com.android.sample.ui.theme.SampleAppTheme
import com.android.sample.ui.trips.CreateTripScreenTestTags
import java.time.Instant
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-mdpi")
class HomeTripCreationNavigationTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
  private val auth = FakeAuthRepository(AuthUser("alice"))
  private val google =
      object : GoogleCredentialClient {
        override suspend fun request(context: Context) = null

        override suspend fun clearSession() {}
      }

  private fun show(trips: List<Trip> = emptyList()) {
    val repository = FakeTripRepository("alice", initialTrips = trips)
    compose.setContent { SampleAppTheme { CommandoApp(auth, repository, google) } }
    compose.onNodeWithTag(AppTestTags.COMMANDO_MODE).performClick()
  }

  @Test
  fun publishFromEmptyHomeOpensCreationAndBackKeepsCommandoMode() {
    show()
    compose.onNodeWithTag(HomeTestTags.TRIP_ACTION).assertIsEnabled().performClick()
    compose.onNodeWithTag(CreateTripScreenTestTags.STORE_FIELD).assertExists()
    compose.onNodeWithTag(CreateTripScreenTestTags.PUBLISH_BUTTON).assertIsNotEnabled()
    compose.onNodeWithTag(AppTestTags.TOP_BAR).assertDoesNotExist()
    compose.onNodeWithTag(AppTestTags.BOTTOM_BAR).assertDoesNotExist()
    compose.onNodeWithTag(CreateTripScreenTestTags.BACK_BUTTON).performClick()
    compose.onNodeWithTag(HomeTestTags.EMPTY_STATE_TITLE).assertIsDisplayed()
    compose.onNodeWithTag(AppTestTags.COMMANDO_MODE).assertIsSelected()
    compose.onNodeWithTag(HomeTestTags.TRIP_ACTION).performClick()
    compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
    compose.onNodeWithTag(HomeTestTags.EMPTY_STATE_TITLE).assertIsDisplayed()
  }

  @Test
  fun publishAnotherTripOpensCreationWithoutLosingTheCurrentTrip() {
    val now = Instant.now()
    val trip =
        Trip(
            "current",
            "alice",
            Location("Migros", 46.52, 6.63),
            now.plusSeconds(3600),
            Location("EPFL", 46.52, 6.57),
            TripStatus.PUBLISHED,
            now,
            now,
        )
    show(listOf(trip))
    compose.onNodeWithTag(HomeTestTags.CURRENT_TRIP).assertIsDisplayed()
    compose.onNodeWithTag(HomeTestTags.TRIP_ACTION).performClick()
    compose.onNodeWithTag(CreateTripScreenTestTags.PUBLISH_BUTTON).assertIsNotEnabled()
    compose.onNodeWithTag(CreateTripScreenTestTags.BACK_BUTTON).performClick()
    compose.onNodeWithTag(HomeTestTags.CURRENT_TRIP).assertIsDisplayed()
    compose.onNodeWithText("Migros").assertIsDisplayed()
  }
}
