package com.android.sample.ui.trips

// AI assistance: Claude Code.
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.android.sample.data.repository.TripError
import com.android.sample.data.repository.TripRepository
import com.android.sample.data.repository.TripResult
import com.android.sample.model.Location
import com.android.sample.model.NewTrip
import com.android.sample.model.Trip
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Trip creation form. Fields are validated on every edit and again when publishing, since the
 * chosen time may have passed in between.
 */
class CreateTripViewModel(
    private val repository: TripRepository,
    private val clock: Clock = Clock.systemDefaultZone(),
) : ViewModel() {
  private enum class Field {
    STORE,
    DATE,
    TIME,
    HANDOFF_LOCATION,
  }

  private val touchedFields = mutableSetOf<Field>()
  private val mutableState = MutableStateFlow(CreateTripUiState())
  val uiState: StateFlow<CreateTripUiState> = mutableState.asStateFlow()

  fun setStore(store: Location) = edit(Field.STORE) { copy(store = store) }

  fun setDate(date: LocalDate) = edit(Field.DATE) { copy(date = date) }

  fun setTime(time: LocalTime) = edit(Field.TIME) { copy(time = time) }

  fun setHandoffLocation(location: Location) =
      edit(Field.HANDOFF_LOCATION) { copy(handoffLocation = location) }

  fun publish() {
    val state = uiState.value
    if (state.isPublishing || state.publishedTrip != null) return
    touchedFields += Field.entries
    val checked = validate(state.copy(publishError = null), clock.instant())
    val newTrip = checked.toNewTrip()
    if (newTrip == null) {
      mutableState.value = checked
      return
    }
    // Set before launching so two taps in the same frame cannot publish twice.
    mutableState.value = checked.copy(isPublishing = true, canPublish = false)
    viewModelScope.launch {
      val result =
          try {
            repository.publishTrip(newTrip)
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
    val state = uiState.value.copy(isPublishing = false)
    mutableState.value =
        when (result) {
          is TripResult.Success -> state.copy(publishedTrip = result.data, canPublish = false)
          is TripResult.Error -> validate(state.copy(publishError = result.error), clock.instant())
        }
  }

  private fun edit(field: Field, change: CreateTripUiState.() -> CreateTripUiState) {
    val state = uiState.value
    if (state.isPublishing || state.publishedTrip != null) return
    touchedFields += field
    mutableState.value = validate(state.change().copy(publishError = null), clock.instant())
  }

  private fun validate(state: CreateTripUiState, now: Instant): CreateTripUiState {
    val errors =
        mapOf(
            Field.STORE to storeError(state),
            Field.DATE to dateError(state, now),
            Field.TIME to timeError(state, now),
            Field.HANDOFF_LOCATION to handoffLocationError(state),
        )
    fun shown(field: Field) = errors[field]?.takeIf { field in touchedFields }
    return state.copy(
        storeError = shown(Field.STORE),
        dateError = shown(Field.DATE),
        timeError = shown(Field.TIME),
        handoffLocationError = shown(Field.HANDOFF_LOCATION),
        canPublish =
            errors.values.all { it == null } && !state.isPublishing && state.publishedTrip == null,
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
}
