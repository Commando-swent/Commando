package com.commando.app.ui.profile

// AI assistance: OpenAI Codex.
import androidx.annotation.MainThread
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.commando.app.data.repository.PublicProfileRepository
import com.commando.app.ui.session.SessionGuard
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Root-scoped completion tracking for explicitly submitted public-name writes. No editor state is
 * retained. The UID stream must describe the same sessions as the app's authenticated graph.
 */
class PublicNameWritesViewModel(
    currentUserId: () -> String?,
    sessionChanges: Flow<String?>,
) : ViewModel() {
  private val pending = mutableMapOf<String, Deferred<Unit>>()
  private var session = SessionGuard(currentUserId)

  init {
    viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
      sessionChanges.collect { uid ->
        if (session.hasEnded(uid)) session = SessionGuard(currentUserId)
      }
    }
  }

  /**
   * Registers before starting, serializes writes for [ownerId], and checks the original session
   * before submitting to the session-bound [profiles]. Cancelling an editor's await cannot cancel
   * this root-owned write. Repository failures and cancellation remain observable through the
   * result.
   */
  @MainThread
  fun submit(
      ownerId: String,
      profiles: Lazy<PublicProfileRepository>,
      name: String?,
  ): Deferred<Unit> {
    val originalSession = session
    check(
        ownerId.isNotBlank() &&
            originalSession.initialUserId == ownerId &&
            !originalSession.hasEnded()
    ) {
      "Public profile session has ended"
    }
    val write =
        synchronized(pending) {
          val previous = pending[ownerId]
          val next =
              viewModelScope.async(start = CoroutineStart.LAZY) {
                previous?.join()
                ensureActive()
                check(!originalSession.hasEnded()) { "Public profile session has ended" }
                if (name == null) profiles.value.removeDisplayName()
                else profiles.value.publishDisplayName(name)
              }
          pending[ownerId] = next
          next.invokeOnCompletion { forget(ownerId, next) }
          next
        }
    write.start()
    return write
  }

  /**
   * Returns completion of all outstanding writes for [ownerId], including rejected writes, before
   * an editor reads committed server data. Cancelling the caller's await never cancels the writes.
   */
  fun pendingCompletion(ownerId: String): Deferred<Unit> = viewModelScope.async {
    while (true) {
      val write = synchronized(pending) { pending[ownerId] } ?: break
      write.join()
      forget(ownerId, write)
    }
  }

  private fun forget(ownerId: String, write: Deferred<Unit>): Unit {
    synchronized(pending) {
      if (pending[ownerId] === write) pending.remove(ownerId, write)
      Unit
    }
  }
}
