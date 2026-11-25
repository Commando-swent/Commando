package com.commando.app.ui.navigation

// AI assistance: Claude Code (#394, sub-issue #428).
import androidx.lifecycle.ViewModelStore
import com.commando.app.data.cache.SessionCache
import com.commando.app.data.repository.FakeOrderRepository
import com.commando.app.model.authentication.AuthUser
import com.commando.app.model.authentication.FakeAuthRepository
import com.commando.app.ui.orders.SessionOrderViewModel
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionCacheHolderTest {
  private val store = ViewModelStore()
  private val auth = FakeAuthRepository(AuthUser("alice"))
  private val key = SessionCache.Key<String>("order/o1")
  private val confirmed = Instant.parse("2030-01-01T12:00:00Z")
  private lateinit var holder: SessionCacheHolder

  @Before
  fun setUp() {
    Dispatchers.setMain(UnconfinedTestDispatcher())
    val session = SessionOrderViewModel("alice", auth) { FakeOrderRepository(it) }
    store.put("orders", session)
    holder = SessionCacheHolder(SessionCache(session::currentUserId), session.sessionChanges)
    store.put("cache", holder)
    assertTrue(holder.cache.put(key, "milk", confirmed))
  }

  @After
  fun tearDown() {
    store.clear()
    Dispatchers.resetMain()
  }

  private suspend fun signIn(uid: String) {
    auth.signInWithEmailResult = Result.success(AuthUser(uid))
    auth.signInWithEmail("", "")
  }

  @Test
  fun accountSwitchEmptiesTheCacheEvenIfTheAccountReturnsBeforeTheNextRead() = runTest {
    signIn("bob")
    signIn("alice")
    // Nothing read the cache while Bob was signed in; it was closed by the session change itself.
    assertNull(holder.cache.get(key))
    assertFalse(holder.cache.put(key, "late", confirmed))
  }

  @Test
  fun signOutEmptiesTheCache() = runTest {
    auth.signOut()
    assertNull(holder.cache.get(key))
  }

  @Test
  fun clearingTheSessionEntryClosesItsCache() {
    val cache = holder.cache
    store.clear()
    assertNull(cache.get(key))
    assertFalse(cache.put(key, "late", confirmed))
  }

  @Test
  fun sameAccountKeepsItsCache() = runTest {
    signIn("alice")
    assertEquals("milk", holder.cache.get(key)?.value)
  }
}
