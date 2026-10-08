package com.android.sample.ui.trips

// AI assistance: Claude Code.
import com.android.sample.data.repository.TripError
import com.android.sample.data.repository.TripRepository
import com.android.sample.data.repository.TripResult
import com.android.sample.model.Location
import com.android.sample.model.NewTrip
import com.android.sample.model.Trip
import com.android.sample.model.TripStatus.PUBLISHED
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
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
  private val drafts = mutableListOf<TripDraft>()

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
    viewModel.setStore("Migros")
    viewModel.setDate(TOMORROW)
    viewModel.setTime(LocalTime.of(18, 30))
    viewModel.setHandoffLocation("EPFL")
    assertEquals(VALID_STATE.copy(maxOrders = "", canPublish = false), viewModel.uiState.value)

    viewModel.setHandoffLocation("   ")

    val state = viewModel.uiState.value
    assertEquals(CreateTripError.HANDOFF_LOCATION_REQUIRED, state.handoffLocationError)
    assertNull(state.maxOrdersError)
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
            maxOrdersError = CreateTripError.MAX_ORDERS_REQUIRED,
        ),
        viewModel.uiState.value,
    )
    assertTrue(drafts.isEmpty() && repository.published.isEmpty())
  }

  @Test
  fun blankStore_isRejectedAndKeptAsTyped() {
    val viewModel = createViewModel()
    viewModel.fillValidForm()

    viewModel.setStore("   ")

    assertEquals("   ", viewModel.uiState.value.store)
    assertEquals(CreateTripError.STORE_REQUIRED, viewModel.uiState.value.storeError)
    assertFalse(viewModel.uiState.value.canPublish)
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
    assertTrue(drafts.isEmpty() && repository.published.isEmpty())
  }

  @Test
  fun maxOrders_mustBeAPositiveWholeNumber() {
    val invalid = listOf("0", "00", "-1", "2.5", "abc", "1e3", "+3", "1 2", "99999999999", "١٢")
    val expected: Map<String, CreateTripError?> =
        mapOf(
            "" to CreateTripError.MAX_ORDERS_REQUIRED,
            "  " to CreateTripError.MAX_ORDERS_REQUIRED,
        ) +
            invalid.associateWith { CreateTripError.MAX_ORDERS_NOT_POSITIVE_INTEGER } +
            listOf("1", " 3 ", "007", "2147483647").associateWith { null }
    expected.forEach { (text, error) ->
      val viewModel = createViewModel()
      viewModel.fillValidForm(maxOrders = text)
      assertEquals("max orders '$text'", error, viewModel.uiState.value.maxOrdersError)
      assertEquals("max orders '$text'", error == null, viewModel.uiState.value.canPublish)
      assertEquals(text, viewModel.uiState.value.maxOrders)
    }
  }

  @Test
  fun publish_sendsTheDraftOnceAndLocksTheForm() = runTest {
    val viewModel = createViewModel()
    viewModel.fillValidForm(store = " Migros ", maxOrders = " 3 ", handoff = "  EPFL ")

    viewModel.publish()
    advanceUntilIdle()
    viewModel.setMaxOrders("5")
    viewModel.publish()
    advanceUntilIdle()

    assertEquals(listOf(VALID_DRAFT), drafts)
    assertEquals(listOf(newTripFor(VALID_DRAFT)), repository.published)
    assertEquals(
        VALID_STATE.copy(
            store = " Migros ",
            maxOrders = " 3 ",
            handoffLocation = "  EPFL ",
            canPublish = false,
            publishedTrip = PUBLISHED_TRIP,
        ),
        viewModel.uiState.value,
    )
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
          VALID_STATE.copy(publishError = error),
          viewModel.uiState.value,
      )
    }
  }

  @Test
  fun retryAfterFailure_publishesAndAnEditClearsTheError() = runTest {
    repository = RecordingRepository(result = TripResult.Error(TripError.NetworkError))
    val viewModel = createViewModel()
    viewModel.fillValidForm()
    viewModel.publish()
    advanceUntilIdle()

    viewModel.setHandoffLocation("Rolex Center")
    assertNull(viewModel.uiState.value.publishError)
    repository.result = TripResult.Success(PUBLISHED_TRIP)
    viewModel.publish()
    advanceUntilIdle()

    assertEquals(2, repository.published.size)
    assertEquals("Rolex Center", repository.published.last().handoffLocation.name)
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
    viewModel.setMaxOrders("9")
    viewModel.setStore("Coop")
    gate.complete(Unit)
    advanceUntilIdle()

    assertEquals(1, drafts.size)
    assertEquals(1, repository.published.size)
    assertEquals(VALID_STATE.copy(publishError = TripError.NetworkError), viewModel.uiState.value)
  }

  @Test
  fun defaultMapping_neverCallsTheRepository() = runTest {
    val viewModel = CreateTripViewModel(repository, Clock.fixed(NOW, ZONE))
    viewModel.fillValidForm()

    viewModel.publish()
    advanceUntilIdle()

    assertNull(VALID_DRAFT.toNewTrip())
    assertEquals(emptyList<NewTrip>(), repository.published)
    assertEquals(VALID_STATE.copy(publishError = TripError.InvalidData), viewModel.uiState.value)
  }

  private fun createViewModel(clock: Clock = Clock.fixed(NOW, ZONE)) =
      CreateTripViewModel(repository, clock) { draft ->
        drafts += draft
        newTripFor(draft)
      }

  private fun CreateTripViewModel.fillValidForm(
      store: String = "Migros",
      date: LocalDate = TOMORROW,
      time: LocalTime = LocalTime.of(18, 30),
      handoff: String = "EPFL",
      maxOrders: String = "3",
  ) {
    setStore(store)
    setDate(date)
    setTime(time)
    setHandoffLocation(handoff)
    setMaxOrders(maxOrders)
  }

  /** Records published trips; returns [result], or throws [exception], after [gate] opens. */
  private class RecordingRepository(
      var result: TripResult<Trip> = TripResult.Success(PUBLISHED_TRIP),
      private val exception: Throwable? = null,
      private val gate: CompletableDeferred<Unit>? = null,
  ) : TripRepository {
    val published = mutableListOf<NewTrip>()

    override suspend fun publishTrip(newTrip: NewTrip): TripResult<Trip> {
      published += newTrip
      gate?.await()
      exception?.let { throw it }
      return result
    }

    override suspend fun getTripById(tripId: String) = error("not used")

    override suspend fun getUpcomingTrips() = error("not used")

    override suspend fun getMyTrips() = error("not used")
  }

  private class MutableClock(var instant: Instant) : Clock() {
    override fun getZone(): ZoneId = ZONE

    override fun withZone(zone: ZoneId): Clock = this

    override fun instant(): Instant = instant
  }

  private companion object {
    val ZONE: ZoneId = ZoneId.of("Europe/Zurich")
    val NOW: Instant = Instant.parse("2026-10-05T10:00:00Z") // 12:00 in Zurich
    val TODAY: LocalDate = LocalDate.of(2026, 10, 5)
    val TOMORROW: LocalDate = TODAY.plusDays(1)
    val LOCAL_NOW: LocalTime = LocalTime.of(12, 0)
    val ALL_ERRORS =
        listOf(
            TripError.NotFound,
            TripError.NetworkError,
            TripError.PermissionDenied,
            TripError.InvalidData,
            TripError.Unknown,
        )

    val VALID_STATE =
        CreateTripUiState(
            store = "Migros",
            date = TOMORROW,
            time = LocalTime.of(18, 30),
            handoffLocation = "EPFL",
            maxOrders = "3",
            canPublish = true,
        )

    val VALID_DRAFT = TripDraft("Migros", Instant.parse("2026-10-06T16:30:00Z"), "EPFL", 3)

    fun place(name: String) = Location(name, 46.52, 6.57)

    fun newTripFor(draft: TripDraft) =
        NewTrip(place(draft.store), draft.scheduledAt, place(draft.handoffLocation))

    val PUBLISHED_TRIP =
        newTripFor(VALID_DRAFT).run {
          Trip("trip-1", "owner-1", store, scheduledAt, handoffLocation, PUBLISHED, NOW, NOW)
        }
  }
}
