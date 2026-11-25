package com.commando.app.ui.chat

// AI assistance: OpenAI Codex.
import androidx.lifecycle.SavedStateHandle
import com.commando.app.model.*
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class MeetupOutboxTest {
  @Test
  fun creationAndReplyRoundTripAndClearAcrossRestoration() {
    val saved = SavedStateHandle(mapOf("meetupActionId" to "incomplete"))
    val outbox = MeetupOutbox(saved)
    assertNull(outbox.pending)
    saved["meetupPlace"] = "Library"
    assertNull(outbox.pending)
    for (action in
        listOf(
            PendingMeetup("create", MeetupProposal("Library", Instant.ofEpochMilli(1000))),
            PendingMeetup("reply", response = MeetupResponse.ACCEPTED),
        )) {
      outbox.pending = action
      assertEquals(action, MeetupOutbox(saved).pending)
      outbox.pending = null
      assertNull(MeetupOutbox(saved).pending)
      assertTrue(saved.keys().isEmpty())
    }
  }
}
