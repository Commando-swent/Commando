package com.android.sample.model

// AI assistance: OpenAI Codex.
/** Uses the same haversine calculation as delivery filtering; never implies a walking route. */
internal fun DeviceLocation.distanceTo(trip: Trip): Double? =
    trip.handoffLocation
        .takeIf { it.hasValidCoordinates() }
        ?.let { distanceMeters(Location("", latitude, longitude), it) }

/** Orders only the currently eligible, filtered trips within the existing handoff radius. */
internal fun nearbyTrips(trips: List<Trip>, position: DeviceLocation): List<Trip> =
    trips
        .mapNotNull { trip -> position.distanceTo(trip)?.let { trip to it } }
        .filter { it.second <= HANDOFF_RADIUS_METERS }
        .sortedBy { it.second }
        .map { it.first }
