package com.commando.app.ui.chat

// AI assistance: OpenAI Codex.
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.commando.app.R
import com.commando.app.data.repository.OrderError
import com.commando.app.model.*
import com.commando.app.ui.theme.SampleAppTheme
import java.time.*
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

  private fun clickText(id: Int) =
      compose.onNodeWithText(compose.activity.getString(id)).performClick()

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
    for (blocked in
        listOf(
            state.copy(context = null),
            state.copy(chat = state.chat!!.copy(status = OrderStatus.DELIVERED)),
            state.copy(
                context = context.copy(trip = context.trip.copy(status = TripStatus.CANCELLED))
            ),
        )) {
      compose.runOnIdle { state = blocked }
      compose.onNodeWithText("Accept").assertDoesNotExist()
    }
    compose.runOnIdle {
      state = chatPreviewState().copy(chat = OrderChat(OrderStatus.ACCEPTED, listOf(message)))
    }
    val responseTag = OrderChatTestTags.MEETUP_RESPONSE + message.id
    compose.onNodeWithTag(responseTag + MeetupResponse.ACCEPTED.name).performClick()
    compose.onNodeWithTag(responseTag + MeetupResponse.DECLINED.name).performClick()
    assertEquals(listOf(MeetupResponse.ACCEPTED, MeetupResponse.DECLINED), replies)
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
    clickText(R.string.create_trip_date_label)
    clickText(R.string.create_trip_dialog_ok)
    clickText(R.string.chat_meetup_time)
    clickText(R.string.create_trip_dialog_ok)
    compose.onNodeWithTag(OrderChatTestTags.MEETUP_LOCATION).performTextReplacement("")
    compose.onNodeWithTag(OrderChatTestTags.MEETUP_SEND).assertIsNotEnabled()
    compose
        .onNodeWithTag(OrderChatTestTags.MEETUP_LOCATION)
        .performTextReplacement(context.trip.handoffLocation.name)
    compose.onNodeWithTag(OrderChatTestTags.MEETUP_SEND).assertIsEnabled().performClick()
    assertEquals(listOf(context.trip.handoffLocation.name to context.trip.scheduledAt), proposals)
    compose.onNodeWithText(compose.activity.getString(R.string.chat_meetup_sending)).assertExists()
  }

  @Test
  fun pendingProposalExpiresWhileTheSameCardIsIdle() {
    val origin = Instant.now()
    val clock =
        object : Clock() {
          override fun instant(): Instant = origin.plusMillis(compose.mainClock.currentTime)

          override fun getZone(): ZoneId = ZoneOffset.UTC

          override fun withZone(zone: ZoneId): Clock = this
        }
    val proposal = MeetupProposal("Library entrance", origin.plusSeconds(10))
    val message =
        ChatMessage(
            "expiring",
            state.context!!.trip.ownerId,
            CHAT_MEETUP_FALLBACK,
            origin,
            meetup = proposal,
        )
    compose.setContent {
      SampleAppTheme {
        MeetupCard(message, state, { _, response -> replies += response }, clock = clock)
      }
    }
    compose.mainClock.autoAdvance = false
    val accept = OrderChatTestTags.MEETUP_RESPONSE + message.id + MeetupResponse.ACCEPTED.name
    val decline = OrderChatTestTags.MEETUP_RESPONSE + message.id + MeetupResponse.DECLINED.name
    compose.onNodeWithTag(accept).assertIsDisplayed()
    compose.onNodeWithTag(decline).assertIsDisplayed()
    compose.mainClock.advanceTimeBy(10_000 - compose.mainClock.currentTime - 1_000)
    compose.onNodeWithTag(accept).assertExists()
    compose.mainClock.advanceTimeBy(2_000)
    compose
        .onNodeWithText(compose.activity.getString(R.string.chat_meetup_expired))
        .assertIsDisplayed()
    compose.onNodeWithTag(accept).assertDoesNotExist()
    compose.onNodeWithTag(decline).assertDoesNotExist()
    assertTrue(replies.isEmpty())
  }

  @Test
  fun extractedSectionsApplyCallerModifiersToTheirRoots() {
    val context = state.context!!
    val owner = state.copy(context = context.copy(viewerId = context.trip.ownerId))
    val message =
        ChatMessage(
            "section",
            context.trip.ownerId,
            CHAT_MEETUP_FALLBACK,
            Instant.now(),
            meetup = MeetupProposal("Library", Instant.now().plusSeconds(3600)),
        )
    compose.setContent {
      SampleAppTheme {
        Column {
          MeetupComposer(owner, { _, _ -> }, Modifier.testTag("meetup-composer-root"))
          MeetupCard(message, state, { _, _ -> }, Modifier.testTag("meetup-card-root"))
          MeetupFeedback(
              MeetupActionState(busy = true),
              {},
              Modifier.testTag("meetup-feedback-root"),
          )
        }
      }
    }
    for (tag in listOf("meetup-composer-root", "meetup-card-root", "meetup-feedback-root")) {
      compose.onNodeWithTag(tag).assertIsDisplayed()
    }
  }
}
