package com.android.sample.ui.map

// AI assistance: OpenAI Codex.
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.android.sample.MainDispatcherRule
import com.android.sample.data.repository.FakeTripRepository
import com.android.sample.data.repository.TripError
import com.android.sample.data.repository.TripRepository
import com.android.sample.data.repository.TripResult
import com.android.sample.model.Location
import com.android.sample.model.Trip
import com.android.sample.model.TripLocations
import com.android.sample.model.TripStatus
import com.android.sample.ui.trips.AvailableTripsUiState
import com.android.sample.ui.trips.AvailableTripsViewModel
import com.android.sample.ui.trips.TripLocationFilterViewModel
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TripMapViewModelTest {
  @get:Rule val main = MainDispatcherRule()
  private val now = Instant.parse("2026-10-28T12:00:00Z")
  private val point: Location = TripLocations.handoffPoints.first { it.name == "CM building" }
  private val farPoint = Location("Lausanne", 46.5167, 6.6291)
  private val first: Trip =
      Trip(
          "real/first",
          "owner",
          TripLocations.stores.first(),
          now.plusSeconds(3600),
          point,
          TripStatus.PUBLISHED,
          now,
          now,
      )
  private val second = first.copy(id = "real-second", scheduledAt = now.plusSeconds(7200))
  private val far = first.copy(id = "far", handoffLocation = farPoint)
  private val session = MutableStateFlow<String?>("requester")
  private val filter = TripLocationFilterViewModel(SavedStateHandle())
  private val fake =
      FakeTripRepository("owner", now = { now }, initialTrips = listOf(first, second))
  private val repository = RecordingRepository(fake)

  private fun model(loadImmediately: Boolean = true) =
      TripMapViewModel(repository, filter.selected, { session.value }, session, loadImmediately)

  private fun content(model: TripMapViewModel) = model.uiState.value as TripMapUiState.Content

  @Test
  fun initialLoadExposesActualIdsCoordinatesAndRepositoryOrder() = runTest {
    repository.read = { TripResult.Success(listOf(second, far, first)) }
    val model = model()
    assertEquals(TripMapUiState.Loading, model.uiState.value)
    advanceUntilIdle()
    assertEquals(listOf(second, far, first), content(model).trips)
    assertEquals(point, content(model).trips.last().handoffLocation)
    assertEquals(1, repository.calls)
  }

  @Test
  fun groupsExactCoordinatesDespiteDifferentNamesAndKeepsAllTripsSelectable() = runTest {
    val renamed = second.copy(handoffLocation = point.copy(name = "Same physical handoff"))
    val moved = far.copy(handoffLocation = farPoint.copy(name = point.name))
    repository.read = { TripResult.Success(listOf(first, moved, renamed)) }
    val model = model()
    advanceUntilIdle()
    assertEquals(listOf(listOf(first, renamed), listOf(moved)), content(model).markerGroups)
    model.selectTrip(renamed.id)
    assertEquals(renamed, content(model).selectedTrip)
    assertEquals(listOf(first, renamed), content(model).selectedGroup)
  }

  @Test
  fun dismissalKeepsTheDeliveryFilter() = runTest {
    filter.select(point)
    val model = model()
    advanceUntilIdle()
    model.selectTrip(first.id)
    model.clearSelection()
    assertNull(content(model).selectedTrip)
    assertTrue(content(model).selectedGroup.isEmpty())
    assertEquals(point, filter.selected.value)
  }

  @Test
  fun listAndMapReactToTheSameFilterWithoutExtraReads() = runTest {
    repository.read = { TripResult.Success(listOf(far, second, first)) }
    val model = model()
    val list = AvailableTripsViewModel(repository, filter.selected)
    advanceUntilIdle()
    filter.select(point)
    advanceUntilIdle()
    assertEquals((list.uiState.value as AvailableTripsUiState.Content).trips, content(model).trips)
    assertEquals(listOf(second, first), content(model).trips)
    filter.clear()
    advanceUntilIdle()
    assertEquals((list.uiState.value as AvailableTripsUiState.Content).trips, content(model).trips)
    assertEquals(listOf(far, second, first), content(model).trips)
    assertEquals(2, repository.calls)
  }

  @Test
  fun refilterClearsATripThatIsNoLongerVisible() = runTest {
    repository.read = { TripResult.Success(listOf(first, far)) }
    val model = model()
    advanceUntilIdle()
    model.selectTrip(far.id)
    filter.select(point)
    advanceUntilIdle()
    assertNull(content(model).selectedTripId)
    filter.clear()
    advanceUntilIdle()
    assertNull(content(model).selectedTripId)
  }

  @Test
  fun reloadKeepsSelectionIfPresentAndClearsItWhenRemoved() = runTest {
    filter.select(point)
    val model = model()
    advanceUntilIdle()
    model.selectTrip(first.id)
    val added = second.copy(id = "new")
    repository.read = { TripResult.Success(listOf(first, added, far)) }
    model.refresh()
    advanceUntilIdle()
    assertEquals(first, content(model).selectedTrip)
    assertEquals(listOf(first, added), content(model).trips)
    repository.read = { TripResult.Success(listOf(added, far)) }
    model.refresh()
    advanceUntilIdle()
    assertEquals(listOf(added), content(model).trips)
    assertNull(content(model).selectedTripId)
    assertEquals(point, filter.selected.value)
  }

  @Test
  fun selectionWhileLoadingIsIgnored() = runTest {
    val model = model()
    model.selectTrip(first.id)
    advanceUntilIdle()
    assertNull(content(model).selectedTrip)
  }

  @Test
  fun unknownSelectionKeepsTheSelectedTrip() = runTest {
    val model = model()
    advanceUntilIdle()
    model.selectTrip(first.id)
    model.selectTrip("unknown")
    assertEquals(first, content(model).selectedTrip)
  }

  @Test
  fun emptyFeedAndNoFilterMatchesAreDistinct() = runTest {
    filter.select(farPoint)
    repository.read = { TripResult.Success(emptyList()) }
    val model = model()
    advanceUntilIdle()
    assertEquals(TripMapUiState.Empty, model.uiState.value)
    repository.read = { TripResult.Success(listOf(first)) }
    model.refresh()
    advanceUntilIdle()
    assertEquals(TripMapUiState.NoMatches(farPoint), model.uiState.value)
  }

  @Test
  fun errorStaysAnErrorOnFilterChangeAndRetryUsesLatestSelection() = runTest {
    repository.read = { TripResult.Error(TripError.NetworkError) }
    val model = model()
    advanceUntilIdle()
    filter.select(point)
    advanceUntilIdle()
    assertEquals(TripMapUiState.Error(TripError.NetworkError), model.uiState.value)
    repository.read = { TripResult.Success(listOf(first, far)) }
    model.refresh()
    advanceUntilIdle()
    assertEquals(listOf(first), content(model).trips)
  }

  @Test
  fun unexpectedExceptionBecomesUnknownError() = runTest {
    repository.read = { throw IllegalStateException("Failed read") }
    val model = model()
    advanceUntilIdle()
    assertEquals(TripMapUiState.Error(TripError.Unknown), model.uiState.value)
  }

  @Test
  fun selectionChangedWhileLoadingIsAppliedOnCompletion() = runTest {
    val reply = CompletableDeferred<TripResult<List<Trip>>>()
    repository.read = { reply.await() }
    val model = model()
    runCurrent()
    filter.select(point)
    runCurrent()
    assertEquals(TripMapUiState.Loading, model.uiState.value)
    reply.complete(TripResult.Success(listOf(far, first)))
    advanceUntilIdle()
    assertEquals(listOf(first), content(model).trips)
  }

  @Test
  fun refreshWhileLoadingKeepsSelectionAndDismissalStillWorks() = runTest {
    val model = model()
    advanceUntilIdle()
    model.selectTrip(first.id)
    val reply = CompletableDeferred<TripResult<List<Trip>>>()
    repository.read = { reply.await() }
    model.refresh()
    runCurrent()
    model.refresh()
    runCurrent()
    reply.complete(TripResult.Success(listOf(first, second)))
    advanceUntilIdle()
    assertEquals(first, content(model).selectedTrip)
    repository.read = { TripResult.Success(listOf(first)) }
    model.refresh()
    model.clearSelection()
    advanceUntilIdle()
    assertNull(content(model).selectedTrip)
  }

  @Test
  fun contentPreservesUndrawableTripsForListParity() = runTest {
    val trips = listOf(far, first.copy(handoffLocation = point.copy(latitude = Double.NaN)))
    repository.read = { TripResult.Success(trips) }
    val model = model()
    advanceUntilIdle()
    val projection = CampusMapProjection(46.518, 46.524, 6.560, 6.572)
    assertEquals(trips, content(model).trips)
    assertEquals(2, content(model).markerGroups.size)
    assertTrue(
        content(model).markerGroups.all { projection.project(it.first().handoffLocation) == null }
    )
  }

  @Test
  fun refreshRejectsAnOlderReplyEvenWhenTheRepositoryIgnoresCancellation() = runTest {
    val oldReply = CompletableDeferred<TripResult<List<Trip>>>()
    repository.read = { withContext(NonCancellable) { oldReply.await() } }
    val model = model()
    runCurrent()
    repository.read = { TripResult.Success(listOf(second)) }
    model.refresh()
    runCurrent()
    assertEquals(listOf(second), content(model).trips)
    oldReply.complete(TripResult.Success(listOf(first)))
    advanceUntilIdle()
    assertEquals(listOf(second), content(model).trips)
  }

  @Test
  fun sessionChangeClearsLoadedTripsAndPreventsFurtherReads() = runTest {
    val model = model()
    advanceUntilIdle()
    model.selectTrip(first.id)
    session.value = "another-requester"
    advanceUntilIdle()
    assertEquals(TripMapUiState.SessionEnded, model.uiState.value)
    session.value = "requester" // Returning to the original account must not revive this model.
    model.refresh()
    model.selectTrip(second.id)
    filter.select(point)
    advanceUntilIdle()
    assertEquals(TripMapUiState.SessionEnded, model.uiState.value)
    assertEquals(1, repository.calls)
  }

  @Test
  fun signOutRejectsAPreviousSessionsLateReply() = runTest {
    val reply = CompletableDeferred<TripResult<List<Trip>>>()
    repository.read = { withContext(NonCancellable) { reply.await() } }
    val model = model()
    runCurrent()
    session.value = null
    runCurrent()
    assertEquals(TripMapUiState.SessionEnded, model.uiState.value)
    reply.complete(TripResult.Success(listOf(first)))
    advanceUntilIdle()
    assertEquals(TripMapUiState.SessionEnded, model.uiState.value)
  }

  @Test
  fun clearedDestinationCannotPublishALateReply() = runTest {
    val reply = CompletableDeferred<TripResult<List<Trip>>>()
    repository.read = { withContext(NonCancellable) { reply.await() } }
    val model = model()
    val store = ViewModelStore()
    store.put("map", model)
    runCurrent()
    store.clear()
    reply.complete(TripResult.Success(listOf(first)))
    advanceUntilIdle()
    assertEquals(TripMapUiState.Loading, model.uiState.value)
  }

  @Test
  fun defaultWaitsForNavigationEntryBeforeLoading() = runTest {
    val model = TripMapViewModel(repository, filter.selected, { session.value }, session)
    advanceUntilIdle()
    assertEquals(0, repository.calls)
    model.refresh()
    advanceUntilIdle()
    assertEquals(1, repository.calls)
    assertEquals(listOf(first, second), content(model).trips)
  }

  @Test
  fun signedOutModelDoesNotReadTrips() = runTest {
    session.value = null
    val model = model()
    advanceUntilIdle()
    assertEquals(TripMapUiState.SessionEnded, model.uiState.value)
    assertEquals(0, repository.calls)
  }

  private class RecordingRepository(fake: TripRepository) : TripRepository by fake {
    var calls = 0
    var read: suspend () -> TripResult<List<Trip>> = { fake.getUpcomingTrips() }

    override suspend fun getUpcomingTrips(): TripResult<List<Trip>> {
      calls++
      return read()
    }
  }
}
