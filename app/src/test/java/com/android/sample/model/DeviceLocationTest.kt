package com.android.sample.model

// AI assistance: OpenAI Codex.
import org.junit.Assert.*
import org.junit.Test

class DeviceLocationTest {
  private val fix = DeviceLocation(46.52, 6.56, 100f, 200_000)

  @Test
  fun acceptsFreshFixAndAgeBoundary() {
    assertTrue(fix.isUsable(200_000))
    assertTrue(fix.isUsable(200_000 + DeviceLocation.MAX_AGE_MILLIS))
    assertFalse(fix.isUsable(200_001 + DeviceLocation.MAX_AGE_MILLIS))
  }

  @Test
  fun rejectsMissingFutureAndNegativeMonotonicTimestamps() {
    assertFalse(fix.copy(elapsedRealtimeMillis = -1).isUsable(200_000))
    assertFalse(fix.isUsable(199_999))
  }

  @Test
  fun rejectsInvalidCoordinatesAndAccuracy() {
    listOf(Double.NaN, Double.POSITIVE_INFINITY, -90.1, 90.1).forEach {
      assertFalse(fix.copy(latitude = it).isUsable(200_000))
    }
    listOf(Double.NaN, Double.NEGATIVE_INFINITY, -180.1, 180.1).forEach {
      assertFalse(fix.copy(longitude = it).isUsable(200_000))
    }
    listOf(Float.NaN, Float.POSITIVE_INFINITY, -1f).forEach {
      assertFalse(fix.copy(accuracyMeters = it).isUsable(200_000))
    }
  }

  @Test
  fun acceptsCoordinateBoundariesAndZeroAccuracy() {
    assertTrue(DeviceLocation(-90.0, -180.0, 0f, 0).isUsable(0))
    assertTrue(DeviceLocation(90.0, 180.0, 0f, 0).isUsable(0))
  }
}
