package com.commando.app.data.cache

// AI assistance: Claude Code (#394, sub-issue #428).
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class SessionCacheTest {
  private val orders = SessionCache.Key<List<String>>("orders")
  private val confirmed = Instant.parse("2030-01-01T12:00:00Z")
  private var uid: String? = "alice"

  @Test
  fun storedValueIsReadBackWithItsConfirmationTime() {
    val cache = SessionCache { uid }
    assertTrue(cache.put(orders, listOf("o1"), confirmed))
    assertEquals(Confirmed(listOf("o1"), confirmed), cache.get(orders))
    cache.remove(orders)
    assertNull(cache.get(orders))
  }

  @Test
  fun accountChangeHidesDataAndRejectsLateWrites() {
    val alice = SessionCache { uid }
    alice.put(orders, listOf("alice order"), confirmed)
    uid = "bob"
    assertNull(alice.get(orders))
    // A response to a request started by Alice arrives after the switch.
    assertFalse(alice.put(orders, listOf("late"), confirmed))
    // Bob's session has its own, empty cache.
    val bob = SessionCache { uid }
    assertNull(bob.get(orders))
    // Alice signing back in does not revive a cache that saw another account.
    uid = "alice"
    assertNull(alice.get(orders))
  }

  @Test
  fun signedOutOrClosedSessionReadsAndWritesNothing() {
    uid = null
    val signedOut = SessionCache { uid }
    assertFalse(signedOut.put(orders, listOf("o1")))
    uid = "alice"
    val cache = SessionCache { uid }
    cache.put(orders, listOf("o1"), confirmed)
    cache.close()
    assertNull(cache.get(orders))
    assertFalse(cache.put(orders, listOf("o2")))
  }
}
