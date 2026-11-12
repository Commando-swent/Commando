package com.android.sample.data.repository

// AI assistance: OpenAI Codex.
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import io.mockk.every
import io.mockk.mockk
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

/** Historical, missing and corrupt snapshots must not manufacture coordinates or capture times. */
class CommandoPositionDecodingTest {
  private val time = Instant.parse("2026-11-09T12:00:00Z")
  private val valid =
      mapOf(
          "latitude" to 46.52,
          "longitude" to 6.56,
          "accuracyMeters" to 30L,
          "capturedAt" to Timestamp(time),
      )

  @Test
  fun absentOrMalformedFieldsProduceNoPosition() {
    val snapshot = mockk<DocumentSnapshot>()
    every { snapshot.data } returns null
    assertNull(snapshot.toCommandoPosition())
    for (field in valid.keys) {
      for (data in listOf(valid - field, valid + (field to "invalid"))) {
        every { snapshot.data } returns data.toMutableMap()
        assertNull(snapshot.toCommandoPosition())
      }
    }
  }

  @Test
  fun numericCoordinatesAndAccuracyRetainTheCaptureTime() {
    val snapshot = mockk<DocumentSnapshot>()
    every { snapshot.data } returns valid.toMutableMap()
    val result = checkNotNull(snapshot.toCommandoPosition())
    assertEquals(46.52, result.latitude, 0.0)
    assertEquals(6.56, result.longitude, 0.0)
    assertEquals(30.0, result.accuracyMeters, 0.0)
    assertEquals(time, result.capturedAt)
  }
}
