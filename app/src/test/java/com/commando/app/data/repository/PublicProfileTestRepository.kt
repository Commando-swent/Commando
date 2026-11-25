package com.commando.app.data.repository

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

// AI assistance: OpenAI Codex.
/** Controllable public-name boundary shared by integration tests; no Firebase access. */
class PublicProfileTestRepository : PublicProfileRepository {
  val reads = mutableListOf<String>()
  val writes = mutableListOf<String?>()
  var name: String? = null
  var read: suspend (String) -> String? = { name }
  var write: suspend (String?) -> Unit = { name = it }

  /** Holds a reply even after reader cancellation, to exercise stale-response protection. */
  fun deferRead(): CompletableDeferred<String?> =
      CompletableDeferred<String?>().also { reply ->
        read = { withContext(NonCancellable) { reply.await() } }
      }

  /** A name becomes persisted only when the controlled write is acknowledged. */
  fun deferWrite(): CompletableDeferred<Unit> =
      CompletableDeferred<Unit>().also { reply ->
        write = { value ->
          reply.await()
          name = value
        }
      }

  override suspend fun getDisplayName(ownerId: String): String? {
    reads += ownerId
    return read(ownerId)
  }

  override suspend fun publishDisplayName(displayName: String) = submit(displayName)

  override suspend fun removeDisplayName() = submit(null)

  private suspend fun submit(value: String?) {
    writes += value
    write(value)
  }
}
