package com.commando.app.ui.components

// AI assistance: Claude Code (#393) wrote these banner tests.
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import com.android.sample.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ConnectivityBannerTest {
  @get:Rule val compose = createComposeRule()
  private var online by mutableStateOf(true)

  private fun text(id: Int) =
      ApplicationProvider.getApplicationContext<android.content.Context>().getString(id)

  private fun show(initial: Boolean) {
    online = initial
    compose.mainClock.autoAdvance = false
    compose.setContent { ConnectivityBanner(online) }
    compose.mainClock.advanceTimeByFrame()
  }

  private fun switchTo(value: Boolean) {
    online = value
    // Apply the state change, then run the frames that start the effect and show its state.
    compose.waitForIdle()
    compose.mainClock.advanceTimeByFrame()
    compose.mainClock.advanceTimeByFrame()
  }

  private fun assertNoBanner() {
    compose.onNodeWithTag(ConnectivityBannerTestTags.OFFLINE).assertDoesNotExist()
    compose.onNodeWithTag(ConnectivityBannerTestTags.BACK_ONLINE).assertDoesNotExist()
  }

  @Test
  fun onlineFromTheStartShowsNothing() {
    show(initial = true)
    compose.mainClock.advanceTimeBy(BACK_ONLINE_VISIBLE_MS * 2)
    assertNoBanner()
  }

  @Test
  fun offlineShowsTitleAndBodyInAPoliteLiveRegion() {
    show(initial = false)
    compose
        .onNodeWithTag(ConnectivityBannerTestTags.OFFLINE)
        .assertIsDisplayed()
        .assert(hasText(text(R.string.connectivity_offline_title)))
        .assert(hasText(text(R.string.connectivity_offline_body)))
        .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
  }

  @Test
  fun backOnlineStaysForItsWindowThenDisappears() {
    show(initial = false)
    switchTo(true)
    compose.onNodeWithTag(ConnectivityBannerTestTags.OFFLINE).assertDoesNotExist()
    compose
        .onNodeWithTag(ConnectivityBannerTestTags.BACK_ONLINE)
        .assertIsDisplayed()
        .assert(hasText(text(R.string.connectivity_back_online_title)))
        .assert(hasText(text(R.string.connectivity_back_online_body)))
        .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
    compose.mainClock.advanceTimeBy(BACK_ONLINE_VISIBLE_MS - 100)
    compose.onNodeWithTag(ConnectivityBannerTestTags.BACK_ONLINE).assertIsDisplayed()
    compose.mainClock.advanceTimeBy(200)
    assertNoBanner()
  }

  @Test
  fun goingOfflineDuringTheBackOnlineWindowShowsOfflineImmediately() {
    show(initial = false)
    switchTo(true)
    compose.mainClock.advanceTimeBy(1_000)
    switchTo(false)
    compose.onNodeWithTag(ConnectivityBannerTestTags.BACK_ONLINE).assertDoesNotExist()
    compose.onNodeWithTag(ConnectivityBannerTestTags.OFFLINE).assertIsDisplayed()
    // The cancelled window must not hide the offline banner or bring "Back online" back.
    compose.mainClock.advanceTimeBy(BACK_ONLINE_VISIBLE_MS * 2)
    compose.onNodeWithTag(ConnectivityBannerTestTags.OFFLINE).assertIsDisplayed()
    compose.onNodeWithTag(ConnectivityBannerTestTags.BACK_ONLINE).assertDoesNotExist()
  }
}
