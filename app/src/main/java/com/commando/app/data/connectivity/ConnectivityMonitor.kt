package com.commando.app.data.connectivity

// AI assistance: Claude Code (#393).
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn

/**
 * Whether this device currently has a validated internet connection.
 *
 * This is a hint for the UI only: `true` does not mean Firestore is reachable or that a write
 * succeeded, and `false` does not mean an earlier write failed. Repositories keep their own error
 * handling, and only a server response confirms an operation.
 */
interface ConnectivityMonitor {
  val isOnline: StateFlow<Boolean>
}

/**
 * One system callback shared by every collector, registered only while someone collects and
 * released 5 s after the last one stops, so rotations do not re-register it.
 */
class AndroidConnectivityMonitor(context: Context, scope: CoroutineScope) : ConnectivityMonitor {
  private val manager = context.getSystemService(ConnectivityManager::class.java)

  override val isOnline: StateFlow<Boolean> = callbackFlow {
    val callback =
        object : ConnectivityManager.NetworkCallback() {
          override fun onCapabilitiesChanged(
              network: Network,
              capabilities: NetworkCapabilities,
          ) {
            trySend(capabilities.isValidatedInternet())
          }

          override fun onLost(network: Network) {
            trySend(false)
          }
        }
    // No callback fires while no network exists, so start from the current state.
    trySend(isOnlineNow())
    manager.registerDefaultNetworkCallback(callback)
    awaitClose { manager.unregisterNetworkCallback(callback) }
  }
      .distinctUntilChanged()
      .stateIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), isOnlineNow())

  private fun isOnlineNow(): Boolean =
      manager.getNetworkCapabilities(manager.activeNetwork)?.isValidatedInternet() == true

  private fun NetworkCapabilities.isValidatedInternet(): Boolean =
      hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
          hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

  private companion object {
    const val STOP_TIMEOUT_MS = 5_000L
  }
}

/** Connectivity set by hand, for tests and previews. */
class FakeConnectivityMonitor(online: Boolean = true) : ConnectivityMonitor {
  private val state = MutableStateFlow(online)
  override val isOnline: StateFlow<Boolean> = state.asStateFlow()

  fun setOnline(online: Boolean) {
    state.value = online
  }
}
