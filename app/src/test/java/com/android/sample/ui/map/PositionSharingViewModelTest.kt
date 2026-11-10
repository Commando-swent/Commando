package com.android.sample.ui.map

// AI assistance: OpenAI Codex.
import androidx.lifecycle.ViewModelStore
import com.android.sample.MainDispatcherRule
import com.android.sample.data.location.*
import com.android.sample.data.repository.*
import com.android.sample.model.*
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Consent, original fix age, lifecycle shutdown and uncertain-write cleanup. */
@OptIn(ExperimentalCoroutinesApi::class)
class PositionSharingViewModelTest {
  @get:Rule val main = MainDispatcherRule()
  private val store = ViewModelStore()
  private val now = Instant.parse("2026-11-10T10:00:00Z")
  private val trip =
      Trip(
          "trip",
          "owner",
          TripLocations.stores.first(),
          now.plusSeconds(3600),
          TripLocations.handoffPoints.first(),
          TripStatus.PUBLISHED,
          now,
          now,
      )
  private val identity = MutableStateFlow<String?>("owner")
  private val owned = MutableStateFlow<TripResult<List<Trip>>>(TripResult.Success(listOf(trip)))
  private val positions = RecordingPositions()
  private var reads = 0
  private var result: DeviceLocationResult =
      DeviceLocationResult.Available(DeviceLocation(46.52, 6.56, 100f, 1000))
  private val trips =
      object : TripRepository by FakeTripRepository("owner") {
        override fun observeMyTrips() = owned
      }

  private fun model(
      source: DeviceLocationProvider = DeviceLocationProvider {
        reads++
        result
      }
  ): PositionSharingViewModel =
      PositionSharingViewModel(
              { positions },
              trips,
              source,
              { identity.value },
              identity,
              Clock.fixed(now, ZoneOffset.UTC),
              { 1500L },
          )
          .also { store.put("sharing", it) }

  private fun modelTest(block: suspend TestScope.() -> Unit) = runTest {
    try {
      block()
    } finally {
      store.clear()
      runCurrent()
    }
  }

  @After
  fun clear() {
    store.clear()
  }

  @Test
  fun nothingIsReadOrUploadedUntilPermissionAndOptIn() = modelTest {
    val model = model()
    runCurrent()
    assertEquals(0, reads)
    model.start(false)
    runCurrent()
    assertEquals(PositionSharingState.Failed(SharingFailureReason.PERMISSION), model.uiState.value)
    assertTrue(positions.writes.isEmpty())
    assertEquals(0, reads)
  }

  @Test
  fun originalCaptureAgeIsConvertedToUtcAndStopDeletesBeforeReturning() = modelTest {
    val model = model()
    model.start(true)
    runCurrent()
    assertEquals(PositionSharingState.Live, model.uiState.value)
    assertEquals(now.minusMillis(500), positions.writes.single().capturedAt)
    assertEquals("trip", positions.tripId)
    assertTrue(model.stop())
    assertNull(positions.current)
    assertEquals(0, owned.subscriptionCount.value)
    advanceTimeBy(PositionSharingViewModel.PUBLICATION_INTERVAL_MILLIS * 2)
    runCurrent()
    assertEquals(1, reads)
    assertEquals(PositionSharingState.Off, model.uiState.value)
  }

  @Test
  fun revokedPermissionStopsPublicationAndDeletesTheLastFix() = modelTest {
    val model = model()
    model.start(true)
    runCurrent()
    result = DeviceLocationResult.PermissionDenied
    advanceTimeBy(PositionSharingViewModel.PUBLICATION_INTERVAL_MILLIS)
    runCurrent()
    assertEquals(PositionSharingState.Failed(SharingFailureReason.PERMISSION), model.uiState.value)
    assertNull(positions.current)
    assertEquals(1, positions.writes.size)
  }

  @Test
  fun deletionFailurePreventsSignOutAndCanBeRetried() = modelTest {
    val model = model()
    model.start(true)
    runCurrent()
    positions.failDelete = true
    assertFalse(model.stop())
    assertNotNull(positions.current)
    assertEquals(PositionSharingState.Failed(SharingFailureReason.STOP), model.uiState.value)
    positions.failDelete = false
    assertTrue(model.stop())
    assertNull(positions.current)
  }

  @Test
  fun lateProviderReplyAfterStopCannotBeUploaded() = modelTest {
    val reply = CompletableDeferred<DeviceLocationResult>()
    val model = model(DeviceLocationProvider { withContext(NonCancellable) { reply.await() } })
    model.start(true)
    runCurrent()
    val stopping = async { model.stop() }
    runCurrent()
    reply.complete(result)
    runCurrent()
    assertTrue(stopping.await())
    assertTrue(positions.writes.isEmpty())
  }

  @Test
  fun sessionEndAndCompletionStopSharingWithoutAnotherUserAction() = modelTest {
    val model = model()
    model.start(true)
    runCurrent()
    owned.value = TripResult.Success(listOf(trip.copy(status = TripStatus.COMPLETED)))
    runCurrent()
    assertNull(positions.current)
    assertEquals(PositionSharingState.Failed(SharingFailureReason.NO_TRIP), model.uiState.value)
    owned.value = TripResult.Success(listOf(trip))
    model.start(true)
    runCurrent()
    identity.value = null
    runCurrent()
    assertNull(positions.current)
    val count = positions.writes.size
    identity.value = "owner"
    model.start(true)
    runCurrent()
    assertEquals(count, positions.writes.size)
  }

  @Test
  fun unconfirmedTripsHideSharingUntilTheServerConfirmsAgain() = modelTest {
    val model = model()
    model.start(true)
    runCurrent()
    owned.value = TripResult.Error(TripError.NetworkError)
    runCurrent()
    assertNull(positions.current)
    assertEquals(PositionSharingState.Starting, model.uiState.value)
    owned.value = TripResult.Success(listOf(trip))
    runCurrent()
    assertEquals(PositionSharingState.Live, model.uiState.value)
    assertEquals(2, positions.writes.size)
  }

  @Test
  fun uncertainPublicationIsDeletedBeforeReportingFailure() = modelTest {
    positions.failPublish = true
    val model = model()
    model.start(true)
    runCurrent()
    assertEquals(PositionSharingState.Failed(SharingFailureReason.PUBLISH), model.uiState.value)
    assertNull(positions.current)
  }

  @Test
  fun expiredFixCannotBePublished() = modelTest {
    result =
        DeviceLocationResult.Available(
            DeviceLocation(46.52, 6.56, 100f, -DeviceLocation.MAX_AGE_MILLIS)
        )
    val model = model()
    model.start(true)
    runCurrent()
    assertEquals(PositionSharingState.Failed(SharingFailureReason.LOCATION), model.uiState.value)
    assertTrue(positions.writes.isEmpty())
  }

  @Test
  fun aProviderThatNeverRepliesTimesOutWithoutPublishing() = modelTest {
    val model = model(DeviceLocationProvider { awaitCancellation() })
    model.start(true)
    runCurrent()
    advanceTimeBy(MapLocationViewModel.REQUEST_TIMEOUT_MILLIS)
    runCurrent()
    assertEquals(PositionSharingState.Failed(SharingFailureReason.LOCATION), model.uiState.value)
    assertTrue(positions.writes.isEmpty())
  }

  private class RecordingPositions : CommandoPositionRepository {
    val writes = mutableListOf<CommandoPosition>()
    var current: CommandoPosition? = null
    var tripId: String? = null
    var failDelete = false
    var failPublish = false

    override suspend fun publish(tripId: String, position: CommandoPosition) {
      this.tripId = tripId
      current = position
      writes += position
      check(!failPublish)
    }

    override suspend fun stopSharing() {
      check(!failDelete)
      current = null
    }

    override fun observe(ownerId: String, orderId: String?): Flow<CommandoPosition?> =
        flowOf(current)
  }
}
