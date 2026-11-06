package com.android.sample.model

// AI assistance: Claude Code.
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Supermarket chain a requester can filter trips by; [storeId] matches [storeIdFor]. */
enum class StoreFilter(val storeId: String?) {
  ALL(null),
  MIGROS("migros"),
  COOP("coop"),
}

/** Departure window, resolved in the device time zone at search time. */
sealed interface DeparturePeriod {
  data object AnyTime : DeparturePeriod

  data object Today : DeparturePeriod

  data object Tomorrow : DeparturePeriod

  /** Whole local days from [start] to [end], both inclusive. */
  data class Custom(val start: LocalDate, val end: LocalDate) : DeparturePeriod
}

/** Order of the matching trips; a display preference, not a filter. */
enum class TripSort {
  /** Repository order, which is earliest departure first; trips are not re-sorted. */
  EARLIEST_DEPARTURE,
  /**
   * Closest handoff to the selected delivery point first. Without a selected point the repository
   * order is kept, so the UI should only offer this option once a point is selected.
   */
  DISTANCE,
}

/** The search criteria added on top of the delivery-location selection, which stays separate. */
data class TripSearchCriteria(
    val store: StoreFilter = StoreFilter.ALL,
    val period: DeparturePeriod = DeparturePeriod.AnyTime,
    val sort: TripSort = TripSort.EARLIEST_DEPARTURE,
) {
  /** True when a store or departure filter is set, i.e. there is something to clear; sort isn't. */
  val isActive: Boolean
    get() = store != StoreFilter.ALL || period != DeparturePeriod.AnyTime
}

/** Why criteria cannot match any upcoming trip, as opposed to simply matching none. */
enum class CriteriaProblem {
  /** Custom range ends before it starts. */
  INVALID_RANGE,
  /** Custom range ended at or before now. */
  EXPIRED_RANGE,
}

/** Half-open departure window: [from] inclusive, [until] exclusive. */
internal data class DepartureWindow(val from: Instant, val until: Instant) {
  operator fun contains(time: Instant) = time >= from && time < until
}

/** The departure window of this period at [now], or null for [DeparturePeriod.AnyTime]. */
internal fun DeparturePeriod.window(now: Instant, zone: ZoneId): DepartureWindow? {
  val today = now.atZone(zone).toLocalDate()
  val (first, last) =
      when (this) {
        DeparturePeriod.AnyTime -> return null
        DeparturePeriod.Today -> today to today
        DeparturePeriod.Tomorrow -> today.plusDays(1) to today.plusDays(1)
        is DeparturePeriod.Custom -> start to end
      }
  // atStartOfDay(zone) handles DST gaps; the end bound is the start of the day after [last].
  return DepartureWindow(
      first.atStartOfDay(zone).toInstant(),
      last.plusDays(1).atStartOfDay(zone).toInstant(),
  )
}

/**
 * The problem with [criteria] at [now], or null when they are valid. Callers check this before
 * [searchTrips], which returns no trips for an invalid or expired range.
 */
fun criteriaProblem(criteria: TripSearchCriteria, now: Instant, zone: ZoneId): CriteriaProblem? {
  val period = criteria.period as? DeparturePeriod.Custom ?: return null
  return when {
    period.end < period.start -> CriteriaProblem.INVALID_RANGE
    period.end.plusDays(1).atStartOfDay(zone).toInstant() <= now -> CriteriaProblem.EXPIRED_RANGE
    else -> null
  }
}

/**
 * Applies the shared delivery-location rule ([filterTripsServing]) and then [criteria] to [trips].
 * It is intended to be shared by the list and the map, so identical inputs yield identical trip IDs
 * in the same order. The repository owns ordering (earliest departure first), so only
 * [TripSort.DISTANCE] with a [point] re-orders; distance ties fall back to departure, then ID.
 * Without store or departure criteria the location-filtered list is returned as is.
 *
 * Callers must check [criteriaProblem] first: an invalid or expired range yields an empty list
 * here, which must be shown as a validation state, not as a regular no-match result.
 */
fun searchTrips(
    trips: List<Trip>,
    point: Location?,
    criteria: TripSearchCriteria,
    now: Instant,
    zone: ZoneId,
): List<Trip> {
  val window = criteria.period.window(now, zone)
  val located = filterTripsServing(trips, point)
  val matches =
      if (!criteria.isActive) located
      else
          located.filter { trip ->
            (criteria.store.storeId == null ||
                storeIdFor(trip.store.name) == criteria.store.storeId) &&
                (window == null || trip.scheduledAt in window)
          }
  // An invalid point already emptied the list in filterTripsServing.
  if (criteria.sort != TripSort.DISTANCE || point == null) return matches
  return matches.sortedWith(
      compareBy<Trip>({ distanceMeters(point, it.handoffLocation) }, { it.scheduledAt }, { it.id })
  )
}
