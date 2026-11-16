package com.android.sample.ui.chat

// AI assistance: OpenAI Codex.
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.android.sample.R
import com.android.sample.data.repository.OrderError
import com.android.sample.model.*
import com.commando.app.model.*
import com.android.sample.ui.orders.*
import com.android.sample.ui.theme.SampleAppTheme
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

  private fun show() = compose.setContent {
    SampleAppTheme {
      OrderChatScreen(
          state,
          { backs++ },
          { retries++ },
          { state = state.copy(draft = it) },
          { sends++ },
      )
    }
  }

  private fun tag(tag: String) = compose.onNodeWithTag(tag)

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
        .onNodeWithText(compose.activity.getString(R.string.chat_text_limit, MAX_CHAT_TEXT_LENGTH))
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
