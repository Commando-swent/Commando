package com.android.sample.ui.map

// AI assistance: Claude Code.
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.android.sample.ui.map.MockMapTestTags as Tags
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-rUS-w412dp-h917dp-mdpi")
class MockMapPreviewsTest {
  @get:Rule val compose = createComposeRule()

  @Test
  fun previewsRenderEachFigmaStateWithItsDistinctiveElement() {
    val expectedTags =
        listOf(
            Tags.MARKER_PREFIX + "sample-migros",
            Tags.SUMMARY,
            Tags.PAGE_PREFIX + "sample-shared",
            Tags.LOCATION_OFF,
            Tags.LOADING,
            Tags.EMPTY,
            Tags.ERROR,
        )
    val states = MockMapPreviewStates().values.toList()
    var index by mutableIntStateOf(0)
    compose.setContent { MockMapPreview(states[index]) }
    expectedTags.forEachIndexed { stateIndex, tag ->
      compose.runOnIdle { index = stateIndex }
      compose.onNodeWithTag(tag).assertIsDisplayed()
    }
  }
}
