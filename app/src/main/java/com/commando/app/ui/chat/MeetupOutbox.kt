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
      val at = saved.get<Long>(AT) ?: return null
      return PendingMeetup(id, MeetupProposal(place, Instant.ofEpochMilli(at)))
    }
    set(action) {
      if (action == null) for (key in listOf(ID, PLACE, AT, RESPONSE)) saved.remove<Any>(key)
      else {
        saved[ID] = action.id
        saved[PLACE] = action.proposal?.location
        saved[AT] = action.proposal?.at?.toEpochMilli()
        saved[RESPONSE] = action.response?.name
      }
    }

  private companion object {
    const val ID = "meetupActionId"
    const val PLACE = "meetupPlace"
    const val AT = "meetupAt"
    const val RESPONSE = "meetupResponse"
  }
}
