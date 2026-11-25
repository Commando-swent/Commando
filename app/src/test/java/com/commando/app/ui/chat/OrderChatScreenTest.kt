package com.commando.app.ui.chat

// AI assistance: OpenAI Codex.
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.android.sample.R
import com.commando.app.data.repository.OrderError
import com.commando.app.model.*
import com.commando.app.ui.orders.*
import com.commando.app.ui.theme.SampleAppTheme
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Native rendering checks include compact French layout, states and stable action selectors. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-rUS-w412dp-h917dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OrderChatScreenTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
  private var state by mutableStateOf(chatPreviewState())
  private var backs = 0
  private var retries = 0
  private var sends = 0
  private val delays = mutableListOf<Int>()

  private fun show() = compose.setContent {
    SampleAppTheme {
      OrderChatScreen(
          state,
          { backs++ },
          { retries++ },
          { state = state.copy(draft = it) },
          { sends++ },
          onDelay = { delays += it },
      )
    }
  }

  private fun tag(tag: String) = compose.onNodeWithTag(tag)

  // Match the existing search-dialog tests: Robolectric loops on wide Material text dialogs.
  @Test
  @Config(qualifiers = "en-rUS-w320dp-h917dp-mdpi")
  fun ownerDelayDialogValidatesDurationAndRequesterSeesTranslatedNotice() {
    state = state.copy(context = state.context!!.copy(viewerId = state.context!!.trip.ownerId))
    show()
    tag(OrderChatTestTags.DELAY).performClick()
    tag(OrderChatTestTags.DELAY_SEND).assertIsNotEnabled()
    tag(OrderChatTestTags.DELAY_INPUT).performTextInput("0")
    tag(OrderChatTestTags.DELAY_SEND).assertIsNotEnabled()
    compose
        .onNodeWithText(
            compose.activity.getString(R.string.chat_delay_invalid, MAX_CHAT_DELAY_MINUTES)
        )
        .assertExists()
    tag(OrderChatTestTags.DELAY_INPUT).performTextReplacement("15")
    screenshot("delay-dialog")
    tag(OrderChatTestTags.DELAY_SEND).assertIsEnabled().performClick()
    assertEquals(listOf(15), delays)
    compose.runOnIdle {
      val context = state.context!!
      state =
          state.copy(
              context = context.copy(viewerId = context.order.requesterId),
              chat =
                  state.chat!!.copy(
                      messages =
                          listOf(
                              ChatMessage(
                                  "delay",
                                  context.trip.ownerId,
                                  "",
                                  context.order.createdAt,
                                  15,
                              )
                          )
                  ),
          )
    }
    tag(OrderChatTestTags.DELAY).assertDoesNotExist()
    compose
        .onNodeWithText(
            compose.activity.resources.getQuantityString(R.plurals.chat_delay_message, 15, 15)
        )
        .assertIsDisplayed()
    compose
        .onNodeWithContentDescription(compose.activity.getString(R.string.chat_delay_notice))
        .assertDoesNotExist()
    compose
        .onAllNodesWithText(compose.activity.getString(R.string.chat_delay_notice))
        .assertCountEquals(1)
    compose
        .onNodeWithText(compose.activity.getString(R.string.chat_delay_notice))
        .assertIsDisplayed()
    screenshot("delay-received")
    compose.runOnIdle {
      state =
          state.copy(
              chat =
                  state.chat!!.copy(
                      messages = state.chat!!.messages.map { it.copy(delayMinutes = 1) }
                  )
          )
    }
    compose.onNodeWithText("Delay: about 1 minute").assertIsDisplayed()
  }

  @Test
  @Config(qualifiers = "fr-rFR-w320dp-h917dp-mdpi")
  fun frenchDelayNoticeAndOwnerDialogRemainLocalized() {
    val context = state.context!!
    state =
        state.copy(
            context = context.copy(viewerId = context.trip.ownerId),
            chat =
                state.chat!!.copy(
                    messages =
                        listOf(
                            ChatMessage(
                                "delay-fr",
                                context.trip.ownerId,
                                "",
                                context.order.createdAt,
                                20,
                            )
                        )
                ),
        )
    show()
    compose.onNodeWithText("Retard : environ 20 minutes").assertIsDisplayed()
    screenshot("delay-fr")
    tag(OrderChatTestTags.DELAY).performClick()
    for (invalid in listOf("letters", (MAX_CHAT_DELAY_MINUTES + 1).toString(), "-1")) {
      tag(OrderChatTestTags.DELAY_INPUT).performTextReplacement(invalid)
      tag(OrderChatTestTags.DELAY_SEND).assertIsNotEnabled()
    }
    compose.onNodeWithText(compose.activity.getString(R.string.profile_edit_cancel)).performClick()
    assertTrue(delays.isEmpty())
    tag(OrderChatTestTags.DELAY_INPUT).assertDoesNotExist()
    compose.runOnIdle { state = state.copy(sendError = OrderError.Unknown) }
    tag(OrderChatTestTags.DELAY).assertIsNotEnabled()
    compose.runOnIdle {
      state = state.copy(chat = state.chat!!.copy(status = OrderStatus.DELIVERED))
    }
    tag(OrderChatTestTags.DELAY).assertDoesNotExist()
  }

  @Test
  @Config(qualifiers = "en-rUS-w320dp-h917dp-mdpi")
  fun deliveryWhileDialogIsOpenRemovesThePendingAction() {
    state = state.copy(context = state.context!!.copy(viewerId = state.context!!.trip.ownerId))
    show()
    tag(OrderChatTestTags.DELAY).performClick()
    tag(OrderChatTestTags.DELAY_INPUT).performTextInput("5")
    compose.runOnIdle {
      state = state.copy(chat = state.chat!!.copy(status = OrderStatus.DELIVERED))
    }
    tag(OrderChatTestTags.DELAY_SEND).assertDoesNotExist()
    assertTrue(delays.isEmpty())
  }

  @Test
  fun failedDelayHasItsOwnFeedbackAndRetryAction() {
    state =
        state.copy(
            context = state.context!!.copy(viewerId = state.context!!.trip.ownerId),
            draft = "typed",
            sendError = OrderError.PermissionDenied,
            failedDelayMinutes = 5,
        )
    show()
    tag(OrderChatTestTags.RETRY_SEND)
        .assertTextContains(
            compose.activity.resources.getQuantityString(R.plurals.chat_delay_failed, 5, 5)
        )
        .performClick()
    assertEquals(listOf(5), delays)
    assertEquals(0, sends)
    compose.runOnIdle { state = state.copy(sendError = OrderError.Unknown) }
    tag(OrderChatTestTags.RETRY_SEND).performClick()
    assertEquals(1, sends)
    assertEquals(1, delays.size)
    compose.runOnIdle { state = state.copy(sending = true) }
    tag(OrderChatTestTags.RETRY_SEND).assertIsNotEnabled()
    compose.runOnIdle {
      state =
          state.copy(
              sending = false,
              sendError = OrderError.PermissionDenied,
              chat = state.chat!!.copy(status = OrderStatus.DELIVERED),
          )
    }
    tag(OrderChatTestTags.RETRY_SEND).assertIsNotEnabled()
  }

  @Test
  @Config(qualifiers = "fr-rFR-w320dp-h917dp-mdpi")
  fun frenchFailedDelayUsesSingularAndPluralDurations() {
    state =
        state.copy(
            context = state.context!!.copy(viewerId = state.context!!.trip.ownerId),
            sendError = OrderError.PermissionDenied,
            failedDelayMinutes = 1,
        )
    show()
    tag(OrderChatTestTags.RETRY_SEND)
        .assertTextContains("Échec de l’envoi du retard de 1 minute. Réessayer cet avis.")
    compose.runOnIdle { state = state.copy(failedDelayMinutes = 2) }
    tag(OrderChatTestTags.RETRY_SEND)
        .assertTextContains("Échec de l’envoi du retard de 2 minutes. Réessayer cet avis.")
  }

  @Test
  fun cancelledTripCannotOpenADelayDialog() {
    val context = state.context!!
    state =
        state.copy(
            context =
                context.copy(
                    viewerId = context.trip.ownerId,
                    trip = context.trip.copy(status = TripStatus.CANCELLED),
                )
        )
    show()
    tag(OrderChatTestTags.DELAY).assertDoesNotExist()
  }

  @Test
  fun cachedHistoryRemainsReadableAndOffersRefreshWithoutSending() {
    state =
        state.copy(
            cached = true,
            lastConfirmedAt = null,
            draft = "Saved draft",
            sendError = OrderError.Unknown,
        )
    show()
    tag(OrderChatTestTags.CACHED).assertExists()
    tag(OrderChatTestTags.MESSAGE + "incoming").assertExists()
    tag(OrderChatTestTags.SEND).assertIsNotEnabled()
    tag(OrderChatTestTags.RETRY_SEND).assertIsNotEnabled()
    tag(OrderChatTestTags.RETRY).performClick()
    assertEquals(1, retries)
    assertEquals(0, sends)
  }

  @Test
  fun conversationInputValidationAndCallbacks() {
    show()
    tag(OrderChatTestTags.MESSAGE + "incoming").assertIsDisplayed()
    tag(OrderChatTestTags.MESSAGE + "outgoing").assertIsDisplayed()
    tag(OrderChatTestTags.SEND).assertIsNotEnabled()
    screenshot("conversation")
    tag(OrderChatTestTags.INPUT).performTextInput("x".repeat(MAX_CHAT_TEXT_LENGTH + 1))
    tag(OrderChatTestTags.SEND).assertIsNotEnabled()
    compose
        .onNodeWithText(
            compose.activity.resources.getQuantityString(
                R.plurals.chat_text_limit,
                MAX_CHAT_TEXT_LENGTH,
                MAX_CHAT_TEXT_LENGTH,
            )
        )
        .assertExists()
    tag(OrderChatTestTags.INPUT).performTextReplacement("Hello")
    tag(OrderChatTestTags.SEND).assertIsEnabled().performClick()
    assertEquals(1, sends)
    compose.runOnIdle { state = state.copy(sending = true) }
    tag(OrderChatTestTags.INPUT).assertIsNotEnabled()
    tag(OrderChatTestTags.SEND).assertIsNotEnabled()
    tag(OrderChatTestTags.BACK).performClick()
    tag(OrderChatTestTags.INFO).performClick()
    assertEquals(2, backs)
  }

  @Test
  fun parentModifiersConstrainTheScreenAndEntryRoots() {
    val entryTag = "parent_chat_entry"
    val context = state.context!!
    compose.setContent {
      SampleAppTheme {
        Column {
          OrderChatScreen(
              state,
              {},
              {},
              {},
              {},
              modifier = Modifier.width(320.dp).height(480.dp),
          )
          OrderChatEntry(
              OrderDetailsUiState.Content(context.order, context.trip, context.viewerId),
              {},
              modifier = Modifier.width(280.dp).testTag(entryTag),
          )
        }
      }
    }
    tag(OrderChatTestTags.SCREEN).assertWidthIsEqualTo(320.dp).assertHeightIsEqualTo(480.dp)
    tag(entryTag).assertWidthIsEqualTo(280.dp)
  }

  @Test
  fun definitiveRejectionAllowsCorrectionButUnknownOutcomeLocksTheInput() {
    state = chatPreviewState().copy(draft = "rejected", sendError = OrderError.InvalidData)
    show()
    tag(OrderChatTestTags.INPUT).assertIsEnabled().performTextReplacement("corrected")
    tag(OrderChatTestTags.SEND).assertIsEnabled().performClick()
    assertEquals("corrected", state.draft)
    assertEquals(1, sends)
    compose.runOnIdle { state = state.copy(sendError = OrderError.Unknown) }
    tag(OrderChatTestTags.INPUT).assertIsNotEnabled()
    tag(OrderChatTestTags.SEND).assertIsNotEnabled()
    tag(OrderChatTestTags.RETRY_SEND).assertIsEnabled()
  }

  @Test
  fun emptyHistorySendRetryAndTypedLoadingErrors() {
    state = chatPreviewState(empty = true)
    show()
    tag(OrderChatTestTags.EMPTY).assertIsDisplayed()
    screenshot("empty")
    compose.runOnIdle { state = state.copy(draft = "preserved", sendError = OrderError.Unknown) }
    tag(OrderChatTestTags.INPUT).assertIsNotEnabled().assertTextContains("preserved")
    tag(OrderChatTestTags.RETRY_SEND).performClick()
    assertEquals(1, sends)
    compose.runOnIdle { state = OrderChatUiState() }
    tag(OrderChatTestTags.LOADING).assertIsDisplayed()
    for (error in
        listOf(
            OrderError.Unknown,
            OrderError.PermissionDenied,
            OrderError.NotFound,
            OrderError.NotAuthenticated,
        )) {
      compose.runOnIdle { state = OrderChatUiState(loading = false, error = error) }
      tag(OrderChatTestTags.ERROR).assertIsDisplayed()
      if (error == OrderError.NotAuthenticated) tag(OrderChatTestTags.RETRY).assertDoesNotExist()
      else tag(OrderChatTestTags.RETRY).performClick()
      tag(OrderChatTestTags.INPUT).assertDoesNotExist()
    }
    assertEquals(3, retries)
    compose.runOnIdle { state = chatPreviewState() }
    tag(OrderChatTestTags.ERROR).assertDoesNotExist()
    tag(OrderChatTestTags.INPUT).assertIsDisplayed()
    tag(OrderChatTestTags.MESSAGE + "incoming").assertIsDisplayed()
    // A late error can coexist with stale content; it must still hide the composer/history.
    compose.runOnIdle { state = state.copy(error = OrderError.NotFound) }
    tag(OrderChatTestTags.ERROR).assertIsDisplayed()
    tag(OrderChatTestTags.INPUT).assertDoesNotExist()
    tag(OrderChatTestTags.HISTORY).assertDoesNotExist()
  }

  @Test
  @Config(qualifiers = "fr-rFR-w360dp-h640dp-mdpi")
  fun terminalHistoryAndLongFrenchTextKeepBackAndReadOnlyVisible() {
    val base = chatPreviewState(OrderStatus.DELIVERED)
    state =
        base.copy(
            chat =
                base.chat!!.copy(
                    messages =
                        listOf(
                            base.chat!!
                                .messages
                                .last()
                                .copy(
                                    text =
                                        "Message long pour vérifier le retour à la ligne. "
                                            .repeat(5)
                                )
                        )
                )
        )
    show()
    tag(OrderChatTestTags.READ_ONLY).assertIsDisplayed()
    tag(OrderChatTestTags.INPUT).assertDoesNotExist()
    tag(OrderChatTestTags.BACK).assertIsDisplayed().performClick()
    tag(OrderChatTestTags.MESSAGE + "outgoing").assertExists()
    screenshot("closed-fr-compact")
    compose.runOnIdle {
      state = state.copy(sendError = OrderError.Unknown, draft = "à réconcilier")
    }
    tag(OrderChatTestTags.READ_ONLY).assertExists()
    tag(OrderChatTestTags.RETRY_SEND).performClick()
    assertEquals(1, sends)
    assertEquals(1, backs)
  }

  @Test
  fun detailsOnlyExposeTheChatToParticipantsAndPendingStaysLocked() {
    val context = chatPreviewState().context!!
    var content by
        mutableStateOf(OrderDetailsUiState.Content(context.order, context.trip, context.viewerId))
    compose.setContent {
      SampleAppTheme { OrderDetailsScreen(content, {}, {}, onChat = { sends++ }) }
    }
    for (viewer in listOf(context.order.requesterId, context.trip.ownerId)) {
      for (status in OrderStatus.entries) {
        compose.runOnIdle {
          content = content.copy(order = content.order.copy(status = status), viewerId = viewer)
        }
        val entry = tag(OrderDetailsTestTags.CHAT).performScrollTo()
        if (status == OrderStatus.PENDING) entry.assertIsNotEnabled()
        else entry.assertIsEnabled().performClick()
      }
    }
    assertEquals((OrderStatus.entries.size - 1) * 2, sends)
    compose.runOnIdle { content = content.copy(mutation = OrderMutationState.Saving) }
    tag(OrderDetailsTestTags.CHAT).assertIsNotEnabled()
    compose.runOnIdle { content = content.copy(viewerId = "outsider") }
    tag(OrderDetailsTestTags.CHAT).assertDoesNotExist()
  }

  @Test
  fun incomingMessagesPreserveTheReadersPositionInOlderHistory() {
    val base = chatPreviewState()
    val sample = base.chat!!.messages.first()
    val history =
        (0..11).map {
          sample.copy(
              id = "history-$it",
              text = sample.text.repeat(5),
              createdAt = sample.createdAt.plusSeconds(it * 43_200L),
          )
        }
    state = base.copy(chat = base.chat.copy(messages = history))
    show()
    tag(OrderChatTestTags.HISTORY).performScrollToIndex(4)
    tag(OrderChatTestTags.MESSAGE + "history-7").assertIsDisplayed()
    compose.runOnIdle {
      state =
          state.copy(
              chat =
                  state.chat!!.copy(
                      messages =
                          history +
                              sample.copy(
                                  id = "new",
                                  createdAt = history.last().createdAt.plusSeconds(60),
                              )
                  )
          )
    }
    tag(OrderChatTestTags.MESSAGE + "history-7").assertIsDisplayed()
    tag(OrderChatTestTags.MESSAGE + "new").assertIsNotDisplayed()
  }

  @Test
  fun cachedHistoryRemainsReadableButSendingAndRefreshAreDisabledOffline() {
    assertOfflineHistory("offline-en")
  }

  @Test
  @Config(qualifiers = "fr-rFR-w412dp-h917dp-mdpi")
  fun cachedHistoryAndConfirmationTimeAreLocalizedInFrench() {
    assertOfflineHistory("offline-fr")
  }

  @Test
  fun coldOfflineChatExplainsThatHistoryMustFirstBeLoadedOnline() {
    state = state.copy(context = null, chat = null, error = OrderError.Unknown, online = false)
    show()
    compose
        .onNodeWithText(compose.activity.getString(R.string.chat_offline_empty))
        .assertIsDisplayed()
    tag(OrderChatTestTags.RETRY).assertIsNotEnabled()
    tag(OrderChatTestTags.HISTORY).assertDoesNotExist()
  }

  private fun assertOfflineHistory(capture: String) {
    state =
        state.copy(
            online = false,
            cached = true,
            lastConfirmedAt = state.chat!!.messages.last().createdAt.plusSeconds(60),
            draft = "Unsent draft",
        )
    show()
    tag(OrderChatTestTags.CACHED).assertIsDisplayed()
    tag(OrderChatTestTags.SEND).assertIsNotEnabled()
    tag(OrderChatTestTags.RETRY).assertIsNotEnabled()
    tag(OrderChatTestTags.INPUT).performTextReplacement("Edited offline")
    assertEquals("Edited offline", state.draft)
    screenshot(capture)
  }

  private fun screenshot(name: String) {
    val output = File("build/reports/order-chat/$name.png")
    output.parentFile?.mkdirs()
    output.outputStream().use {
      compose
          .onRoot()
          .captureToImage()
          .asAndroidBitmap()
          .compress(Bitmap.CompressFormat.PNG, 100, it)
    }
  }
}
