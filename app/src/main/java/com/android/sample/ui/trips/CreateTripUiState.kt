package com.android.sample.ui.trips

// AI assistance: Claude Code.
import com.android.sample.data.repository.TripError
import com.android.sample.model.Trip
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
  MAX_ORDERS_REQUIRED,
  MAX_ORDERS_NOT_POSITIVE_INTEGER,
}

/**
 * State of the trip creation form.
 *
 * Field errors are only reported once the user has edited that field or tried to publish, so an
 * untouched form shows no errors. [maxOrders] is kept as typed so invalid input stays visible.
 */
data class CreateTripUiState(
    val store: String = "",
    val date: LocalDate? = null,
    val time: LocalTime? = null,
    val handoffLocation: String = "",
    val maxOrders: String = "",
    val storeError: CreateTripError? = null,
    val dateError: CreateTripError? = null,
    val timeError: CreateTripError? = null,
    val handoffLocationError: CreateTripError? = null,
    val maxOrdersError: CreateTripError? = null,
    /** True when every field is valid and nothing is being or has been published. */
    val canPublish: Boolean = false,
    val isPublishing: Boolean = false,
    /** Set when the last publication failed; the inputs are kept so the user can retry. */
    val publishError: TripError? = null,
    /** Set once the trip has been published; the form is then locked. */
    val publishedTrip: Trip? = null,
)
