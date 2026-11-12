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
import androidx.lifecycle.viewmodel.compose.viewModel
import com.android.sample.data.location.AndroidDeviceLocationProvider
import com.android.sample.data.location.DeviceLocationProvider

/**
 * Slot-only permission/lifecycle bridge; it owns no layout root or visual modifier. Acquisition
 * state stays in the ViewModel; the host releases it on background or session end.
 */
@Composable
internal fun MapLocationHost(
    sessionEnded: Boolean,
    provider: DeviceLocationProvider? = null,
    content: @Composable (MapLocationUiState, () -> Unit) -> Unit,
) {
  val context = LocalContext.current
  val activity = LocalActivity.current
  val source = provider ?: remember(context) { AndroidDeviceLocationProvider(context) }
  val model: MapLocationViewModel = viewModel { MapLocationViewModel(source) }
  val state by model.uiState.collectAsState()
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  val ended by rememberUpdatedState(sessionEnded)
  var attached by remember { mutableStateOf(true) }
  var permissionPending by rememberSaveable { mutableStateOf(false) }
  val canRequest = {
    attached && !ended && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
  }
  val permission =
      rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (permissionPending && canRequest()) model.request(it)
        permissionPending = false
      }
  DisposableEffect(model, lifecycle, activity) {
    attached = true
    val observer = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_RESUME) model.refreshLocationValidity()
      if (event == Lifecycle.Event.ON_STOP && activity?.isChangingConfigurations != true) {
        permissionPending = false
        model.stop()
      }
    }
    lifecycle.addObserver(observer)
    onDispose {
      attached = false
      lifecycle.removeObserver(observer)
      if (activity?.isChangingConfigurations != true) {
        permissionPending = false
        model.stop()
      }
    }
  }
  LaunchedEffect(sessionEnded) {
    if (sessionEnded) {
      permissionPending = false
      model.stop()
    }
  }
  content(if (sessionEnded) MapLocationUiState.Idle else state) {
    if (canRequest() && state != MapLocationUiState.Loading) {
      if (
          ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
              PackageManager.PERMISSION_GRANTED
      )
          model.request(true)
      else if (!permissionPending) {
        permissionPending = true
        permission.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
      }
    }
  }
}
