package com.android.sample.ui.map

// AI assistance: OpenAI Codex.
import android.Manifest
import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.*
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import io.mockk.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.util.ReflectionHelpers

/** Permission results must belong to an explicit opt-in in the current foreground lifecycle. */
@RunWith(RobolectricTestRunner::class)
class PositionSharingHostTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
  private val model = mockk<PositionSharingViewModel>(relaxed = true)
  private val lifecycle =
      object : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle
          get() = registry
      }
  private val attached = mutableStateOf(true)
  private val eligibleRole = mutableStateOf(true)
  private var request = 0
  private val registry =
      object : ActivityResultRegistry() {
        override fun <I, O> onLaunch(
            requestCode: Int,
            contract: ActivityResultContract<I, O>,
            input: I,
            options: ActivityOptionsCompat?,
        ) {
          request = requestCode
        }
      }
  private val permissionOwner =
      object : ActivityResultRegistryOwner {
        override val activityResultRegistry = registry
      }

  @Before
  fun show() {
    every { model.uiState } returns MutableStateFlow(PositionSharingState.Off)
    shadowOf(ApplicationProvider.getApplicationContext<Application>())
        .denyPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
    lifecycle.registry.currentState = Lifecycle.State.RESUMED
    compose.setContent {
      CompositionLocalProvider(
          LocalLifecycleOwner provides lifecycle,
          LocalActivityResultRegistryOwner provides permissionOwner,
      ) {
        if (attached.value) {
          val (state, toggle) = rememberPositionSharingControl(model, enabled = eligibleRole.value)
          PositionSharingCard(state, toggle)
        }
      }
    }
  }

  @After fun clear() = unmockkAll()

  @Test
  fun latePermissionAfterRoleExitCannotStartSharing() {
    compose.onNodeWithTag(PositionSharingTestTags.SHARING_TOGGLE).performClick()
    compose.runOnIdle { eligibleRole.value = false }
    compose.waitForIdle()
    compose.runOnIdle { registry.dispatchResult(request, true) }
    compose.waitForIdle()
    verify(exactly = 0) { model.start(any()) }
  }

  @Test
  fun disposingTheHostStartsViewModelOwnedCleanup() {
    compose.runOnIdle { attached.value = false }
    compose.waitForIdle()
    verify(exactly = 1) { model.requestStop() }
  }

  @Test
  fun denialThenGrantOnlyStartsAfterEachExplicitTap() {
    verify(exactly = 0) { model.start(any()) }
    compose.onNodeWithTag(PositionSharingTestTags.SHARING_TOGGLE).performClick()
    compose.runOnIdle { registry.dispatchResult(request, false) }
    compose.waitForIdle()
    verify { model.start(false) }
    compose.onNodeWithTag(PositionSharingTestTags.SHARING_TOGGLE).performClick()
    compose.runOnIdle { registry.dispatchResult(request, true) }
    compose.waitForIdle()
    verify(exactly = 1) { model.start(true) }
  }

  @Test
  fun configurationStopPreservesForegroundConsent() {
    shadowOf(ApplicationProvider.getApplicationContext<Application>())
        .grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
    compose.onNodeWithTag(PositionSharingTestTags.SHARING_TOGGLE).performClick()
    compose.runOnIdle {
      ReflectionHelpers.setField(compose.activity, "mChangingConfigurations", true)
      lifecycle.registry.currentState = Lifecycle.State.CREATED
    }
    compose.waitForIdle()
    verify(exactly = 1) { model.start(true) }
    verify(exactly = 0) { model.requestStop() }
  }

  @Test
  fun latePermissionAfterBackgroundCannotRestartSharing() {
    compose.onNodeWithTag(PositionSharingTestTags.SHARING_TOGGLE).performClick()
    compose.runOnIdle {
      lifecycle.registry.currentState = Lifecycle.State.CREATED
      lifecycle.registry.currentState = Lifecycle.State.RESUMED
      registry.dispatchResult(request, true)
    }
    compose.waitForIdle()
    verify(exactly = 0) { model.start(any()) }
    verify { model.requestStop() }
  }
}
