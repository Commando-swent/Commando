package com.commando.app.data.cache

// AI assistance: Claude Code (#394, sub-issue #428).
import java.time.Instant
import kotlin.reflect.KType
import kotlin.reflect.typeOf

/** A value the server confirmed, and when it did. */
data class Confirmed<T>(val value: T, val confirmedAt: Instant)

/**
 * In-memory cache of server-confirmed data for one signed-in account (see docs/session-cache.md).
 *
 * It is bound to the session identity: once [currentUserId] stops returning the account it was
 * created for, or the session is [close]d, it is emptied for good: reads return null, writes are
 * dropped and removals do nothing. A request started before sign-out therefore cannot fill the next
 * account's cache, which is a new instance. Every operation runs under one lock, so a write can
 * never land after [close]. Data lives only as long as the app process.
 */
class SessionCache(private val currentUserId: () -> String?) {
  private val lock = Any()
  private val owner = currentUserId()
  private val entries = mutableMapOf<Key<*>, Confirmed<*>>()
  private var closed = false

  /**
   * Identifies one cached value by name and type, so two keys with the same name but different
   * types never share an entry. Only [key] creates keys, so the type always matches [T]; the
   * constructor and `copy` are internal only because the inline factory needs them.
   */
  @ConsistentCopyVisibility
  data class Key<T> @PublishedApi internal constructor(val name: String, val type: KType)

  /** Stores [value] as confirmed at [confirmedAt]; returns false if the session has ended. */
  fun <T> put(key: Key<T>, value: T, confirmedAt: Instant = Instant.now()): Boolean =
      synchronized(lock) {
        if (!isActive()) return false
        entries[key] = Confirmed(value, confirmedAt)
        true
      }

  /** The last confirmed value for [key], or null if none or the session has ended. */
  @Suppress("UNCHECKED_CAST") // Safe: a key's type is part of its identity.
  fun <T> get(key: Key<T>): Confirmed<T>? =
      synchronized(lock) { if (isActive()) entries[key] as Confirmed<T>? else null }

  /**
   * Invalidates [key], for example when the server reports the document deleted or no longer
   * readable. After the session has ended the cache is already empty, so this does nothing.
   */
  fun remove(key: Key<*>) {
    synchronized(lock) { if (isActive()) entries.remove(key) }
  }

  /** Invalidates entries in a typed namespace whose cached values match [predicate]. */
  internal inline fun <reified T> removeMatching(
      prefix: String,
      noinline predicate: (T) -> Boolean,
  ) = removeMatching(prefix, typeOf<T>(), predicate)

  @Suppress("UNCHECKED_CAST") // The key type is checked before casting its value.
  private fun <T> removeMatching(prefix: String, type: KType, predicate: (T) -> Boolean) {
    synchronized(lock) {
      if (!isActive()) return
      entries.entries.removeAll { (key, entry) ->
        key.type == type && key.name.startsWith(prefix) && predicate(entry.value as T)
      }
    }
  }

  /** Ends the session: drops every entry and rejects later reads and writes. */
  fun close() {
    synchronized(lock) {
      closed = true
      entries.clear()
    }
  }

  /** For tests: whether nothing is stored, including values hidden by a closed session. */
  internal fun isEmpty(): Boolean = synchronized(lock) { entries.isEmpty() }

  /** Once the identity has changed, the cache stays closed even if the same account returns. */
  private fun isActive(): Boolean {
    if (!closed && (owner == null || currentUserId() != owner)) close()
    return !closed
  }

  companion object {
    /** A key for values of type [T], for example `SessionCache.key<Order>("order/$id")`. */
    inline fun <reified T> key(name: String): Key<T> = Key(name, typeOf<T>())
  }
}
