package com.android.sample.ui.trips

// AI assistance: Claude Code.
import com.commando.app.model.Location
import com.commando.app.model.Trip
import com.commando.app.model.TripStatus
import java.time.Instant

/** Published trip fixture shared by the JVM and device trip discovery tests. */
fun discoveryTrip(
    id: String,
    store: Location,
    handoff: Location,
    scheduledAt: Instant,
    ownerId: String = "owner",
    createdAt: Instant = scheduledAt,
) =
    Trip(
        id = id,
        ownerId = ownerId,
        store = store,
        scheduledAt = scheduledAt,
        handoffLocation = handoff,
        status = TripStatus.PUBLISHED,
        createdAt = createdAt,
        updatedAt = createdAt,
    )
