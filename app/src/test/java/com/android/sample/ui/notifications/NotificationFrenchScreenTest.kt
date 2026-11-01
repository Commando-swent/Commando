package com.android.sample.ui.notifications

// AI assistance: OpenAI Codex.
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.android.sample.model.*
import com.android.sample.ui.theme.SampleAppTheme
import java.io.File
import java.time.Instant
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** French strings and all three notification types, rendered by the production Compose screens. */
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
    compose.onNodeWithText("Nouvelle commande reçue").assertIsDisplayed()
    capture("inbox-fr")
    compose
        .onNodeWithTag(NotificationTestTags.ROW + notifications.last().event.id)
        .performScrollTo()
    compose.onNodeWithText("Commande refusée").assertIsDisplayed()
    compose
        .onNodeWithTag(NotificationTestTags.READ + notifications.last().event.id)
        .assertDoesNotExist()
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
    compose.onNodeWithText("Réponses aux commandes").assertIsDisplayed()
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
