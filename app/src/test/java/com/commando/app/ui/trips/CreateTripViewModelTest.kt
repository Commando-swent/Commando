package com.commando.app.ui.trips

// AI assistance: Claude Code wrote the intent ID and lock tests (#394) and the timeout, offline
// and saved intent tests (#429).
import androidx.lifecycle.SavedStateHandle
import com.commando.app.data.repository.FakeTripRepository
import com.commando.app.data.repository.TripError
import com.commando.app.data.repository.TripRepository
import com.commando.app.data.repository.TripResult
import com.commando.app.model.Location
import com.commando.app.model.NewTrip
import com.commando.app.model.Trip
import com.commando.app.model.TripLocations
import com.commando.app.model.TripStatus.PUBLISHED
import com.commando.app.model.TripTransition
import com.commando.app.model.storeIdFor
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CreateTripViewModelTest {

  private lateinit var repository: RecordingRepository
  private val online = MutableStateFlow(true)

  @Before
  fun setUp() {
    Dispatchers.setMain(StandardTestDispatcher())
    repository = RecordingRepository()
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  @Test
  fun errorsAreHiddenUntilTheFieldIsEdited() {
    val viewModel = createViewModel()
    assertEquals(CreateTripUiState(), viewModel.uiState.value)

    viewModel.setStore(STORE)
    viewModel.setDate(TOMORROW)
    viewModel.setTime(LocalTime.of(18, 30))
    assertEquals(
        VALID_STATE.copy(handoffLocation = null, canPublish = false),
        viewModel.uiState.value,
    )

    viewModel.setHandoffLocation(HANDOFF)
    assertEquals(VALID_STATE, viewModel.uiState.value)
  }

  @Test
  fun publishOnEmptyForm_showsEveryErrorAndCallsNothing() = runTest {
    val viewModel = createViewModel()

    viewModel.publish()
    advanceUntilIdle()

    assertEquals(
        CreateTripUiState(
            storeError = CreateTripError.STORE_REQUIRED,
            dateError = CreateTripError.DATE_REQUIRED,
            timeError = CreateTripError.TIME_REQUIRED,
            handoffLocationError = CreateTripError.HANDOFF_LOCATION_REQUIRED,
        ),
        viewModel.uiState.value,
    )
    assertTrue(repository.published.isEmpty())
  }

  @Test
  fun dateBeforeTodayInTheClockZone_isRejected() {
    val viewModel = createViewModel()
    viewModel.setDate(TODAY.minusDays(1))
    assertEquals(CreateTripError.DATE_IN_PAST, viewModel.uiState.value.dateError)

    // 22:30 UTC on 5 October is already 6 October in Zurich.
    val lateViewModel = createViewModel(Clock.fixed(Instant.parse("2026-10-05T22:30:00Z"), ZONE))
    lateViewModel.setDate(TODAY)

    assertEquals(CreateTripError.DATE_IN_PAST, lateViewModel.uiState.value.dateError)
  }

  @Test
  fun timeMustBeStrictlyInTheFuture() {
    val expected =
        mapOf(
            LocalTime.of(11, 0) to CreateTripError.TIME_IN_PAST,
            LOCAL_NOW to CreateTripError.TIME_IN_PAST,
            LOCAL_NOW.plusMinutes(1) to null,
        )
    expected.forEach { (time, error) ->
      val viewModel = createViewModel()
      viewModel.setDate(TODAY)
      viewModel.setTime(time)
      assertEquals("time $time", error, viewModel.uiState.value.timeError)
    }
  }

  @Test
  fun timeThatPassesBeforePublish_isRejectedOnPublish() = runTest {
    val clock = MutableClock(NOW)
    val viewModel = createViewModel(clock)
    viewModel.fillValidForm(date = TODAY, time = LOCAL_NOW.plusMinutes(5))
    assertTrue(viewModel.uiState.value.canPublish)

    clock.instant = NOW.plusSeconds(600)
    viewModel.publish()
    advanceUntilIdle()

    assertEquals(CreateTripError.TIME_IN_PAST, viewModel.uiState.value.timeError)
    assertFalse(viewModel.uiState.value.canPublish)
    assertTrue(repository.published.isEmpty())
  }

  @Test
  fun publish_sendsTheChosenPlacesOnceAndLocksTheForm() = runTest {
    val viewModel = createViewModel()
    viewModel.fillValidForm()

    viewModel.publish()
    advanceUntilIdle()
    viewModel.setStore(OTHER_STORE)
    viewModel.publish()
    advanceUntilIdle()

    assertEquals(listOf(NEW_TRIP), repository.published)
    assertEquals(
        VALID_STATE.copy(canPublish = false, publishedTrip = PUBLISHED_TRIP),
        viewModel.uiState.value,
    )
  }

  @Test
  fun publish_isAcceptedByTheSharedFakeRepository() = runTest {
    val fake = FakeTripRepository(currentUserId = "me", now = { NOW })
    val viewModel = createViewModel(repo = fake)
    viewModel.fillValidForm()

    viewModel.publish()
    advanceUntilIdle()

    val trip = requireNotNull(viewModel.uiState.value.publishedTrip)
    assertEquals(STORE, trip.store)
    assertEquals(HANDOFF, trip.handoffLocation)
    assertEquals(NEW_TRIP.scheduledAt, trip.scheduledAt)
    assertEquals(listOf(trip), (fake.getMyTrips() as TripResult.Success).data)
  }

  @Test
  fun publish_coopTripKeepsTheCoopStoreSoItsCatalogueIsCoop() = runTest {
    val coop = TripLocations.stores.single { it.name == "Coop Ecublens" }
    val fake = FakeTripRepository(currentUserId = "me", now = { NOW })
    val viewModel = createViewModel(repo = fake)
    viewModel.fillValidForm()
    viewModel.setStore(coop)

    viewModel.publish()
    advanceUntilIdle()

    val trip = requireNotNull(viewModel.uiState.value.publishedTrip)
    assertEquals(coop, trip.store)
    assertEquals(coop, (fake.getTripById(trip.id) as TripResult.Success).data.store)
    assertEquals("coop", storeIdFor(trip.store.name))
  }

  @Test
  fun failedPublish_keepsTheInputsAndAllowsARetry() = runTest {
    val timeout = runCatching { withTimeout(1) { awaitCancellation() } }.exceptionOrNull()
    val failures =
        ALL_ERRORS.map { it to null } +
            listOf(TripError.Unknown to IllegalStateException("down"), TripError.Unknown to timeout)
    failures.forEach { (error, exception) ->
      repository = RecordingRepository(result = TripResult.Error(error), exception = exception)
      val viewModel = createViewModel()
      viewModel.fillValidForm()

      viewModel.publish()
      advanceUntilIdle()

      assertEquals(
          "$error/$exception",
          VALID_STATE.copy(publishError = error, hasUncertainPublication = error in UNCERTAIN),
          viewModel.uiState.value,
      )
    }
  }

  @Test
  fun retryAfterFailure_publishesAndAnEditClearsTheError() = runTest {
    repository = RecordingRepository(result = TripResult.Error(TripError.InvalidData))
    val viewModel = createViewModel()
    viewModel.fillValidForm()
    viewModel.publish()
    advanceUntilIdle()

    viewModel.setHandoffLocation(OTHER_HANDOFF)
    assertNull(viewModel.uiState.value.publishError)
    repository.result = TripResult.Success(PUBLISHED_TRIP)
    viewModel.publish()
    advanceUntilIdle()

    assertEquals(2, repository.published.size)
    assertEquals(OTHER_HANDOFF, repository.published.last().handoffLocation)
    assertEquals(PUBLISHED_TRIP, viewModel.uiState.value.publishedTrip)
  }

  @Test
  fun whilePublishing_secondPublishAndEditsAreIgnored() = runTest {
    val gate = CompletableDeferred<Unit>()
    repository = RecordingRepository(result = TripResult.Error(TripError.NetworkError), gate = gate)
    val viewModel = createViewModel()
    viewModel.fillValidForm()

    viewModel.publish()
    assertEquals(VALID_STATE.copy(canPublish = false, isPublishing = true), viewModel.uiState.value)
    viewModel.publish()
    runCurrent()
    viewModel.publish()
    viewModel.setStore(OTHER_STORE)
    viewModel.setHandoffLocation(OTHER_HANDOFF)
    gate.complete(Unit)
    advanceUntilIdle()

    assertEquals(1, repository.published.size)
    assertEquals(
        VALID_STATE.copy(publishError = TripError.NetworkError, hasUncertainPublication = true),
        viewModel.uiState.value,
    )
  }

  @Test
  fun retryAfterUncertainFailure_ignoresEditsAndResendsTheIntentEvenAfterDeparture() = runTest {
    val failures =
        listOf(
            TripError.NetworkError to null,
            TripError.Unknown to null,
            TripError.Unknown to IllegalStateException("down"),
        )
    failures.forEach { (error, exception) ->
      repository = RecordingRepository(result = TripResult.Error(error), exception = exception)
      val clock = MutableClock(NOW)
      val viewModel = createViewModel(clock)
      viewModel.fillValidForm()
      viewModel.publish()
      advanceUntilIdle()
      val locked = viewModel.uiState.value
      viewModel.setStore(OTHER_STORE)
      viewModel.setDate(TOMORROW.plusDays(1))
      viewModel.setTime(LocalTime.of(9, 0))
      viewModel.setHandoffLocation(OTHER_HANDOFF)
      assertTrue(locked.hasUncertainPublication && locked.canPublish)
      assertEquals("Edits wait for the uncertain attempt", locked, viewModel.uiState.value)

      clock.instant = NEW_TRIP.scheduledAt.plusSeconds(60) // Past departure: no re-validation.
      viewModel.publish()
      advanceUntilIdle()
      val past = viewModel.uiState.value // Locked fields show no error; only the retry remains.
      assertTrue(past.canPublish && past.dateError == null && past.timeError == null)

      repository.exception = null
      repository.result = TripResult.Success(PUBLISHED_TRIP)
      viewModel.publish()
      advanceUntilIdle()

      assertEquals("$error/$exception", 3, repository.tripIds.size)
      assertEquals("$error/$exception", 1, repository.tripIds.distinct().size)
      assertEquals(listOf(NEW_TRIP, NEW_TRIP, NEW_TRIP), repository.published)
      assertEquals(PUBLISHED_TRIP, viewModel.uiState.value.publishedTrip)
      assertFalse(viewModel.uiState.value.hasUncertainPublication)
    }
  }

  @Test
  fun definitiveFailureAfterUncertainOne_unlocksTheFormAndStartsANewIntent() = runTest {
    repository = RecordingRepository(result = TripResult.Error(TripError.NetworkError))
    val viewModel = createViewModel()
    viewModel.fillValidForm()
    viewModel.publish()
    advanceUntilIdle()
    repository.result = TripResult.Error(TripError.InvalidData)
    viewModel.publish()
    advanceUntilIdle()

    assertFalse(viewModel.uiState.value.hasUncertainPublication)
    viewModel.setHandoffLocation(OTHER_HANDOFF)
    repository.result = TripResult.Success(PUBLISHED_TRIP)
    viewModel.publish()
    advanceUntilIdle()

    assertEquals(OTHER_HANDOFF, repository.published[2].handoffLocation)
    assertEquals(repository.tripIds[0], repository.tripIds[1])
    assertNotEquals(repository.tripIds[1], repository.tripIds[2])
  }

  @Test
  fun retryAfterDefinitiveFailure_usesANewTripId() = runTest {
    listOf(TripError.InvalidData, TripError.PermissionDenied).forEach { error ->
      repository = RecordingRepository(result = TripResult.Error(error))
      val viewModel = createViewModel()
      viewModel.fillValidForm()
      viewModel.publish()
      advanceUntilIdle()

      repository.result = TripResult.Success(PUBLISHED_TRIP)
      viewModel.publish()
      advanceUntilIdle()

      assertEquals("$error", 2, repository.tripIds.distinct().size)
      assertEquals(PUBLISHED_TRIP, viewModel.uiState.value.publishedTrip)
    }
  }

  @Test
  fun eachViewModelIntentAndRepeatedTaps_useOneTripIdPerIntent() = runTest {
    val gate = CompletableDeferred<Unit>()
    repository = RecordingRepository(gate = gate)
    val first = createViewModel().apply { fillValidForm() }
    first.publish()
    first.publish()
    runCurrent()
    first.publish()
    gate.complete(Unit)
    advanceUntilIdle()
    createViewModel().apply { fillValidForm() }.publish()
    advanceUntilIdle()

    assertEquals(2, repository.tripIds.size)
    assertEquals(2, repository.tripIds.distinct().size)
    assertTrue(repository.tripIds.all { it.isNotBlank() })
  }

  @Test
  fun timeout_isUncertainAndTheRetryReusesTheTripId() = runTest {
    val gate = CompletableDeferred<Unit>()
    repository = RecordingRepository(gate = gate)
    val viewModel = createViewModel()
    viewModel.fillValidForm()

    viewModel.publish()
    advanceTimeBy(TIMEOUT_SECONDS * 1_000 - 1)
    assertTrue(viewModel.uiState.value.isPublishing)
    advanceUntilIdle()
    assertEquals(
        VALID_STATE.copy(publishError = TripError.Unknown, hasUncertainPublication = true),
        viewModel.uiState.value,
    )

    gate.complete(Unit)
    viewModel.publish()
    advanceUntilIdle()

    assertEquals(2, repository.tripIds.size)
    assertEquals(1, repository.tripIds.distinct().size)
    assertEquals(PUBLISHED_TRIP, viewModel.uiState.value.publishedTrip)
  }

  @Test
  fun offline_refusesBeforeAnyWriteWithoutConsumingAnIdUntilATapOnline() = runTest {
    online.value = false
    val savedState = SavedStateHandle()
    val viewModel = createViewModel(savedState = savedState)
    viewModel.fillValidForm()

    viewModel.publish()
    advanceUntilIdle()

    assertTrue(repository.tripIds.isEmpty())
    assertNull(savedState.get<List<String>>(PENDING_KEY))
    assertEquals(VALID_STATE.copy(isOffline = true), viewModel.uiState.value)
    online.value = true
    viewModel.publish()
    advanceUntilIdle()
    assertEquals(1, repository.tripIds.size)
    assertFalse(viewModel.uiState.value.isOffline)
  }

  @Test
  fun offline_anEditClearsTheRefusal() = runTest {
    online.value = false
    val viewModel = createViewModel().apply { fillValidForm() }
    viewModel.publish()
    assertTrue(viewModel.uiState.value.isOffline)

    viewModel.setTime(LocalTime.of(19, 0))

    assertFalse(viewModel.uiState.value.isOffline)
    assertTrue(repository.tripIds.isEmpty())
  }

  @Test
  fun offline_keepsAnUncertainIntentPendingForTheNextRetry() = runTest {
    repository = RecordingRepository(result = TripResult.Error(TripError.NetworkError))
    val viewModel = createViewModel()
    viewModel.fillValidForm()
    viewModel.publish()
    advanceUntilIdle()

    online.value = false
    viewModel.publish()
    advanceUntilIdle()
    val refused = viewModel.uiState.value
    assertTrue(refused.isOffline && refused.hasUncertainPublication && refused.canPublish)
    assertEquals(1, repository.tripIds.size)

    online.value = true
    repository.result = TripResult.Success(PUBLISHED_TRIP)
    viewModel.publish()
    advanceUntilIdle()
    assertEquals(1, repository.tripIds.distinct().size)
    assertEquals(PUBLISHED_TRIP, viewModel.uiState.value.publishedTrip)
  }

  @Test
  fun recreation_restoresTheUncertainIntentForTheSameAccountOnly() = runTest {
    repository = RecordingRepository(result = TripResult.Error(TripError.NetworkError))
    val savedState = SavedStateHandle()
    createViewModel(savedState = savedState).apply { fillValidForm() }.publish()
    advanceUntilIdle()
    val saved = savedState.get<List<String>>(PENDING_KEY)

    val other = createViewModel(userId = "other", savedState = savedState)
    assertEquals(CreateTripUiState(), other.uiState.value)
    assertNull(savedState.get<List<String>>(PENDING_KEY))

    savedState[PENDING_KEY] = saved
    val restored = createViewModel(savedState = savedState)
    assertEquals(VALID_STATE.copy(hasUncertainPublication = true), restored.uiState.value)
    restored.setStore(OTHER_STORE)
    repository.result = TripResult.Success(PUBLISHED_TRIP)
    restored.publish()
    advanceUntilIdle()
    assertEquals(listOf(NEW_TRIP, NEW_TRIP), repository.published)
    assertEquals(1, repository.tripIds.distinct().size)
  }

  @Test
  fun restoredIntentRefusedAfterDeparture_unlocksWithTheFieldErrorsShown() = runTest {
    repository = RecordingRepository(result = TripResult.Error(TripError.NetworkError))
    val savedState = SavedStateHandle()
    createViewModel(savedState = savedState).apply { fillValidForm() }.publish()
    advanceUntilIdle()

    val past = Clock.fixed(NEW_TRIP.scheduledAt.plusSeconds(60), ZONE)
    val restored = createViewModel(clock = past, savedState = savedState)
    repository.result = TripResult.Error(TripError.InvalidData)
    restored.publish()
    advanceUntilIdle()

    val state = restored.uiState.value
    assertEquals(CreateTripError.TIME_IN_PAST, state.timeError) // Same day, past time.
    assertFalse(state.canPublish || state.hasUncertainPublication)
    assertNull(savedState.get<List<String>>(PENDING_KEY))
  }

  @Test
  fun malformedSavedIntent_isDroppedForAnEmptyForm() {
    val savedState =
        SavedStateHandle(mapOf(PENDING_KEY to arrayListOf("me", "trip-1", "not a date")))

    assertEquals(CreateTripUiState(), createViewModel(savedState = savedState).uiState.value)
    assertNull(savedState.get<List<String>>(PENDING_KEY))
  }

  @Test
  fun savedIntent_isClearedOnceTheIntentSettles() = runTest {
    listOf(TripResult.Success(PUBLISHED_TRIP), TripResult.Error(TripError.InvalidData)).forEach {
      repository = RecordingRepository(result = TripResult.Error(TripError.Unknown))
      val savedState = SavedStateHandle()
      val viewModel = createViewModel(savedState = savedState).apply { fillValidForm() }
      viewModel.publish()
      advanceUntilIdle()
      assertNotNull(savedState.get<List<String>>(PENDING_KEY))

      repository.result = it
      viewModel.publish()
      advanceUntilIdle()
      assertNull("$it", savedState.get<List<String>>(PENDING_KEY))
    }
  }

  private fun createViewModel(
      clock: Clock = Clock.fixed(NOW, ZONE),
      userId: String = "me",
      repo: TripRepository = repository,
      savedState: SavedStateHandle = SavedStateHandle(),
  ) =
      CreateTripViewModel(
          repo,
          savedState,
          userId,
          online,
          clock,
          TIMEOUT_SECONDS.seconds,
      )

  private fun CreateTripViewModel.fillValidForm(
      date: LocalDate = TOMORROW,
      time: LocalTime = LocalTime.of(18, 30),
  ) {
    setStore(STORE)
    setDate(date)
    setTime(time)
    setHandoffLocation(HANDOFF)
  }

  /** Records published trips; returns [result], or throws [exception], after [gate] opens. */
  private class RecordingRepository(
      var result: TripResult<Trip> = TripResult.Success(PUBLISHED_TRIP),
      var exception: Throwable? = null,
      private val gate: CompletableDeferred<Unit>? = null,
  ) : TripRepository {
    val published = mutableListOf<NewTrip>()

    val tripIds = mutableListOf<String>()

    override suspend fun publishTrip(tripId: String, newTrip: NewTrip): TripResult<Trip> {
      tripIds += tripId
      published += newTrip
      gate?.await()
      exception?.let { throw it }
      return result
    }

    override suspend fun getTripById(tripId: String) = error("not used")

    override suspend fun getUpcomingTrips() = error("not used")

    override suspend fun getMyTrips() = error("not used")

    override suspend fun transitionTrip(tripId: String, transition: TripTransition) =
        error("not used")
  }

  private class MutableClock(var instant: Instant) : Clock() {
    override fun getZone(): ZoneId = ZONE

    override fun withZone(zone: ZoneId): Clock = this

    override fun instant(): Instant = instant
  }

  private companion object {
    const val PENDING_KEY = "createTripPendingIntent"
    const val TIMEOUT_SECONDS = 15L
    val ZONE: ZoneId = ZoneId.of("Europe/Zurich")
    val NOW: Instant = Instant.parse("2026-10-05T10:00:00Z") // 12:00 in Zurich
    val TODAY: LocalDate = LocalDate.of(2026, 10, 5)
    val TOMORROW: LocalDate = TODAY.plusDays(1)
    val LOCAL_NOW: LocalTime = LocalTime.of(12, 0)
    val UNCERTAIN = setOf(TripError.NetworkError, TripError.Unknown)
    val ALL_ERRORS =
        listOf(
            TripError.NotFound,
            TripError.NetworkError,
            TripError.PermissionDenied,
            TripError.InvalidData,
            TripError.Unknown,
        )

    val STORE: Location = TripLocations.stores[0]
    val OTHER_STORE: Location = TripLocations.stores[1]
    val HANDOFF: Location = TripLocations.handoffPoints[0]
    val OTHER_HANDOFF: Location = TripLocations.handoffPoints[1]

    val VALID_STATE =
        CreateTripUiState(
            store = STORE,
            date = TOMORROW,
            time = LocalTime.of(18, 30),
            handoffLocation = HANDOFF,
            canPublish = true,
        )

    // 18:30 on 6 October in Zurich.
    val NEW_TRIP = NewTrip(STORE, Instant.parse("2026-10-06T16:30:00Z"), HANDOFF)

    val PUBLISHED_TRIP = NEW_TRIP.run {
      Trip("trip-1", "owner-1", store, scheduledAt, handoffLocation, PUBLISHED, NOW, NOW)
    }
  }
}
