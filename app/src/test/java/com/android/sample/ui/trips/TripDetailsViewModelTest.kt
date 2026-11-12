package com.android.sample.ui.trips

// AI assistance: Claude Code for the close and complete tests (#233).
import com.android.sample.data.repository.FakeTripRepository
import com.android.sample.data.repository.TripError
import com.android.sample.data.repository.TripRepository
import com.android.sample.data.repository.TripResult
import com.android.sample.model.Location
import com.android.sample.model.Trip
import com.android.sample.model.TripStatus
import com.android.sample.model.TripTransition
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TripDetailsViewModelTest {
  private val now = Instant.parse("2026-10-06T12:00:00Z")
  private val trip =
      Trip(
          id = "trip",
          ownerId = "owner",
          store = Location("Migros", 46.52, 6.63),
          scheduledAt = now.plusSeconds(3600),
          handoffLocation = Location("EPFL", 46.52, 6.57),
          status = TripStatus.PUBLISHED,
          createdAt = now,
          updatedAt = now,
      )
  private val fake = FakeTripRepository("owner", now = { now }, initialTrips = listOf(trip))
  private val repository = RecordingRepository(fake)

  @Before
  fun setUp() {
    Dispatchers.setMain(StandardTestDispatcher())
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  @Test
  fun startsLoadingAndAutomaticallyRequestsSuppliedTripId() = runTest {
    repository.gate = CompletableDeferred()
    val viewModel = TripDetailsViewModel(repository, trip.id)
    assertEquals(TripDetailsUiState.Loading, viewModel.uiState.value)
    assertTrue(repository.requestedIds.isEmpty())

    runCurrent()
    assertEquals(listOf(trip.id), repository.requestedIds)
    assertEquals(TripDetailsUiState.Loading, viewModel.uiState.value)

    repository.gate!!.complete(Unit)
    advanceUntilIdle()
    assertEquals(TripDetailsUiState.Content(trip), viewModel.uiState.value)
  }

  @Test
  fun suppliedIdIsForwardedUnchanged() = runTest {
    val suppliedId = " trip/opaque ID "
    repository.result = TripResult.Success(trip)
    val viewModel = TripDetailsViewModel(repository, suppliedId)
    advanceUntilIdle()
    assertEquals(listOf(suppliedId), repository.requestedIds)
    assertEquals(TripDetailsUiState.Content(trip), viewModel.uiState.value)
  }

  @Test
  fun missingTripBecomesNotFound() = runTest {
    val viewModel = TripDetailsViewModel(repository, "missing")
    advanceUntilIdle()
    assertEquals(listOf("missing"), repository.requestedIds)
    assertEquals(TripDetailsUiState.Error(TripError.NotFound), viewModel.uiState.value)
  }

  @Test
  fun repositoryErrorsArePreserved() = runTest {
    for (error in
        listOf(
            TripError.NotFound,
            TripError.NetworkError,
            TripError.PermissionDenied,
            TripError.InvalidData,
            TripError.Unknown,
        )) {
      fake.forcedError = error
      val viewModel = TripDetailsViewModel(repository, trip.id)
      advanceUntilIdle()
      assertEquals(TripDetailsUiState.Error(error), viewModel.uiState.value)
    }
  }

  @Test
  fun contentPreservesReturnedTripForEveryStatus() = runTest {
    for (status in TripStatus.entries) {
      // Details must also display past trips and statuses excluded from upcoming trips.
      val returned =
          trip.copy(
              id = "repository-result",
              ownerId = "another-owner",
              scheduledAt = now.minusSeconds(3600),
              status = status,
          )
      repository.result = TripResult.Success(returned)
      val viewModel = TripDetailsViewModel(repository, trip.id)
      advanceUntilIdle()
      val state = viewModel.uiState.value as TripDetailsUiState.Content
      assertSame(returned, state.trip)
    }
  }

  @Test
  fun retryLoadsSameIdAndCanRecoverFromError() = runTest {
    fake.forcedError = TripError.NetworkError
    val viewModel = TripDetailsViewModel(repository, trip.id)
    advanceUntilIdle()
    assertEquals(TripDetailsUiState.Error(TripError.NetworkError), viewModel.uiState.value)

    fake.forcedError = null
    repository.gate = CompletableDeferred()
    viewModel.retry()
    assertEquals(TripDetailsUiState.Loading, viewModel.uiState.value)
    runCurrent()
    assertEquals(listOf(trip.id, trip.id), repository.requestedIds)
    assertEquals(TripDetailsUiState.Loading, viewModel.uiState.value)

    repository.gate!!.complete(Unit)
    advanceUntilIdle()
    assertEquals(TripDetailsUiState.Content(trip), viewModel.uiState.value)
  }

  @Test
  fun unexpectedExceptionBecomesUnknownAndRetryCanRecover() = runTest {
    repository.failure = IllegalStateException("Unexpected repository failure")
    val viewModel = TripDetailsViewModel(repository, trip.id)
    advanceUntilIdle()
    assertEquals(TripDetailsUiState.Error(TripError.Unknown), viewModel.uiState.value)

    repository.failure = null
    viewModel.retry()
    advanceUntilIdle()
    assertEquals(listOf(trip.id, trip.id), repository.requestedIds)
    assertEquals(TripDetailsUiState.Content(trip), viewModel.uiState.value)
  }

  @Test
  fun repeatedRetriesCannotStartConcurrentLoads() = runTest {
    repository.gate = CompletableDeferred()
    val viewModel = TripDetailsViewModel(repository, trip.id)
    repeat(3) { viewModel.retry() }
    runCurrent()
    repeat(3) { viewModel.retry() }
    runCurrent()
    assertEquals(listOf(trip.id), repository.requestedIds)

    repository.gate!!.complete(Unit)
    advanceUntilIdle()
    assertEquals(TripDetailsUiState.Content(trip), viewModel.uiState.value)

    val refreshedTrip = trip.copy(status = TripStatus.COMPLETED, updatedAt = now.plusSeconds(60))
    repository.result = TripResult.Success(refreshedTrip)
    repository.gate = CompletableDeferred()
    viewModel.retry()
    repeat(3) { viewModel.retry() }
    runCurrent()
    repeat(3) { viewModel.retry() }
    runCurrent()
    assertEquals(listOf(trip.id, trip.id), repository.requestedIds)
    assertEquals(TripDetailsUiState.Loading, viewModel.uiState.value)

    repository.gate!!.complete(Unit)
    advanceUntilIdle()
    assertEquals(TripDetailsUiState.Content(refreshedTrip), viewModel.uiState.value)
  }

  @Test
  fun cancellationDoesNotBecomeErrorAndReleasesLoadGuard() = runTest {
    repository.failure = CancellationException("Repository request cancelled")
    val viewModel = TripDetailsViewModel(repository, trip.id)
    advanceUntilIdle()
    assertEquals(TripDetailsUiState.Loading, viewModel.uiState.value)

    repository.failure = null
    viewModel.retry()
    advanceUntilIdle()
    assertEquals(listOf(trip.id, trip.id), repository.requestedIds)
    assertEquals(TripDetailsUiState.Content(trip), viewModel.uiState.value)
  }

  @Test
  fun confirmedCloseShowsTheStoredInProgressTrip() = runTest {
    val viewModel = loaded()
    viewModel.requestTransition(TripTransition.CLOSE)
    assertEquals(TripTransition.CLOSE, content(viewModel).pendingTransition)

    viewModel.confirmTransition()
    assertTrue(content(viewModel).transitionInProgress)
    assertNull(content(viewModel).pendingTransition)
    advanceUntilIdle()

    val state = content(viewModel)
    assertEquals(TripStatus.IN_PROGRESS, state.trip.status)
    assertFalse(state.transitionInProgress)
    assertNull(state.transitionError)
    assertEquals(listOf(trip.id to TripTransition.CLOSE), repository.transitions)
  }

  @Test
  fun dismissedOrUnrequestedTransitionWritesNothing() = runTest {
    val viewModel = loaded()
    viewModel.confirmTransition()
    viewModel.requestTransition(TripTransition.CLOSE)
    viewModel.dismissTransition()
    viewModel.confirmTransition()
    advanceUntilIdle()
    assertEquals(TripDetailsUiState.Content(trip), viewModel.uiState.value)
    assertTrue(repository.transitions.isEmpty())
  }

  @Test
  fun failedTransitionKeepsTheTripAndTheNextRequestClearsTheError() = runTest {
    repository.transitionResult = TripResult.Error(TripError.OrdersStillOpen)
    val viewModel = loaded()
    viewModel.requestTransition(TripTransition.CLOSE)
    viewModel.confirmTransition()
    advanceUntilIdle()
    assertEquals(
        TripDetailsUiState.Content(trip, transitionError = TripError.OrdersStillOpen),
        viewModel.uiState.value,
    )

    viewModel.requestTransition(TripTransition.CLOSE)
    assertEquals(
        TripDetailsUiState.Content(trip, pendingTransition = TripTransition.CLOSE),
        viewModel.uiState.value,
    )
  }

  @Test
  fun unexpectedTransitionFailureBecomesUnknown() = runTest {
    repository.transitionFailure = IllegalStateException("Unexpected repository failure")
    val viewModel = loaded()
    viewModel.requestTransition(TripTransition.CLOSE)
    viewModel.confirmTransition()
    advanceUntilIdle()
    assertEquals(TripError.Unknown, content(viewModel).transitionError)
    assertFalse(content(viewModel).transitionInProgress)
  }

  @Test
  fun requestsAreIgnoredWhileATransitionIsWritten() = runTest {
    repository.transitionGate = CompletableDeferred()
    val viewModel = loaded()
    viewModel.requestTransition(TripTransition.CLOSE)
    viewModel.confirmTransition()
    runCurrent()
    viewModel.requestTransition(TripTransition.CLOSE)
    assertNull(content(viewModel).pendingTransition)

    repository.transitionGate!!.complete(Unit)
    advanceUntilIdle()
    assertEquals(listOf(trip.id to TripTransition.CLOSE), repository.transitions)
  }

  @Test
  fun transitionActionsAreIgnoredBeforeTheTripIsLoaded() = runTest {
    repository.gate = CompletableDeferred()
    val viewModel = TripDetailsViewModel(repository, trip.id)
    viewModel.requestTransition(TripTransition.CLOSE)
    viewModel.dismissTransition()
    viewModel.confirmTransition()
    runCurrent()
    assertEquals(TripDetailsUiState.Loading, viewModel.uiState.value)
    assertTrue(repository.transitions.isEmpty())
  }

  @Test
  fun reloadDuringATransitionShowsTheReloadedTrip() = runTest {
    repository.transitionGate = CompletableDeferred()
    val viewModel = loaded()
    viewModel.requestTransition(TripTransition.CLOSE)
    viewModel.confirmTransition()
    runCurrent()

    repository.gate = CompletableDeferred()
    viewModel.retry()
    repository.transitionGate!!.complete(Unit)
    runCurrent()
    assertEquals(TripDetailsUiState.Loading, viewModel.uiState.value)

    repository.gate!!.complete(Unit)
    advanceUntilIdle()
    assertEquals(TripStatus.IN_PROGRESS, content(viewModel).trip.status)
  }

  private fun TestScope.loaded(): TripDetailsViewModel {
    val viewModel = TripDetailsViewModel(repository, trip.id)
    advanceUntilIdle()
    return viewModel
  }

  private fun content(viewModel: TripDetailsViewModel) =
      viewModel.uiState.value as TripDetailsUiState.Content

  private class RecordingRepository(private val fake: FakeTripRepository) : TripRepository by fake {
    val requestedIds = mutableListOf<String>()
    var gate: CompletableDeferred<Unit>? = null
    var result: TripResult<Trip>? = null
    var failure: Exception? = null
    val transitions = mutableListOf<Pair<String, TripTransition>>()
    var transitionGate: CompletableDeferred<Unit>? = null
    var transitionResult: TripResult<Trip>? = null
    var transitionFailure: Exception? = null

    override suspend fun transitionTrip(
        tripId: String,
        transition: TripTransition,
    ): TripResult<Trip> {
      transitions.add(tripId to transition)
      transitionGate?.await()
      transitionFailure?.let { throw it }
      return transitionResult ?: fake.transitionTrip(tripId, transition)
    }

    override suspend fun getTripById(tripId: String): TripResult<Trip> {
      requestedIds.add(tripId)
      gate?.await()
      failure?.let { throw it }
      return result ?: fake.getTripById(tripId)
    }
  }
}
