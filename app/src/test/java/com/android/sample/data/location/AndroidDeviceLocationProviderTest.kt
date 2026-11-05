package com.android.sample.data.location

// AI assistance: OpenAI Codex.
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import androidx.core.location.LocationManagerCompat
import androidx.core.util.Consumer
import com.android.sample.model.DeviceLocation
import io.mockk.*
import java.util.concurrent.Executor
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class AndroidDeviceLocationProviderTest {
  private val context = mockk<Context>()
  private val manager = mockk<LocationManager>()
  private val callback = slot<Consumer<Location?>>()
  private val cancellation = slot<CancellationSignal>()
  private lateinit var provider: AndroidDeviceLocationProvider

  @Before
  fun setup() {
    every { context.applicationContext } returns context
    every {
      context.checkPermission(Manifest.permission.ACCESS_COARSE_LOCATION, any(), any())
    } returns PackageManager.PERMISSION_GRANTED
    every { context.getSystemService(LocationManager::class.java) } returns manager
    every { context.mainExecutor } returns Executor { it.run() }
    every { manager.isLocationEnabled } returns true
    every { manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) } returns true
    mockkStatic(LocationManagerCompat::class)
    every {
      LocationManagerCompat.getCurrentLocation(
          manager,
          LocationManager.NETWORK_PROVIDER,
          capture(cancellation),
          any(),
          capture(callback),
      )
    } just Runs
    provider = AndroidDeviceLocationProvider(context)
  }

  @After
  fun teardown() {
    unmockkStatic(LocationManagerCompat::class)
  }

  @Test
  fun denialNeverReadsLocationServices() = runTest {
    every { context.checkPermission(any(), any(), any()) } returns PackageManager.PERMISSION_DENIED
    assertEquals(DeviceLocationResult.PermissionDenied, provider.currentLocation())
    verify(exactly = 0) { context.getSystemService(LocationManager::class.java) }
  }

  @Test
  fun missingServiceIsUnavailable() = runTest {
    every { context.getSystemService(LocationManager::class.java) } returns null
    assertEquals(DeviceLocationResult.Unavailable, provider.currentLocation())
  }

  @Test
  fun disabledServicesAreReported() = runTest {
    every { manager.isLocationEnabled } returns false
    assertEquals(DeviceLocationResult.ServicesDisabled, provider.currentLocation())
  }

  @Test
  fun unavailableNetworkDoesNotRequestFineLocationOrGps() = runTest {
    every { manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) } returns false
    assertEquals(DeviceLocationResult.Unavailable, provider.currentLocation())
    verify(exactly = 0) {
      LocationManagerCompat.getCurrentLocation(
          any(),
          any(),
          any<CancellationSignal>(),
          any(),
          any(),
      )
    }
  }

  @Test
  fun convertsSingleFixWithAccuracyAndMonotonicTime() = runTest {
    val pending = async { provider.currentLocation() }
    runCurrent()
    val fix =
        Location(LocationManager.NETWORK_PROVIDER).apply {
          latitude = 46.52
          longitude = 6.56
          accuracy = 150f
          elapsedRealtimeNanos = 123_000_000L
        }
    callback.captured.accept(fix)
    assertEquals(
        DeviceLocationResult.Available(DeviceLocation(46.52, 6.56, 150f, 123)),
        pending.await(),
    )
  }

  @Test
  fun nullFixIsUnavailable() = runTest {
    val pending = async { provider.currentLocation() }
    runCurrent()
    callback.captured.accept(null)
    assertEquals(DeviceLocationResult.Unavailable, pending.await())
  }

  @Test
  fun fixWithoutAccuracyIsUnavailable() = runTest {
    val pending = async { provider.currentLocation() }
    runCurrent()
    callback.captured.accept(Location(LocationManager.NETWORK_PROVIDER))
    assertEquals(DeviceLocationResult.Unavailable, pending.await())
  }

  @Test
  fun cancellationCancelsPlatformAndLateCallbackIsIgnored() = runTest {
    val pending = async { provider.currentLocation() }
    runCurrent()
    pending.cancel()
    runCurrent()
    assertTrue(cancellation.captured.isCanceled)
    callback.captured.accept(null)
    assertTrue(pending.isCancelled)
  }

  @Test
  fun revokedPermissionAndProviderErrorsAreMapped() = runTest {
    listOf(
            SecurityException() to DeviceLocationResult.PermissionDenied,
            IllegalArgumentException() to DeviceLocationResult.Unavailable,
            IllegalStateException() to DeviceLocationResult.Failure,
        )
        .forEach { (error, expected) ->
          every { manager.isLocationEnabled } throws error
          assertEquals(expected, provider.currentLocation())
        }
  }

  @Test
  fun revocationWhileRegisteringRequestIsAlsoMapped() = runTest {
    every {
      LocationManagerCompat.getCurrentLocation(
          any(),
          any(),
          any<CancellationSignal>(),
          any(),
          any(),
      )
    } throws SecurityException()
    assertEquals(DeviceLocationResult.PermissionDenied, provider.currentLocation())
  }

  @Test
  fun cancellationPropagatesInsteadOfReturningFailure() = runTest {
    var recorded: DeviceLocationResult? = null
    val job = launch { recorded = provider.currentLocation() }
    runCurrent()
    job.cancel()
    runCurrent()
    assertNull(recorded)
    assertTrue(job.isCancelled)
  }
}
