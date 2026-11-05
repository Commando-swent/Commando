package com.android.sample.model

// AI assistance: OpenAI Codex.
/** A transient device fix. Its monotonic timestamp must never be persisted across reboots. */
data class DeviceLocation(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val elapsedRealtimeMillis: Long,
) {
  /** Rejects invalid, future and older-than-two-minute fixes before displaying a position. */
  fun isUsable(nowElapsedMillis: Long): Boolean =
      latitude.isFinite() &&
          latitude in -90.0..90.0 &&
          longitude.isFinite() &&
          longitude in -180.0..180.0 &&
          accuracyMeters.isFinite() &&
          accuracyMeters >= 0f &&
          elapsedRealtimeMillis >= 0 &&
          nowElapsedMillis >= elapsedRealtimeMillis &&
          nowElapsedMillis - elapsedRealtimeMillis <= MAX_AGE_MILLIS

  companion object {
    const val MAX_AGE_MILLIS = 120_000L
  }
}
