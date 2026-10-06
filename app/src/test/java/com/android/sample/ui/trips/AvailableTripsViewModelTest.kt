package com.android.sample.ui.trips

import com.android.sample.data.repository.FakeTripRepository
import com.android.sample.data.repository.TripError
import com.android.sample.data.repository.TripRepository
import com.android.sample.data.repository.TripResult
import com.android.sample.model.Location
import com.android.sample.model.Trip
import com.android.sample.model.TripStatus
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
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
class AvailableTripsViewModelTest {
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
  fun startsLoadingAndAutomaticallyRequestsUpcomingTrips() = runTest {
    repository.gate = CompletableDeferred()
    val viewModel = AvailableTripsViewModel(repository)
    assertEquals(AvailableTripsUiState.Loading, viewModel.uiState.value)
    assertEquals(0, repository.calls)
    runCurrent()
    assertEquals(1, repository.calls)
    assertEquals(AvailableTripsUiState.Loading, viewModel.uiState.value)
    repository.gate!!.complete(Unit)
    advanceUntilIdle()
    assertEquals(AvailableTripsUiState.Content(listOf(trip)), viewModel.uiState.value)
  }

  @Test
  fun emptySuccessBecomesEmpty() = runTest {
    val viewModel = AvailableTripsViewModel(FakeTripRepository("owner", now = { now }))
    advanceUntilIdle()
    assertEquals(AvailableTripsUiState.Empty, viewModel.uiState.value)
  }

  @Test
  fun repositoryErrorsBecomeErrorState() = runTest {
    for (error in
        listOf(
            TripError.NotFound,
            TripError.NetworkError,
            TripError.PermissionDenied,
            TripError.InvalidData,
            TripError.Unknown,
        )) {
      fake.forcedError = error
      val viewModel = AvailableTripsViewModel(repository)
      advanceUntilIdle()
      assertEquals(AvailableTripsUiState.Error(error), viewModel.uiState.value)
    }
  }

  @Test
  fun retryMakesFreshRequestAndCanRecoverFromError() = runTest {
    fake.forcedError = TripError.NetworkError
    val viewModel = AvailableTripsViewModel(repository)
    advanceUntilIdle()
    assertEquals(1, repository.calls)
    assertEquals(AvailableTripsUiState.Error(TripError.NetworkError), viewModel.uiState.value)
    fake.forcedError = null
    viewModel.retry()
    assertEquals(AvailableTripsUiState.Loading, viewModel.uiState.value)
    advanceUntilIdle()
    assertEquals(2, repository.calls)
    assertEquals(AvailableTripsUiState.Content(listOf(trip)), viewModel.uiState.value)
  }

  @Test
  fun unexpectedExceptionBecomesUnknownAndRetryCanRecover() = runTest {
    repository.failure = IllegalStateException("Unexpected repository failure")
    val viewModel = AvailableTripsViewModel(repository)
    advanceUntilIdle()
    assertEquals(1, repository.calls)
    assertEquals(AvailableTripsUiState.Error(TripError.Unknown), viewModel.uiState.value)

    repository.failure = null
    viewModel.retry()
    advanceUntilIdle()
    assertEquals(2, repository.calls)
    assertEquals(AvailableTripsUiState.Content(listOf(trip)), viewModel.uiState.value)
  }

  @Test
  fun contentPreservesRepositoryListExactlyWithoutFilteringSortingOrLimiting() = runTest {
    // Deliberately opaque results verify this consumer does not enforce repository rules.
    val returned =
        listOf(
            trip.copy(id = "later", scheduledAt = now.plusSeconds(7200)),
            trip.copy(id = "past", scheduledAt = now.minusSeconds(1)),
            trip.copy(id = "cancelled", status = TripStatus.CANCELLED),
            trip,
            trip,
        )
    repository.result = TripResult.Success(returned)
    val viewModel = AvailableTripsViewModel(repository)
    advanceUntilIdle()
    val state = viewModel.uiState.value as AvailableTripsUiState.Content
    assertSame(returned, state.trips)
  }

  @Test
  fun repeatedRetriesCannotStartConcurrentLoads() = runTest {
    repository.gate = CompletableDeferred()
    val viewModel = AvailableTripsViewModel(repository)
    repeat(3) { viewModel.retry() }
    runCurrent()
    repeat(3) { viewModel.retry() }
    runCurrent()
    assertEquals(1, repository.calls)
    repository.gate!!.complete(Unit)
    advanceUntilIdle()
    viewModel.retry()
    advanceUntilIdle()
    assertEquals(2, repository.calls)
  }

  @Test
  fun cancellationDoesNotBecomeErrorAndReleasesLoadGuard() = runTest {
    repository.cancel = true
    val viewModel = AvailableTripsViewModel(repository)
    advanceUntilIdle()
    assertEquals(AvailableTripsUiState.Loading, viewModel.uiState.value)
    repository.cancel = false
    viewModel.retry()
    advanceUntilIdle()
    assertEquals(2, repository.calls)
    assertEquals(AvailableTripsUiState.Content(listOf(trip)), viewModel.uiState.value)
  }

  private class RecordingRepository(private val fake: FakeTripRepository) : TripRepository by fake {
    var calls = 0
    var gate: CompletableDeferred<Unit>? = null
    var result: TripResult<List<Trip>>? = null
    var cancel = false
    var failure: Exception? = null

    override suspend fun getUpcomingTrips(): TripResult<List<Trip>> {
      calls++
      gate?.await()
      if (cancel) throw CancellationException()
      failure?.let { throw it }
      return result ?: fake.getUpcomingTrips()
    }
  }
}
