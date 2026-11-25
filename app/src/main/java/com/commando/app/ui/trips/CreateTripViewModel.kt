package com.commando.app.ui.trips

// AI assistance: Claude Code, including the publication intent ID (#394) and its bounded,
// offline-aware and saved retry (#429).
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.commando.app.data.repository.TripError
import com.commando.app.data.repository.TripRepository
import com.commando.app.data.repository.TripResult
import com.commando.app.model.Location
import com.commando.app.model.NewTrip
import com.commando.app.model.Trip
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Trip creation form. Fields are validated on every edit and again when publishing, since the
 * chosen time may have passed in between. An attempt whose outcome is unknown is kept in
 * [savedStateHandle] for [userId], so it survives process death for that account only. Publishing
 * is refused while [isOnline] is false, and a wait longer than [publishTimeout] is uncertain.
 */
class CreateTripViewModel(
    private val repository: TripRepository,
    private val savedStateHandle: SavedStateHandle,
    private val userId: String,
    private val isOnline: StateFlow<Boolean>,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val publishTimeout: Duration = 15.seconds,
) : ViewModel() {
  private enum class Field {
    STORE,
    DATE,
    TIME,
    HANDOFF_LOCATION,
  }

  private val touchedFields = mutableSetOf<Field>()
  /**
   * The trip ID and trip of an attempt whose outcome is unknown. Retries resend exactly this
   * intent, never a new ID per tap, and edits wait until the server settles it.
   */
  private var pending: Pair<String, NewTrip>? = null
    set(value) {
      field = value
      savedStateHandle[PENDING_KEY] = value?.let { (id, trip) -> trip.toSaved(id) }
    }

  private val mutableState = MutableStateFlow(CreateTripUiState())
  val uiState: StateFlow<CreateTripUiState> = mutableState.asStateFlow()

  init {
    // Another account's attempt, or an unreadable one, is dropped: it is not this user's to check.
    pending =
        runCatching {
          savedStateHandle
              .get<ArrayList<String>>(PENDING_KEY)
              ?.takeIf { it.size == SAVED_SIZE && it[0] == userId }
              ?.let { it[1] to it.toNewTrip() }
        }
            .getOrNull()
    pending?.let { (_, trip) ->
      // Errors are shown if a retry fails definitively and unlocks the form.
      touchedFields += Field.entries
      val local = trip.scheduledAt.atZone(clock.zone)
      mutableState.value =
          validate(
              CreateTripUiState(
                  store = trip.store,
                  date = local.toLocalDate(),
                  time = local.toLocalTime(),
                  handoffLocation = trip.handoffLocation,
                  hasUncertainPublication = true,
              ),
              clock.instant(),
          )
    }
  }

  fun setStore(store: Location) = edit(Field.STORE) { copy(store = store) }

  fun setDate(date: LocalDate) = edit(Field.DATE) { copy(date = date) }

  fun setTime(time: LocalTime) = edit(Field.TIME) { copy(time = time) }

  fun setHandoffLocation(location: Location) =
      edit(Field.HANDOFF_LOCATION) { copy(handoffLocation = location) }

  fun publish() {
    val state = uiState.value
    if (state.isPublishing || state.publishedTrip != null) return
    // An uncertain attempt is resent as it was, even once its departure has passed.
    if (pending == null) touchedFields += Field.entries
    val checked =
        if (pending != null) state else validate(state.copy(publishError = null), clock.instant())
    val newTrip =
        pending?.second
            ?: checked.toNewTrip()
            ?: run {
              mutableState.value = checked
              return
            }
    // Refused before any write, and before a new intent ID exists; a pending one stays pending.
    if (!isOnline.value) {
      mutableState.value = checked.copy(isOffline = true)
      return
    }
    val tripId = pending?.first ?: UUID.randomUUID().toString()
    // Set before launching so two taps in the same frame cannot publish twice.
    mutableState.value =
        state.copy(isPublishing = true, canPublish = false, publishError = null, isOffline = false)
    pending = tripId to newTrip
    viewModelScope.launch {
      val result =
          try {
            // A timeout may hide a server commit, so it is uncertain like an unknown failure.
            withTimeoutOrNull(publishTimeout) { repository.publishTrip(tripId, newTrip) }
                ?: TripResult.Error(TripError.Unknown)
          } catch (cancelled: CancellationException) {
            // Rethrow only if this coroutine was cancelled; a repository timeout is a failure.
            currentCoroutineContext().ensureActive()
            TripResult.Error(TripError.Unknown)
          } catch (_: Exception) {
            TripResult.Error(TripError.Unknown)
          }
      onPublishResult(result)
    }
  }

  private fun onPublishResult(result: TripResult<Trip>) {
    // Only a network or unknown failure may hide a server commit, so only those keep the ID.
    val uncertain =
        result is TripResult.Error &&
            (result.error == TripError.NetworkError || result.error == TripError.Unknown)
    if (!uncertain) pending = null
    val state = uiState.value.copy(isPublishing = false, hasUncertainPublication = pending != null)
    mutableState.value =
        when (result) {
          is TripResult.Success -> state.copy(publishedTrip = result.data, canPublish = false)
          is TripResult.Error -> validate(state.copy(publishError = result.error), clock.instant())
        }
  }

  private fun edit(field: Field, change: CreateTripUiState.() -> CreateTripUiState) {
    val state = uiState.value
    if (state.isPublishing || state.publishedTrip != null || pending != null) return
    touchedFields += field
    mutableState.value =
        validate(state.change().copy(publishError = null, isOffline = false), clock.instant())
  }

  private fun validate(state: CreateTripUiState, now: Instant): CreateTripUiState {
    val errors =
        mapOf(
            Field.STORE to storeError(state),
            Field.DATE to dateError(state, now),
            Field.TIME to timeError(state, now),
            Field.HANDOFF_LOCATION to handoffLocationError(state),
        )
    // A locked form shows no field error, since only the retry is possible.
    fun shown(field: Field) = errors[field]?.takeIf { field in touchedFields && pending == null }
    return state.copy(
        storeError = shown(Field.STORE),
        dateError = shown(Field.DATE),
        timeError = shown(Field.TIME),
        handoffLocationError = shown(Field.HANDOFF_LOCATION),
        canPublish =
            (pending != null || errors.values.all { it == null }) &&
                !state.isPublishing &&
                state.publishedTrip == null,
    )
  }

  private fun storeError(state: CreateTripUiState) =
      if (state.store == null) CreateTripError.STORE_REQUIRED else null

  private fun dateError(state: CreateTripUiState, now: Instant) =
      when {
        state.date == null -> CreateTripError.DATE_REQUIRED
        state.date < now.atZone(clock.zone).toLocalDate() -> CreateTripError.DATE_IN_PAST
        else -> null
      }

  private fun timeError(state: CreateTripUiState, now: Instant): CreateTripError? {
    val date = state.date
    return when {
      state.time == null -> CreateTripError.TIME_REQUIRED
      date == null || dateError(state, now) != null -> null
      scheduledAt(date, state.time) <= now -> CreateTripError.TIME_IN_PAST
      else -> null
    }
  }

  private fun handoffLocationError(state: CreateTripUiState) =
      if (state.handoffLocation == null) CreateTripError.HANDOFF_LOCATION_REQUIRED else null

  private fun scheduledAt(date: LocalDate, time: LocalTime): Instant =
      LocalDateTime.of(date, time).atZone(clock.zone).toInstant()

  /** [canPublish] already checked every field, including that the time is in the future. */
  private fun CreateTripUiState.toNewTrip(): NewTrip? {
    if (!canPublish || store == null || date == null || time == null) return null
    if (handoffLocation == null) return null
    return NewTrip(store, scheduledAt(date, time), handoffLocation)
  }

  /** Strings only, so it stays bundleable: uid, trip ID, then the trip's fields in order. */
  private fun NewTrip.toSaved(tripId: String): ArrayList<String> =
      ArrayList(
          listOf(userId, tripId, scheduledAt.toString()) +
              listOf(store, handoffLocation).flatMap {
                listOf(it.name, "${it.latitude}", "${it.longitude}")
              }
      )

  private companion object {
    const val PENDING_KEY = "createTripPendingIntent"
    const val SAVED_SIZE = 9

    fun List<String>.toNewTrip(): NewTrip {
      fun location(at: Int) = Location(this[at], this[at + 1].toDouble(), this[at + 2].toDouble())
      return NewTrip(location(3), Instant.parse(this[2]), location(6))
    }
  }
}
