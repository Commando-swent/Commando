package com.android.sample.ui.home

// AI assistance: OpenAI Codex.
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.android.sample.data.repository.FakeTripRepository
import com.android.sample.data.repository.TripRepository
import com.android.sample.data.repository.TripResult
import com.android.sample.model.*
import com.android.sample.model.authentication.AuthUser
import com.android.sample.model.authentication.FakeAuthRepository
import com.android.sample.ui.navigation.*
import com.android.sample.ui.theme.SampleAppTheme
import java.io.File
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeCurrentTripTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
  private val now = Instant.now()
  private val trip =
      Trip(
          "current",
          "alice",
          Location("Migros · Coop", 46.52, 6.63),
          now.plusSeconds(3600),
          Location("Rolex Learning Center", 46.52, 6.57),
          TripStatus.PUBLISHED,
          now,
          now,
      )

  @Test
  fun publishedTripShowsRealDataAndPassesItsIdToManagement() {
    var selected: String? = null
    compose.setContent {
      SampleAppTheme {
        AppScaffold(CommandoScreens.Home, AppMode.Commando, {}, {}, {}) { padding ->
          HomeScreen(
              AppMode.Commando,
              Modifier.padding(padding),
              onPublishTrip = {},
              tripState = HomeTripUiState.Content(trip),
              onManageTrip = { selected = it },
          )
        }
      }
    }
    compose.onNodeWithText("Your next trip").assertIsDisplayed()
    compose.onNodeWithText(trip.store.name).assertIsDisplayed()
    compose.onNodeWithText(trip.handoffLocation.name).assertIsDisplayed()
    compose.onNodeWithText("Open").assertIsDisplayed()
    compose.onNodeWithTag(HomeTestTags.EMPTY_STATE_TITLE).assertDoesNotExist()
    compose.onNodeWithTag(HomeTestTags.MANAGE_TRIP).performClick()
    assertEquals(trip.id, selected)
    val file = File("build/reports/home/current-trip.png").apply { parentFile!!.mkdirs() }
    file.outputStream().use {
      compose
          .onRoot()
          .captureToImage()
          .asAndroidBitmap()
          .compress(Bitmap.CompressFormat.PNG, 100, it)
    }
  }

  @Test
  fun ongoingTripShowsShoppingStatusAndUnavailableActionsAreDisabled() {
    compose.setContent {
      SampleAppTheme {
        HomeScreen(
            AppMode.Commando,
            tripState = HomeTripUiState.Content(trip.copy(status = TripStatus.IN_PROGRESS)),
        )
      }
    }
    compose.onNodeWithText("Your trip").assertIsDisplayed()
    compose.onNodeWithText("Shopping now").assertIsDisplayed()
    compose.onNodeWithTag(HomeTestTags.MANAGE_TRIP).assertIsNotEnabled()
    compose.onNodeWithTag(HomeTestTags.TRIP_ACTION).assertIsNotEnabled()
  }

  @Test
  fun changingAccountDoesNotReuseThePreviousUsersTrip() = runTest {
    val auth = FakeAuthRepository(AuthUser("alice"))
    val aliceTrips = FakeTripRepository("alice", { now }, listOf(trip))
    val trips =
        object : TripRepository by aliceTrips {
          override suspend fun getMyTrips(): TripResult<List<Trip>> =
              FakeTripRepository(requireNotNull(auth.currentUser).uid, { now }, listOf(trip))
                  .getMyTrips()
        }
    compose.setContent { SampleAppTheme { CommandoApp(auth, trips) } }
    compose.onNodeWithTag(AppTestTags.COMMANDO_MODE).performClick()
    compose.onNodeWithTag(HomeTestTags.CURRENT_TRIP).assertIsDisplayed()
    auth.signInWithEmailResult = Result.success(AuthUser("bob"))
    auth.signInWithEmail("", "")
    compose.onNodeWithTag(AppTestTags.COMMANDO_MODE).performClick()
    compose.onNodeWithTag(HomeTestTags.CURRENT_TRIP).assertDoesNotExist()
    compose.onNodeWithText("Heading to the shop soon?").assertIsDisplayed()
  }

  @Test
  fun returningFromProfileReloadsTheTripAndSignOutRemovesIt() {
    val auth = FakeAuthRepository(AuthUser("alice"))
    val trips = FakeTripRepository("alice", { now }, listOf(trip))
    compose.setContent { SampleAppTheme { CommandoApp(auth, trips) } }
    compose.onNodeWithTag(AppTestTags.COMMANDO_MODE).performClick()
    compose.onNodeWithTag(HomeTestTags.CURRENT_TRIP).assertIsDisplayed()
    compose.onNodeWithTag(AppTestTags.PROFILE_BUTTON).performClick()
    trips.forcedError = com.android.sample.data.repository.TripError.NetworkError
    compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
    compose.onNodeWithTag(HomeTestTags.RETRY).assertIsDisplayed()
    trips.forcedError = null
    compose.onNodeWithTag(HomeTestTags.RETRY).performClick()
    compose.onNodeWithTag(HomeTestTags.CURRENT_TRIP).assertIsDisplayed()
    compose.onNodeWithTag(AppTestTags.PROFILE_BUTTON).performClick()
    compose.onNodeWithTag(NavigationTestTags.SIGN_OUT_BUTTON).performClick()
    compose.onNodeWithTag(HomeTestTags.CURRENT_TRIP).assertDoesNotExist()
    compose.onNodeWithTag(NavigationTestTags.LOGIN_SCREEN).assertIsDisplayed()
  }
}
