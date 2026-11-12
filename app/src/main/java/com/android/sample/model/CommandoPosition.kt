package com.android.sample.model

// AI assistance: OpenAI Codex.
import java.time.Duration
import java.time.Instant

/** Cross-device fix; unlike DeviceLocation, its capture time is a wall-clock instant. */
data class CommandoPosition(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Double,
    val capturedAt: Instant,
) {
  /** A fix is live for strictly less than two minutes; future and invalid fixes fail closed. */
  fun isFresh(now: Instant): Boolean =
      isValidCoordinate(latitude, longitude) &&
          accuracyMeters.isFinite() &&
          accuracyMeters in 0.0..MAX_ACCURACY_METERS &&
          !capturedAt.isAfter(now) &&
          Duration.between(capturedAt, now) < MAX_AGE

  companion object {
    /** Mirrors the position write validation in firestore.rules. */
    val MAX_AGE: Duration = Duration.ofMinutes(2)
    /** Reject meaningless accuracy estimates above 100 km; mirrored by firestore.rules. */
    const val MAX_ACCURACY_METERS = 100_000.0
  }
}
