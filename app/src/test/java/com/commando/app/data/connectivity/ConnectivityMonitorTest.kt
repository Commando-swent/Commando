package com.commando.app.data.connectivity

// AI assistance: Claude Code (#393) wrote these monitor tests.
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectivityMonitorTest {
  private val context = ApplicationProvider.getApplicationContext<Context>()
  private val manager = context.getSystemService(ConnectivityManager::class.java)
  private val shadow = shadowOf(manager)
  private val network = ShadowNetwork.newInstance(7)

  private fun capabilities(vararg caps: Int): NetworkCapabilities =
      ShadowNetworkCapabilities.newInstance().also { c ->
        caps.forEach { shadowOf(c).addCapability(it) }
      }

  private val validated =
      capabilities(
          NetworkCapabilities.NET_CAPABILITY_INTERNET,
          NetworkCapabilities.NET_CAPABILITY_VALIDATED,
      )

  private fun startOnline(online: Boolean) {
    val active = manager.activeNetwork
    shadow.setNetworkCapabilities(active, if (online) validated else capabilities())
  }

  private fun TestScope.collect(monitor: ConnectivityMonitor, into: MutableList<Boolean>) =
      backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
        monitor.isOnline.collect { into += it }
      }

  @Test
  fun initialValueReflectsCurrentValidatedConnectivity() = runTest {
    startOnline(true)
    assertEquals(true, AndroidConnectivityMonitor(context, backgroundScope).isOnline.value)
    startOnline(false)
    assertEquals(false, AndroidConnectivityMonitor(context, backgroundScope).isOnline.value)
  }

  @Test
  fun emitsTransitionsOnceAndTreatsUnvalidatedNetworksAsOffline() = runTest {
    startOnline(true)
    val monitor = AndroidConnectivityMonitor(context, backgroundScope)
    val values = mutableListOf<Boolean>()
    collect(monitor, values)
    runCurrent()
    val callback = shadow.networkCallbacks.single()

    callback.onLost(network)
    runCurrent()
    callback.onCapabilitiesChanged(network, validated)
    callback.onCapabilitiesChanged(network, validated)
    runCurrent()
    callback.onCapabilitiesChanged(
        network,
        capabilities(NetworkCapabilities.NET_CAPABILITY_INTERNET),
    )
    callback.onLost(network)
    runCurrent()

    assertEquals(listOf(true, false, true, false), values)
    assertEquals(false, monitor.isOnline.value)
  }

  @Test
  fun collectorsShareOneCallbackThatIsReleasedFiveSecondsAfterTheLastStops() = runTest {
    startOnline(true)
    val monitor = AndroidConnectivityMonitor(context, backgroundScope)
    assertEquals(0, shadow.networkCallbacks.size)
    val first = collect(monitor, mutableListOf())
    val second = collect(monitor, mutableListOf())
    runCurrent()
    assertEquals(1, shadow.networkCallbacks.size)

    first.cancel()
    second.cancel()
    advanceTimeBy(4_999)
    runCurrent()
    assertEquals("Released before the stop timeout", 1, shadow.networkCallbacks.size)
    advanceTimeBy(2)
    runCurrent()
    assertEquals(0, shadow.networkCallbacks.size)

    collect(monitor, mutableListOf())
    runCurrent()
    assertEquals(1, shadow.networkCallbacks.size)
  }

  @Test
  fun fakeMonitorEmitsWhatSetOnlineSets() = runTest {
    val fake = FakeConnectivityMonitor(online = false)
    val values = mutableListOf<Boolean>()
    collect(fake, values)
    fake.setOnline(true)
    fake.setOnline(true)
    fake.setOnline(false)
    assertEquals(listOf(false, true, false), values)
  }
}
