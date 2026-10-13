package com.android.sample.ui.map

// AI assistance: OpenAI Codex. Behavioral tests live with the components they cover.
import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
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
@Config(sdk = [34], qualifiers = "en-rUS-w412dp-h749dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MockMapComponentsTest {
  @get:Rule val compose = createComposeRule()
  private val sample = MockMapSamples.trips.first()

  @Test
  fun markerSelectionIsHoistedAndInvokesTheSuppliedAction() {
    var selected by mutableStateOf(false)
    var clicks = 0
    compose.setContent {
      SampleAppTheme {
        MapMarker(
            sample,
            selected,
            sample.time,
            1,
            {
              clicks++
              selected = true
            },
            Modifier,
        )
      }
    }
    val marker = compose.onNodeWithTag(MockMapTestTags.MARKER_PREFIX + sample.id)
    marker.assertIsNotSelected().assertHeightIsAtLeast(48.dp).performClick().assertIsSelected()
    assertEquals(1, clicks)
    marker.performClick()
    assertEquals(2, clicks)
  }

  @Test
  fun clusterCountDisappearsOnSelectionAndAccessibilityUsesDisplayedTime() {
    var selected by mutableStateOf(false)
    compose.setContent {
      MaterialTheme(colorScheme = lightColorScheme()) {
        MapMarker(sample, selected, "20:45", 3, { selected = true }, Modifier)
      }
    }
    compose.onNodeWithText("+2").assertIsDisplayed()
    compose.onNodeWithText("20:45").assertIsDisplayed()
    compose.onNodeWithContentDescription("Sample trip: Migros EPFL at 20:45").performClick()
    compose.onNodeWithText("+2").assertDoesNotExist()
    compose.onNodeWithTag(MockMapTestTags.MARKER_PREFIX + sample.id).assertIsSelected()
  }

  @Test
  fun separateMarkersDispatchOnlyTheirOwnActions() {
    val calls = mutableListOf<String>()
    val second = sample.copy(id = "other", store = "Other store", time = "19:00")
    compose.setContent {
      SampleAppTheme {
        Column {
          listOf(sample, second).forEach { trip ->
            MapMarker(trip, false, trip.time, 1, { calls.add(trip.id) }, Modifier)
          }
        }
      }
    }
    compose.onNodeWithTag(MockMapTestTags.MARKER_PREFIX + second.id).performClick()
    assertEquals(listOf("other"), calls)
  }

  @Test
  fun illustrationAndEmptyStateRenderWithoutInteractiveOrNetworkControls() {
    compose.setContent {
      SampleAppTheme {
        Box(Modifier.fillMaxSize()) {
          MapIllustration(Modifier.matchParentSize())
          EmptyMapCard(Modifier.padding(top = 186.dp, start = 24.dp, end = 24.dp))
        }
      }
    }
    compose.onNodeWithText("No trips on the map yet").assertIsDisplayed()
    compose.onNodeWithTag(MockMapTestTags.EMPTY).assertIsDisplayed()
    compose.onAllNodes(hasClickAction()).assertCountEquals(0)
    val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
    assertEquals(412, bitmap.width)
    assertEquals(749, bitmap.height)
    val file = File("build/reports/mock-map/components-empty.png").apply { parentFile!!.mkdirs() }
    file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
  }
}
