package com.android.sample.ui.trips

// AI assistance: Claude Code.
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.sample.data.repository.TripError
import com.android.sample.data.repository.TripRepository
import com.android.sample.data.repository.TripResult
import com.android.sample.model.Location
import com.android.sample.model.NewTrip
import com.android.sample.model.Trip
import com.android.sample.model.TripStatus
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CreateTripScreenTest {

  @get:Rule val composeTestRule = createComposeRule()

  private var state by mutableStateOf(CreateTripUiState())
  private val events = mutableListOf<String>()

  @Test
  fun emptyForm_showsPlaceholdersAndCannotPublish() {
    setContent()

    composeTestRule.onNodeWithText("Publish a trip").assertExists()
    composeTestRule.onNodeWithText("Select date").assertExists()
    composeTestRule.onNodeWithText("Select time").assertExists()
    node(CreateTripScreenTestTags.PUBLISH_BUTTON).assertIsNotEnabled()
    (ERROR_TAGS + CreateTripScreenTestTags.ERROR_BANNER).forEach { node(it).assertDoesNotExist() }
  }

  @Test
  fun validationErrors_areShownUnderTheirFields() {
    state =
        CreateTripUiState(
            storeError = CreateTripError.STORE_REQUIRED,
            dateError = CreateTripError.DATE_REQUIRED,
            timeError = CreateTripError.TIME_REQUIRED,
            handoffLocationError = CreateTripError.HANDOFF_LOCATION_REQUIRED,
            maxOrdersError = CreateTripError.MAX_ORDERS_NOT_POSITIVE_INTEGER,
        )
    setContent()

    ERROR_TAGS.forEach { node(it).assertExists() }
  }

  @Test
  fun readyForm_showsTheValuesAndPublishes() {
    state = READY
    setContent()

    node(CreateTripScreenTestTags.STORE_INPUT).assertTextContains("Migros")
    node(CreateTripScreenTestTags.DATE_FIELD).assertTextContains("Today, 5 Oct")
    node(CreateTripScreenTestTags.TIME_FIELD).assertTextContains("17:30")
    node(CreateTripScreenTestTags.PUBLISH_BUTTON).assertIsEnabled().performClick()
    state = READY.copy(date = TODAY.plusDays(1))
    node(CreateTripScreenTestTags.DATE_FIELD).assertTextContains("Tomorrow, 6 Oct")
    state = READY.copy(date = TODAY.plusDays(2))
    node(CreateTripScreenTestTags.DATE_FIELD).assertTextContains("Wed, 7 Oct")

    composeTestRule.runOnIdle { assertEquals(listOf("publish"), events) }
  }

  @Test
  fun publishing_disablesTheWholeForm() {
    state = READY.copy(canPublish = false, isPublishing = true)
    setContent()

    listOf(
            CreateTripScreenTestTags.BACK_BUTTON,
            CreateTripScreenTestTags.STORE_INPUT,
            CreateTripScreenTestTags.DATE_FIELD,
            CreateTripScreenTestTags.TIME_FIELD,
            CreateTripScreenTestTags.HANDOFF_LOCATION_INPUT,
            CreateTripScreenTestTags.MAX_ORDERS_INPUT,
            CreateTripScreenTestTags.PUBLISH_BUTTON,
        )
        .forEach { node(it).assertIsNotEnabled() }
    node(CreateTripScreenTestTags.BACK_BUTTON).performClick()

    composeTestRule.runOnIdle { assertEquals(emptyList<String>(), events) }
  }

  @Test
  fun failedPublication_showsTheBannerAndKeepsTheInputs() {
    state = READY.copy(publishError = TripError.NetworkError)
    setContent()

    node(CreateTripScreenTestTags.ERROR_BANNER).assertExists()
    node(CreateTripScreenTestTags.HANDOFF_LOCATION_INPUT).assertTextContains("EPFL")
    node(CreateTripScreenTestTags.PUBLISH_BUTTON).assertTextContains("Try again")
  }

  @Test
  fun userActions_areForwarded() {
    state = READY.copy(store = "", handoffLocation = "", maxOrders = "")
    setContent()

    node(CreateTripScreenTestTags.STORE_INPUT).performTextInput("Coop")
    node(CreateTripScreenTestTags.HANDOFF_LOCATION_INPUT).performTextInput("Rolex Center")
    node(CreateTripScreenTestTags.MAX_ORDERS_INPUT).performTextInput("4")
    node(CreateTripScreenTestTags.BACK_BUTTON).performClick()

    composeTestRule.runOnIdle {
      assertEquals("Coop", state.store)
      assertEquals("Rolex Center", state.handoffLocation)
      assertEquals("4", state.maxOrders)
      assertEquals(listOf("back"), events)
    }
  }

  @Test
  fun screen_publishesThroughTheViewModelAndNotifiesOnce() {
    val published = mutableListOf<NewTrip>()
    val repository =
        object : TripRepository {
          override suspend fun publishTrip(newTrip: NewTrip): TripResult<Trip> {
            published += newTrip
            return TripResult.Success(TRIP)
          }

          override suspend fun getTripById(tripId: String) = error("not used")

          override suspend fun getUpcomingTrips() = error("not used")

          override suspend fun getMyTrips() = error("not used")
        }
    val viewModel = CreateTripViewModel(repository, Clock.fixed(NOW, ZONE)) { TRIP_REQUEST }
    val notified = mutableListOf<Trip>()
    composeTestRule.setContent { CreateTripScreen(viewModel, {}, { notified += it }) }
    composeTestRule.runOnIdle {
      viewModel.setDate(TODAY.plusDays(1))
      viewModel.setTime(LocalTime.of(18, 30))
    }
    node(CreateTripScreenTestTags.STORE_INPUT).performTextInput("Migros")
    node(CreateTripScreenTestTags.HANDOFF_LOCATION_INPUT).performTextInput("EPFL")
    node(CreateTripScreenTestTags.MAX_ORDERS_INPUT).performTextInput("3")

    // Drive the clock by hand so the snackbar can be observed before it times out.
    composeTestRule.mainClock.autoAdvance = false
    node(CreateTripScreenTestTags.PUBLISH_BUTTON).performClick()
    composeTestRule.mainClock.advanceTimeBy(1_000)
    composeTestRule.onNodeWithText("Trip published").assertExists()
    composeTestRule.mainClock.advanceTimeBy(10_000)
    composeTestRule.mainClock.autoAdvance = true

    composeTestRule.runOnIdle {
      assertEquals(listOf(TRIP_REQUEST), published)
      assertEquals(listOf(TRIP), notified)
    }
  }

  /**
   * Renders the content like the ViewModel would: text edits update [state], the rest is logged.
   */
  private fun setContent() {
    composeTestRule.setContent {
      CreateTripContent(
          state = state,
          onBack = { events += "back" },
          onStoreChange = { state = state.copy(store = it) },
          onDateSelected = { events += "date $it" },
          onTimeSelected = { events += "time $it" },
          onHandoffLocationChange = { state = state.copy(handoffLocation = it) },
          onMaxOrdersChange = { state = state.copy(maxOrders = it) },
          onPublish = { events += "publish" },
          today = TODAY,
      )
    }
  }

  /** Finds a node by tag and scrolls the form to it when it is inside the scrollable area. */
  private fun node(tag: String): SemanticsNodeInteraction =
      composeTestRule.onNodeWithTag(tag).also { runCatching { it.performScrollTo() } }

  private companion object {
    val ZONE: ZoneId = ZoneId.of("Europe/Zurich")
    val NOW: Instant = Instant.parse("2026-10-05T10:00:00Z")
    val TODAY: LocalDate = LocalDate.of(2026, 10, 5)

    val READY =
        CreateTripUiState(
            store = "Migros",
            date = TODAY,
            time = LocalTime.of(17, 30),
            handoffLocation = "EPFL",
            maxOrders = "3",
            canPublish = true,
        )

    val ERROR_TAGS =
        listOf(
            CreateTripScreenTestTags.STORE_ERROR,
            CreateTripScreenTestTags.DATE_ERROR,
            CreateTripScreenTestTags.TIME_ERROR,
            CreateTripScreenTestTags.HANDOFF_LOCATION_ERROR,
            CreateTripScreenTestTags.MAX_ORDERS_ERROR,
        )

    val TRIP_REQUEST =
        NewTrip(Location("Migros", 46.52, 6.57), Instant.EPOCH, Location("EPFL", 46.52, 6.57))
    val TRIP = TRIP_REQUEST.run {
      Trip("trip-1", "me", store, scheduledAt, handoffLocation, TripStatus.PUBLISHED, NOW, NOW)
    }
  }
}
