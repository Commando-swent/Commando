package com.android.sample.ui.home

// AI assistance: OpenAI Codex.
import com.android.sample.data.repository.*
import com.android.sample.model.*
import java.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class HomeViewModelTest {
  private val now = Instant.parse("2026-10-07T12:00:00Z")
  private val trip =
      Trip(
          "trip",
          "alice",
          Location("Migros", 46.52, 6.63),
          now.plusSeconds(3600),
          Location("Rolex Learning Center", 46.52, 6.57),
          TripStatus.PUBLISHED,
          now,
          now,
      )
  private val fake = FakeTripRepository("alice", { now })
  private val repository =
      object : TripRepository by fake {
        var result: TripResult<List<Trip>> = TripResult.Success(emptyList())

        override suspend fun getMyTrips(): TripResult<List<Trip>> {
          return result
        }
      }

  @Before
  fun setUp() {
    Dispatchers.setMain(StandardTestDispatcher())
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  private fun viewModel() = HomeViewModel(repository, { now })

  @Test
  fun selectsNearestPublishedTripAndIgnoresClosedAndPastTrips() = runTest {
    repository.result =
        TripResult.Success(
            listOf(
                trip.copy(id = "later", scheduledAt = now.plusSeconds(7200)),
                trip.copy(id = "past", scheduledAt = now.minusSeconds(1)),
                trip.copy(id = "completed", status = TripStatus.COMPLETED),
                trip.copy(id = "cancelled", status = TripStatus.CANCELLED),
                trip,
            )
        )
    val vm = viewModel()
    vm.refresh()
    advanceUntilIdle()
    assertEquals(HomeTripUiState.Content(trip), vm.uiState.value)
  }

  @Test
  fun refreshKeepsExistingContentVisibleUntilTheResultArrives() = runTest {
    repository.result = TripResult.Success(listOf(trip))
    val vm = viewModel()
    vm.refresh()
    advanceUntilIdle()
    val updated = trip.copy(store = Location("Coop", 46.52, 6.63))
    repository.result = TripResult.Success(listOf(updated))
    vm.refresh()
    assertEquals(HomeTripUiState.Content(trip), vm.uiState.value)
    advanceUntilIdle()
    assertEquals(HomeTripUiState.Content(updated), vm.uiState.value)
  }

  @Test
  fun ongoingTripTakesPriorityEvenAfterItsScheduledDeparture() = runTest {
    val ongoing =
        trip.copy(
            id = "ongoing",
            status = TripStatus.IN_PROGRESS,
            scheduledAt = now.minusSeconds(3600),
        )
    repository.result = TripResult.Success(listOf(trip, ongoing))
    val vm = viewModel()
    vm.refresh()
    advanceUntilIdle()
    assertEquals(HomeTripUiState.Content(ongoing), vm.uiState.value)
  }

  @Test
  fun refreshUpdatesTripAndReturnsToEmptyAfterCompletion() = runTest {
    val vm = viewModel()
    vm.refresh()
    advanceUntilIdle()
    assertEquals(HomeTripUiState.Empty, vm.uiState.value)
    repository.result = TripResult.Success(listOf(trip))
    vm.refresh()
    advanceUntilIdle()
    assertEquals(HomeTripUiState.Content(trip), vm.uiState.value)
    repository.result = TripResult.Success(listOf(trip.copy(status = TripStatus.COMPLETED)))
    vm.refresh()
    advanceUntilIdle()
    assertEquals(HomeTripUiState.Empty, vm.uiState.value)
  }

  @Test
  fun errorDoesNotPretendThereIsNoTripAndRetryRecovers() = runTest {
    repository.result = TripResult.Error(TripError.NetworkError)
    val vm = viewModel()
    vm.refresh()
    advanceUntilIdle()
    assertEquals(HomeTripUiState.Error(TripError.NetworkError), vm.uiState.value)
    repository.result = TripResult.Success(listOf(trip))
    vm.refresh()
    advanceUntilIdle()
    assertEquals(HomeTripUiState.Content(trip), vm.uiState.value)
  }
}
