package com.commando.app.model

// AI assistance: Claude Code (trip cancellation: #449).

import org.junit.Assert.assertEquals
import org.junit.Test

class TripLifecycleTest {
  @Test
  fun transitions_areExactlyCloseCompleteAndCancel() {
    assertEquals(listOf("CLOSE", "COMPLETE", "CANCEL"), TripTransition.entries.map { it.name })
    assertEquals(TripStatus.PUBLISHED to TripStatus.IN_PROGRESS, TripTransition.CLOSE.pair())
    assertEquals(TripStatus.IN_PROGRESS to TripStatus.COMPLETED, TripTransition.COMPLETE.pair())
    assertEquals(TripStatus.PUBLISHED to TripStatus.CANCELLED, TripTransition.CANCEL.pair())
    val settledFirst = TripTransition.entries.filter { it.requiresSettledOrders }
    assertEquals(listOf(TripTransition.COMPLETE, TripTransition.CANCEL), settledFirst)
  }

  @Test
  fun blocksTripCompletion_isTrueOnlyForOrdersThatStillNeedAction() {
    val blocking = setOf(OrderStatus.PENDING, OrderStatus.ACCEPTED, OrderStatus.READY_FOR_HANDOFF)
    OrderStatus.entries.forEach { assertEquals("$it", it in blocking, it.blocksTripCompletion()) }
  }

  private fun TripTransition.pair() = source to target
}
