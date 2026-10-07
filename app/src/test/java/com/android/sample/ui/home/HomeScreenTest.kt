package com.android.sample.ui.home

// AI assistance: OpenAI Codex.
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.android.sample.ui.navigation.AppMode
import com.android.sample.ui.navigation.AppScaffold
import com.android.sample.ui.navigation.CommandoScreens
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
        AppScaffold(
            CommandoScreens.Home,
            AppMode.Requester,
            onSwitchMode = {},
            onHome = {},
            onProfile = {},
        ) { padding ->
          HomeScreen(AppMode.Requester, Modifier.padding(padding), onFindTrip = { calls++ })
        }
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
        AppScaffold(
            CommandoScreens.Home,
            AppMode.Commando,
            onSwitchMode = {},
            onHome = {},
            onProfile = {},
        ) { padding ->
          HomeScreen(
              AppMode.Commando,
              modifier = Modifier.padding(padding),
              onPublishTrip = { calls++ },
          )
        }
      }
    }
    compose.onNodeWithTag(HomeTestTags.TRIP_ACTION).performClick()
    assertEquals(1, calls)
    saveScreenshot("commando")
  }

  @Test
  fun unavailableTripDestinationIsDisabled() {
    compose.setContent { SampleAppTheme { HomeScreen(AppMode.Requester) } }
    compose.onNodeWithTag(HomeTestTags.TRIP_ACTION).assertIsNotEnabled()
  }

  private fun saveScreenshot(name: String) {
    val image = compose.onRoot().captureToImage().asAndroidBitmap()
    val file = File("build/reports/home/$name.png")
    file.parentFile?.mkdirs()
    file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
  }
}
