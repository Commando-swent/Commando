package com.android.sample.ui.map

// AI assistance: OpenAI Codex.
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/** Permission and foreground bridge; this helper owns no layout. Opt-in never happens on resume. */
@Composable
internal fun rememberPositionSharingControl(
    model: PositionSharingViewModel,
): Pair<PositionSharingState, (Boolean) -> Unit> {
  val context = LocalContext.current
  val activity = LocalActivity.current
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  val state by model.uiState.collectAsState()
  var attached by remember { mutableStateOf(true) }
  var pending by rememberSaveable { mutableStateOf(false) }
  val mayStart = { attached && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }
  val permission =
      rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (pending && mayStart()) model.start(it)
        pending = false
      }
  DisposableEffect(model, lifecycle, activity) {
    attached = true
    val observer = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_STOP && activity?.isChangingConfigurations != true) {
        pending = false
        model.stop()
      }
    }
    lifecycle.addObserver(observer)
    onDispose {
      attached = false
      lifecycle.removeObserver(observer)
      if (activity?.isChangingConfigurations != true) {
        pending = false
        model.stop()
      }
    }
  }
  return state to
      { enabled: Boolean ->
        if (!enabled) {
          pending = false
          model.stop()
        } else if (mayStart() && !pending) {
          if (
              ContextCompat.checkSelfPermission(
                  context,
                  Manifest.permission.ACCESS_COARSE_LOCATION,
              ) == PackageManager.PERMISSION_GRANTED
          )
              model.start(true)
          else {
            pending = true
            permission.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
          }
        }
      }
}
