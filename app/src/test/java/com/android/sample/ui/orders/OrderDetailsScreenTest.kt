package com.android.sample.ui.orders

// AI assistance: OpenAI Codex.

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.android.sample.data.repository.OrderError
import com.android.sample.data.repository.TripError
import com.android.sample.model.*
import com.android.sample.ui.theme.SampleAppTheme
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
class OrderDetailsScreenTest {
  @get:Rule val compose = createComposeRule()
  private lateinit var originalTimeZone: TimeZone
  private val time = Instant.parse("2026-10-13T16:30:00Z")
  private val trip =
      Trip(
          "trip",
          "commando",
          Location("Stored store", 46.52, 6.57),
          time,
          Location("Stored handoff", 46.51, 6.58),
          TripStatus.COMPLETED,
          time,
          time,
      )
  private val order =
      Order(
          "order",
          trip.id,
          "requester",
          listOf(
              OrderItem("milk", "Stored oat milk", 220, 2),
              OrderItem("bread", "Stored bread", 180, 3),
          ),
          405,
          OrderStatus.PENDING,
          time,
      )
  private val state = mutableStateOf<OrderDetailsUiState>(OrderDetailsUiState.Content(order, trip))
  private var backs = 0
  private var retries = 0

  @Before
  fun setUp() {
    originalTimeZone = TimeZone.getDefault()
    TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
  }

  @After
  fun tearDown() {
    TimeZone.setDefault(originalTimeZone)
  }

  private fun departure() =
      DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
          .withLocale(Locale.US)
          .withZone(ZoneId.of("UTC"))
          .format(time)

  private fun show(initial: OrderDetailsUiState = state.value, fontScale: Float? = null) {
    state.value = initial
    compose.setContent {
      val density = LocalDensity.current
      CompositionLocalProvider(
          LocalDensity provides Density(density.density, fontScale ?: density.fontScale)
      ) {
        SampleAppTheme {
          OrderDetailsScreen(state.value, onBack = { backs++ }, onRetry = { retries++ })
        }
      }
    }
  }

  private fun backWorks() {
    compose
        .onNodeWithContentDescription("Back")
        .assertIsDisplayed()
        .assertHasClickAction()
        .performClick()
    compose.runOnIdle { assertEquals(1, backs) }
  }

  private fun assertProtectedContentAbsent() {
    for (text in
        listOf(
            "Stored oat milk",
            "Stored bread",
            "Stored store",
            "Stored handoff",
            departure(),
            "CHF 4.05",
            "PENDING",
        )) {
      compose.onAllNodesWithText(text, substring = true).assertCountEquals(0)
    }
  }

  @Test
  fun contentDisplaysStoredFieldsAndHasOnlyBackAsAnAction() {
    show()
    for (text in
        listOf(
            "Stored store",
            departure(),
            "Stored handoff",
            "2 × Stored oat milk",
            "3 × Stored bread",
            "CHF 4.05",
        )) {
      compose.onNodeWithText(text).performScrollTo().assertIsDisplayed()
    }
    compose.onAllNodesWithText("PENDING").assertCountEquals(1)
    compose.onNodeWithText("PENDING").assertIsDisplayed()
    // Exhaustive action checks also exclude editing, Accept/Reject and submission controls.
    compose.onAllNodes(hasClickAction()).assertCountEquals(1)
    compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.SetText)).assertCountEquals(0)
    backWorks()
  }

  @Test
  fun loadingHasAccessibleProgressWithoutProtectedContent() {
    show(OrderDetailsUiState.Loading)
    compose
        .onNodeWithContentDescription("Loading order details")
        .assertIsDisplayed()
        .assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.ProgressBarRangeInfo,
                ProgressBarRangeInfo.Indeterminate,
            )
        )
        .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
    assertProtectedContentAbsent()
    compose.onNodeWithText("Retry").assertDoesNotExist()
    backWorks()
  }

  @Test
  fun notFoundOffersBackWithoutRetryOrProtectedContent() {
    show(OrderDetailsUiState.NotFound)
    compose.onNodeWithText("This order could not be found.").assertIsDisplayed()
    compose.onNodeWithText("Retry").assertDoesNotExist()
    assertProtectedContentAbsent()
    backWorks()
  }

  @Test
  fun orderFailureOffersWorkingRetryAndBack() {
    assertErrorActions(OrderDetailsUiState.Error.OrderFailure(OrderError.PermissionDenied))
  }

  @Test
  fun tripFailureOffersWorkingRetryAndBack() {
    assertErrorActions(OrderDetailsUiState.Error.TripFailure(TripError.NetworkError))
  }

  private fun assertErrorActions(error: OrderDetailsUiState.Error) {
    show(error)
    compose.onNodeWithText("Something went wrong while loading this order.").assertIsDisplayed()
    assertProtectedContentAbsent()
    compose.runOnIdle {
      assertEquals(0, retries)
      assertEquals(0, backs)
    }
    compose
        .onNodeWithText("Retry")
        .performScrollTo()
        .assertIsDisplayed()
        .assertIsEnabled()
        .performClick()
    compose.runOnIdle {
      assertEquals(1, retries)
      assertEquals(0, backs)
    }
    backWorks()
  }

  @Test
  fun switchingFromContentToNonContentStatesRemovesProtectedInformation() {
    show()
    for ((next, message) in
        listOf(
            OrderDetailsUiState.Loading to null,
            OrderDetailsUiState.NotFound to "This order could not be found.",
            OrderDetailsUiState.Error.OrderFailure(OrderError.PermissionDenied) to
                "Something went wrong while loading this order.",
        )) {
      compose.runOnIdle { state.value = OrderDetailsUiState.Content(order, trip) }
      compose.onNodeWithText("2 × Stored oat milk").performScrollTo().assertIsDisplayed()
      compose.onNodeWithText("Stored handoff").performScrollTo().assertIsDisplayed()
      compose.runOnIdle { state.value = next }
      assertProtectedContentAbsent()
      if (message != null) compose.onNodeWithText(message).performScrollTo().assertIsDisplayed()
      else
          compose
              .onNodeWithContentDescription("Loading order details")
              .performScrollTo()
              .assertIsDisplayed()
    }
    backWorks()
  }

  @Test
  @Config(qualifiers = "en-rUS-w320dp-h320dp-mdpi")
  fun longNamesAndLargeTextInShortViewportKeepContentAndBackReachable() {
    val name = "Stored wholegrain bread with sunflower seeds and a very long product description"
    val store = "Stored supermarket at the far end of the university shopping district"
    val handoff = "Stored handoff beside the main entrance of the university learning center"
    show(
        OrderDetailsUiState.Content(
            order.copy(items = listOf(OrderItem("long", name, 180, 12))),
            trip.copy(
                store = trip.store.copy(name = store),
                handoffLocation = trip.handoffLocation.copy(name = handoff),
            ),
        ),
        fontScale = 1.5f,
    )
    compose.onRoot().assertWidthIsEqualTo(320.dp).assertHeightIsEqualTo(320.dp)
    compose.onNodeWithContentDescription("Back").assertIsDisplayed()
    for (text in listOf(store, handoff)) {
      compose.onNodeWithText(text).performScrollTo().assertIsDisplayed()
    }
    compose.onNodeWithText("12 × $name").performScrollTo().assertIsDisplayed()
    compose.onNodeWithText("CHF 4.05").performScrollTo().assertIsDisplayed()
    backWorks()
  }

  @Test
  @Config(qualifiers = "en-rUS-w320dp-h320dp-mdpi")
  fun shortViewportKeepsErrorRetryAndBackReachable() {
    show(OrderDetailsUiState.Error.TripFailure(TripError.Unknown), fontScale = 1.5f)
    compose.onRoot().assertWidthIsEqualTo(320.dp).assertHeightIsEqualTo(320.dp)
    compose.onNodeWithContentDescription("Back").assertIsDisplayed()
    compose
        .onNodeWithText("Retry")
        .performScrollTo()
        .assertIsDisplayed()
        .assertIsEnabled()
        .performClick()
    compose.runOnIdle { assertEquals(1, retries) }
    backWorks()
  }
}
