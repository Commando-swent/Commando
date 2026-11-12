package com.android.sample.ui.navigation

// AI assistance: OpenAI Codex.
import android.Manifest
import android.app.Application
import android.content.Context
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.credentials.Credential
import androidx.test.core.app.ApplicationProvider
import com.android.sample.data.location.DeviceLocationProvider
import com.android.sample.data.location.DeviceLocationResult
import com.android.sample.data.repository.*
import com.android.sample.model.*
import com.android.sample.model.authentication.AuthUser
import com.android.sample.model.authentication.FakeAuthRepository
import com.android.sample.ui.auth.GoogleCredentialClient
import com.android.sample.ui.home.HomeTestTags
import com.android.sample.ui.map.MockMapTestTags
import com.android.sample.ui.map.OrderTrackingTestTags
import com.android.sample.ui.map.PositionSharingTestTags
import com.android.sample.ui.map.TripMapTestTags
import com.android.sample.ui.orders.MyOrdersTestTags
import com.android.sample.ui.orders.OrderDetailsTestTags
import com.android.sample.ui.theme.SampleAppTheme
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
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

  private fun show(
      tripRepository: TripRepository = trips,
      positions: CommandoPositionRepository? = null,
      source: DeviceLocationProvider? = null,
  ) {
    content = {
      SampleAppTheme {
        CommandoApp(
            auth,
            tripRepository,
            credentials,
            orderRepositoryFactory = factory,
            positionRepositoryFactory = positions?.let { { _ -> it } },
            locationProvider = source,
        )
      }
    }
    compose.setContent(content)
  }

  private fun click(tag: String) = compose.onNodeWithTag(tag).performClick()

  private fun back() = compose.runOnIdle {
    compose.activity.onBackPressedDispatcher.onBackPressed()
  }

  private fun card() = compose.onNodeWithTag(MyOrdersTestTags.ORDER_CARD_PREFIX + order.id)

  @Test
  fun failedCleanupBlocksSignOutAndRoleExitRequiresFreshSharingConsent() {
    shadowOf(ApplicationProvider.getApplicationContext<Application>())
        .grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
    val storage = MutableStateFlow<Map<String, CommandoPosition>>(emptyMap())
    val transport = FakeCommandoPositionRepository(MutableStateFlow("alice"), positions = storage)
    var failDelete = true
    val positions =
        object : CommandoPositionRepository by transport {
          override suspend fun stopSharing() {
            check(!failDelete)
            transport.stopSharing()
          }
        }
    val owned = FakeTripRepository("alice", initialTrips = listOf(trip.copy(id = "sharing-trip")))
    val source = DeviceLocationProvider {
      DeviceLocationResult.Available(
          DeviceLocation(46.521, 6.566, 20f, SystemClock.elapsedRealtime())
      )
    }
    show(owned, positions, source)
    click(AppTestTags.COMMANDO_MODE)
    compose.onNodeWithTag(PositionSharingTestTags.SHARING_TOGGLE).performScrollTo().performClick()
    compose.waitUntil(5_000) { storage.value.isNotEmpty() }
    click(AppTestTags.PROFILE_BUTTON)
    click(NavigationTestTags.SIGN_OUT_BUTTON)
    compose.waitForIdle()
    assertNotNull(auth.currentUser)
    assertTrue(storage.value.isNotEmpty())
    compose.runOnIdle { failDelete = false }
    click(AppTestTags.REQUESTER_MODE)
    compose.waitUntil(5_000) { storage.value.isEmpty() }
    click(AppTestTags.COMMANDO_MODE)
    click(AppTestTags.HOME_BUTTON)
    compose
        .onNodeWithTag(PositionSharingTestTags.SHARING_TOGGLE)
        .performScrollTo()
        .assertIsOff()
        .performClick()
    compose.waitUntil(5_000) { storage.value.isNotEmpty() }
    click(AppTestTags.PROFILE_BUTTON)
    // The previous cleanup-error snackbar can overlap this button; activate its accessible action.
    compose.onNodeWithTag(NavigationTestTags.SIGN_OUT_BUTTON).performSemanticsAction(
        SemanticsActions.OnClick
    ) {
      it()
    }
    compose.waitUntil(5_000) { auth.currentUser == null }
    assertTrue(storage.value.isEmpty())
  }

  @Test
  fun selectedOrderOpensItsCommandoAndMarkerDisappearsWhenSharingStops() {
    val fixes =
        MutableStateFlow<CommandoPosition?>(CommandoPosition(46.521, 6.566, 30.0, Instant.now()))
    var witness: Pair<String, String?>? = null
    val positions =
        object : CommandoPositionRepository {
          override suspend fun publish(tripId: String, position: CommandoPosition) = Unit

          override suspend fun stopSharing() = Unit

          override fun observe(ownerId: String, orderId: String?) = flow {
            witness = ownerId to orderId
            emitAll(fixes)
          }
        }
    show(
        object : TripRepository by trips {
          override suspend fun getTripById(tripId: String) =
              TripResult.Success(trip.copy(ownerId = "commando"))
        },
        positions,
    )
    click(AppTestTags.ORDERS_BUTTON)
    card().performClick()
    click(OrderDetailsTestTags.TRACK)
    compose.onNodeWithTag(OrderTrackingTestTags.MARKER).assertExists()
    assertEquals("commando" to order.id, witness)
    compose.runOnIdle { fixes.value = null }
    compose.onNodeWithTag(OrderTrackingTestTags.MARKER).assertDoesNotExist()
    back()
    compose.onNodeWithTag(OrderDetailsTestTags.TRACK).assertExists()
    compose.runOnIdle { assertEquals(0, fixes.subscriptionCount.value) }
  }

  @Test
  fun ordersOpenExactOpaqueIdAndBothBackActionsReturnToTheListWithoutDuplicates() {
    show()
    repeat(2) { click(AppTestTags.ORDERS_BUTTON) }
    card().assertIsDisplayed()
    card().performClick()
    compose.onNodeWithText("Order details").assertIsDisplayed()
    compose.onNodeWithText("Milk", substring = true).assertExists()
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
    compose.onNodeWithTag(com.android.sample.ui.auth.WelcomeTestTags.SCREEN).assertIsDisplayed()
    auth.signInWithEmailResult = Result.success(alice)
    auth.signInWithEmail("", "")
    click(AppTestTags.ORDERS_BUTTON)
    compose.onNodeWithTag(MyOrdersTestTags.EMPTY).assertIsDisplayed()
    assertEquals(2, factoryCalls)
  }

  @Test
  fun emptyMapHasNoSyntheticMarkersOrTripLookupsAndReturnsToList() {
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
    compose.onNodeWithTag(TripMapTestTags.STATUS).assertIsDisplayed()
    compose.onNodeWithTag(MockMapTestTags.MARKER_PREFIX + "sample-migros").assertDoesNotExist()
    compose.onNodeWithTag(MockMapTestTags.DETAILS).assertDoesNotExist()
    assertTrue(lookups.isEmpty())
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
  fun ordersLoadOnceOnEntryAndRefreshOnceOnReturn() {
    var reads = 0
    decorate = { delegate ->
      object : OrderRepository by delegate {
        override suspend fun getMyOrders(): OrderResult<List<Order>> {
          reads++
          return delegate.getMyOrders()
        }
      }
    }
    show()
    click(AppTestTags.ORDERS_BUTTON)
    card().assertIsDisplayed()
    compose.runOnIdle { assertEquals(1, reads) }
    card().performClick()
    compose.onNodeWithText("Order details").assertIsDisplayed()
    back()
    card().assertIsDisplayed()
    compose.runOnIdle { assertEquals(2, reads) }
  }

  @Test
  fun receiptCallbackUsesExistingDetailsRouteAndShowsConfirmedDelivery() {
    val ready = order.copy(status = OrderStatus.READY_FOR_HANDOFF)
    val confirmedIds = mutableListOf<String>()
    decorate = { delegate ->
      object : OrderRepository by delegate {
        override suspend fun getOrderById(orderId: String) = OrderResult.Success(ready)

        override suspend fun confirmReceipt(orderId: String): OrderResult<Order> {
          confirmedIds += orderId
          return OrderResult.Success(ready.copy(status = OrderStatus.DELIVERED))
        }
      }
    }
    show()
    click(AppTestTags.ORDERS_BUTTON)
    card().performClick()
    compose.onNodeWithTag(OrderDetailsTestTags.CONFIRM_RECEIPT).performScrollTo().performClick()
    compose.onNodeWithText("DELIVERED").assertIsDisplayed()
    compose.onNodeWithTag(OrderDetailsTestTags.CONFIRM_RECEIPT).assertDoesNotExist()
    compose.runOnIdle { assertEquals(listOf(order.id), confirmedIds) }
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
