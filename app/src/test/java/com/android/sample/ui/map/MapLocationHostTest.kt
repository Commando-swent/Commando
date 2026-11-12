package com.android.sample.ui.map

// AI assistance: OpenAI Codex.
import android.Manifest
import android.app.Application
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import com.android.sample.data.location.*
import com.android.sample.model.DeviceLocation
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MapLocationHostTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
  private var ended by mutableStateOf(false)
  private var visible by mutableStateOf(true)
  private var calls = 0
  private var cancelled = false
  private var read: suspend () -> DeviceLocationResult = { DeviceLocationResult.ServicesDisabled }
  private var observed: MapLocationUiState = MapLocationUiState.Idle
  private val owner =
      object : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle
          get() = registry
      }
  private var pendingPermissionCode = 0
  private val permissions = mutableListOf<String>()
  private val registry =
      object : ActivityResultRegistry() {
        override fun <I, O> onLaunch(
            requestCode: Int,
            contract: ActivityResultContract<I, O>,
            input: I,
            options: ActivityOptionsCompat?,
        ) {
          pendingPermissionCode = requestCode
          permissions.add(input as String)
        }
      }
  private val permissionOwner =
      object : ActivityResultRegistryOwner {
        override val activityResultRegistry = registry
      }

  @Before
  fun setup() {
    shadowOf(ApplicationProvider.getApplicationContext<Application>())
        .denyPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
    owner.registry.currentState = Lifecycle.State.RESUMED
  }

  private fun show(granted: Boolean = false) {
    if (granted)
        shadowOf(ApplicationProvider.getApplicationContext<Application>())
            .grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
    val provider = DeviceLocationProvider {
      calls++
      read()
    }
    compose.setContent {
      CompositionLocalProvider(
          LocalLifecycleOwner provides owner,
          LocalActivityResultRegistryOwner provides permissionOwner,
      ) {
        if (visible)
            MapLocationHost(ended, provider) { state, locate ->
              SideEffect { observed = state }
              TextButton(locate, Modifier.testTag(HostTestTags.LOCATE)) { Text("Locate") }
            }
      }
    }
  }

  @Test
  fun entryDoesNotAskPermissionAndDenialDoesNotReadProvider() {
    show()
    assertEquals(0, calls)
    assertTrue(permissions.isEmpty())
    compose.onNodeWithTag(HostTestTags.LOCATE).performClick()
    assertEquals(listOf(Manifest.permission.ACCESS_COARSE_LOCATION), permissions)
    compose.runOnIdle { registry.dispatchResult(pendingPermissionCode, false) }
    compose.waitForIdle()
    assertEquals(MapLocationUiState.Resolved(DeviceLocationResult.PermissionDenied), observed)
    assertEquals(0, calls)
  }

  @Test
  fun grantReadsProviderAndAlreadyGrantedSkipsDialog() {
    show()
    compose.onNodeWithTag(HostTestTags.LOCATE).performClick()
    compose.runOnIdle { registry.dispatchResult(pendingPermissionCode, true) }
    compose.waitForIdle()
    assertEquals(1, calls)
    assertEquals(MapLocationUiState.Resolved(DeviceLocationResult.ServicesDisabled), observed)
    compose.runOnIdle {
      shadowOf(ApplicationProvider.getApplicationContext<Application>())
          .grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
    }
    compose.onNodeWithTag(HostTestTags.LOCATE).performClick()
    assertEquals(2, calls)
    assertEquals(1, permissions.size)
  }

  @Test
  fun backgroundCancelsAcquisitionAndResumeDoesNotRestartIt() {
    read = {
      try {
        awaitCancellation()
      } finally {
        cancelled = true
      }
    }
    show(granted = true)
    compose.onNodeWithTag(HostTestTags.LOCATE).performClick()
    compose.waitForIdle()
    assertEquals(MapLocationUiState.Loading, observed)
    compose.onNodeWithTag(HostTestTags.LOCATE).performClick()
    assertEquals(1, calls)
    compose.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
    compose.waitForIdle()
    assertTrue(cancelled)
    assertEquals(MapLocationUiState.Idle, observed)
    compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
    assertEquals(1, calls)
  }

  @Test
  fun latePermissionResultAfterBackgroundIsIgnored() {
    show()
    compose.onNodeWithTag(HostTestTags.LOCATE).performClick()
    compose.runOnIdle {
      owner.registry.currentState = Lifecycle.State.CREATED
      owner.registry.currentState = Lifecycle.State.RESUMED
      registry.dispatchResult(pendingPermissionCode, true)
    }
    compose.waitForIdle()
    assertEquals(0, calls)
  }

  @Test
  fun sessionEndClearsCoordinatesAndBlocksFurtherRequests() {
    read = {
      DeviceLocationResult.Available(
          DeviceLocation(46.52, 6.56, 150f, SystemClock.elapsedRealtime())
      )
    }
    show(granted = true)
    compose.onNodeWithTag(HostTestTags.LOCATE).performClick()
    compose.runOnIdle { ended = true }
    compose.waitForIdle()
    assertEquals(MapLocationUiState.Idle, observed)
    compose.onNodeWithTag(HostTestTags.LOCATE).performClick()
    assertEquals(1, calls)
  }

  @Test
  fun leavingCompositionCancelsPendingAcquisition() {
    read = {
      try {
        awaitCancellation()
      } finally {
        cancelled = true
      }
    }
    show(granted = true)
    compose.onNodeWithTag(HostTestTags.LOCATE).performClick()
    compose.runOnIdle { visible = false }
    compose.waitForIdle()
    assertTrue(cancelled)
  }

  @Test
  fun configurationStopPreservesPendingAcquisition() {
    read = {
      try {
        awaitCancellation()
      } finally {
        cancelled = true
      }
    }
    show(granted = true)
    compose.onNodeWithTag(HostTestTags.LOCATE).performClick()
    compose.waitForIdle()
    compose.runOnIdle {
      ReflectionHelpers.setField(compose.activity, "mChangingConfigurations", true)
      owner.registry.currentState = Lifecycle.State.CREATED
    }
    compose.waitForIdle()
    assertFalse(cancelled)
    assertEquals(MapLocationUiState.Loading, observed)
    compose.runOnIdle {
      ReflectionHelpers.setField(compose.activity, "mChangingConfigurations", false)
      owner.registry.currentState = Lifecycle.State.RESUMED
      owner.registry.currentState = Lifecycle.State.CREATED
    }
    compose.waitForIdle()
    assertTrue(cancelled)
  }
}

private object HostTestTags {
  const val LOCATE = "location_host_request"
}
