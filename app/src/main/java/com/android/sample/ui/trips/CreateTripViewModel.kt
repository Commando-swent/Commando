package com.android.sample.ui.trips

// AI assistance: Claude Code.
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.android.sample.data.repository.TripError
import com.android.sample.data.repository.TripRepository
import com.android.sample.data.repository.TripResult
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
 * chosen time may have passed in between. [toNewTrip] is the single conversion to the repository
 * model.
 */
class CreateTripViewModel(
    private val repository: TripRepository,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val toNewTrip: (TripDraft) -> NewTrip? = TripDraft::toNewTrip,
) : ViewModel() {
  private enum class Field {
    STORE,
    DATE,
    TIME,
    HANDOFF_LOCATION,
    MAX_ORDERS,
  }

  private val touchedFields = mutableSetOf<Field>()
  private val mutableState = MutableStateFlow(CreateTripUiState())
  val uiState: StateFlow<CreateTripUiState> = mutableState.asStateFlow()

  fun setStore(store: String) = edit(Field.STORE) { copy(store = store) }

  fun setDate(date: LocalDate) = edit(Field.DATE) { copy(date = date) }

  fun setTime(time: LocalTime) = edit(Field.TIME) { copy(time = time) }

  fun setHandoffLocation(location: String) =
      edit(Field.HANDOFF_LOCATION) { copy(handoffLocation = location) }

  fun setMaxOrders(maxOrders: String) = edit(Field.MAX_ORDERS) { copy(maxOrders = maxOrders) }

  fun publish() {
    val state = uiState.value
    if (state.isPublishing || state.publishedTrip != null) return
    touchedFields += Field.entries
    val now = clock.instant()
    val checked = validate(state.copy(publishError = null), now)
    val draft = checked.toDraft()
    if (draft == null) {
      mutableState.value = checked
      return
    }
    val newTrip = toNewTrip(draft)
    if (newTrip == null) {
      mutableState.value = checked.copy(publishError = TripError.InvalidData)
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
            Field.MAX_ORDERS to maxOrdersError(state),
        )
    fun shown(field: Field) = errors[field]?.takeIf { field in touchedFields }
    return state.copy(
        storeError = shown(Field.STORE),
        dateError = shown(Field.DATE),
        timeError = shown(Field.TIME),
        handoffLocationError = shown(Field.HANDOFF_LOCATION),
        maxOrdersError = shown(Field.MAX_ORDERS),
        canPublish =
            errors.values.all { it == null } && !state.isPublishing && state.publishedTrip == null,
    )
  }

  private fun storeError(state: CreateTripUiState) =
      if (state.store.isBlank()) CreateTripError.STORE_REQUIRED else null

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
      if (state.handoffLocation.isBlank()) CreateTripError.HANDOFF_LOCATION_REQUIRED else null

  private fun maxOrdersError(state: CreateTripUiState): CreateTripError? {
    val text = state.maxOrders.trim()
    return when {
      text.isEmpty() -> CreateTripError.MAX_ORDERS_REQUIRED
      parseMaxOrders(text) == null -> CreateTripError.MAX_ORDERS_NOT_POSITIVE_INTEGER
      else -> null
    }
  }

  /** Accepts only plain digits that fit in an Int and are at least 1. */
  private fun parseMaxOrders(text: String): Int? =
      text.takeIf { it.all { char -> char in '0'..'9' } }?.toIntOrNull()?.takeIf { it >= 1 }

  private fun scheduledAt(date: LocalDate, time: LocalTime): Instant =
      LocalDateTime.of(date, time).atZone(clock.zone).toInstant()

  /** [canPublish] already checked every field, including that the time is in the future. */
  private fun CreateTripUiState.toDraft(): TripDraft? {
    if (!canPublish || date == null || time == null) return null
    val maxOrders = parseMaxOrders(maxOrders.trim()) ?: return null
    return TripDraft(store.trim(), scheduledAt(date, time), handoffLocation.trim(), maxOrders)
  }
}
