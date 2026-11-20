package com.android.sample.ui.map

// AI assistance: OpenAI Codex.
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/** Owns live-map subscriptions only while this navigation entry is resumed. */
@Composable
internal fun LiveMapHost(
    model: LiveMapViewModel,
    content: @Composable (LiveMapState, () -> Unit) -> Unit,
) {
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  DisposableEffect(model, lifecycle) {
    val observer = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_RESUME) model.start()
      else if (event == Lifecycle.Event.ON_PAUSE) model.stop()
    }
    lifecycle.addObserver(observer)
    onDispose {
      lifecycle.removeObserver(observer)
      model.stop()
    }
  }
  val state by model.uiState.collectAsState()
  content(state) { if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) model.start() }
}
