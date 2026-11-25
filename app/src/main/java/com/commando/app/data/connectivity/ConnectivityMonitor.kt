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
 * Whether the device has a validated internet connection: a UI hint only. `true` never means a
 * write succeeded and `false` never means one failed; only a server response confirms an operation.
 */
interface ConnectivityMonitor {
  val isOnline: StateFlow<Boolean>
}

/** One system callback shared by all collectors, released 5 s after the last one stops. */
class AndroidConnectivityMonitor(context: Context, scope: CoroutineScope) : ConnectivityMonitor {
  private val manager = context.getSystemService(ConnectivityManager::class.java)

  override val isOnline: StateFlow<Boolean> = callbackFlow {
    val lock = Any() // Orders the initial read with the callbacks, which run on another thread.
    val callback =
        object : ConnectivityManager.NetworkCallback() {
          override fun onCapabilitiesChanged(
              network: Network,
              capabilities: NetworkCapabilities,
          ) {
            synchronized(lock) { trySend(capabilities.isValidatedInternet()) }
          }

          override fun onLost(network: Network) {
            synchronized(lock) { trySend(false) }
          }
        }
    manager.registerDefaultNetworkCallback(callback)
    // Read only once registered: no callback fires while no network exists, and a disconnect during
    // registration would otherwise leave an earlier `true` in place.
    synchronized(lock) { trySend(isOnlineNow()) }
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
