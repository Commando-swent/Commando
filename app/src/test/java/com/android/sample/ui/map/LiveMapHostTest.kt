package com.android.sample.ui.map

// AI assistance: OpenAI Codex.
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.mockk.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Returning to an entry restarts once; background retry and removed entries cannot subscribe. */
@RunWith(RobolectricTestRunner::class)
class LiveMapHostTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

  @Test
  fun resumePauseRetryAndDisposalOwnTheSubscription() {
    val model = mockk<LiveMapViewModel>(relaxed = true)
    every { model.uiState } returns MutableStateFlow(LiveMapState.Loading)
    val owner =
        object : LifecycleOwner {
          val registry = LifecycleRegistry.createUnsafe(this)
          override val lifecycle: Lifecycle
            get() = registry
        }
    owner.registry.currentState = Lifecycle.State.RESUMED
    var attached by mutableStateOf(true)
    var retry: () -> Unit = {}
    compose.setContent {
      CompositionLocalProvider(LocalLifecycleOwner provides owner) {
        if (attached)
            LiveMapHost(model) { _, action ->
              retry = action
              Text("Map")
            }
      }
    }
    compose.waitForIdle()
    verify(exactly = 1) { model.start() }
    compose.runOnIdle {
      owner.registry.currentState = Lifecycle.State.STARTED
      retry()
    }
    verify(exactly = 1) { model.stop() }
    verify(exactly = 1) { model.start() }
    compose.runOnIdle {
      owner.registry.currentState = Lifecycle.State.RESUMED
      retry()
    }
    verify(exactly = 3) { model.start() }
    compose.runOnIdle { attached = false }
    compose.waitForIdle()
    verify(exactly = 2) { model.stop() }
    unmockkAll()
  }
}
