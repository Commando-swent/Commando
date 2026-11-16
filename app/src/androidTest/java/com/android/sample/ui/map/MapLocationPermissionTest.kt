package com.android.sample.ui.map

// AI assistance: OpenAI Codex.

import android.Manifest
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import com.android.sample.data.location.*
import com.android.sample.ui.theme.SampleAppTheme
import com.commando.app.model.*
import java.time.Instant
import java.util.regex.Pattern
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises Locate with the real OS permission state; acquisition always requires an explicit tap.
 */
@RunWith(AndroidJUnit4::class)
class MapLocationPermissionTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

  @Test
  fun permissionIsRequestedOnlyOnLocateAndPositionAppearsAfterGrant() {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val context = instrumentation.targetContext
    fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
    if (!hasLocationPermission()) {
      // A previous denied run must not make Android suppress the permission dialog forever.
      instrumentation.uiAutomation
          .executeShellCommand(
              "pm clear-permission-flags ${context.packageName} ${Manifest.permission.ACCESS_COARSE_LOCATION} user-set user-fixed"
          )
          .use { descriptor ->
            java.io.FileInputStream(descriptor.fileDescriptor).use { it.readBytes() }
          }
    }
    val device = UiDevice.getInstance(instrumentation)
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
    // A resumed activity can still be waiting for the previous window to release focus.
    compose.waitUntil(DEVICE_WAIT_MILLIS) {
      compose.runOnUiThread {
        val activity = compose.activity
        activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
            activity.hasWindowFocus()
      }
    }
    val permissionWasGranted = hasLocationPermission()
    val locate = compose.onNodeWithTag(TripMapTestTags.LOCATE).assertIsDisplayed()
    locate.performSemanticsAction(SemanticsActions.OnClick) { it() }
    if (!permissionWasGranted) {
      val button =
          device.wait(
              Until.findObject(By.res(ALLOW_PERMISSION_BUTTON_ID)),
              DEVICE_WAIT_MILLIS,
          )
      if (button != null) button.click()
      else
          assertTrue(
              "Locate did not open the Android location permission dialog",
              hasLocationPermission(),
          )
    }
    compose.waitUntil(DEVICE_WAIT_MILLIS) { reads == 1 }
    assertTrue("Android did not grant approximate location", hasLocationPermission())
    compose.onNodeWithTag(MapLocationTestTags.POSITION).assertIsDisplayed()
  }

  private companion object {
    const val DEVICE_WAIT_MILLIS = 15_000L
    val ALLOW_PERMISSION_BUTTON_ID: Pattern =
        Pattern.compile("[^:]+:id/permission_allow_(foreground_only_)?button")
  }
}
