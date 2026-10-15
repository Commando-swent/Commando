package com.android.sample.ui.navigation

// AI assistance: OpenAI Codex.
import android.content.Context
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.credentials.Credential
import com.android.sample.data.repository.*
import com.android.sample.model.*
import com.android.sample.model.authentication.AuthUser
import com.android.sample.model.authentication.FakeAuthRepository
import com.android.sample.ui.auth.GoogleCredentialClient
import com.android.sample.ui.home.HomeTestTags
import com.android.sample.ui.map.MockMapTestTags
import com.android.sample.ui.orders.MyOrdersTestTags
import com.android.sample.ui.theme.SampleAppTheme
import java.io.File
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h917dp-mdpi")
class OrderNavigationTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
  private val alice = AuthUser("alice", displayName = "Alice", email = "alice@example.com")
  private val auth = FakeAuthRepository(alice)
  private val time = Instant.parse("2030-01-01T12:00:00Z")
  private val trip =
      Trip(
          "trip / % 雪",
          "alice",
          Location("Migros EPFL", 46.5, 6.5),
          time,
          Location("EPFL", 46.5, 6.5),
          TripStatus.IN_PROGRESS,
          time,
          time,
      )
  private val order =
      Order(
          "order / % 雪",
          trip.id,
          "alice",
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
  private var factoryCalls = 0
  private lateinit var orders: FakeOrderRepository
  private var decorate: (OrderRepository) -> OrderRepository = { it }
  private val factory: (() -> String?) -> OrderRepository = { userId ->
    factoryCalls++
    orders =
        FakeOrderRepository(
            userId,
            initialOrders = if (factoryCalls == 1) listOf(order) else emptyList(),
        )
    decorate(orders)
  }

  private lateinit var content: @Composable () -> Unit

  private fun show(tripRepository: TripRepository = trips) {
    content = { SampleAppTheme { CommandoApp(auth, tripRepository, credentials, factory) } }
    compose.setContent(content)
  }

  private fun click(tag: String) = compose.onNodeWithTag(tag).performClick()

  private fun back() = compose.runOnIdle {
    compose.activity.onBackPressedDispatcher.onBackPressed()
  }

  private fun capture(name: String) {
    val file = File("build/reports/integration130/$name.png").apply { parentFile?.mkdirs() }
    file.outputStream().use {
      compose
          .onRoot()
          .captureToImage()
          .asAndroidBitmap()
          .compress(Bitmap.CompressFormat.PNG, 100, it)
    }
  }

  private fun card() = compose.onNodeWithTag(MyOrdersTestTags.ORDER_CARD_PREFIX + order.id)

  @Test
  fun ordersOpenExactOpaqueIdAndBothBackActionsReturnToTheListWithoutDuplicates() {
    show()
    repeat(2) { click(AppTestTags.ORDERS_BUTTON) }
    card().assertIsDisplayed()
    capture("my-orders")
    card().performClick()
    compose.onNodeWithText("Order details").assertIsDisplayed()
    compose.onNodeWithText("Milk", substring = true).assertExists()
    capture("order-details")
    compose.onNodeWithContentDescription("Back").performClick()
    card().assertIsDisplayed().performClick()
    back()
    card().assertIsDisplayed()
    assertEquals(1, factoryCalls)
    back()
    compose.onNodeWithTag(NavigationTestTags.HOME_SCREEN).assertIsDisplayed()
  }

  @Test
  fun listRefreshesOnReturnAndRepositorySurvivesActivityRecreation() = runTest {
    show()
    click(AppTestTags.ORDERS_BUTTON)
    card().assertIsDisplayed()
    click(AppTestTags.PROFILE_BUTTON)
    val saved =
        orders.createOrder(OrderDraft(trip.id, listOf(OrderItem("apple", "Apple", 100, 1))))
            as OrderResult.Success
    back()
    compose.onNodeWithTag(MyOrdersTestTags.ORDER_CARD_PREFIX + saved.data.id).assertExists()
    compose.activityRule.scenario.recreate()
    compose.activityRule.scenario.onActivity { activity -> activity.setContent(content = content) }
    compose.onNodeWithTag(MyOrdersTestTags.ORDER_CARD_PREFIX + saved.data.id).assertExists()
    assertEquals(1, factoryCalls)
  }

  @Test
  fun accountChangeDiscardsOrdersAndDelayedDetailsCannotAppearInNewSession() = runTest {
    val late = CompletableDeferred<OrderResult<Order>>()
    decorate = { delegate ->
      object : OrderRepository by delegate {
        override suspend fun getOrderById(orderId: String): OrderResult<Order> = late.await()
      }
    }
    show()
    click(AppTestTags.ORDERS_BUTTON)
    card().performClick()
    compose.onNodeWithText("Order details").assertIsDisplayed()
    compose.runOnIdle { auth.signInWithEmailResult = Result.success(AuthUser("bob")) }
    auth.signInWithEmail("", "")
    compose.onNodeWithTag(NavigationTestTags.HOME_SCREEN).assertIsDisplayed()
    late.complete(OrderResult.Success(order))
    click(AppTestTags.ORDERS_BUTTON)
    compose.onNodeWithTag(MyOrdersTestTags.EMPTY).assertIsDisplayed()
    card().assertDoesNotExist()
    compose.onNodeWithText("Milk", substring = true).assertDoesNotExist()
    assertEquals(2, factoryCalls)
    back()
    compose.onNodeWithTag(NavigationTestTags.HOME_SCREEN).assertIsDisplayed()
  }

  @Test
  fun signOutAndSameAccountSignInCreateAFreshOrderStore() = runTest {
    show()
    click(AppTestTags.ORDERS_BUTTON)
    card().assertIsDisplayed()
    click(AppTestTags.PROFILE_BUTTON)
    click(NavigationTestTags.SIGN_OUT_BUTTON)
    compose.onNodeWithTag(NavigationTestTags.LOGIN_SCREEN).assertIsDisplayed()
    auth.signInWithEmailResult = Result.success(alice)
    auth.signInWithEmail("", "")
    click(AppTestTags.ORDERS_BUTTON)
    compose.onNodeWithTag(MyOrdersTestTags.EMPTY).assertIsDisplayed()
    assertEquals(2, factoryCalls)
  }

  @Test
  fun mapDoesNotSendSyntheticMarkerIdsToTheTripRepositoryAndReturnsToList() {
    val lookups = mutableListOf<String>()
    show(
        object : TripRepository by trips {
          override suspend fun getTripById(tripId: String): TripResult<Trip> {
            lookups.add(tripId)
            return trips.getTripById(tripId)
          }
        }
    )
    click(AppTestTags.TRIPS_BUTTON)
    click(NavigationTestTags.OPEN_MAP)
    click(MockMapTestTags.MARKER_PREFIX + "sample-migros")
    compose.onNodeWithTag(MockMapTestTags.DETAILS).performScrollTo().assertIsNotEnabled()
    assertTrue(lookups.isEmpty())
    capture("mock-map")
    click(NavigationTestTags.MAP_LIST)
    compose.onNodeWithTag(NavigationTestTags.OPEN_MAP).assertIsDisplayed()
    back()
    compose.onNodeWithTag(NavigationTestTags.HOME_SCREEN).assertIsDisplayed()
  }

  @Test
  fun emptyOrdersBrowseTripsAndTabSwitchingDoNotDuplicateDestinations() {
    factoryCalls = 1 // Start this session without seeded orders.
    show()
    click(AppTestTags.ORDERS_BUTTON)
    click(MyOrdersTestTags.BROWSE_TRIPS)
    compose.onNodeWithTag(NavigationTestTags.OPEN_MAP).assertIsDisplayed()
    click(AppTestTags.ORDERS_BUTTON)
    compose.onNodeWithTag(MyOrdersTestTags.EMPTY).assertIsDisplayed()
    back()
    compose.onNodeWithTag(NavigationTestTags.HOME_SCREEN).assertIsDisplayed()
    click(AppTestTags.COMMANDO_MODE)
    compose.onNodeWithTag(AppTestTags.ORDERS_BUTTON).assertIsNotEnabled()
  }

  @Test
  fun repositoryFailureIsVisibleAndRetryUsesTheSameSessionStore() {
    show()
    compose.runOnIdle { orders.forcedError = OrderError.Unknown }
    click(AppTestTags.ORDERS_BUTTON)
    compose.onNodeWithTag(MyOrdersTestTags.ERROR).assertIsDisplayed()
    compose.runOnIdle { orders.forcedError = null }
    click(MyOrdersTestTags.RETRY)
    card().assertIsDisplayed()
    assertEquals(1, factoryCalls)
  }

  @Test
  fun commandoCanOpenHisPublishedTripFromHomeAndReturn() {
    show()
    click(AppTestTags.COMMANDO_MODE)
    click(HomeTestTags.MANAGE_TRIP)
    compose.onNodeWithText("Migros EPFL").assertIsDisplayed()
    compose.onNodeWithText("Add items to this run").assertIsNotEnabled()
    back()
    compose.onNodeWithTag(HomeTestTags.CURRENT_TRIP).assertIsDisplayed()
    compose.onNodeWithTag(AppTestTags.COMMANDO_MODE).assertIsSelected()
  }
}
