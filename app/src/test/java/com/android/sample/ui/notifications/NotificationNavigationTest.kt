package com.android.sample.ui.notifications

// AI assistance: OpenAI Codex.
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.android.sample.data.repository.*
import com.android.sample.model.*
import com.android.sample.model.authentication.AuthUser
import com.android.sample.model.authentication.FakeAuthRepository
import com.android.sample.ui.auth.GoogleCredentialClient
import com.android.sample.ui.auth.WelcomeTestTags
import com.android.sample.ui.navigation.*
import com.android.sample.ui.theme.SampleAppTheme
import java.time.Instant
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Full Profile → inbox/preferences → order navigation with account-scoped injected repositories.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-rUS-w398dp-h844dp-mdpi")
class NotificationNavigationTest {
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
  fun profileInboxAndPreferencesUseTheRepositoryAndClearOnAccountChange() {
    val auth = FakeAuthRepository(AuthUser("alice", "Alice", "alice@example.com"))
    val trips = FakeTripRepository("alice")
    var settings = NotificationPreferences()
    val requestedOrders = mutableListOf<String>()
    val notifications =
        object : NotificationRepository {
          override suspend fun getNotifications() = OrderResult.Success(listOf(notice))

          override suspend fun markAsRead(notificationId: String) = OrderResult.Success(Unit)

          override suspend fun getPreferences() = OrderResult.Success(settings)

          override suspend fun updatePreferences(
              newOrders: Boolean?,
              orderDecisions: Boolean?,
          ): OrderResult<Unit> {
            settings =
                settings.copy(
                    newOrders = newOrders ?: settings.newOrders,
                    orderDecisions = orderDecisions ?: settings.orderDecisions,
                )
            return OrderResult.Success(Unit)
          }
        }
    val credentials =
        object : GoogleCredentialClient {
          override suspend fun request(context: Context) = null

          override suspend fun clearSession() = Unit
        }
    val restoration = StateRestorationTester(compose)
    restoration.setContent {
      SampleAppTheme {
        CommandoApp(
            auth,
            trips,
            credentials,
            orderRepositoryFactory = { uid ->
              object : OrderRepository by FakeOrderRepository(uid, initialOrders = emptyList()) {
                override suspend fun getOrderById(orderId: String): OrderResult<Order> {
                  requestedOrders += orderId
                  return OrderResult.Error(OrderError.NotFound)
                }
              }
            },
            notificationRepositoryFactory = { notifications },
        )
      }
    }
    node(AppTestTags.PROFILE_BUTTON).performClick()
    node(NotificationTestTags.PREFERENCES).performScrollTo().performClick()
    node(NotificationTestTags.NEW_ORDERS).performScrollTo().performClick()
    node(NotificationTestTags.NEW_ORDERS).assertIsOff()
    restoration.emulateSavedInstanceStateRestore()
    node(NotificationTestTags.NEW_ORDERS).assertIsOff()
    node(NotificationTestTags.BACK).performClick()
    node(NotificationTestTags.PREFERENCES).performScrollTo().performClick()
    node(NotificationTestTags.NEW_ORDERS).assertIsOff()
    node(NotificationTestTags.BACK).performClick()
    node(NotificationTestTags.INBOX).performScrollTo().performClick()
    node(NotificationTestTags.ORDER + notice.event.id).performScrollTo().performClick()
    compose.runOnIdle { assertEquals(listOf(notice.event.orderId), requestedOrders) }
    compose.runOnIdle { auth.signOut() }
    node(WelcomeTestTags.SCREEN).assertIsDisplayed()
    node(NotificationTestTags.ROW + notice.event.id).assertDoesNotExist()
  }
}
