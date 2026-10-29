package com.android.sample.ui.navigation

// AI assistance: Claude Code.
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.credentials.Credential
import com.android.sample.data.repository.FakeOrderRepository
import com.android.sample.data.repository.FakeTripRepository
import com.android.sample.data.repository.OrderRepository
import com.android.sample.model.Location
import com.android.sample.model.Order
import com.android.sample.model.OrderItem
import com.android.sample.model.OrderStatus
import com.android.sample.model.Trip
import com.android.sample.model.TripStatus
import com.android.sample.model.authentication.AuthUser
import com.android.sample.model.authentication.FakeAuthRepository
import com.android.sample.ui.auth.GoogleCredentialClient
import com.android.sample.ui.orders.IncomingOrdersTestTags
import com.android.sample.ui.theme.SampleAppTheme
import com.android.sample.ui.trips.MyTripsTestTags
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-rUS-w412dp-h917dp-mdpi")
class IncomingOrdersNavigationTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
  private val auth = FakeAuthRepository(AuthUser("alice"))
  private val time = Instant.parse("2030-01-01T12:00:00Z")
  private val trip =
      Trip(
          "trip-1",
          "alice",
          Location("Migros EPFL", 46.5, 6.5),
          time,
          Location("EPFL", 46.5, 6.5),
          TripStatus.PUBLISHED,
          time,
          time,
      )
  // Placed by bob on alice's trip, so alice can decide on it.
  private val order =
      Order(
          "order-1",
          trip.id,
          "bob",
          listOf(OrderItem("milk", "Milk", 220, 2)),
          150,
          OrderStatus.PENDING,
          time,
      )
  private val trips =
      FakeTripRepository("alice", now = { time.minusSeconds(60) }, initialTrips = listOf(trip))
  private val credentials =
      object : GoogleCredentialClient {
        override suspend fun request(context: Context): Credential? = null

        override suspend fun clearSession() {}
      }
  private var sessions = 0
  // Each signed-in session gets its own store; only the first one holds bob's order.
  private val factory: (() -> String?) -> OrderRepository = { userId ->
    FakeOrderRepository(
        userId,
        initialOrders = if (sessions++ == 0) listOf(order) else emptyList(),
        tripRepository = trips,
    )
  }

  private fun show() {
    compose.setContent {
      SampleAppTheme { CommandoApp(auth, trips, credentials, orderRepositoryFactory = factory) }
    }
  }

  private fun click(tag: String) = compose.onNodeWithTag(tag).performClick()

  private fun back() = compose.runOnIdle {
    compose.activity.onBackPressedDispatcher.onBackPressed()
  }

  private fun card() = compose.onNodeWithTag(IncomingOrdersTestTags.ORDER_CARD_PREFIX + order.id)

  /** My Trips → Trip Details → incoming orders, as the owner in Commando mode. */
  private fun openIncomingOrders() {
    show()
    click(AppTestTags.COMMANDO_MODE)
    click(AppTestTags.TRIPS_BUTTON)
    click(MyTripsTestTags.TRIP_CARD_PREFIX + trip.id)
    compose.onNodeWithText("View orders").performScrollTo().performClick()
  }

  @Test
  fun ownerOpensIncomingOrdersThenDetailsAndBackReturnsToTheSameList() {
    openIncomingOrders()
    compose.onNodeWithText("Incoming orders").assertIsDisplayed()
    card().assertIsDisplayed().assert(hasText("PENDING")).performClick()
    compose.onNodeWithText("Order details").assertIsDisplayed()
    back()
    card().assertIsDisplayed()
    click(AppTestTags.BACK_BUTTON)
    compose.onNodeWithText("View orders").assertExists()
  }

  @Test
  fun decisionInOrderDetailsAppearsWhenReturningToTheList() {
    openIncomingOrders()
    card().performClick()
    compose.onNodeWithText("Accept").performScrollTo().performClick()
    compose.onNodeWithText("Order status updated.").assertExists()
    back()
    card().assert(hasText("ACCEPTED"))
  }

  @Test
  fun requesterModeDoesNotOfferTheTripOrders() {
    show()
    click(AppTestTags.TRIPS_BUTTON)
    compose.onNodeWithText(trip.store.name).performClick()
    compose.onNodeWithText("Add items to this run").assertExists()
    compose.onNodeWithText("View orders").assertDoesNotExist()
  }

  @Test
  fun accountChangeClearsThePreviousAccountsIncomingOrders() {
    openIncomingOrders()
    card().assertIsDisplayed()
    compose.runOnIdle { auth.signInWithEmailResult = Result.success(AuthUser("bob")) }
    // The fake only swaps its session, so this call does not suspend.
    runBlocking { auth.signInWithEmail("", "") }
    compose.onNodeWithTag(NavigationTestTags.HOME_SCREEN).assertIsDisplayed()
    card().assertDoesNotExist()
    assertEquals(2, sessions)
  }
}
