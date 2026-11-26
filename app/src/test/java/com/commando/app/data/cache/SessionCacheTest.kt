package com.commando.app.data.cache

// AI assistance: Claude Code (#394, sub-issue #428).
import java.time.Instant
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Test

class SessionCacheTest {
  private val orders = SessionCache.key<List<String>>("orders")
  private val confirmed = Instant.parse("2030-01-01T12:00:00Z")
  private var uid: String? = "alice"

  @Test
  fun storedValueIsReadBackWithItsConfirmationTime() {
    val cache = SessionCache { uid }
    assertTrue(cache.put(orders, listOf("o1"), confirmed))
    assertEquals(Confirmed(listOf("o1"), confirmed), cache.get(orders))
  }

  @Test
  fun removingAnEntryMakesItUnavailable() {
    val cache = SessionCache { uid }
    cache.put(orders, listOf("o1"), confirmed)
    cache.remove(orders)
    assertNull(cache.get(orders))
  }

  @Test
  fun keysWithTheSameNameAndDifferentTypesNeverShareAnEntry() {
    val cache = SessionCache { uid }
    val count = SessionCache.key<Int>("orders")
    cache.put(orders, listOf("o1"), confirmed)
    cache.put(count, 1, confirmed)
    assertEquals(listOf("o1"), cache.get(orders)?.value)
    assertEquals(1, cache.get(count)?.value)
    assertEquals(orders, SessionCache.key<List<String>>("orders"))
  }

  @Test
  fun matchingRemovalLeavesOtherTypesNamespacesAndValuesIntact() {
    val cache = SessionCache { uid }
    val matching = SessionCache.key<String>("details/1")
    val otherValue = SessionCache.key<String>("details/2")
    val otherType = SessionCache.key<Int>("details/1")
    val otherNamespace = SessionCache.key<String>("other/1")
    cache.put(matching, "stale")
    cache.put(otherValue, "fresh")
    cache.put(otherType, 1)
    cache.put(otherNamespace, "stale")

    cache.removeMatching<String>("details/") { it == "stale" }

    assertNull(cache.get(matching))
    assertEquals("fresh", cache.get(otherValue)?.value)
    assertEquals(1, cache.get(otherType)?.value)
    assertEquals("stale", cache.get(otherNamespace)?.value)
  }

  @Test
  fun matchingRemovalNeverReadsValuesAfterAccountChange() {
    val cache = SessionCache { uid }
    val details = SessionCache.key<String>("details/1")
    cache.put(details, "private")
    uid = "bob"

    cache.removeMatching<String>("details/") { error("Must not read previous account data") }

    assertTrue(cache.isEmpty())
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
    assertNull(SessionCache { uid }.get(orders))
    // Alice signing back in does not revive a cache that saw another account.
    uid = "alice"
    assertNull(alice.get(orders))
    alice.remove(orders)
    assertTrue(alice.isEmpty())
  }

  @Test
  fun signedOutOrClosedSessionReadsAndWritesNothing() {
    uid = null
    assertFalse(SessionCache { uid }.put(orders, listOf("o1")))
    uid = "alice"
    val cache = SessionCache { uid }
    cache.put(orders, listOf("o1"), confirmed)
    cache.close()
    assertNull(cache.get(orders))
    assertFalse(cache.put(orders, listOf("o2")))
  }

  @Test
  fun writesRacingCloseNeverRemainStored() {
    repeat(200) {
      val cache = SessionCache { uid }
      val start = CountDownLatch(1)
      val writers =
          List(4) {
            thread {
              start.await()
              while (cache.put(orders, listOf("o"))) Unit
            }
          }
      start.countDown()
      cache.close()
      writers.forEach { it.join() }
      assertTrue("A write landed after close", cache.isEmpty())
    }
  }
}
