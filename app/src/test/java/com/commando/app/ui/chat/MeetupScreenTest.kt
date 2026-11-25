package com.commando.app.ui.chat

// AI assistance: OpenAI Codex.
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.android.sample.R
import com.commando.app.data.repository.OrderError
import com.commando.app.model.*
import com.commando.app.ui.theme.SampleAppTheme
import java.io.File
import java.time.Instant
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-rUS-w320dp-h917dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MeetupScreenTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
  private var state by mutableStateOf(chatPreviewState())
  private var feedback by mutableStateOf(MeetupActionState())
  private var retries = 0
  private val proposals = mutableListOf<Pair<String, Instant>>()
  private val replies = mutableListOf<MeetupResponse>()

  private fun show() = compose.setContent {
    SampleAppTheme {
      OrderChatScreen(
          state,
          {},
          {},
          {},
          {},
          onRespond = { _, response -> replies += response },
          meetupState = feedback,
          onMeetupRetry = { retries++ },
          onPropose = { place, at ->
            proposals += place to at
            feedback = MeetupActionState(busy = true)
          },
      )
    }
  }

  private fun capture(name: String) {
    val file = File("build/reports/meetup/$name.png")
    file.parentFile?.mkdirs()
    val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
    file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
  }

  @Test
  fun requesterSeesDetailsAndTheAcceptedResponseRemainsInHistory() {
    val context = state.context!!
    val proposal = MeetupProposal("Library entrance", Instant.now().plusSeconds(3600))
    val message =
        ChatMessage(
            "meeting",
            context.trip.ownerId,
            CHAT_MEETUP_FALLBACK,
            Instant.now(),
            meetup = proposal,
        )
    state = state.copy(chat = state.chat!!.copy(messages = listOf(message)))
    show()
    compose.onNodeWithText(proposal.location).assertIsDisplayed()
    capture("pending-en")
    val responseTag = OrderChatTestTags.MEETUP_RESPONSE + message.id
    compose.onNodeWithTag(responseTag + MeetupResponse.ACCEPTED.name).performClick()
    assertEquals(listOf(MeetupResponse.ACCEPTED), replies)
    compose.runOnIdle { feedback = MeetupActionState(error = OrderError.Unknown) }
    compose.onNodeWithTag(OrderChatTestTags.MEETUP_RETRY).performClick()
    assertEquals(1, retries)
    for ((updated, label) in
        listOf(
            proposal.copy(response = MeetupResponse.ACCEPTED, respondedAt = Instant.now()) to
                "Meetup accepted",
            proposal.copy(response = MeetupResponse.DECLINED, respondedAt = Instant.now()) to
                "Meetup declined",
            proposal.copy(at = Instant.EPOCH) to "Proposal expired",
        )) {
      compose.runOnIdle {
        state =
            state.copy(
                chat = OrderChat(OrderStatus.ACCEPTED, listOf(message.copy(meetup = updated)))
            )
      }
      compose.onNodeWithText(label).assertIsDisplayed()
      compose.onNodeWithTag(responseTag + MeetupResponse.DECLINED.name).assertDoesNotExist()
    }
  }

  @Test
  @Config(qualifiers = "fr-rFR-w320dp-h917dp-mdpi")
  fun ownerFormRequiresLocationAndAFutureTime() {
    val context = state.context!!
    state = state.copy(context = context.copy(viewerId = context.trip.ownerId))
    show()
    compose.onNodeWithTag(OrderChatTestTags.MEETUP).performClick()
    compose.onNodeWithTag(OrderChatTestTags.MEETUP_SEND).assertIsNotEnabled()
    capture("form-fr")
    compose
        .onNodeWithText(compose.activity.getString(R.string.create_trip_date_label))
        .performClick()
    compose
        .onNodeWithText(compose.activity.getString(R.string.create_trip_dialog_ok))
        .performClick()
    compose.onNodeWithText(compose.activity.getString(R.string.chat_meetup_time)).performClick()
    compose
        .onNodeWithText(compose.activity.getString(R.string.create_trip_dialog_ok))
        .performClick()
    compose.onNodeWithTag(OrderChatTestTags.MEETUP_LOCATION).performTextReplacement("")
    compose.onNodeWithTag(OrderChatTestTags.MEETUP_SEND).assertIsNotEnabled()
    compose
        .onNodeWithTag(OrderChatTestTags.MEETUP_LOCATION)
        .performTextReplacement(context.trip.handoffLocation.name)
    compose.onNodeWithTag(OrderChatTestTags.MEETUP_SEND).assertIsEnabled().performClick()
    assertEquals(listOf(context.trip.handoffLocation.name to context.trip.scheduledAt), proposals)
    compose.onNodeWithText(compose.activity.getString(R.string.chat_meetup_sending)).assertExists()
    assertTrue(replies.isEmpty())
  }
}
