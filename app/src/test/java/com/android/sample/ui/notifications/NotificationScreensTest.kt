package com.android.sample.ui.notifications

// AI assistance: OpenAI Codex.

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.android.sample.data.repository.*
import com.android.sample.model.*
import com.android.sample.ui.navigation.*
import com.android.sample.ui.theme.SampleAppTheme
import java.time.Instant
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Screen actions, confirmed values and retry feedback with a short viewport. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-rUS-w398dp-h844dp-mdpi")
class NotificationScreensTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
  private val notice =
      OrderNotification(
          OrderNotificationEvent(
              "order / % 雪",
              "alice",
              NotificationEventType.ORDER_ACCEPTED,
              Instant.EPOCH,
          )
      )

  private fun node(tag: String) = compose.onNodeWithTag(tag)

  @Test
  fun rowsOpenTheExactOrderAndOnlyUnreadRowsOfferTheReadAction() {
    val read =
        notice.copy(
            event =
                notice.event.copy(orderId = "read", type = NotificationEventType.ORDER_REJECTED),
            isRead = true,
        )
    val placed =
        notice.copy(
            event = notice.event.copy(orderId = "placed", type = NotificationEventType.ORDER_PLACED)
        )
    var opened = ""
    var marked = ""
    var state by mutableStateOf(NotificationPageState.Content(listOf(notice, read, placed)))
    compose.setContent {
      SampleAppTheme {
        NotificationInboxScreen(
            state,
            {},
            {},
            {},
            { marked = it },
            {},
            { opened = it },
        )
      }
    }
    node(NotificationTestTags.ORDER + notice.event.id).performScrollTo().performClick()
    node(NotificationTestTags.READ + notice.event.id).performClick()
    node(NotificationTestTags.LIST).performScrollToKey(read.event.id)
    node(NotificationTestTags.READ + read.event.id).assertDoesNotExist()
    node(NotificationTestTags.LIST).performScrollToKey(placed.event.id)
    node(NotificationTestTags.ROW + placed.event.id).assertIsDisplayed()
    compose.runOnIdle { state = state.copy(isSaving = true) }
    node(NotificationTestTags.LIST).performScrollToKey(notice.event.id)
    node(NotificationTestTags.READ + notice.event.id).assertIsNotEnabled()
    node(NotificationTestTags.ORDER + notice.event.id).assertIsEnabled()
    compose.runOnIdle {
      assertEquals(notice.event.orderId, opened)
      assertEquals(notice.event.id, marked)
    }
  }

  @Test
  fun loadingEmptyErrorRetryAndSessionEndNeverShowStaleRows() {
    var state by
        mutableStateOf<NotificationPageState<List<OrderNotification>>>(
            NotificationPageState.Loading
        )
    var retries = 0
    compose.setContent {
      SampleAppTheme {
        Box(Modifier.height(320.dp)) {
          NotificationInboxScreen(state, {}, {}, { retries++ }, {}, {}, {})
        }
      }
    }
    node(NotificationTestTags.LOADING).assertIsDisplayed()
    compose.runOnIdle { state = NotificationPageState.Content(emptyList()) }
    node(NotificationTestTags.RETRY).performClick()
    compose.runOnIdle { state = NotificationPageState.Error(OrderError.Unknown) }
    node(NotificationTestTags.RETRY).performClick()
    compose.runOnIdle {
      state = NotificationPageState.Content(listOf(notice), saveError = OrderError.Unknown)
    }
    node(NotificationTestTags.SAVE_RETRY).performScrollTo().assertIsDisplayed()
    compose.runOnIdle {
      state = NotificationPageState.SessionEnded
      assertEquals(2, retries)
    }
    node(NotificationTestTags.ROW + notice.event.id).assertDoesNotExist()
  }

  @Test
  fun preferencesKeepConfirmedSwitchesAndOfferASaveRetryOnAShortScreen() {
    var state by
        mutableStateOf<NotificationPageState<NotificationPreferences>>(
            NotificationPageState.Content(NotificationPreferences())
        )
    var changed: NotificationPreferences? = null
    var retries = 0
    compose.setContent {
      SampleAppTheme {
        Box(Modifier.height(320.dp)) {
          NotificationPreferencesScreen(state, {}, {}, { changed = it }, { retries++ })
        }
      }
    }
    node(NotificationTestTags.NEW_ORDERS).performScrollTo().assertIsOn().performClick()
    compose.runOnIdle {
      assertEquals(NotificationPreferences(false, true), changed)
      state =
          NotificationPageState.Content(NotificationPreferences(), saveError = OrderError.Unknown)
    }
    node(NotificationTestTags.NEW_ORDERS).assertIsOn()
    node(NotificationTestTags.SAVE_RETRY).performScrollTo().performClick()
    compose.runOnIdle {
      assertEquals(1, retries)
      state = NotificationPageState.Content(NotificationPreferences(), isSaving = true)
    }
    node(NotificationTestTags.DECISIONS).performScrollTo().assertIsNotEnabled()
    compose.runOnIdle {
      state =
          NotificationPageState.Content(
              NotificationPreferences(),
              refreshError = OrderError.Unknown,
          )
    }
    node(NotificationTestTags.DECISIONS).assertIsEnabled()
    compose
        .onNodeWithText(
            compose.activity.getString(com.android.sample.R.string.notifications_refresh_error)
        )
        .performScrollTo()
        .assertIsDisplayed()
    node(NotificationTestTags.SAVE_RETRY).performScrollTo().performClick()
    compose.runOnIdle { assertEquals(2, retries) }
  }
}
