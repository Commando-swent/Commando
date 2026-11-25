package com.android.sample.ui.chat

// AI assistance: OpenAI Codex.
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.android.sample.ui.theme.SampleAppTheme
import com.commando.app.model.*
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
  private val replies = mutableListOf<MeetupResponse>()

  private fun show() = compose.setContent {
    SampleAppTheme {
      OrderChatScreen(state, {}, {}, {}, {}, onRespond = { _, response -> replies += response })
    }
  }

  private fun capture(name: String) {
    val file = File("build/reports/meetup/$name.png")
    file.parentFile?.mkdirs()
    file.outputStream().use {
      compose
          .onRoot()
          .captureToImage()
          .asAndroidBitmap()
          .compress(Bitmap.CompressFormat.PNG, 100, it)
    }
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
    compose
        .onNodeWithTag(
            OrderChatTestTags.MEETUP_RESPONSE + message.id + MeetupResponse.ACCEPTED.name
        )
        .performClick()
    assertEquals(listOf(MeetupResponse.ACCEPTED), replies)
    val accepted =
        message.copy(
            meetup = proposal.copy(response = MeetupResponse.ACCEPTED, respondedAt = Instant.now())
        )
    compose.runOnIdle {
      state = state.copy(chat = OrderChat(OrderStatus.ACCEPTED, listOf(accepted)))
    }
    compose.onNodeWithText("Meetup accepted").assertIsDisplayed()
    compose
        .onNodeWithTag(
            OrderChatTestTags.MEETUP_RESPONSE + message.id + MeetupResponse.DECLINED.name
        )
        .assertDoesNotExist()
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
    compose.onNodeWithTag(OrderChatTestTags.MEETUP_LOCATION).performTextReplacement("")
    compose.onNodeWithTag(OrderChatTestTags.MEETUP_SEND).assertIsNotEnabled()
    assertTrue(replies.isEmpty())
  }
}
