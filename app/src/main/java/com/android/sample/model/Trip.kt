package com.android.sample.model

import java.time.Instant

data class Trip(
    val id: String,
    val ownerId: String,
    val store: Location,
    val scheduledAt: Instant,
    val handoffLocation: Location,
    val status: TripStatus,
    val createdAt: Instant,
    val updatedAt: Instant,
)

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
