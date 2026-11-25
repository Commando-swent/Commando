package com.commando.app.data.cache

// AI assistance: Claude Code (#394, sub-issue #428).
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** A value the server confirmed, and when it did. */
data class Confirmed<T>(val value: T, val confirmedAt: Instant)

/**
 * In-memory cache of server-confirmed data for one signed-in account (see docs/session-cache.md).
 *
 * It is bound to the session identity: once [currentUserId] stops returning the account it was
 * created for, even briefly, or the session is [close]d, it is emptied for good: reads return null
 * and writes are dropped. A request started before sign-out therefore cannot fill the next
 * account's cache, which is a new instance. Data lives only as long as the app process.
 */
class SessionCache(private val currentUserId: () -> String?) {
  private val owner = currentUserId()
  private val entries = ConcurrentHashMap<String, Confirmed<*>>()
  @Volatile private var closed = false

  /** Typed key, so each value is read back with the type it was stored with. */
  class Key<T>(val name: String)

  /** Stores [value] as confirmed at [confirmedAt]; returns false if the session has ended. */
  fun <T> put(key: Key<T>, value: T, confirmedAt: Instant = Instant.now()): Boolean {
    if (!isActive()) return false
    entries[key.name] = Confirmed(value, confirmedAt)
    // If the session ended during the write, this check closes the cache and drops the value.
    return isActive()
  }

  /** The last confirmed value for [key], or null if none or the session has ended. */
  @Suppress("UNCHECKED_CAST")
  fun <T> get(key: Key<T>): Confirmed<T>? =
      if (isActive()) entries[key.name] as Confirmed<T>? else null

  fun remove(key: Key<*>) {
    entries.remove(key.name)
  }

  /** Ends the session: drops every entry and rejects later reads and writes. */
  fun close() {
    closed = true
    entries.clear()
  }

  /** Once the identity has changed, the cache stays closed even if the same account returns. */
  private fun isActive(): Boolean {
    if (!closed && (owner == null || currentUserId() != owner)) close()
    return !closed
  }
}
