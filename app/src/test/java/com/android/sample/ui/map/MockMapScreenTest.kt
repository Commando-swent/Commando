package com.android.sample.ui.map

// AI assistance: OpenAI Codex.
import android.graphics.Bitmap
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.android.sample.ui.map.MockMapTestTags as Tags
import com.android.sample.ui.navigation.*
import com.android.sample.ui.theme.SampleAppTheme
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-rUS-w412dp-h917dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MockMapScreenTest {
  @get:Rule val compose = createComposeRule()

  private fun show(
      samples: List<MockMapTrip> = MockMapSamples.trips,
      initialStatus: MockMapStatus = MockMapStatus.Content,
      locationOff: Boolean = false,
      details: (String) -> Unit = {},
  ) {
    val viewModel = MockMapViewModel(samples, initialStatus, locationOff)
    compose.setContent {
      SampleAppTheme {
        AppScaffold(CommandoScreens.AvailableTrips, AppMode.Requester, {}, {}, {}) { padding ->
          MockMapScreen(viewModel, details, Modifier.padding(padding))
        }
      }
    }
  }

  @Test
  fun selectionShowsSampleInformationAndDetailsEmitsOnlyTheSelectedId() {
    val calls = mutableListOf<String>()
    show(details = { calls.add(it) })
    text("Prototype · sample trips only").assertIsDisplayed()
    tag(Tags.SUMMARY).assertDoesNotExist()
    text("+1").assertIsDisplayed()
    tag(Tags.MARKER_PREFIX + "sample-shared").assertDoesNotExist()
    capture("content")
    tag(Tags.MARKER_PREFIX + "sample-migros").performClick().assertIsSelected()
    text("Migros EPFL").assertIsDisplayed()
    text("Sample departure · 12:30").assertIsDisplayed()
    text("CM building").assertIsDisplayed()
    assertTrue(calls.isEmpty())
    capture("selected")
    tag(Tags.DETAILS).performClick()
    assertEquals(listOf("sample-migros"), calls)
    tag(Tags.MARKER_PREFIX + "sample-denner").performClick().assertIsSelected()
    text("Denner EPFL").assertIsDisplayed()
    text("Rolex Learning Center").assertIsDisplayed()
    tag(Tags.MARKER_PREFIX + "sample-migros").assertIsNotSelected()
    tag(Tags.DETAILS).performClick()
    assertEquals(listOf("sample-migros", "sample-denner"), calls)
    text("2 TRIPS AT THIS HANDOFF POINT").assertIsDisplayed()
    capture("shared")
    tag(Tags.PAGE_PREFIX + "sample-shared").performClick().assertIsSelected()
    compose
        .onNodeWithContentDescription("Sample trip: Migros EPFL at 18:15")
        .assertIsSelected()
        .performClick()
    tag(Tags.MARKER_PREFIX + "sample-shared").assertIsSelected()
    text("Sample departure · 18:15").assertIsDisplayed()
    tag(Tags.DETAILS).performClick()
    assertEquals(listOf("sample-migros", "sample-denner", "sample-shared"), calls)
    tag(Tags.PAGE_PREFIX + "sample-denner").performClick().assertIsSelected()
    text("Denner EPFL").assertIsDisplayed()
    tag(Tags.DETAILS).performClick()
    assertEquals(listOf("sample-migros", "sample-denner", "sample-shared", "sample-denner"), calls)
    compose.onNodeWithContentDescription("Close trip preview").performClick()
    tag(Tags.SUMMARY).assertDoesNotExist()
    tag(Tags.MARKER_PREFIX + "sample-denner").assertIsNotSelected()
  }

  @Test
  fun injectedEmptyDataShowsEmptyStateWithoutMarkersOrDetails() {
    show(emptyList())
    text("No trips on the map yet").assertIsDisplayed()
    tag(Tags.DETAILS).assertDoesNotExist()
    MockMapSamples.trips.forEach { tag(Tags.MARKER_PREFIX + it.id).assertDoesNotExist() }
    capture("empty")
  }

  @Test
  fun removedSampleDoesNotLeaveStaleDetailsVisible() {
    var state by mutableStateOf(MockMapUiState(selectedTripId = "sample-migros"))
    compose.setContent { SampleAppTheme { MockMapScreen(state, {}, {}, {}) } }
    text("Migros EPFL").assertIsDisplayed()
    compose.runOnIdle { state = state.copy(trips = emptyList()) }
    tag(Tags.SUMMARY).assertDoesNotExist()
    tag(Tags.EMPTY).assertIsDisplayed()
    compose.runOnIdle { state = state.copy(status = MockMapStatus.Loading) }
    tag(Tags.SUMMARY).assertDoesNotExist()
    tag(Tags.EMPTY).assertDoesNotExist()
    compose.runOnIdle { state = state.copy(status = MockMapStatus.Error) }
    tag(Tags.ERROR).assertIsDisplayed()
    tag(Tags.EMPTY).assertDoesNotExist()
    tag(Tags.RETRY).assertIsNotEnabled()
  }

  @Test
  @Config(qualifiers = "en-rUS-w640dp-h320dp-land-mdpi")
  fun detailsRemainReachableOnShortScreens() {
    show()
    tag(Tags.MARKER_PREFIX + "sample-market").performScrollTo().performClick()
    tag(Tags.DETAILS).performScrollTo().assertIsDisplayed()
  }

  @Test
  fun locationOffAllowOnlyRevealsTheSamplePosition() {
    show(locationOff = true)
    tag(Tags.LOCATION_OFF).assertIsDisplayed()
    tag(Tags.LOCATION).assertDoesNotExist()
    capture("location-off")
    tag(Tags.ALLOW).performClick()
    tag(Tags.LOCATION_OFF).assertDoesNotExist()
    compose
        .onNodeWithContentDescription("Sample position — not your device location")
        .assertIsDisplayed()
    tag(Tags.MARKER_PREFIX + "sample-market").performClick()
    text("Campus market").assertIsDisplayed()
  }

  @Test
  fun loadingShowsSkeletonsWithoutSelectableTripsOrEmptyMessage() {
    val sample = MockMapSamples.trips.first().copy(id = "injected-skeleton")
    show(
        samples = listOf(sample, sample.copy(id = "same-point")),
        initialStatus = MockMapStatus.Loading,
    )
    tag(Tags.SKELETON_PREFIX + sample.id).assertExists()
    tag(Tags.SKELETON_PREFIX + "same-point").assertDoesNotExist()
    tag(Tags.SKELETON_PREFIX + "sample-market").assertDoesNotExist()
    tag(Tags.LOADING).assertIsDisplayed()
    tag(Tags.EMPTY).assertDoesNotExist()
    tag(Tags.SUMMARY).assertDoesNotExist()
    MockMapSamples.trips.forEach { tag(Tags.MARKER_PREFIX + it.id).assertDoesNotExist() }
    capture("loading")
  }

  @Test
  fun errorRetryRestoresInjectedSamples() {
    val sample = MockMapSamples.trips.first().copy(id = "retry-sample", store = "Retry shop")
    var details: String? = null
    show(samples = listOf(sample), initialStatus = MockMapStatus.Error) { details = it }
    tag(Tags.ERROR).assertIsDisplayed()
    tag(Tags.MARKER_PREFIX + sample.id).assertDoesNotExist()
    capture("error")
    tag(Tags.RETRY).performClick()
    tag(Tags.ERROR).assertDoesNotExist()
    tag(Tags.MARKER_PREFIX + sample.id).performClick()
    text("Retry shop").assertIsDisplayed()
    tag(Tags.DETAILS).performClick()
    assertEquals(sample.id, details)
  }

  private fun tag(value: String) = compose.onNodeWithTag(value)

  private fun text(value: String) = compose.onNodeWithText(value)

  private fun capture(name: String) {
    val file = File("build/reports/mock-map/$name.png").apply { parentFile!!.mkdirs() }
    val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
    file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
  }
}
