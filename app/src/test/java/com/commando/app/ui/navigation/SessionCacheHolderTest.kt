package com.commando.app.ui.navigation

// AI assistance: Claude Code (#394, sub-issue #428).
import androidx.lifecycle.ViewModelStore
import com.android.sample.MainDispatcherRule
import com.commando.app.data.cache.SessionCache
import com.commando.app.data.repository.FakeOrderRepository
import com.commando.app.model.authentication.AuthUser
import com.commando.app.model.authentication.FakeAuthRepository
import com.commando.app.ui.orders.SessionOrderViewModel
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionCacheHolderTest {
  // A standard dispatcher: the holder's collector only runs when the test lets it.
  @get:Rule val main = MainDispatcherRule()
  private val store = ViewModelStore()
  private val auth = FakeAuthRepository(AuthUser("alice"))
  private val key = SessionCache.key<String>("order/o1")
  private val confirmed = Instant.parse("2030-01-01T12:00:00Z")

  @After fun tearDown() = store.clear()

  private fun TestScope.holder(): SessionCacheHolder {
    val session = SessionOrderViewModel("alice", auth) { FakeOrderRepository(it) }
    store.put("orders", session)
    val holder = SessionCacheHolder(SessionCache(session::currentUserId), session.sessionChanges)
    store.put("cache", holder)
    advanceUntilIdle() // Starts the collector.
    assertTrue(holder.cache.put(key, "milk", confirmed))
    return holder
  }

  private suspend fun signIn(uid: String) {
    auth.signInWithEmailResult = Result.success(AuthUser(uid))
    auth.signInWithEmail("", "")
  }

  @Test
  fun observedAccountSwitchEmptiesTheCacheForGood() =
      runTest(main.dispatcher) {
        val cache = holder().cache
        signIn("bob")
        advanceUntilIdle() // The collector sees Bob before anything reads the cache.
        assertTrue(cache.isEmpty())
        signIn("alice")
        advanceUntilIdle()
        assertNull(cache.get(key))
        assertFalse(cache.put(key, "late", confirmed))
      }

  @Test
  fun signOutEmptiesTheCache() =
      runTest(main.dispatcher) {
        val cache = holder().cache
        auth.signOut()
        advanceUntilIdle()
        assertTrue(cache.isEmpty())
      }

  @Test
  fun signOutAndQuickSignInBackNeverReviveTheCache() =
      runTest(main.dispatcher) {
        val cache = holder().cache
        assertTrue(signOutClosing(cache) { auth.signOut().isSuccess })
        signIn("alice") // No advance: the collector never sees the sign-out.
        advanceUntilIdle()
        assertNull(cache.get(key))
        assertFalse(cache.put(key, "late", confirmed))
      }

  @Test
  fun failedSignOutKeepsTheCache() =
      runTest(main.dispatcher) {
        val cache = holder().cache
        assertFalse(signOutClosing(cache) { false })
        assertEquals("milk", cache.get(key)?.value)
      }

  @Test
  fun clearingTheSessionEntryClosesItsCache() =
      runTest(main.dispatcher) {
        val cache = holder().cache
        store.clear()
        assertTrue(cache.isEmpty())
        assertFalse(cache.put(key, "late", confirmed))
      }

  @Test
  fun sameAccountKeepsItsCache() =
      runTest(main.dispatcher) {
        val cache = holder().cache
        signIn("alice")
        advanceUntilIdle()
        assertEquals("milk", cache.get(key)?.value)
      }
}
