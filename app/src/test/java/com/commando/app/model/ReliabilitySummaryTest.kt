package com.commando.app.model

// AI assistance: OpenAI Codex.
import org.junit.Assert.*
import org.junit.Test

class ReliabilitySummaryTest {
  @Test
  fun percentageRequiresFiveEvaluatedCommitments() {
    for (count in 0..4) assertNull(ReliabilitySummary("owner", count, 0).percentage)
    assertEquals(80, ReliabilitySummary("owner", 4, 1).percentage)
  }

  @Test
  fun percentageRoundsToNearestWholeWithHalvesUpward() {
    for ((completed, late, expected) in
        listOf(Triple(1, 5, 17), Triple(2, 4, 33), Triple(1, 7, 13))) {
      assertEquals(expected, ReliabilitySummary("owner", completed, late).percentage)
    }
  }

  @Test
  fun maximumCountersPreserveLongSumAndScaledNumerator() {
    val summary = ReliabilitySummary("owner", Int.MAX_VALUE, Int.MAX_VALUE)
    assertEquals(4294967294L, summary.evaluatedCount)
    assertEquals(50, summary.percentage)
    assertEquals(100, summary.copy(lateCancellationCount = 0).percentage)
    assertEquals(0, summary.copy(completedCount = 0).percentage)
  }

  @Test(expected = IllegalArgumentException::class)
  fun negativeCompletedCountIsRejected() {
    ReliabilitySummary("owner", -1, 0)
  }

  @Test(expected = IllegalArgumentException::class)
  fun negativeLateCountIsRejected() {
    ReliabilitySummary("owner", 0, -1)
  }
}
