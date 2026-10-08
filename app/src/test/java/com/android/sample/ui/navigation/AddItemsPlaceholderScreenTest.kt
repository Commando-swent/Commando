package com.android.sample.ui.navigation

// AI assistance: OpenAI Codex.
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-rUS-w640dp-h320dp-land-mdpi")
class AddItemsPlaceholderScreenTest {
  @get:Rule val compose = createComposeRule()

  @Test
  fun backIsReachableInShortViewportWithLargeTextAndInvokesCallbackOnce() {
    var backs = 0
    compose.setContent {
      CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
        MaterialTheme { AddItemsPlaceholderScreen(onBack = { backs++ }) }
      }
    }
    compose.onRoot().assertWidthIsEqualTo(640.dp).assertHeightIsEqualTo(320.dp)
    compose.onNodeWithText("Add items").assertIsDisplayed()
    compose
        .onNodeWithText("This feature will be available in Sprint 2.")
        .performScrollTo()
        .assertIsDisplayed()
    compose.runOnIdle { assertEquals(0, backs) }
    compose
        .onNodeWithText("Back")
        .performScrollTo()
        .assertIsDisplayed()
        .assertHasClickAction()
        .performClick()
    compose.runOnIdle { assertEquals(1, backs) }
  }
}
