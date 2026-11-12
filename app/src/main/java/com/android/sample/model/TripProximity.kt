package com.android.sample.model

// AI assistance: OpenAI Codex.
/** Uses the same haversine calculation as delivery filtering; never implies a walking route. */
internal fun DeviceLocation.distanceTo(trip: Trip): Double? =
    trip.handoffLocation
        .takeIf { it.hasValidCoordinates() }
        ?.let { distanceMeters(Location("", latitude, longitude), it) }

/** Nearby claims require uncertainty no larger than the handoff search radius. */
internal fun DeviceLocation.canIdentifyNearbyTrips(): Boolean =
    accuracyMeters.isFinite() && accuracyMeters >= 0f && accuracyMeters <= HANDOFF_RADIUS_METERS

/** Orders only the currently eligible, filtered trips within the existing handoff radius. */
internal fun nearbyTrips(trips: List<Trip>, position: DeviceLocation): List<Trip> =
    if (!position.canIdentifyNearbyTrips()) emptyList()
    else
        trips
            .mapNotNull { trip -> position.distanceTo(trip)?.let { trip to it } }
            .filter { it.second <= HANDOFF_RADIUS_METERS }
            .sortedBy { it.second }
            .map { it.first }
