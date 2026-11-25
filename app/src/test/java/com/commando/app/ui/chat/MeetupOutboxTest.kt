package com.commando.app.ui.chat

// AI assistance: OpenAI Codex.
import androidx.lifecycle.SavedStateHandle
import com.commando.app.model.*
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class MeetupOutboxTest {
  @Test
  fun incompleteSnapshotDoesNotRestoreAnAction() {
    val saved = SavedStateHandle(mapOf("meetupActionId" to "incomplete"))
    assertNull(MeetupOutbox(saved).pending)
    saved["meetupPlace"] = "Library"
    assertNull(MeetupOutbox(saved).pending)
    saved["meetupAtSeconds"] = 123L
    assertNull(MeetupOutbox(saved).pending)
  }

  @Test
  fun proposalKeepsNanosecondsAcrossSavedStateRecreation() {
    val saved = SavedStateHandle()
    val action =
        PendingMeetup("create", MeetupProposal("Library", Instant.ofEpochSecond(123, 123456789)))
    MeetupOutbox(saved).pending = action
    val restored = SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })
    val outbox = MeetupOutbox(restored)
    assertEquals(action, outbox.pending)
    outbox.pending = null
    assertTrue(restored.keys().isEmpty())
  }

  @Test
  fun replyRestoresWithoutProposalAndClearsSavedFields() {
    val saved = SavedStateHandle()
    val action = PendingMeetup("reply", response = MeetupResponse.ACCEPTED)
    MeetupOutbox(saved).pending = action
    val outbox = MeetupOutbox(saved)
    assertEquals(action, outbox.pending)
    outbox.pending = null
    assertNull(outbox.pending)
    assertTrue(saved.keys().isEmpty())
  }

  @Test
  fun previousMillisecondSnapshotCanStillBeRestored() {
    val at = Instant.ofEpochMilli(1000)
    val saved =
        SavedStateHandle(
            mapOf(
                "meetupActionId" to "create",
                "meetupPlace" to "Library",
                "meetupAt" to at.toEpochMilli(),
            )
        )
    val outbox = MeetupOutbox(saved)
    assertEquals(PendingMeetup("create", MeetupProposal("Library", at)), outbox.pending)
    outbox.pending = PendingMeetup("reply", response = MeetupResponse.DECLINED)
    assertFalse(saved.contains("meetupAt"))
  }
}
