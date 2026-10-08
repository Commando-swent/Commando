package com.android.sample.ui.trips

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.android.sample.data.repository.TripError
import com.android.sample.model.Location
import com.android.sample.model.Trip
import com.android.sample.model.TripStatus
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
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
class TripDetailsScreenTest {
  @get:Rule val compose = createComposeRule()
  private lateinit var originalTimeZone: TimeZone
  private val departure = Instant.parse("2026-10-06T13:00:00Z")
  private val trip =
      Trip(
          id = "migros-trip",
          ownerId = "owner",
          store = Location("Migros", 46.52, 6.63),
          scheduledAt = departure,
          handoffLocation = Location("EPFL", 46.52, 6.57),
          status = TripStatus.PUBLISHED,
          createdAt = departure,
          updatedAt = departure,
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

  private fun show(
      state: TripDetailsUiState,
      onRetry: () -> Unit = {},
      onBack: () -> Unit = {},
      onAddItems: ((String) -> Unit)? = {},
      largeText: Boolean = false,
  ) {
    compose.setContent {
      val density = LocalDensity.current
      CompositionLocalProvider(
          LocalDensity provides if (largeText) Density(density = 1f, fontScale = 2f) else density
      ) {
        MaterialTheme {
          TripDetailsScreen(
              state = state,
              onRetry = onRetry,
              onBack = onBack,
              onAddItems = onAddItems,
          )
        }
      }
    }
  }

  @Test
  fun loadingDisplaysProgressAndBackInvokesCallbackOnce() {
    var backs = 0
    show(TripDetailsUiState.Loading, onBack = { backs++ })
    compose.onNodeWithText("Loading trip details").assertIsDisplayed()
    compose
        .onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
        .assertIsDisplayed()
    compose.runOnIdle { assertEquals(0, backs) }
    compose.onNodeWithText("Back").assertIsDisplayed().assertHasClickAction().performClick()
    compose.runOnIdle { assertEquals(1, backs) }
  }

  @Test
  fun contentDisplaysTripDetailsAndAddItemsInvokesCallbackWithExactId() {
    val addedTripIds = mutableListOf<String>()
    show(TripDetailsUiState.Content(trip), onAddItems = { addedTripIds.add(it) })
    compose.onNodeWithText("Migros").assertIsDisplayed()
    val expectedDeparture =
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
            .withLocale(Locale.US)
            .withZone(ZoneId.of("UTC"))
            .format(departure)
    compose.onNodeWithText(expectedDeparture).assertIsDisplayed()
    compose.onNodeWithText("EPFL").assertIsDisplayed()
    compose.runOnIdle { assertEquals(emptyList<String>(), addedTripIds) }
    compose
        .onNodeWithText("Add items to this run")
        .assertIsDisplayed()
        .assertTextEquals("Add items to this run")
        .assertIsEnabled()
        .assertHasClickAction()
        .performClick()
    compose.runOnIdle { assertEquals(listOf(trip.id), addedTripIds) }
  }

  @Test
  fun publishedTripWithoutAddItemsCallbackShowsDisabledCta() {
    show(TripDetailsUiState.Content(trip), onAddItems = null)
    compose
        .onNodeWithText("Add items to this run")
        .assertIsDisplayed()
        .assertIsNotEnabled()
        .performTouchInput { click() }
    compose.onNodeWithText("Migros").assertIsDisplayed()
    compose.onNodeWithText("Add items to this run").assertIsNotEnabled()
  }

  @Test
  fun inProgressAddItemsIsEnabledAndInvokesCallbackWithExactId() {
    val addedTripIds = mutableListOf<String>()
    show(
        TripDetailsUiState.Content(trip.copy(status = TripStatus.IN_PROGRESS)),
        onAddItems = { addedTripIds.add(it) },
    )
    compose.runOnIdle { assertEquals(emptyList<String>(), addedTripIds) }
    compose
        .onNodeWithText("Add items to this run")
        .assertIsDisplayed()
        .assertIsEnabled()
        .performClick()
    compose.runOnIdle { assertEquals(listOf(trip.id), addedTripIds) }
  }

  @Test
  fun completedAddItemsIsDisabledAndDoesNotInvokeCallback() {
    assertAddItemsDisabled(TripStatus.COMPLETED)
  }

  @Test
  fun cancelledAddItemsIsDisabledAndDoesNotInvokeCallback() {
    assertAddItemsDisabled(TripStatus.CANCELLED)
  }

  private fun assertAddItemsDisabled(status: TripStatus) {
    val addedTripIds = mutableListOf<String>()
    show(
        TripDetailsUiState.Content(trip.copy(status = status)),
        onAddItems = { addedTripIds.add(it) },
    )
    compose
        .onNodeWithText("Add items to this run")
        .assertIsDisplayed()
        .assertIsNotEnabled()
        .performTouchInput { click() }
    compose.runOnIdle { assertEquals(emptyList<String>(), addedTripIds) }
  }

  @Test
  fun notFoundDisplaysExactMessageWithoutRetryAndBackWorks() {
    var backs = 0
    var retries = 0
    show(
        TripDetailsUiState.Error(TripError.NotFound),
        onRetry = { retries++ },
        onBack = { backs++ },
    )
    compose
        .onNodeWithText("This trip could not be found.")
        .assertIsDisplayed()
        .assertTextEquals("This trip could not be found.")
    compose.onNodeWithText("Retry").assertDoesNotExist()
    compose.runOnIdle {
      assertEquals(0, backs)
      assertEquals(0, retries)
    }
    compose.onNodeWithText("Back").assertIsDisplayed().assertHasClickAction().performClick()
    compose.runOnIdle {
      assertEquals(1, backs)
      assertEquals(0, retries)
    }
  }

  @Test
  fun otherErrorDisplaysExactMessageAndRetryAndBackWork() {
    var retries = 0
    var backs = 0
    show(
        TripDetailsUiState.Error(TripError.NetworkError),
        onRetry = { retries++ },
        onBack = { backs++ },
    )
    compose
        .onNodeWithText("Something went wrong while loading this trip.")
        .assertIsDisplayed()
        .assertTextEquals("Something went wrong while loading this trip.")
    compose.runOnIdle {
      assertEquals(0, retries)
      assertEquals(0, backs)
    }
    compose.onNodeWithText("Retry").assertIsDisplayed().assertHasClickAction().performClick()
    compose.runOnIdle {
      assertEquals(1, retries)
      assertEquals(0, backs)
    }
    compose.onNodeWithText("Back").assertIsDisplayed().assertHasClickAction().performClick()
    compose.runOnIdle {
      assertEquals(1, retries)
      assertEquals(1, backs)
    }
  }

  @Test
  @Config(qualifiers = "en-rUS-w640dp-h320dp-land-mdpi")
  fun contentAddItemsIsReachableInShortViewportWithLargeText() {
    val addedTripIds = mutableListOf<String>()
    show(
        TripDetailsUiState.Content(trip),
        onAddItems = { addedTripIds.add(it) },
        largeText = true,
    )
    compose.onRoot().assertWidthIsEqualTo(640.dp).assertHeightIsEqualTo(320.dp)
    compose.onNodeWithText("Back").assertIsDisplayed().assertHasClickAction()
    compose.runOnIdle { assertEquals(emptyList<String>(), addedTripIds) }
    val addItems = compose.onNodeWithText("Add items to this run")
    addItems.assertIsNotDisplayed()
    addItems.performScrollTo().assertIsDisplayed().assertHasClickAction().performClick()
    compose.runOnIdle { assertEquals(listOf(trip.id), addedTripIds) }
    compose.onNodeWithText("Back").assertIsDisplayed().assertHasClickAction()
  }

  @Test
  @Config(qualifiers = "en-rUS-w640dp-h320dp-land-mdpi")
  fun errorRetryIsReachableInShortViewportWithLargeText() {
    var retries = 0
    show(
        TripDetailsUiState.Error(TripError.Unknown),
        onRetry = { retries++ },
        largeText = true,
    )
    compose.onRoot().assertWidthIsEqualTo(640.dp).assertHeightIsEqualTo(320.dp)
    compose.onNodeWithText("Back").assertIsDisplayed().assertHasClickAction()
    compose.runOnIdle { assertEquals(0, retries) }
    val retry = compose.onNodeWithText("Retry")
    retry.performScrollTo().assertIsDisplayed().assertHasClickAction().performClick()
    compose.runOnIdle { assertEquals(1, retries) }
    compose.onNodeWithText("Back").assertIsDisplayed().assertHasClickAction()
  }
}
