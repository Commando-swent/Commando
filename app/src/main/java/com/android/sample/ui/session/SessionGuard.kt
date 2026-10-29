package com.android.sample.ui.session

// AI assistance: OpenAI Codex.
/** Remembers session changes even if the original account returns before a late response. */
internal class SessionGuard(private val currentUserId: () -> String?) {
  val initialUserId = currentUserId()
  var ended = false
    private set

  fun hasEnded(userId: String? = currentUserId()): Boolean {
    ended = ended || userId != initialUserId
    return ended
  }
}
