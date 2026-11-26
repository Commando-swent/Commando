package com.commando.app.ui.trips

// AI assistance: Claude Code for the close and complete tests (#233).
// AI assistance: OpenAI Codex for public-name refresh regression coverage.
import com.commando.app.MainDispatcherRule
import com.commando.app.data.repository.FakeTripRepository
import com.commando.app.data.repository.PublicProfileTestRepository
import com.commando.app.data.repository.TripError
import com.commando.app.data.repository.TripRepository
import com.commando.app.data.repository.TripResult
import com.commando.app.model.Location
import com.commando.app.model.Trip
import com.commando.app.model.TripStatus
import com.commando.app.model.TripTransition
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
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

  @get:Rule val mainDispatcherRule = MainDispatcherRule()

  @Test
  fun networkLossDuringOwnerTransitionRequiresFreshConfirmation() = runTest {
    val online = MutableStateFlow(true)
    val cache = com.commando.app.data.cache.SessionCache { "owner" }
    val vm =
        TripDetailsViewModel(
            repository,
            trip.id,
            currentUserId = { "owner" },
            cache = cache,
            online = online,
        )
    advanceUntilIdle()
    repository.transitionGate = CompletableDeferred()
    vm.requestTransition(TripTransition.CLOSE)
    vm.confirmTransition()
    runCurrent()
    online.value = false
    advanceUntilIdle()
    repository.transitionGate!!.complete(Unit)
    advanceUntilIdle()
    assertEquals(TripDetailsUiState.Error(TripError.NetworkError, true), vm.uiState.value)
    online.value = true
    advanceUntilIdle()
    assertEquals(TripStatus.IN_PROGRESS, content(vm).trip.status)
  }

  @Test
  fun expiredSessionDropsLateTripReadAndAllSubsequentActions() = runTest {
    var uid: String? = "owner"
    val session = MutableStateFlow(uid)
    val cache = com.commando.app.data.cache.SessionCache { uid }
    repository.gate = CompletableDeferred()
    val vm =
        TripDetailsViewModel(
            repository,
            trip.id,
            currentUserId = { uid },
            sessionChanges = session,
            cache = cache,
        )
    runCurrent()
    uid = "new-owner"
    session.value = uid
    runCurrent()
    repository.gate!!.complete(Unit)
    advanceUntilIdle()
    vm.retry()
    assertEquals(TripDetailsUiState.Error(TripError.PermissionDenied), vm.uiState.value)
    assertEquals(listOf(trip.id), repository.requestedIds)
  }

  @Test
  fun staleTripWhileConnectedCannotStartOrCommitATransition() = runTest {
    val online = MutableStateFlow(true)
    val cache = com.commando.app.data.cache.SessionCache { "owner" }
    val vm = TripDetailsViewModel(repository, trip.id, online = online, cache = cache)
    advanceUntilIdle()
    fake.forcedError = TripError.Unknown
    vm.retry()
    advanceUntilIdle()
    val state = content(vm)
    assertNotNull(state.cachedAt)
    vm.requestTransition(TripTransition.CLOSE)
    vm.confirmTransition()
    assertTrue(repository.transitions.isEmpty())
    assertEquals(state, vm.uiState.value)
    cache.close()
    fake.forcedError = null
    vm.retry()
    advanceUntilIdle()
    assertEquals(TripDetailsUiState.Error(TripError.PermissionDenied), vm.uiState.value)
  }

  @Test
  fun offlineTripRetainsTimeRejectsTransitionsAndEvictsDeniedData() = runTest {
    val online = MutableStateFlow(false)
    val cache = com.commando.app.data.cache.SessionCache { "owner" }
    val vm = TripDetailsViewModel(repository, trip.id, cache = cache, online = online)
    assertEquals(TripDetailsUiState.Error(TripError.NetworkError, true), vm.uiState.value)
    online.value = true
    advanceUntilIdle()
    online.value = false
    advanceUntilIdle()
    val stale = vm.uiState.value as TripDetailsUiState.Content
    assertNotNull(stale.cachedAt)
    vm.retry()
    vm.requestTransition(TripTransition.CLOSE)
    vm.confirmTransition()
    assertEquals(stale, vm.uiState.value)
    fake.forcedError = TripError.PermissionDenied
    online.value = true
    advanceUntilIdle()
    assertEquals(TripDetailsUiState.Error(TripError.PermissionDenied), vm.uiState.value)
    online.value = false
    advanceUntilIdle()
    assertEquals(TripDetailsUiState.Error(TripError.NetworkError, true), vm.uiState.value)
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

  private val profiles = PublicProfileTestRepository()

  private fun TestScope.publicTrip(user: MutableStateFlow<String?> = MutableStateFlow("viewer")) =
      TripDetailsViewModel(repository, trip.id, lazyOf(profiles), { user.value }, user).also {
        runCurrent()
      }

  @Test
  fun constructionUsedByReviewOrderNeverLooksUpPublicNames() = runTest {
    publicTrip()
    assertTrue(profiles.reads.isEmpty())
  }

  @Test
  fun publicNameUsesLoadedOwnerAndDeduplicatesPendingRequests() = runTest {
    val response = profiles.deferRead()
    val model = publicTrip()
    model.refreshPublicName("wrong-owner")
    model.refreshPublicName(trip.ownerId)
    model.refreshPublicName(trip.ownerId)
    runCurrent()
    assertEquals(listOf(trip.ownerId), profiles.reads)
    response.complete("Public owner")
    runCurrent()
    assertEquals(trip.ownerId to "Public owner", model.publicName.value)
  }

  @Test
  fun sameOwnerRefreshRetainsConfirmedNameUntilSuccessfulNullReplacesItWithFallback() = runTest {
    val model = publicTrip()
    profiles.read = { "Confirmed" }
    model.refreshPublicName(trip.ownerId)
    runCurrent()
    val response = profiles.deferRead()
    model.refreshPublicName(trip.ownerId)
    assertEquals(trip.ownerId to "Confirmed", model.publicName.value)
    runCurrent()
    assertEquals(trip.ownerId to "Confirmed", model.publicName.value)
    response.complete(null)
    runCurrent()
    assertEquals(trip.ownerId to null, model.publicName.value)
    assertEquals(TripDetailsUiState.Content(trip), model.uiState.value)
  }

  @Test
  fun failedSameOwnerRefreshRetainsConfirmedName() = runTest {
    val model = publicTrip()
    profiles.name = "Confirmed"
    model.refreshPublicName(trip.ownerId)
    runCurrent()
    profiles.read = { error("Offline") }
    model.refreshPublicName(trip.ownerId)
    runCurrent()
    assertEquals(trip.ownerId to "Confirmed", model.publicName.value)
  }

  @Test
  fun initialPublicNameFailureUsesFallback() = runTest {
    val model = publicTrip()
    profiles.read = { error("Offline") }
    model.refreshPublicName(trip.ownerId)
    runCurrent()
    assertEquals(trip.ownerId to null, model.publicName.value)
  }

  @Test
  fun changedOwnerRejectsLateNameResponse() = runTest {
    profiles.name = "Confirmed"
    val model = publicTrip()
    model.refreshPublicName(trip.ownerId)
    runCurrent()
    val response = profiles.deferRead()
    model.refreshPublicName(trip.ownerId)
    runCurrent()
    repository.result = TripResult.Success(trip.copy(ownerId = "next"))
    profiles.read = { "Next" }
    model.retry()
    runCurrent()
    model.refreshPublicName("next")
    assertEquals("next" to null, model.publicName.value)
    runCurrent()
    response.complete("Old")
    runCurrent()
    assertEquals("next" to "Next", model.publicName.value)
  }

  @Test
  fun sessionChangeClearsPublicNameAndRejectsLateResponse() = runTest {
    val user = MutableStateFlow<String?>("viewer")
    profiles.name = "Confirmed"
    val model = publicTrip(user)
    model.refreshPublicName(trip.ownerId)
    runCurrent()
    val response = profiles.deferRead()
    model.refreshPublicName(trip.ownerId)
    runCurrent()
    user.value = "other"
    model.refreshPublicName(trip.ownerId)
    assertNull(model.publicName.value)
    runCurrent()
    response.complete("Old")
    runCurrent()
    assertNull(model.publicName.value)
    model.refreshPublicName(trip.ownerId)
    runCurrent()
    assertEquals(2, profiles.reads.size)
  }

  @Test
  fun absentPublicRepositoryLeavesTripDetailsUsable() = runTest {
    val model = TripDetailsViewModel(repository, trip.id)
    runCurrent()
    model.refreshPublicName(trip.ownerId)
    runCurrent()
    assertNull(model.publicName.value)
    assertEquals(TripDetailsUiState.Content(trip), model.uiState.value)
  }

  @Test
  fun publicLookupRequiresLoadedTripContent() = runTest {
    repository.result = TripResult.Error(TripError.NotFound)
    val model = publicTrip()
    model.refreshPublicName(trip.ownerId)
    model.refreshPublicName(null)
    runCurrent()
    assertTrue(profiles.reads.isEmpty())
    assertNull(model.publicName.value)
    assertEquals(TripDetailsUiState.Error(TripError.NotFound), model.uiState.value)
  }

  @Test
  fun snapshotChangeInsidePublicReadRejectsNameWithoutSessionEmission() = runTest {
    var uid: String? = "viewer"
    profiles.read = {
      uid = null
      "Stale"
    }
    val model = TripDetailsViewModel(repository, trip.id, lazyOf(profiles), { uid })
    runCurrent()
    model.refreshPublicName(trip.ownerId)
    runCurrent()
    assertEquals(trip.ownerId to null, model.publicName.value)
    model.refreshPublicName(trip.ownerId)
    assertNull(model.publicName.value)
    assertEquals(listOf(trip.ownerId), profiles.reads)
  }
}
