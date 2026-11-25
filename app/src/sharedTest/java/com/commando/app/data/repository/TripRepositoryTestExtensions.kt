package com.commando.app.data.repository

// AI assistance: Claude Code (#429).
import com.commando.app.model.NewTrip
import com.commando.app.model.Trip
import java.util.UUID

/** Test shortcut: publishes [newTrip] as a new intent, under a fresh trip ID. */
suspend fun TripRepository.publishTrip(newTrip: NewTrip): TripResult<Trip> =
    publishTrip(UUID.randomUUID().toString(), newTrip)
