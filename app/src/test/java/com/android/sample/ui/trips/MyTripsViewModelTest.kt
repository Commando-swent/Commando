package com.android.sample.ui.trips

// AI assistance: Claude Code.
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
class MyTripsViewModelTest {
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
  private val otherOwnersTrip = trip.copy(id = "other", ownerId = "someone-else")
  private val fake =
      FakeTripRepository("owner", now = { now }, initialTrips = listOf(trip, otherOwnersTrip))
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
  fun startsLoadingAndAutomaticallyRequestsOwnTrips() = runTest {
    repository.gate = CompletableDeferred()
    val viewModel = MyTripsViewModel(repository, "owner")
    assertEquals(MyTripsUiState.Loading, viewModel.uiState.value)
    assertEquals(0, repository.calls)
    runCurrent()
    assertEquals(1, repository.calls)
    assertEquals(MyTripsUiState.Loading, viewModel.uiState.value)
    repository.gate!!.complete(Unit)
    advanceUntilIdle()
    assertEquals(MyTripsUiState.Content(listOf(trip)), viewModel.uiState.value)
  }

  @Test
  fun tripsOwnedByOtherAccountsAreNeverDisplayed() = runTest {
    repository.result = TripResult.Success(listOf(otherOwnersTrip, trip))
    val viewModel = MyTripsViewModel(repository, "owner")
    advanceUntilIdle()
    assertEquals(MyTripsUiState.Content(listOf(trip)), viewModel.uiState.value)
  }

  @Test
  fun newAccountDoesNotSeePreviousAccountTrips() = runTest {
    // The repository still returns the previous account's trips; the new account's list is empty.
    val previous = MyTripsViewModel(repository, "owner")
    advanceUntilIdle()
    assertEquals(MyTripsUiState.Content(listOf(trip)), previous.uiState.value)

    val next = MyTripsViewModel(repository, "new-owner")
    advanceUntilIdle()
    assertEquals(MyTripsUiState.Empty, next.uiState.value)
  }

  @Test
  fun blankOwnerIsDeniedWithoutRequestingTrips() = runTest {
    val viewModel = MyTripsViewModel(repository, " ")
    advanceUntilIdle()
    assertEquals(0, repository.calls)
    assertEquals(MyTripsUiState.Error(TripError.PermissionDenied), viewModel.uiState.value)
  }

  @Test
  fun emptySuccessBecomesEmpty() = runTest {
    val viewModel = MyTripsViewModel(FakeTripRepository("owner", now = { now }), "owner")
    advanceUntilIdle()
    assertEquals(MyTripsUiState.Empty, viewModel.uiState.value)
  }

  @Test
  fun allStoredStatusesAreShownWithActiveTripsBeforeClosedOnes() = runTest {
    val later = trip.copy(id = "later", scheduledAt = now.plusSeconds(7200))
    val inProgress = trip.copy(id = "in-progress", status = TripStatus.IN_PROGRESS)
    val oldCompleted =
        trip.copy(id = "old", status = TripStatus.COMPLETED, scheduledAt = now.minusSeconds(7200))
    val recentCancelled =
        trip.copy(id = "recent", status = TripStatus.CANCELLED, scheduledAt = now.minusSeconds(60))
    repository.result =
        TripResult.Success(listOf(oldCompleted, later, recentCancelled, inProgress, trip))
    val viewModel = MyTripsViewModel(repository, "owner")
    advanceUntilIdle()
    assertEquals(
        MyTripsUiState.Content(listOf(inProgress, trip, later, recentCancelled, oldCompleted)),
        viewModel.uiState.value,
    )
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
      val viewModel = MyTripsViewModel(repository, "owner")
      advanceUntilIdle()
      assertEquals(MyTripsUiState.Error(error), viewModel.uiState.value)
    }
  }

  @Test
  fun retryMakesFreshRequestAndCanRecoverFromError() = runTest {
    fake.forcedError = TripError.NetworkError
    val viewModel = MyTripsViewModel(repository, "owner")
    advanceUntilIdle()
    assertEquals(1, repository.calls)
    assertEquals(MyTripsUiState.Error(TripError.NetworkError), viewModel.uiState.value)
    fake.forcedError = null
    viewModel.retry()
    assertEquals(MyTripsUiState.Loading, viewModel.uiState.value)
    advanceUntilIdle()
    assertEquals(2, repository.calls)
    assertEquals(MyTripsUiState.Content(listOf(trip)), viewModel.uiState.value)
  }

  @Test
  fun unexpectedExceptionBecomesUnknownAndRetryCanRecover() = runTest {
    repository.failure = IllegalStateException("Unexpected repository failure")
    val viewModel = MyTripsViewModel(repository, "owner")
    advanceUntilIdle()
    assertEquals(MyTripsUiState.Error(TripError.Unknown), viewModel.uiState.value)

    repository.failure = null
    viewModel.retry()
    advanceUntilIdle()
    assertEquals(2, repository.calls)
    assertEquals(MyTripsUiState.Content(listOf(trip)), viewModel.uiState.value)
  }

  @Test
  fun repeatedRetriesCannotStartConcurrentLoads() = runTest {
    repository.gate = CompletableDeferred()
    val viewModel = MyTripsViewModel(repository, "owner")
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
    val viewModel = MyTripsViewModel(repository, "owner")
    advanceUntilIdle()
    assertEquals(MyTripsUiState.Loading, viewModel.uiState.value)
    repository.cancel = false
    viewModel.retry()
    advanceUntilIdle()
    assertEquals(2, repository.calls)
    assertEquals(MyTripsUiState.Content(listOf(trip)), viewModel.uiState.value)
  }

  private class RecordingRepository(private val fake: FakeTripRepository) : TripRepository by fake {
    var calls = 0
    var gate: CompletableDeferred<Unit>? = null
    var result: TripResult<List<Trip>>? = null
    var cancel = false
    var failure: Exception? = null

    override suspend fun getMyTrips(): TripResult<List<Trip>> {
      calls++
      gate?.await()
      if (cancel) throw CancellationException()
      failure?.let { throw it }
      return result ?: fake.getMyTrips()
    }
  }
}
