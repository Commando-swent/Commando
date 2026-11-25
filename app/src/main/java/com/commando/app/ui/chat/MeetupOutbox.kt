package com.commando.app.ui.chat

// AI assistance: OpenAI Codex.
import androidx.lifecycle.SavedStateHandle
import com.commando.app.model.MeetupProposal
import com.commando.app.model.MeetupResponse
import java.time.Instant

/** One uncertain creation or reply; a reply reuses the persisted proposal's identity. */
internal data class PendingMeetup(
    val id: String,
    val proposal: MeetupProposal? = null,
    val response: MeetupResponse? = null,
)

/** Saves only Android-supported primitive values; no Firebase or UI objects enter saved state. */
internal class MeetupOutbox(private val saved: SavedStateHandle) {
  var pending: PendingMeetup?
    get() {
      val id = saved.get<String>(ID) ?: return null
      val response = saved.get<String>(RESPONSE)
      if (response != null) return PendingMeetup(id, response = MeetupResponse.valueOf(response))
      val place = saved.get<String>(PLACE) ?: return null
      val seconds = saved.get<Long>(SECONDS)
      val at =
          if (seconds != null) {
            val nanos = saved.get<Int>(NANOS) ?: return null
            Instant.ofEpochSecond(seconds, nanos.toLong())
          } else {
            Instant.ofEpochMilli(saved.get<Long>(AT) ?: return null)
          }
      return PendingMeetup(id, MeetupProposal(place, at))
    }
    set(action) {
      if (action == null)
          for (key in listOf(ID, PLACE, AT, SECONDS, NANOS, RESPONSE)) saved.remove<Any>(key)
      else {
        saved[ID] = action.id
        saved[PLACE] = action.proposal?.location
        saved.remove<Long>(AT)
        saved[SECONDS] = action.proposal?.at?.epochSecond
        saved[NANOS] = action.proposal?.at?.nano
        saved[RESPONSE] = action.response?.name
      }
    }

  private companion object {
    const val ID = "meetupActionId"
    const val PLACE = "meetupPlace"
    const val AT = "meetupAt"
    const val SECONDS = "meetupAtSeconds"
    const val NANOS = "meetupAtNanos"
    const val RESPONSE = "meetupResponse"
  }
}
