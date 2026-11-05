package com.android.sample.ui.map

// AI assistance: OpenAI Codex.
import androidx.lifecycle.ViewModelStore
import com.android.sample.MainDispatcherRule
import com.android.sample.data.location.DeviceLocationProvider
import com.android.sample.data.location.DeviceLocationResult
import com.android.sample.model.DeviceLocation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.*
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MapLocationViewModelTest {
  @get:Rule val main = MainDispatcherRule()
  private val position = DeviceLocationResult.Available(DeviceLocation(46.52, 6.56, 200f, 200_000))
  private var calls = 0
  private var elapsedMillis = 200_000L
  private var read: suspend () -> DeviceLocationResult = { position }

  private fun model() =
      MapLocationViewModel(
          DeviceLocationProvider {
            calls++
            read()
          },
          { elapsedMillis },
      )

  private fun result(model: MapLocationViewModel) =
      (model.uiState.value as MapLocationUiState.Resolved).result

  @Test
  fun constructionDoesNotReadLocation() = runTest {
    val model = model()
    advanceUntilIdle()
    assertEquals(MapLocationUiState.Idle, model.uiState.value)
    assertEquals(0, calls)
  }

  @Test
  fun denialDoesNotReadLocation() = runTest {
    val model = model()
    model.request(false)
    assertEquals(DeviceLocationResult.PermissionDenied, result(model))
    assertEquals(0, calls)
  }

  @Test
  fun requestAfterDenialReadsLocation() = runTest {
    val model = model()
    model.request(false)
    model.request(true)
    assertEquals(MapLocationUiState.Loading, model.uiState.value)
    runCurrent()
    assertEquals(position, result(model))
    assertEquals(1, calls)
  }

  @Test
  fun exposesEveryExpectedProviderFallback() = runTest {
    val model = model()
    listOf(
            DeviceLocationResult.PermissionDenied,
            DeviceLocationResult.ServicesDisabled,
            DeviceLocationResult.Unavailable,
            DeviceLocationResult.Failure,
        )
        .forEach { fallback ->
          read = { fallback }
          model.request(true)
          advanceUntilIdle()
          assertEquals(fallback, result(model))
        }
  }

  @Test
  fun rejectsStaleFutureAndInvalidPositions() = runTest {
    val model = model()
    listOf(
            position.location.copy(elapsedRealtimeMillis = 0),
            position.location.copy(elapsedRealtimeMillis = 200_001),
            position.location.copy(latitude = Double.NaN),
        )
        .forEach { fix ->
          read = { DeviceLocationResult.Available(fix) }
          model.request(true)
          advanceUntilIdle()
          assertEquals(DeviceLocationResult.Unavailable, result(model))
        }
  }

  @Test
  fun timeoutCancelsAcquisitionAndAllowsRetry() = runTest {
    var cancelled = false
    read = {
      try {
        awaitCancellation()
      } finally {
        cancelled = true
      }
    }
    val model = model()
    model.request(true)
    advanceUntilIdle()
    assertTrue(cancelled)
    assertEquals(DeviceLocationResult.Unavailable, result(model))
    read = { position }
    model.request(true)
    runCurrent()
    assertEquals(position, result(model))
  }

  @Test
  fun stopForgetsResolvedCoordinates() = runTest {
    val model = model()
    model.request(true)
    runCurrent()
    model.stop()
    assertEquals(MapLocationUiState.Idle, model.uiState.value)
  }

  @Test
  fun stopCancelsPendingRequest() = runTest {
    var cancelled = false
    read = {
      try {
        awaitCancellation()
      } finally {
        cancelled = true
      }
    }
    val model = model()
    model.request(true)
    runCurrent()
    model.stop()
    advanceUntilIdle()
    assertTrue(cancelled)
    assertEquals(MapLocationUiState.Idle, model.uiState.value)
  }

  @Test
  fun supersededReplyCannotOverrideNewPermissionDenial() = runTest {
    val pending = CompletableDeferred<DeviceLocationResult>()
    read = { withContext(NonCancellable) { pending.await() } }
    val model = model()
    model.request(true)
    runCurrent()
    model.request(false)
    pending.complete(position)
    advanceUntilIdle()
    assertEquals(DeviceLocationResult.PermissionDenied, result(model))
  }

  @Test
  fun clearingViewModelCancelsPlatformRequest() = runTest {
    var cancelled = false
    read = {
      try {
        awaitCancellation()
      } finally {
        cancelled = true
      }
    }
    val model = model()
    val store = ViewModelStore()
    store.put("location", model)
    model.request(true)
    runCurrent()
    store.clear()
    advanceUntilIdle()
    assertTrue(cancelled)
  }

  @Test
  fun retainedFixExpiresAfterMaxAge() = runTest {
    val model = model()
    model.request(true)
    runCurrent()
    assertEquals(position, result(model))
    advanceTimeBy(DeviceLocation.MAX_AGE_MILLIS - 1)
    runCurrent()
    assertEquals(position, result(model))
    advanceTimeBy(1)
    runCurrent()
    assertEquals(MapLocationUiState.Idle, model.uiState.value)
  }

  @Test
  fun unexpectedProviderExceptionIsReportedAsFailure() = runTest {
    read = { throw UnsupportedOperationException("Platform location failure") }
    val model = model()
    model.request(true)
    runCurrent()
    assertEquals(DeviceLocationResult.Failure, result(model))
  }

  @Test
  fun providerCancellationIsNotPresentedAsFailure() = runTest {
    read = { throw CancellationException("Location request cancelled") }
    val model = model()
    model.request(true)
    runCurrent()
    assertEquals(MapLocationUiState.Loading, model.uiState.value)
    model.stop()
  }

  @Test
  fun resumeDropsExpiredFixEvenWhenTheExpiryTimerHasNotAdvanced() = runTest {
    val model = model()
    model.request(true)
    runCurrent()
    elapsedMillis += DeviceLocation.MAX_AGE_MILLIS + 1
    model.refreshLocationValidity()
    assertEquals(MapLocationUiState.Idle, model.uiState.value)
    assertEquals(1, calls)
  }

  @Test
  fun resumeDropsFixAtItsExactExpiryBoundary() = runTest {
    val model = model()
    model.request(true)
    runCurrent()
    elapsedMillis += DeviceLocation.MAX_AGE_MILLIS
    model.refreshLocationValidity()
    assertEquals(MapLocationUiState.Idle, model.uiState.value)
  }

  @Test
  fun resumeKeepsFreshFixWithoutAnotherProviderRead() = runTest {
    val model = model()
    model.request(true)
    runCurrent()
    elapsedMillis += 1
    model.refreshLocationValidity()
    assertEquals(position, result(model))
    assertEquals(1, calls)
    model.stop()
  }

  @Test
  fun resumeWithoutAFixDoesNotAcquireLocation() = runTest {
    val model = model()
    model.refreshLocationValidity()
    assertEquals(MapLocationUiState.Idle, model.uiState.value)
    assertEquals(0, calls)
  }
}
