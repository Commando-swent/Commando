package com.commando.app.ui.notifications

// AI assistance: OpenAI Codex; cancellation row (#401): Claude Code.
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.commando.app.R
import com.commando.app.model.*
import com.commando.app.ui.theme.SampleAppTheme
import java.io.File
import java.time.Instant
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** French strings and every notification type, rendered by the production Compose screens. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fr-rFR-w398dp-h1050dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NotificationFrenchScreenTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
  private val notifications =
      NotificationEventType.entries.mapIndexed { index, type ->
        OrderNotification(
            OrderNotificationEvent(
                "CMD-${2048 + index}",
                "alice",
                type,
                Instant.parse("2026-11-01T09:42:00Z").minusSeconds(index * 3600L),
            ),
            isRead = index == 2,
        )
      }

  @Test
  fun inboxShowsFrenchEventLabelsAndReadActions() {
    compose.setContent {
      SampleAppTheme {
        Surface {
          NotificationInboxScreen(
              NotificationPageState.Content(notifications),
              {},
              {},
              {},
              {},
              {},
              {},
          )
        }
      }
    }
    org.junit.Assert.assertEquals(
        "fr",
        compose.activity.resources.configuration.locales[0].language,
    )
    compose
        .onNodeWithText(compose.activity.getString(R.string.notifications_placed))
        .assertIsDisplayed()
    capture("inbox-fr")
    val rejected = notifications.first { it.event.type == NotificationEventType.ORDER_REJECTED }
    compose.onNodeWithTag(NotificationTestTags.ROW + rejected.event.id).performScrollTo()
    compose
        .onNodeWithText(compose.activity.getString(R.string.notifications_rejected))
        .assertIsDisplayed()
    compose.onNodeWithTag(NotificationTestTags.READ + rejected.event.id).assertDoesNotExist()
    compose
        .onNodeWithTag(NotificationTestTags.ROW + notifications.last().event.id)
        .performScrollTo()
    compose
        .onNodeWithText(compose.activity.getString(R.string.notifications_cancelled))
        .assertIsDisplayed()
  }

  @Test
  fun preferencesExposeBothSupportedCategoriesInFrench() {
    compose.setContent {
      SampleAppTheme {
        Surface {
          NotificationPreferencesScreen(
              NotificationPageState.Content(NotificationPreferences()),
              {},
              {},
              {},
              {},
          )
        }
      }
    }
    compose.onNodeWithTag(NotificationTestTags.NEW_ORDERS).assertIsOn()
    compose
        .onNodeWithText(compose.activity.getString(R.string.notifications_decisions))
        .assertIsDisplayed()
    capture("preferences-fr")
  }

  private fun capture(name: String) {
    val file = File("build/reports/notifications/$name.png").apply { parentFile!!.mkdirs() }
    file.outputStream().use {
      compose
          .onRoot()
          .captureToImage()
          .asAndroidBitmap()
          .compress(Bitmap.CompressFormat.PNG, 100, it)
    }
  }
}
