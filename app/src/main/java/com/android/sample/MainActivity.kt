package com.android.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.tooling.preview.Preview
import com.android.sample.data.repository.TripError
import com.android.sample.model.Location
import com.android.sample.model.Trip
import com.android.sample.model.TripStatus
import com.android.sample.resources.C
import com.android.sample.ui.auth.GoogleCredentialClient
import com.android.sample.ui.home.HomeScreen
import com.android.sample.ui.home.HomeTripUiState
import com.android.sample.ui.navigation.AppMode
import com.android.sample.ui.navigation.AppScaffold
import com.android.sample.ui.navigation.CommandoApp
import com.android.sample.ui.navigation.CommandoScreens
import com.android.sample.ui.theme.SampleAppTheme
import java.time.LocalDate
import java.time.ZoneId

class MainActivity : ComponentActivity() {
  private val googleCredentials by lazy { GoogleCredentialClient(this) }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent {
      SampleAppTheme {
        // A surface container using the 'background' color from the theme
        Surface(
            modifier = Modifier.fillMaxSize().semantics { testTag = C.Tag.main_screen_container },
            color = MaterialTheme.colorScheme.background,
        ) {
          CommandoApp(
              requestGoogleCredential = googleCredentials::requestCredential,
              clearCredentialState = googleCredentials::clearSession,
          )
        }
      }
    }
  }
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
  Text(text = "Hello $name!", modifier = modifier.semantics { testTag = C.Tag.greeting })
}

// AI assistance: OpenAI Codex. Preview the screens without requiring an authenticated session.
@Preview(name = "Home · Requester", group = "Home", widthDp = 412, heightDp = 915)
@Composable
private fun RequesterHomePreview() {
  MainHomePreview(AppMode.Requester)
}

@Preview(name = "Home · Commando", group = "Home", widthDp = 412, heightDp = 915)
@Composable
private fun CommandoHomePreview() {
  MainHomePreview(AppMode.Commando)
}

@Composable
private fun MainHomePreview(
    initialMode: AppMode,
    tripState: HomeTripUiState = HomeTripUiState.Empty,
) {
  var mode by rememberSaveable { mutableStateOf(initialMode) }
  SampleAppTheme {
    AppScaffold(
        currentScreen = CommandoScreens.Home,
        mode = mode,
        onSwitchMode = { mode = it },
        onHome = {},
        onProfile = {},
    ) { padding ->
      HomeScreen(
          mode = mode,
          modifier = Modifier.padding(padding),
          onFindTrip = {},
          onPublishTrip = {},
          tripState = tripState,
          onManageTrip = {},
      )
    }
  }
}

@Preview(name = "Home · Trip published", group = "Home", widthDp = 412, heightDp = 915)
@Composable
private fun PublishedTripHomePreview() {
  MainHomePreview(AppMode.Commando, previewTrip(TripStatus.PUBLISHED))
}

@Preview(name = "Home · Trip in progress", group = "Home", widthDp = 412, heightDp = 915)
@Composable
private fun OngoingTripHomePreview() {
  MainHomePreview(AppMode.Commando, previewTrip(TripStatus.IN_PROGRESS))
}

@Preview(name = "Home · Trip loading", group = "Home", widthDp = 412, heightDp = 915)
@Composable
private fun LoadingTripHomePreview() {
  MainHomePreview(AppMode.Commando, HomeTripUiState.Loading)
}

@Preview(name = "Home · Trip error", group = "Home", widthDp = 412, heightDp = 915)
@Composable
private fun ErrorTripHomePreview() {
  MainHomePreview(AppMode.Commando, HomeTripUiState.Error(TripError.NetworkError))
}

private fun previewTrip(status: TripStatus): HomeTripUiState.Content {
  val departure = LocalDate.now().atTime(17, 30).atZone(ZoneId.systemDefault()).toInstant()
  return HomeTripUiState.Content(
      Trip(
          "preview",
          "preview",
          Location("Migros · Coop", 46.52, 6.63),
          departure,
          Location("Rolex Learning Center", 46.52, 6.57),
          status,
          departure,
          departure,
      )
  )
}
