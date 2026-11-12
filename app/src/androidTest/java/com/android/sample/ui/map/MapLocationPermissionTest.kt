package com.android.sample.ui.map

// AI assistance: OpenAI Codex.

import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import com.android.sample.data.location.*
import com.android.sample.model.*
import com.android.sample.ui.theme.SampleAppTheme
import java.time.Instant
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the real permission dialog with an injected fix, independently of network services. */
@RunWith(AndroidJUnit4::class)
class MapLocationPermissionTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

  @Test
  fun permissionIsRequestedOnlyOnLocateAndPositionAppearsAfterGrant() {
    var reads = 0
    val now = Instant.now()
    val point = TripLocations.handoffPoints.first { it.name == "CM building" }
    val trip =
        Trip(
            "actual-id",
            "owner",
            TripLocations.stores.first(),
            now.plusSeconds(3600),
            point,
            TripStatus.PUBLISHED,
            now,
            now,
        )
    val provider = DeviceLocationProvider {
      reads++
      DeviceLocationResult.Available(
          DeviceLocation(point.latitude, point.longitude, 150f, SystemClock.elapsedRealtime())
      )
    }
    compose.setContent {
      SampleAppTheme {
        MapLocationHost(false, provider) { location, locate ->
          TripMapScreen(
              TripMapUiState.Content(listOf(trip)),
              {},
              {},
              {},
              {},
              {},
              locationState = location,
              onLocate = locate,
          )
        }
      }
    }
    compose.onNodeWithTag(MapLocationTestTags.POSITION).assertDoesNotExist()
    assertEquals(0, reads)
    compose.onNodeWithTag(TripMapTestTags.LOCATE).performClick()
    val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    val button =
        device.wait(
            Until.findObject(By.res(ALLOW_FOREGROUND_BUTTON_ID)),
            DEVICE_WAIT_MILLIS,
        )
    assertNotNull("The real Android approximate-location dialog must open", button)
    button.click()
    compose.waitUntil(DEVICE_WAIT_MILLIS) { reads == 1 }
    compose.onNodeWithTag(MapLocationTestTags.POSITION).assertIsDisplayed()
  }

  private companion object {
    const val DEVICE_WAIT_MILLIS = 5_000L
    const val ALLOW_FOREGROUND_BUTTON_ID =
        "com.android.permissioncontroller:id/permission_allow_foreground_only_button"
  }
}
