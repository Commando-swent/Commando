package com.commando.app.ui.navigation

// AI assistance: Claude Code moved these helpers out of CommandoApp so every route builder can
// share them (#322); the shared resume refresh was written with Claude Code earlier.
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavHostController
import com.commando.app.ui.orders.SessionOrderViewModel

/**
 * Calls [onResume] whenever the current destination resumes, including when it is first shown and
 * when the user comes back to it. [key] restarts the observer when the destination's state changes.
 */
@Composable
internal fun RefreshOnResume(key: Any, onResume: () -> Unit) {
  val lifecycleOwner = LocalLifecycleOwner.current
  DisposableEffect(lifecycleOwner, key) {
    val observer = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_RESUME) onResume()
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }
}

/** Reuse an existing top-level destination rather than stacking copies when switching tabs. */
internal fun NavHostController.openScreen(screen: CommandoScreens) {
  if (!popBackStack(screen.name, inclusive = false)) {
    navigate(screen.name) { launchSingleTop = true }
  }
}

/** Clears the previous edit cart when navigation leaves its flow; rotation retains the cart. */
@Composable
internal fun DiscardAbandonedEdit(orders: SessionOrderViewModel, editingOrderId: String?) {
  var previousEditId by remember { mutableStateOf<String?>(null) }
  LaunchedEffect(editingOrderId) {
    if (previousEditId != editingOrderId) previousEditId?.let(orders::finishEdit)
    previousEditId = editingOrderId
  }
}
