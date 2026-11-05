package com.android.sample.ui.map

// AI assistance: OpenAI Codex.
import androidx.lifecycle.ViewModelStore
import com.android.sample.MainDispatcherRule
import com.android.sample.data.location.DeviceLocationProvider
import com.android.sample.data.location.DeviceLocationResult
import com.android.sample.model.DeviceLocation
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
  private var read: suspend () -> DeviceLocationResult = { position }

  private fun model() =
      MapLocationViewModel(
          DeviceLocationProvider {
            calls++
            read()
          },
          { 200_000 },
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
  fun denialDoesNotReadAndRetryAfterGrantSucceeds() = runTest {
    val model = model()
    model.request(false)
    assertEquals(DeviceLocationResult.PermissionDenied, result(model))
    assertEquals(0, calls)
    model.request(true)
    assertEquals(MapLocationUiState.Loading, model.uiState.value)
    advanceUntilIdle()
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
    advanceUntilIdle()
    assertEquals(position, result(model))
  }

  @Test
  fun leavingScreenCancelsRequestAndForgetsResolvedCoordinates() = runTest {
    val model = model()
    model.request(true)
    advanceUntilIdle()
    model.stop()
    assertEquals(MapLocationUiState.Idle, model.uiState.value)
    var cancelled = false
    read = {
      try {
        awaitCancellation()
      } finally {
        cancelled = true
      }
    }
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
    assertEquals(MapLocationUiState.Idle, model.uiState.value)
  }
}
