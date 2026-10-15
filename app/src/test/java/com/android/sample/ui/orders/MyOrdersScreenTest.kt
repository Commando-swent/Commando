package com.android.sample.ui.orders

// AI assistance: Claude (Anthropic).
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.android.sample.data.repository.FakeOrderRepository
import com.android.sample.data.repository.FakeTripRepository
import com.android.sample.data.repository.OrderError
import com.android.sample.model.Location
import com.android.sample.model.Order
import com.android.sample.model.OrderItem
import com.android.sample.model.OrderStatus
import com.android.sample.model.Trip
import com.android.sample.model.TripStatus
import com.android.sample.ui.theme.SampleAppTheme
import com.android.sample.ui.trips.formatTripDeparture
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-rUS")
class MyOrdersScreenTest {
  @get:Rule val compose = createComposeRule()
  private lateinit var originalTimeZone: TimeZone
  private val created = Instant.parse("2026-10-01T08:00:00Z")
  private val migrosDeparture = Instant.parse("2026-10-06T13:00:00Z")
  private val coopDeparture = Instant.parse("2026-10-07T17:45:00Z")
  private val migrosTrip = trip("migros-trip", "Migros EPFL", migrosDeparture, "Rolex Center")
  private val coopTrip = trip("coop-trip", "Coop Ecublens", coopDeparture, "BC Building")

  // Products total CHF 6.20, commission CHF 4.50: the row must show the commission.
  private val aliceMigros =
      order(
          "order-a1",
          "migros-trip",
          "alice",
          listOf(OrderItem("milk", "Milk", 165, 2), OrderItem("bread", "Bread", 290, 1)),
          450,
          created,
      )
  private val aliceCoop =
      order(
          "order-a2",
          "coop-trip",
          "alice",
          listOf(OrderItem("apple", "Apple", 80, 1)),
          205,
          created.minusSeconds(60),
      )
  private val bobMigros =
      order(
          "order-b1",
          "migros-trip",
          "bob",
          listOf(OrderItem("eggs", "Eggs", 400, 4)),
          1000,
          created,
      )

  @Before
  fun setUp() {
    originalTimeZone = TimeZone.getDefault()
    TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
  }

  @After
  fun tearDown() {
    TimeZone.setDefault(originalTimeZone)
  }

  private fun trip(id: String, store: String, at: Instant, handoff: String) =
      Trip(
          id = id,
          ownerId = "commando",
          store = Location(store, 46.52, 6.57),
          scheduledAt = at,
          handoffLocation = Location(handoff, 46.52, 6.57),
          status = TripStatus.PUBLISHED,
          createdAt = created,
          updatedAt = created,
      )

  private fun order(
      id: String,
      tripId: String,
      requester: String,
      items: List<OrderItem>,
      commission: Int,
      createdAt: Instant,
  ) = Order(id, tripId, requester, items, commission, OrderStatus.PENDING, createdAt)

  private fun departure(at: Instant) = formatTripDeparture(at, Locale.US, ZoneId.of("UTC"))

  private fun show(
      state: MyOrdersUiState,
      onRetry: () -> Unit = {},
      onOrderClick: (String) -> Unit = {},
      onBrowseTrips: () -> Unit = {},
  ) {
    compose.setContent {
      SampleAppTheme { MyOrdersScreen(state, onRetry, onOrderClick, onBrowseTrips) }
    }
  }

  @Test
  fun loadingShowsThreeSkeletonCardsWithDescription() {
    show(MyOrdersUiState.Loading)
    compose
        .onNodeWithTag(MyOrdersTestTags.LOADING)
        .assertIsDisplayed()
        .assertContentDescriptionEquals("Loading your orders")
    assertEquals(
        3,
        compose.onNodeWithTag(MyOrdersTestTags.LOADING).fetchSemanticsNode().children.size,
    )
    compose.onNodeWithText("My Orders").assertIsDisplayed()
    compose.onAllNodesWithText("PENDING").assertCountEquals(0)
  }

  @Test
  fun contentRowShowsStoreDepartureHandoffItemsCommissionAndStatus() {
    show(MyOrdersUiState.Content(listOf(MyOrderRow(aliceMigros, migrosTrip))))
    val card = MyOrdersTestTags.ORDER_CARD_PREFIX + "order-a1"
    compose.onNodeWithTag(card).assertIsDisplayed()
    // The clickable card merges its descendants, so its texts are asserted on the card node.
    fun inCard(text: String) = compose.onNodeWithTag(card).assert(hasText(text))
    inCard("Migros EPFL")
    inCard(departure(migrosDeparture))
    inCard("Handoff: Rolex Center")
    inCard("3 items · CHF 4.50")
    inCard("PENDING")
    compose.onAllNodesWithText("CHF 6.20", substring = true).assertCountEquals(0)
    compose.onAllNodesWithText("Trip unavailable").assertCountEquals(0)
  }

  @Test
  fun singleItemUsesSingularAndPadsCents() {
    show(MyOrdersUiState.Content(listOf(MyOrderRow(aliceCoop, coopTrip))))
    compose.onNodeWithText("1 item · CHF 2.05").assertIsDisplayed()
    compose.onNodeWithText("Coop Ecublens").assertIsDisplayed()
    compose.onNodeWithText(departure(coopDeparture)).assertIsDisplayed()
    compose.onNodeWithText("Handoff: BC Building").assertIsDisplayed()
  }

  @Test
  fun missingTripKeepsRowVisibleWithoutHandoff() {
    show(MyOrdersUiState.Content(listOf(MyOrderRow(aliceMigros, null))))
    compose.onNodeWithTag(MyOrdersTestTags.ORDER_CARD_PREFIX + "order-a1").assertIsDisplayed()
    compose.onNodeWithText("Trip unavailable").assertIsDisplayed()
    compose.onNodeWithText("3 items · CHF 4.50").assertIsDisplayed()
    compose.onNodeWithText("PENDING").assertIsDisplayed()
    compose.onAllNodesWithText("Handoff:", substring = true).assertCountEquals(0)
    compose.onAllNodesWithText(departure(migrosDeparture)).assertCountEquals(0)
  }

  @Test
  fun clickingRowsReportsEachRowsOrderId() {
    val selected = mutableListOf<String>()
    var retries = 0
    var browses = 0
    show(
        MyOrdersUiState.Content(
            listOf(MyOrderRow(aliceMigros, migrosTrip), MyOrderRow(aliceCoop, coopTrip))
        ),
        onRetry = { retries++ },
        onOrderClick = { selected += it },
        onBrowseTrips = { browses++ },
    )
    compose.onNodeWithTag(MyOrdersTestTags.ORDER_CARD_PREFIX + "order-a2").performClick()
    assertEquals(listOf("order-a2"), selected)
    compose.onNodeWithTag(MyOrdersTestTags.ORDER_CARD_PREFIX + "order-a1").performClick()
    assertEquals(listOf("order-a2", "order-a1"), selected)
    assertEquals(0, retries)
    assertEquals(0, browses)
  }

  @Test
  fun emptyShowsMessageHelpAndBrowseTrips() {
    var browses = 0
    var retries = 0
    show(MyOrdersUiState.Empty, onRetry = { retries++ }, onBrowseTrips = { browses++ })
    compose.onNodeWithTag(MyOrdersTestTags.EMPTY).assertIsDisplayed()
    compose.onNodeWithText("No orders yet.").assertIsDisplayed()
    compose
        .onNodeWithText("Orders you place on a trip will appear here with their status.")
        .assertIsDisplayed()
    compose.onNodeWithTag(MyOrdersTestTags.BROWSE_TRIPS).assertTextEquals("Browse trips")
    compose.onNodeWithTag(MyOrdersTestTags.BROWSE_TRIPS).performClick()
    assertEquals(1, browses)
    assertEquals(0, retries)
    compose.onAllNodesWithTag(MyOrdersTestTags.RETRY).assertCountEquals(0)
  }

  @Test
  fun errorShowsMessageAndRetry() {
    var retries = 0
    var browses = 0
    show(
        MyOrdersUiState.Error(OrderError.Unknown),
        onRetry = { retries++ },
        onBrowseTrips = { browses++ },
    )
    compose.onNodeWithTag(MyOrdersTestTags.ERROR).assertIsDisplayed()
    compose.onNodeWithText("Something went wrong while loading your orders.").assertIsDisplayed()
    compose.onNodeWithTag(MyOrdersTestTags.RETRY).assertTextEquals("Retry").performClick()
    assertEquals(1, retries)
    assertEquals(0, browses)
    compose.onAllNodesWithTag(MyOrdersTestTags.BROWSE_TRIPS).assertCountEquals(0)
  }

  private fun showInShortViewportWithLargeText(
      state: MyOrdersUiState,
      height: Int,
      onRetry: () -> Unit,
      onBrowseTrips: () -> Unit,
  ) {
    compose.setContent {
      CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
        SampleAppTheme { MyOrdersScreen(state, onRetry, {}, onBrowseTrips) }
      }
    }
    compose.onRoot().assertWidthIsEqualTo(640.dp).assertHeightIsEqualTo(height.dp)
  }

  // The error card has no help text, so Retry still fits at h320dp; h240dp makes it overflow.
  @Test
  @Config(qualifiers = "en-rUS-w640dp-h240dp-land-mdpi")
  fun errorRetryIsReachableInShortViewportWithLargeText() {
    var retries = 0
    var browses = 0
    showInShortViewportWithLargeText(
        MyOrdersUiState.Error(OrderError.Unknown),
        height = 240,
        onRetry = { retries++ },
        onBrowseTrips = { browses++ },
    )
    val retry = compose.onNodeWithTag(MyOrdersTestTags.RETRY)
    retry.assertIsNotDisplayed()
    retry.performScrollTo().assertIsDisplayed().assertHasClickAction().performClick()
    compose.runOnIdle {
      assertEquals(1, retries)
      assertEquals(0, browses)
    }
  }

  @Test
  @Config(qualifiers = "en-rUS-w640dp-h320dp-land-mdpi")
  fun emptyBrowseTripsIsReachableInShortViewportWithLargeText() {
    var retries = 0
    var browses = 0
    showInShortViewportWithLargeText(
        MyOrdersUiState.Empty,
        height = 320,
        onRetry = { retries++ },
        onBrowseTrips = { browses++ },
    )
    val browse = compose.onNodeWithTag(MyOrdersTestTags.BROWSE_TRIPS)
    browse.assertIsNotDisplayed()
    browse.performScrollTo().assertIsDisplayed().assertHasClickAction().performClick()
    compose.runOnIdle {
      assertEquals(1, browses)
      assertEquals(0, retries)
    }
  }

  @Test
  fun connectedScreenShowsRequesterOrdersAndSwitchesOnRequesterChange() {
    var session = "alice"
    val viewModel =
        MyOrdersViewModel(
            FakeOrderRepository({ session }, listOf(aliceMigros, aliceCoop, bobMigros)),
            FakeTripRepository("commando", initialTrips = listOf(migrosTrip, coopTrip)),
            "alice",
        )
    var requester by mutableStateOf("alice")
    compose.setContent {
      SampleAppTheme { MyOrdersScreen(viewModel, requester, onOrderClick = {}, onBrowseTrips = {}) }
    }
    compose.waitForIdle()
    compose.onNodeWithTag(MyOrdersTestTags.ORDER_CARD_PREFIX + "order-a1").assertIsDisplayed()
    compose.onNodeWithTag(MyOrdersTestTags.ORDER_CARD_PREFIX + "order-a2").assertIsDisplayed()
    compose.onNodeWithText("3 items · CHF 4.50").assertIsDisplayed()
    compose.onNodeWithText("1 item · CHF 2.05").assertIsDisplayed()
    compose.onNodeWithText("Coop Ecublens").assertIsDisplayed()
    compose.onAllNodesWithTag(MyOrdersTestTags.ORDER_CARD_PREFIX + "order-b1").assertCountEquals(0)

    compose.runOnIdle {
      session = "bob"
      requester = "bob"
    }
    compose.waitForIdle()
    compose.onAllNodesWithTag(MyOrdersTestTags.ORDER_CARD_PREFIX + "order-a1").assertCountEquals(0)
    compose.onAllNodesWithTag(MyOrdersTestTags.ORDER_CARD_PREFIX + "order-a2").assertCountEquals(0)
    compose.onAllNodesWithText("Coop Ecublens").assertCountEquals(0)
    compose.onNodeWithTag(MyOrdersTestTags.ORDER_CARD_PREFIX + "order-b1").assertIsDisplayed()
    compose.onNodeWithText("4 items · CHF 10.00").assertIsDisplayed()
    compose.onNodeWithText("Migros EPFL").assertIsDisplayed()
    compose.onNodeWithText("Handoff: Rolex Center").assertIsDisplayed()
  }

  @Test
  fun switchingRequesterNeverDrawsPreviousRequesterRows() {
    var session = "alice"
    val viewModel =
        MyOrdersViewModel(
            FakeOrderRepository({ session }, listOf(aliceMigros, aliceCoop, bobMigros)),
            FakeTripRepository("commando", initialTrips = listOf(migrosTrip, coopTrip)),
            "alice",
        )
    var requester by mutableStateOf("alice")
    val composedFor = mutableListOf<String>()
    compose.setContent {
      val current = requester
      SideEffect { composedFor += current }
      SampleAppTheme { MyOrdersScreen(viewModel, current, onOrderClick = {}, onBrowseTrips = {}) }
    }
    compose.waitForIdle()
    compose.onNodeWithTag(MyOrdersTestTags.ORDER_CARD_PREFIX + "order-a1").assertIsDisplayed()

    compose.mainClock.autoAdvance = false
    compose.runOnUiThread {
      session = "bob"
      requester = "bob"
    }
    // First frame for the new requester, before the LaunchedEffect's setRequester has published.
    Snapshot.sendApplyNotifications()
    compose.mainClock.advanceTimeByFrame()
    // Proves this frame already composed with the new requester.
    assertEquals("bob", composedFor.last())
    compose.onAllNodesWithTag(MyOrdersTestTags.ORDER_CARD_PREFIX + "order-a1").assertCountEquals(0)
    compose.onAllNodesWithTag(MyOrdersTestTags.ORDER_CARD_PREFIX + "order-a2").assertCountEquals(0)
    compose.onAllNodesWithText("Coop Ecublens").assertCountEquals(0)
    compose.onNodeWithTag(MyOrdersTestTags.LOADING).assertIsDisplayed()

    compose.mainClock.autoAdvance = true
    compose.waitForIdle()
    compose.onAllNodesWithTag(MyOrdersTestTags.LOADING).assertCountEquals(0)
    compose.onNodeWithTag(MyOrdersTestTags.ORDER_CARD_PREFIX + "order-b1").assertIsDisplayed()
    compose.onNodeWithText("4 items · CHF 10.00").assertIsDisplayed()
    compose.onNodeWithText("Handoff: Rolex Center").assertIsDisplayed()
    compose.onAllNodesWithTag(MyOrdersTestTags.ORDER_CARD_PREFIX + "order-a1").assertCountEquals(0)
  }
}
