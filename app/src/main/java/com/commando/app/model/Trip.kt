package com.commando.app.model

// AI assistance: Claude Code (#429).
import java.time.Instant
import java.time.temporal.ChronoUnit

data class Trip(
    val id: String,
    val ownerId: String,
    val store: Location,
    val scheduledAt: Instant,
    val handoffLocation: Location,
    val status: TripStatus,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
  /**
   * True when this stored trip is the publication of [intent]. Firestore keeps microseconds, so the
   * departure is compared at that precision by every repository.
   */
  fun isPublicationOf(intent: NewTrip): Boolean =
      store == intent.store &&
          handoffLocation == intent.handoffLocation &&
          scheduledAt.truncatedTo(ChronoUnit.MICROS) ==
              intent.scheduledAt.truncatedTo(ChronoUnit.MICROS)
}

data class NewTrip(
    val store: Location,
    val scheduledAt: Instant,
    val handoffLocation: Location,
)

data class Location(val name: String, val latitude: Double, val longitude: Double)

enum class TripStatus {
  PUBLISHED,
  IN_PROGRESS,
  COMPLETED,
  CANCELLED,
}
