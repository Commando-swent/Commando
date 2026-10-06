package com.android.sample.ui.home

// AI assistance: OpenAI Codex.
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.android.sample.ui.theme.SampleAppTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeScreenTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

  @Test
  fun requesterActionCallsFindTrip() {
    var calls = 0
    compose.setContent {
      SampleAppTheme {
        HomeScreen(HomeUiState(), onSwitchMode = {}, onProfile = {}, onFindTrip = { calls++ })
      }
    }
    compose.onNodeWithTag(HomeTestTags.TRIP_ACTION).performClick()
    assertEquals(1, calls)
    saveScreenshot("requester")
  }

  @Test
  fun commandoActionCallsPublishTrip() {
    var calls = 0
    compose.setContent {
      SampleAppTheme {
        HomeScreen(
            HomeUiState(HomeMode.Commando),
            onSwitchMode = {},
            onProfile = {},
            onPublishTrip = { calls++ },
        )
      }
    }
    compose.onNodeWithTag(HomeTestTags.TRIP_ACTION).performClick()
    assertEquals(1, calls)
    saveScreenshot("commando")
  }

  @Test
  fun unavailableTripDestinationIsDisabled() {
    compose.setContent {
      SampleAppTheme { HomeScreen(HomeUiState(), onSwitchMode = {}, onProfile = {}) }
    }
    compose.onNodeWithTag(HomeTestTags.TRIP_ACTION).assertIsNotEnabled()
  }

  private fun saveScreenshot(name: String) {
    val image = compose.onRoot().captureToImage().asAndroidBitmap()
    val file = File("build/reports/home/$name.png")
    file.parentFile?.mkdirs()
    file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
  }
}
