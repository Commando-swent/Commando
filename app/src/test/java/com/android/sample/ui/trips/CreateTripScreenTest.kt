package com.android.sample.ui.trips

// AI assistance: Claude Code.
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.sample.data.repository.FakeTripRepository
import com.android.sample.data.repository.TripError
import com.android.sample.data.repository.TripResult
import com.android.sample.model.Trip
import com.android.sample.model.TripLocations
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
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
    node(CreateTripScreenTestTags.STORE_FIELD).assertTextContains("Select a store")
    node(CreateTripScreenTestTags.DATE_FIELD).assertTextContains("Select date")
    node(CreateTripScreenTestTags.TIME_FIELD).assertTextContains("Select time")
    node(CreateTripScreenTestTags.HANDOFF_LOCATION_FIELD)
        .assertTextContains("Select a meeting point")
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
        )
    setContent()

    ERROR_TAGS.forEach { node(it).assertExists() }
  }

  @Test
  fun readyForm_showsTheValuesAndPublishes() {
    state = READY
    setContent()

    node(CreateTripScreenTestTags.STORE_FIELD).assertTextContains(STORE.name)
    node(CreateTripScreenTestTags.HANDOFF_LOCATION_FIELD).assertTextContains(HANDOFF.name)
    node(CreateTripScreenTestTags.DATE_FIELD).assertTextContains("Today, 5 Oct")
    node(CreateTripScreenTestTags.TIME_FIELD).assertTextContains("17:30")
    node(CreateTripScreenTestTags.PUBLISH_BUTTON).assertIsEnabled().performClick()
    state = READY.copy(date = TODAY.plusDays(1))
    node(CreateTripScreenTestTags.DATE_FIELD).assertTextContains("Tomorrow, 6 Oct")
    state = READY.copy(date = TODAY.plusDays(2))
    node(CreateTripScreenTestTags.DATE_FIELD).assertTextContains("Wed, 7 Oct")
    node(CreateTripScreenTestTags.BACK_BUTTON).performClick()

    composeTestRule.runOnIdle { assertEquals(listOf("publish", "back"), events) }
  }

  @Test
  fun publishing_disablesTheWholeForm() {
    state = READY.copy(canPublish = false, isPublishing = true)
    setContent()

    listOf(
            CreateTripScreenTestTags.BACK_BUTTON,
            CreateTripScreenTestTags.STORE_FIELD,
            CreateTripScreenTestTags.DATE_FIELD,
            CreateTripScreenTestTags.TIME_FIELD,
            CreateTripScreenTestTags.HANDOFF_LOCATION_FIELD,
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
    node(CreateTripScreenTestTags.STORE_FIELD).assertTextContains(STORE.name)
    node(CreateTripScreenTestTags.HANDOFF_LOCATION_FIELD).assertTextContains(HANDOFF.name)
    node(CreateTripScreenTestTags.PUBLISH_BUTTON).assertTextContains("Try again")
  }

  @Test
  fun screen_confirmsThePublicationBeforeLeaving() {
    val repository = FakeTripRepository(currentUserId = "me", now = { NOW })
    val viewModel = CreateTripViewModel(repository, Clock.fixed(NOW, ZONE))
    val notified = mutableListOf<Trip>()
    var onScreen by mutableStateOf(true)
    // Like navigation would, the callback removes the screen.
    composeTestRule.setContent {
      if (onScreen) {
        CreateTripScreen(
            viewModel,
            {},
            {
              notified += it
              onScreen = false
            },
        )
      }
    }
    composeTestRule.runOnIdle {
      viewModel.setStore(STORE)
      viewModel.setDate(TODAY.plusDays(1))
      viewModel.setTime(LocalTime.of(18, 30))
      viewModel.setHandoffLocation(HANDOFF)
    }

    // Drive the clock by hand so the snackbar can be observed before it times out.
    composeTestRule.mainClock.autoAdvance = false
    node(CreateTripScreenTestTags.PUBLISH_BUTTON).performClick()
    composeTestRule.mainClock.advanceTimeBy(1_000)
    composeTestRule.onNodeWithText("Trip published").assertExists()
    assertEquals(emptyList<Trip>(), notified)
    composeTestRule.mainClock.advanceTimeBy(10_000)
    composeTestRule.mainClock.autoAdvance = true

    composeTestRule.runOnIdle {
      val stored = runBlocking { (repository.getMyTrips() as TripResult.Success).data }
      assertEquals(listOf(viewModel.uiState.value.publishedTrip), stored)
      assertEquals(stored, notified)
    }
    composeTestRule.onNodeWithTag(CreateTripScreenTestTags.PUBLISH_BUTTON).assertDoesNotExist()
  }

  /** Renders the content like the ViewModel would and logs the forwarded actions. */
  private fun setContent() {
    composeTestRule.setContent {
      CreateTripContent(
          state = state,
          onBack = { events += "back" },
          onStoreSelected = { events += "store ${it.name}" },
          onDateSelected = { events += "date $it" },
          onTimeSelected = { events += "time $it" },
          onHandoffLocationSelected = { events += "handoff ${it.name}" },
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
    val STORE = TripLocations.stores[0]
    val HANDOFF = TripLocations.handoffPoints[0]

    val READY =
        CreateTripUiState(
            store = STORE,
            date = TODAY,
            time = LocalTime.of(17, 30),
            handoffLocation = HANDOFF,
            canPublish = true,
        )

    val ERROR_TAGS =
        listOf(
            CreateTripScreenTestTags.STORE_ERROR,
            CreateTripScreenTestTags.DATE_ERROR,
            CreateTripScreenTestTags.TIME_ERROR,
            CreateTripScreenTestTags.HANDOFF_LOCATION_ERROR,
        )
  }
}
