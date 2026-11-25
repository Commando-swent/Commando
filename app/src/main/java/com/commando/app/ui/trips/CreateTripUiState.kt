package com.commando.app.ui.trips

// AI assistance: Claude Code, including the uncertain publication lock (#394) and the
// offline refusal (#429).
import com.commando.app.data.repository.TripError
import com.commando.app.model.Location
import com.commando.app.model.Trip
import java.time.LocalDate
import java.time.LocalTime

/** Reasons a trip creation field is rejected. The screen maps each one to a message. */
enum class CreateTripError {
  STORE_REQUIRED,
  DATE_REQUIRED,
  DATE_IN_PAST,
  TIME_REQUIRED,
  TIME_IN_PAST,
  HANDOFF_LOCATION_REQUIRED,
}

/**
 * State of the trip creation form.
 *
 * Field errors are only reported once the user has edited that field or tried to publish, so an
 * untouched form shows no errors.
 */
data class CreateTripUiState(
    val store: Location? = null,
    val date: LocalDate? = null,
    val time: LocalTime? = null,
    val handoffLocation: Location? = null,
    val storeError: CreateTripError? = null,
    val dateError: CreateTripError? = null,
    val timeError: CreateTripError? = null,
    val handoffLocationError: CreateTripError? = null,
    /** True when every field is valid and nothing is being or has been published. */
    val canPublish: Boolean = false,
    val isPublishing: Boolean = false,
    /**
     * Set when the last publication failed; the inputs are kept so the user can retry. With
     * [hasUncertainPublication] the trip may exist, so it is not shown as a failure.
     */
    val publishError: TripError? = null,
    /**
     * True after a network or unknown failure: the trip may exist, so the form is locked and only a
     * retry of the same publication is possible. A timeout or a restored attempt is uncertain too.
     */
    val hasUncertainPublication: Boolean = false,
    /** True when the last tap was refused offline, before any write; the next tap retries. */
    val isOffline: Boolean = false,
    /** Set once the trip has been published; the form is then locked. */
    val publishedTrip: Trip? = null,
)
