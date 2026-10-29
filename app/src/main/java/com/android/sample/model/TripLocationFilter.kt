package com.android.sample.model

// AI assistance: Claude Code; OpenAI Codex (shared feed classification).

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Straight-line radius, inclusive, within which a trip's handoff point serves a selected point. */
const val HANDOFF_RADIUS_METERS = 500.0

private const val EARTH_RADIUS_METERS = 6_371_008.8

/** Great-circle (haversine) distance in meters between two coordinates. */
internal fun distanceMeters(from: Location, to: Location): Double {
  val dLat = Math.toRadians(to.latitude - from.latitude)
  val dLon = Math.toRadians(to.longitude - from.longitude)
  val h =
      sin(dLat / 2) * sin(dLat / 2) +
          cos(Math.toRadians(from.latitude)) *
              cos(Math.toRadians(to.latitude)) *
              sin(dLon / 2) *
              sin(dLon / 2)
  return 2 * EARTH_RADIUS_METERS * asin(min(1.0, sqrt(h)))
}

/**
 * Keeps the trips whose handoff location lies within [radiusMeters] (inclusive) of [point], in
 * their original order. A `null` point returns every trip unchanged. With a selected point, trips
 * with invalid handoff coordinates are dropped, and an invalid point matches nothing.
 */
fun filterTripsServing(
    trips: List<Trip>,
    point: Location?,
    radiusMeters: Double = HANDOFF_RADIUS_METERS,
): List<Trip> {
  // ponytail: client-side filtering is fine at campus scale; move to a geo query (e.g. geohash)
  // only if trip volume grows.
  if (point == null) return trips
  if (!point.hasValidCoordinates()) return emptyList()
  return trips.filter {
    it.handoffLocation.hasValidCoordinates() &&
        distanceMeters(point, it.handoffLocation) <= radiusMeters
  }
}

/** Finite latitude in -90..90 and longitude in -180..180; the name is not checked. */
fun Location.hasValidCoordinates(): Boolean =
    latitude.isFinite() &&
        latitude in -90.0..90.0 &&
        longitude.isFinite() &&
        longitude in -180.0..180.0

/** Shared filtered-feed outcomes for the requester list and map. */
sealed interface TripFeed {
  data object Empty : TripFeed

  data class NoMatches(val location: Location) : TripFeed

  data class Matches(val trips: List<Trip>) : TripFeed
}

/**
 * Distinguishes an empty source from zero filter matches, preserving repository eligibility/order.
 */
fun classifyTrips(trips: List<Trip>, point: Location?): TripFeed {
  if (trips.isEmpty()) return TripFeed.Empty
  val matches = filterTripsServing(trips, point)
  return if (point != null && matches.isEmpty()) TripFeed.NoMatches(point)
  else TripFeed.Matches(matches)
}
