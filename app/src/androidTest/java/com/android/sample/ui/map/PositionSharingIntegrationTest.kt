package com.android.sample.ui.map

// AI assistance: OpenAI Codex.
import android.Manifest
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.android.sample.data.location.*
import com.android.sample.data.repository.*
import com.android.sample.model.*
import com.android.sample.ui.home.*
import com.android.sample.ui.navigation.*
import com.android.sample.ui.theme.SampleAppTheme
import java.io.File
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Actual Android lifecycle and Compose interactions; injected fixes do not prove GPS acquisition.
 */
@RunWith(AndroidJUnit4::class)
class PositionSharingIntegrationTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

  @Test
  fun foregroundSharingRequiresATapAndStopsOnToggleAndBackground() {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    instrumentation.uiAutomation.grantRuntimePermission(
        instrumentation.targetContext.packageName,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    )
    val now = Instant.now()
    val trip = trip("own", "owner", now)
    val identity = MutableStateFlow<String?>("owner")
    val storage = MutableStateFlow<Map<String, CommandoPosition>>(emptyMap())
    val repository = FakeCommandoPositionRepository(identity, positions = storage)
    var reads = 0
    val model =
        PositionSharingViewModel(
            { repository },
            FakeTripRepository("owner", initialTrips = listOf(trip)),
            DeviceLocationProvider {
              reads++
              DeviceLocationResult.Available(
                  DeviceLocation(46.521, 6.566, 100f, SystemClock.elapsedRealtime())
              )
            },
            { identity.value },
            identity,
        )
    compose.runOnUiThread { compose.activity.viewModelStore.put("sharing-device", model) }
    compose.setContent {
      SampleAppTheme {
        val (state, toggle) = rememberPositionSharingControl(model)
        AppScaffold(
            CommandoScreens.Home,
            AppMode.Commando,
            onSwitchMode = {},
            onHome = {},
            onProfile = {},
        ) { padding ->
          HomeScreen(
              AppMode.Commando,
              Modifier.padding(padding),
              tripState = HomeTripUiState.Content(trip),
              onManageTrip = {},
              onPublishTrip = {},
              sharingControl = { PositionSharingCard(state, toggle) },
          )
        }
      }
    }
    compose.onNodeWithTag(PositionSharingTestTags.SHARING_TOGGLE).assertIsOff()
    assertEquals(0, reads)
    compose.onNodeWithTag(PositionSharingTestTags.SHARING_TOGGLE).performScrollTo().performClick()
    compose.waitUntil(WAIT_MILLIS) { storage.value.containsKey("owner") }
    compose.onNodeWithTag(PositionSharingTestTags.SHARING_TOGGLE).assertIsOn()
    screenshot("position-sharing-active.png")
    compose.onNodeWithTag(PositionSharingTestTags.SHARING_TOGGLE).performClick()
    compose.waitUntil(WAIT_MILLIS) { storage.value.isEmpty() }
    compose.onNodeWithTag(PositionSharingTestTags.SHARING_TOGGLE).assertIsOff()
    compose.onNodeWithTag(PositionSharingTestTags.SHARING_TOGGLE).performClick()
    compose.waitUntil(WAIT_MILLIS) { storage.value.isNotEmpty() }
    UiDevice.getInstance(instrumentation).pressHome()
    compose.waitUntil(WAIT_MILLIS) { storage.value.isEmpty() }
  }

  private fun screenshot(name: String) {
    compose.waitForIdle()
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val output = File(context.getExternalFilesDir(null), name)
    assertTrue(
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).takeScreenshot(output)
    )
  }

  private fun trip(id: String, owner: String, now: Instant) =
      Trip(
          id,
          owner,
          TripLocations.stores.first(),
          now.plusSeconds(3600),
          TripLocations.handoffPoints.first(),
          TripStatus.PUBLISHED,
          now,
          now,
      )

  private companion object {
    const val WAIT_MILLIS = 15_000L
  }
}
