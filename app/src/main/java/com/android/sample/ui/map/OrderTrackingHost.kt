package com.android.sample.ui.map

// AI assistance: OpenAI Codex.
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/** Starts only while resumed; leaving the map cancels observation and clears coordinates. */
@Composable
internal fun OrderTrackingHost(model: OrderTrackingViewModel, onBack: () -> Unit) {
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
  TripMapScreen(
      TripMapUiState.Content(emptyList()),
      model::start,
      {},
      {},
      {},
      onBack,
      tracking = state,
  )
}

/** Stable selectors for the selected-order map. */
object OrderTrackingTestTags {
  const val MARKER = "order_tracking_marker"
}
