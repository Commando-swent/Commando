package com.commando.app.ui.chat

// AI assistance: OpenAI Codex.
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.credentials.Credential
import com.android.sample.R
import com.commando.app.data.repository.*
import com.commando.app.data.repository.FakeOrderRepository
import com.commando.app.data.repository.FakeRatingRepository
import com.commando.app.data.repository.FakeTripRepository
import com.commando.app.data.repository.OrderChatRepository
import com.commando.app.data.repository.OrderResult
import com.commando.app.data.repository.context
import com.commando.app.model.*
import com.commando.app.model.authentication.AuthUser
import com.commando.app.model.authentication.FakeAuthRepository
import com.commando.app.ui.auth.GoogleCredentialClient
import com.commando.app.ui.navigation.*
import com.commando.app.ui.orders.*
import com.commando.app.ui.theme.SampleAppTheme
import com.commando.app.ui.trips.MyTripsTestTags
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real app routes, session replacement and foreground subscription lifetime. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-rUS-w412dp-h917dp-mdpi")
class OrderChatNavigationTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
  private val fixture = chatPreviewState()
  private val context = fixture.context!!
  private val order = context.order
  private val trips = FakeTripRepository(context.trip.ownerId, initialTrips = listOf(context.trip))
  private lateinit var auth: FakeAuthRepository
  private var subscriptions = 0
  private val opened = mutableListOf<String>()
  private val credentials =
      object : GoogleCredentialClient {
        override suspend fun request(context: Context): Credential? = null

        override suspend fun clearSession() {}
      }

  private fun show(viewer: String) {
    auth = FakeAuthRepository(AuthUser(viewer))
    compose.setContent {
      SampleAppTheme {
        CommandoApp(
            auth,
            trips,
            credentials,
            orderRepositoryFactory = { uid ->
              FakeOrderRepository(uid, initialOrders = listOf(order), tripRepository = trips)
            },
            ratingRepositoryFactory = { uid ->
              FakeRatingRepository(uid, FakeOrderRepository(uid), trips)
            },
            chatRepositoryFactory = { uid ->
              object : OrderChatRepository {
                override suspend fun proposeMeetup(
                    orderId: String,
                    messageId: String,
                    proposal: com.commando.app.model.MeetupProposal,
                ): OrderResult<Unit> = error("Not used")

                override suspend fun respondToMeetup(
                    orderId: String,
                    messageId: String,
                    response: com.commando.app.model.MeetupResponse,
                ): OrderResult<Unit> = error("Not used")

                override fun observeChat(orderId: String) =
                    flow<OrderResult<OrderChat>> {
                      opened += orderId
                      subscriptions++
                      try {
                        emitAll(MutableStateFlow(OrderResult.Success(fixture.chat!!)))
                      } finally {
                        subscriptions--
                      }
                    }

                override suspend fun sendDelayNotice(
                    orderId: String,
                    messageId: String,
                    minutes: Int,
                ): OrderResult<Unit> = OrderResult.Success(Unit)

                override suspend fun sendMessage(
                    orderId: String,
                    messageId: String,
                    text: String,
                ): OrderResult<Unit> {
                  assertEquals(viewer, uid())
                  return OrderResult.Success(Unit)
                }
              }
            },
        )
      }
    }
  }

  private fun click(tag: String) = compose.onNodeWithTag(tag).performClick()

  private fun openFromDetails() {
    compose.onNodeWithTag(OrderDetailsTestTags.CHAT).performScrollTo().performClick()
    compose.waitUntil(5_000) { subscriptions == 1 }
    compose.onNodeWithTag(OrderChatTestTags.MESSAGE + "incoming").assertIsDisplayed()
    assertEquals(order.id, opened.last())
  }

  @Test
  fun requesterReturnsToTheSameDetailsAndSignOutRemovesChat() {
    show(order.requesterId)
    click(AppTestTags.ORDERS_BUTTON)
    click(MyOrdersTestTags.ORDER_CARD_PREFIX + order.id)
    openFromDetails()
    click(OrderChatTestTags.BACK)
    compose.waitUntil(5_000) { subscriptions == 0 }
    compose.onNodeWithTag(OrderDetailsTestTags.CHAT).assertExists()
    openFromDetails()
    compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
    compose.waitUntil(5_000) { subscriptions == 0 }
    compose.onNodeWithTag(OrderDetailsTestTags.CHAT).assertExists()
    openFromDetails()
    compose.runOnIdle { auth.signOut() }
    compose.waitUntil(5_000) { subscriptions == 0 }
    compose.onNodeWithTag(OrderChatTestTags.SCREEN).assertDoesNotExist()
    assertEquals(CommandoScreens.OrderChat.route, "OrderChat/{orderId}")
    assertTrue(CommandoScreens.orderChatRoute(order.id).contains("%25"))
  }

  @Test
  fun commandoOpensTheSameOrderFromIncomingOrders() {
    show(context.trip.ownerId)
    click(AppTestTags.COMMANDO_MODE)
    click(AppTestTags.TRIPS_BUTTON)
    click(MyTripsTestTags.TRIP_CARD_PREFIX + context.trip.id)
    compose
        .onNodeWithText(compose.activity.getString(R.string.trip_details_view_orders))
        .performScrollTo()
        .performClick()
    click(IncomingOrdersTestTags.ORDER_CARD_PREFIX + order.id)
    openFromDetails()
    click(OrderChatTestTags.INFO)
    compose.waitUntil(5_000) { subscriptions == 0 }
    compose.onNodeWithTag(OrderDetailsTestTags.CHAT).assertExists()
  }
}
