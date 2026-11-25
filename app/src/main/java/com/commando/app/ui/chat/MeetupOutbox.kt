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
    get() =
        saved.get<String>(ID)?.let { id ->
          saved.get<String>(RESPONSE)?.let {
            PendingMeetup(id, response = MeetupResponse.valueOf(it))
          }
              ?: saved.get<String>(PLACE)?.let {
                PendingMeetup(
                    id,
                    MeetupProposal(it, Instant.ofEpochMilli(checkNotNull(saved.get<Long>(AT)))),
                )
              }
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
