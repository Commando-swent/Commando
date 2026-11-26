package com.commando.app.model

// AI assistance: Claude Code; OpenAI Codex split deterministic #387 boundary regressions.
import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OrderCancellationTest {
  private val acceptedAt = Instant.parse("2026-11-20T12:00:00Z")

  @Test
  fun cancellationGracePeriodIsTenMinutes() {
    // This policy must match cancellationGraceEnds in firestore.rules.
    assertEquals(Duration.ofMinutes(10), COMMANDO_CANCELLATION_GRACE)
  }

  @Test
  fun cancellationStrictlyWithinTheGracePeriodIsPenaltyFree() {
    assertTrue(isPenaltyFreeCancellation(acceptedAt, acceptedAt))
    val lastMoment = acceptedAt.plus(COMMANDO_CANCELLATION_GRACE).minusMillis(1)
    assertTrue(isPenaltyFreeCancellation(acceptedAt, lastMoment))
  }

  @Test
  fun cancellationAtExactlyTenMinutesIsPenalized() {
    assertFalse(isPenaltyFreeCancellation(acceptedAt, acceptedAt.plus(COMMANDO_CANCELLATION_GRACE)))
  }

  @Test
  fun cancellationAfterTenMinutesIsPenalized() {
    val afterGracePeriod = acceptedAt.plus(COMMANDO_CANCELLATION_GRACE).plusMillis(1)
    assertFalse(isPenaltyFreeCancellation(acceptedAt, afterGracePeriod))
  }

  @Test
  fun cancellationBeforeAcceptanceIsRejected() {
    assertThrows(IllegalArgumentException::class.java) {
      isPenaltyFreeCancellation(acceptedAt, acceptedAt.minusMillis(1))
    }
  }
}
