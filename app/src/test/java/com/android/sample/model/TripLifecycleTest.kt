package com.android.sample.model

// AI assistance: Claude Code.

import org.junit.Assert.assertEquals
import org.junit.Test

class TripLifecycleTest {
  @Test
  fun transitions_areExactlyCloseAndComplete() {
    assertEquals(listOf("CLOSE", "COMPLETE"), TripTransition.entries.map { it.name })
    assertEquals(TripStatus.PUBLISHED to TripStatus.IN_PROGRESS, TripTransition.CLOSE.pair())
    assertEquals(TripStatus.IN_PROGRESS to TripStatus.COMPLETED, TripTransition.COMPLETE.pair())
  }

  @Test
  fun blocksTripCompletion_isTrueOnlyForOrdersThatStillNeedAction() {
    val blocking = setOf(OrderStatus.PENDING, OrderStatus.ACCEPTED, OrderStatus.READY_FOR_HANDOFF)
    OrderStatus.entries.forEach { assertEquals("$it", it in blocking, it.blocksTripCompletion()) }
  }

  private fun TripTransition.pair() = source to target
}
