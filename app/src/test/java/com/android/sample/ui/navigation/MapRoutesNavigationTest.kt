package com.android.sample.ui.navigation

// AI assistance: Claude Code (characterization tests for map routes and sharing sign-out, #380).
import android.Manifest
import android.app.Application
import android.content.Context
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.credentials.Credential
import androidx.test.core.app.ApplicationProvider
import com.android.sample.R
import com.android.sample.data.location.DeviceLocationProvider
import com.android.sample.data.location.DeviceLocationResult
import com.android.sample.data.repository.*
import com.android.sample.model.authentication.AuthUser
import com.android.sample.model.authentication.FakeAuthRepository
import com.android.sample.ui.auth.GoogleCredentialClient
import com.android.sample.ui.auth.WelcomeTestTags
import com.android.sample.ui.map.PositionSharingTestTags
import com.android.sample.ui.theme.SampleAppTheme
import com.commando.app.model.*
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Pins that sign-out waits for position sharing to stop and that a failed stop keeps the session,
 * and the stop error and retry on a switch to Requester mode. Map and tracking navigation are
 * covered by CommandoAppTest and OrderNavigationTest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w412dp-h917dp-mdpi")
class MapRoutesNavigationTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
  private val auth =
      FakeAuthRepository(AuthUser("alice", displayName = "Alice", email = "alice@example.com"))
  private val time = Instant.parse("2030-01-01T12:00:00Z")
  private val trip =
      Trip(
          "sharing-trip",
          "alice",
          Location("Migros EPFL", 46.5, 6.5),
          time,
          Location("EPFL", 46.5, 6.5),
          TripStatus.IN_PROGRESS,
          time,
          time,
      )
  private val storage = MutableStateFlow<Map<String, CommandoPosition>>(emptyMap())
  private val transport =
      FakeCommandoPositionRepository(MutableStateFlow("alice"), positions = storage)
  private var stopGate = CompletableDeferred<Unit>()
  private var stopCalls = 0
  private val positions =
      object : CommandoPositionRepository by transport {
        override suspend fun stopSharing() {
          stopCalls++
          stopGate.await()
          transport.stopSharing()
        }
      }

  @Before
  fun startSharingInCommandoMode() {
    shadowOf(ApplicationProvider.getApplicationContext<Application>())
        .grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
    val source = DeviceLocationProvider {
      DeviceLocationResult.Available(
          DeviceLocation(46.521, 6.566, 20f, SystemClock.elapsedRealtime())
      )
    }
    compose.setContent {
      SampleAppTheme {
        CommandoApp(
            auth,
            FakeTripRepository("alice", initialTrips = listOf(trip)),
            object : GoogleCredentialClient {
              override suspend fun request(context: Context): Credential? = null

              override suspend fun clearSession() {}
            },
            orderRepositoryFactory = { FakeOrderRepository(it) },
            ratingRepositoryFactory = { error("Map routes must not initialize ratings") },
            positionRepositoryFactory = { positions },
            locationProvider = source,
        )
      }
    }
    click(AppTestTags.COMMANDO_MODE)
    compose.onNodeWithTag(PositionSharingTestTags.SHARING_TOGGLE).performScrollTo().performClick()
    compose.waitUntil(5_000) { storage.value.isNotEmpty() }
    click(AppTestTags.PROFILE_BUTTON)
  }

  @Test
  fun signOutFromProfileWaitsForSharingStopBeforeLeavingTheSession() {
    click(NavigationTestTags.SIGN_OUT_BUTTON)
    compose.waitForIdle()
    assertEquals(1, stopCalls)
    compose.onNodeWithTag(NavigationTestTags.PROFILE_SCREEN).assertIsDisplayed()
    compose.onNodeWithTag(WelcomeTestTags.SCREEN).assertDoesNotExist()
    assertNotNull(auth.currentUser)
    assertTrue(storage.value.isNotEmpty())

    compose.runOnIdle { stopGate.complete(Unit) }
    compose.waitUntil(5_000) { auth.currentUser == null }
    compose.onNodeWithTag(WelcomeTestTags.SCREEN).assertIsDisplayed()
    compose.onNodeWithTag(NavigationTestTags.PROFILE_SCREEN).assertDoesNotExist()
    assertTrue(storage.value.isEmpty())
  }

  @Test
  fun failedSharingStopKeepsProfileWithErrorAndRetrySignsOut() {
    stopGate.completeExceptionally(IllegalStateException("delete failed"))
    click(NavigationTestTags.SIGN_OUT_BUTTON)
    assertStopFailureKeepsSession()
    // The cancelled worker and the explicit stop may each attempt the delete once.
    val failedAttempts = stopCalls
    assertTrue(failedAttempts > 0)

    // A second sign-out while the delete still fails retries it and keeps the session.
    dismissStopError()
    signOutAccessibly()
    assertStopFailureKeepsSession()
    val repeatedAttempts = stopCalls
    assertTrue(repeatedAttempts > failedAttempts)

    compose.runOnIdle { stopGate = CompletableDeferred(Unit) }
    signOutAccessibly()
    compose.waitUntil(5_000) { auth.currentUser == null }
    compose.onNodeWithTag(WelcomeTestTags.SCREEN).assertIsDisplayed()
    assertTrue(stopCalls > repeatedAttempts)
    assertTrue(storage.value.isEmpty())
  }

  @Test
  fun failedSharingStopOnRequesterSwitchShowsErrorAndKeepsSession() {
    stopGate.completeExceptionally(IllegalStateException("delete failed"))
    click(AppTestTags.REQUESTER_MODE)
    assertStopFailureKeepsSession()
    val failedAttempts = stopCalls
    assertTrue(failedAttempts > 0)

    // Entering Commando mode reports no stop error; leaving it again retries the failed delete.
    dismissStopError()
    click(AppTestTags.COMMANDO_MODE)
    compose.waitForIdle()
    compose.onNodeWithText(string(R.string.position_sharing_stop)).assertDoesNotExist()
    assertNotNull(auth.currentUser)
    click(AppTestTags.REQUESTER_MODE)
    assertStopFailureKeepsSession()
    assertTrue(stopCalls > failedAttempts)
  }

  @Test
  fun requesterSwitchWaitsForPendingStopAndShowsNoErrorOnSuccess() {
    click(AppTestTags.REQUESTER_MODE)
    compose.waitForIdle()
    assertTrue(stopCalls > 0)
    assertTrue(storage.value.isNotEmpty())

    compose.runOnIdle { stopGate.complete(Unit) }
    compose.waitUntil(5_000) { storage.value.isEmpty() }
    compose.onNodeWithText(string(R.string.position_sharing_stop)).assertDoesNotExist()
    compose.onNodeWithTag(NavigationTestTags.PROFILE_SCREEN).assertIsDisplayed()
    assertNotNull(auth.currentUser)
  }

  private fun assertStopFailureKeepsSession() {
    compose.onNodeWithText(string(R.string.position_sharing_stop)).assertIsDisplayed()
    compose.onNodeWithTag(NavigationTestTags.PROFILE_SCREEN).assertIsDisplayed()
    compose.onNodeWithTag(WelcomeTestTags.SCREEN).assertDoesNotExist()
    assertNotNull(auth.currentUser)
    assertTrue(storage.value.isNotEmpty())
  }

  /** Lets the short snackbar time out on the virtual clock, so a new error must show it again. */
  private fun dismissStopError() {
    compose.mainClock.advanceTimeBy(10_000)
    compose.onNodeWithText(string(R.string.position_sharing_stop)).assertDoesNotExist()
  }

  // The stop-error snackbar can overlap the button; activate its accessible action instead.
  private fun signOutAccessibly() {
    compose.onNodeWithTag(NavigationTestTags.SIGN_OUT_BUTTON).performSemanticsAction(
        SemanticsActions.OnClick
    ) {
      it()
    }
  }

  private fun click(tag: String) = compose.onNodeWithTag(tag).performClick()

  private fun string(id: Int) = compose.activity.getString(id)
}
