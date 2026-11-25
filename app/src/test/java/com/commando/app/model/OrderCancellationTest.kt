package com.commando.app.model

// AI assistance: Claude Code; OpenAI Codex split deterministic #387 boundary regressions.
import java.time.Instant
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OrderCancellationTest {
  private val acceptedAt = Instant.parse("2026-11-20T12:00:00Z")

  @Test
  fun cancellationStrictlyWithinTheGracePeriodIsPenaltyFree() {
    assertTrue(isPenaltyFreeCancellation(acceptedAt, acceptedAt))
    val lastMoment = acceptedAt.plus(COMMANDO_CANCELLATION_GRACE).minusMillis(1)
    assertTrue(isPenaltyFreeCancellation(acceptedAt, lastMoment))
  }

  @Test
  fun cancellationAtExactlyTenMinutesIsPenalized() {
    assertFalse(isPenaltyFreeCancellation(acceptedAt, acceptedAt.plusSeconds(600)))
  }

  @Test
  fun cancellationAfterTenMinutesIsPenalized() {
    assertFalse(isPenaltyFreeCancellation(acceptedAt, acceptedAt.plusSeconds(660)))
  }

  @Test
  fun cancellationBeforeAcceptanceIsRejected() {
    assertThrows(IllegalArgumentException::class.java) {
      isPenaltyFreeCancellation(acceptedAt, acceptedAt.minusMillis(1))
    }
  }
}
