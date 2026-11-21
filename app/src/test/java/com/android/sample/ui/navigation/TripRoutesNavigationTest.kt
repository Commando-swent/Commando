package com.android.sample.ui.navigation

// AI assistance: Claude Code (characterization tests for trip routes, #322; owner earnings, #301).
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.credentials.Credential
import androidx.test.core.app.ApplicationProvider
import com.android.sample.R
import com.android.sample.ui.theme.SampleAppTheme
import com.commando.app.data.repository.FakeOrderRepository
import com.commando.app.data.repository.FakeRatingRepository
import com.commando.app.data.repository.FakeTripRepository
import com.commando.app.data.repository.OrderRepository
import com.commando.app.data.repository.OrderResult
import com.commando.app.data.repository.SavedTripSearchRepositoryLocal
import com.commando.app.model.Location
import com.commando.app.model.Order
import com.commando.app.model.OrderItem
import com.commando.app.model.OrderStatus
import com.commando.app.model.StoreFilter
import com.commando.app.model.Trip
import com.commando.app.model.TripSearchCriteria
import com.commando.app.model.authentication.AuthUser
import com.commando.app.model.authentication.FakeAuthRepository
import com.commando.app.ui.auth.GoogleCredentialClient
import com.commando.app.ui.home.HomeTestTags
import com.commando.app.ui.orders.IncomingOrdersTestTags
import com.commando.app.ui.trips.AvailableTripsScreenTestTags
import com.commando.app.ui.trips.CreateTripScreenTestTags
import com.commando.app.ui.trips.MyTripsTestTags
import com.commando.app.ui.trips.TripEarningsTestTags
import com.commando.app.ui.trips.TripOwnerTestTags
import com.commando.app.ui.trips.TripSearchTestTags
import com.commando.app.ui.trips.discoveryTrip
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Pins the trip destinations' behavior through the public [CommandoApp] entry point. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rUS-w320dp-h800dp-mdpi")
class TripRoutesNavigationTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
  private val time = Instant.parse("2030-01-01T12:00:00Z")
  private val migros =
      discoveryTrip(
          "migros",
          Location("Migros Ecublens", 46.52, 6.63),
          Location("First handoff", 46.52, 6.57),
          time,
          "alice",
      )
  private val coop = migros.copy(id = "coop", store = Location("Coop Renens", 46.53, 6.64))
  // Same chain as the preset, but over 1 km from CE building, so only the point hides it.
  private val farCoop = coop.copy(id = "far-coop", handoffLocation = Location("Far", 46.53, 6.58))
  private val carolsCoop = coop.copy(id = "carols-coop", ownerId = "carol")

  private val coopOrder =
      Order(
          "order-coop",
          coop.id,
          "bob",
          listOf(OrderItem("milk", "Milk", 220, 1)),
          150,
          OrderStatus.PENDING,
          time,
      )

  private val readyOrder =
      coopOrder.copy(id = "order-ready", status = OrderStatus.READY_FOR_HANDOFF)

  /** Trip IDs of every orders-by-trip query, i.e. every earnings or incoming-orders load. */
  private val tripQueries = mutableListOf<String>()
  // Lets a test act as the requester on the same repository the app uses.
  private var actingUser: String? = null
  private lateinit var orderRepository: FakeOrderRepository

  private val credentials =
      object : GoogleCredentialClient {
        override suspend fun request(context: Context): Credential? = null

        override suspend fun clearSession() {}
      }

  private fun show(trips: FakeTripRepository) {
    val presets =
        SavedTripSearchRepositoryLocal(
            ApplicationProvider.getApplicationContext(),
            { "alice" },
            ioDispatcher = Dispatchers.Unconfined,
        )
    runBlocking { presets.saveSearch("Coop only", TripSearchCriteria(StoreFilter.COOP)) }
    val orders =
        listOf(
            coopOrder,
            coopOrder.copy(id = "order-migros", tripId = migros.id),
            coopOrder.copy(id = "order-delivered", status = OrderStatus.DELIVERED),
            readyOrder,
        )
    compose.setContent {
      SampleAppTheme {
        CommandoApp(
            FakeAuthRepository(AuthUser("alice")),
            trips,
            credentials,
            orderRepositoryFactory = { session ->
              val fake =
                  FakeOrderRepository({ actingUser ?: session() }, orders, tripRepository = trips)
              orderRepository = fake
              object : OrderRepository by fake {
                override suspend fun getOrdersForTrip(tripId: String): OrderResult<List<Order>> {
                  tripQueries += tripId
                  return fake.getOrdersForTrip(tripId)
                }
              }
            },
            savedSearchRepositoryFactory = { presets },
            ratingRepositoryFactory = { FakeRatingRepository(it, FakeOrderRepository(it), trips) },
        )
      }
    }
  }

  private fun trips(vararg trips: Trip) =
      FakeTripRepository("alice", now = { time.minusSeconds(60) }, initialTrips = trips.toList())

  private fun click(tag: String) = compose.onNodeWithTag(tag).performClick()

  private fun scrollTo(tag: String) {
    compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag(tag))
  }

  private fun scrollAndClick(tag: String) {
    scrollTo(tag)
    click(tag)
  }

  private fun string(id: Int, vararg args: Any) = compose.activity.getString(id, *args)

  private fun addItems() =
      compose.onNodeWithText(string(R.string.trip_details_add_items)).performScrollTo()

  private fun earnings() = compose.onNodeWithTag(TripEarningsTestTags.AMOUNT)

  private fun openCoopAsOwner() {
    click(AppTestTags.HOME_BUTTON)
    click(AppTestTags.COMMANDO_MODE)
    click(AppTestTags.TRIPS_BUTTON)
    click(MyTripsTestTags.TRIP_CARD_PREFIX + coop.id)
  }

  private fun viewOrders() = compose.onNodeWithText(string(R.string.trip_details_view_orders))

  private fun confirmDialog() =
      compose.onNodeWithText(string(R.string.create_trip_dialog_ok)).performClick()

  private fun openCoopAsRequester() {
    scrollAndClick(AvailableTripsScreenTestTags.tripCard(coop.id))
    addItems().assertIsEnabled()
    viewOrders().assertDoesNotExist()
    compose.onNodeWithTag(TripOwnerTestTags.TRANSITION).assertDoesNotExist()
    compose.onNodeWithTag(TripEarningsTestTags.CARD).assertDoesNotExist()
  }

  @Test
  fun modeDecidesTripDetailsActionsAndViewOrdersOpensTheSelectedTrip() {
    show(trips(migros, coop))
    click(AppTestTags.TRIPS_BUTTON)
    openCoopAsRequester()
    click(AppTestTags.BACK_BUTTON)

    openCoopAsOwner()
    compose.onNodeWithTag(TripOwnerTestTags.TRANSITION).assertExists()
    // Only the delivered order's commission counts, not the pending or ready ones.
    earnings().assertTextEquals("CHF 1.50")
    // The add action stays on screen but cannot be used outside Requester mode.
    addItems().assertIsNotEnabled()
    viewOrders().performScrollTo().performClick()
    compose.onNodeWithTag(IncomingOrdersTestTags.ORDER_CARD_PREFIX + coopOrder.id).assertExists()
    compose
        .onNodeWithTag(IncomingOrdersTestTags.ORDER_CARD_PREFIX + "order-migros")
        .assertDoesNotExist()
    click(AppTestTags.BACK_BUTTON)
    viewOrders().assertExists()
    click(AppTestTags.BACK_BUTTON)

    click(AppTestTags.HOME_BUTTON)
    click(AppTestTags.REQUESTER_MODE)
    click(AppTestTags.TRIPS_BUTTON)
    openCoopAsRequester()
  }

  @Test
  fun earningsReloadWhenTheOwnerComesBackToTripDetails() {
    show(trips(migros, coop))
    openCoopAsOwner()
    earnings().assertTextEquals("CHF 1.50")
    viewOrders().performScrollTo().performClick()
    // The requester confirms receipt while the owner is away from Trip Details.
    actingUser = readyOrder.requesterId
    runBlocking { orderRepository.confirmReceipt(readyOrder.id) }
    actingUser = null
    click(AppTestTags.BACK_BUTTON)
    earnings().assertTextEquals("CHF 3.00")
  }

  @Test
  fun earningsAreQueriedOnlyForTheOwnerInCommandoMode() {
    show(trips(migros, coop, carolsCoop))
    click(AppTestTags.TRIPS_BUTTON)
    // Requester mode, on the user's own trip and on someone else's.
    openCoopAsRequester()
    click(AppTestTags.BACK_BUTTON)
    scrollAndClick(AvailableTripsScreenTestTags.tripCard(carolsCoop.id))
    addItems().assertIsEnabled()
    compose.onNodeWithTag(TripEarningsTestTags.CARD).assertDoesNotExist()
    compose.runOnIdle { assertEquals(emptyList<String>(), tripQueries) }
    click(AppTestTags.BACK_BUTTON)
    openCoopAsOwner()
    earnings().assertTextEquals("CHF 1.50")
    compose.runOnIdle { assertEquals(listOf(coop.id), tripQueries) }
  }

  @Test
  fun appliedPresetAndDeliveryPointSurviveTripDetails() {
    show(trips(migros, coop, farCoop))
    click(HomeTestTags.TRIP_ACTION)
    scrollAndClick(AvailableTripsScreenTestTags.LOCATION_FIELD)
    click(CreateTripScreenTestTags.locationOption("CE building"))
    scrollAndClick(TripSearchTestTags.TOGGLE)
    compose.onNodeWithText("Coop only").performScrollTo().performClick()
    val active =
        string(
            R.string.trips_filters_active,
            string(R.string.trips_store_coop),
        )
    val assertFiltered = {
      scrollTo(TripSearchTestTags.TOGGLE)
      compose.onNodeWithTag(TripSearchTestTags.TOGGLE).assert(hasText(active))
      scrollTo(AvailableTripsScreenTestTags.LOCATION_FIELD)
      compose
          .onNodeWithTag(AvailableTripsScreenTestTags.LOCATION_FIELD)
          .assert(hasText("CE building"))
      scrollTo(AvailableTripsScreenTestTags.tripCard(coop.id))
      for (hidden in listOf(migros, farCoop)) {
        compose.onNodeWithTag(AvailableTripsScreenTestTags.tripCard(hidden.id)).assertDoesNotExist()
      }
    }
    assertFiltered()
    click(AvailableTripsScreenTestTags.tripCard(coop.id))
    addItems().assertIsEnabled()
    click(AppTestTags.BACK_BUTTON)
    assertFiltered()
  }

  @Test
  fun publishingFromCreateTripReturnsHomeShowingTheNewTrip() {
    show(FakeTripRepository("alice")) // Real clock, like the creation form.
    click(AppTestTags.COMMANDO_MODE)
    click(HomeTestTags.TRIP_ACTION)
    click(CreateTripScreenTestTags.STORE_FIELD)
    click(CreateTripScreenTestTags.locationOption("Coop Ecublens"))
    click(CreateTripScreenTestTags.HANDOFF_LOCATION_FIELD)
    click(CreateTripScreenTestTags.locationOption("CE building"))
    click(CreateTripScreenTestTags.DATE_FIELD)
    // Any day of next month is in the future; the default 12:00 time is then valid too.
    compose.onNodeWithContentDescription("Change to next month").performClick()
    compose.onNode(hasText(" 15, ", substring = true) and hasClickAction()).performClick()
    confirmDialog()
    click(CreateTripScreenTestTags.TIME_FIELD)
    confirmDialog()
    click(CreateTripScreenTestTags.PUBLISH_BUTTON)
    compose.onNodeWithText(string(R.string.create_trip_published)).assertExists()
    // Virtual time: Home is reached once the short confirmation snackbar has timed out.
    compose.mainClock.advanceTimeBy(5_000)
    compose.onNodeWithTag(HomeTestTags.CURRENT_TRIP).assertIsDisplayed()
    compose.onNodeWithText("Coop Ecublens").assertIsDisplayed()
    compose.onNodeWithTag(CreateTripScreenTestTags.STORE_FIELD).assertDoesNotExist()
    compose.onNodeWithTag(AppTestTags.COMMANDO_MODE).assertIsSelected()
  }
}
