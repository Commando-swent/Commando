package com.commando.app

// AI assistance: OpenAI Codex. Preview the screens without requiring an authenticated session.
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.commando.app.data.repository.TripError
import com.commando.app.model.*
import com.commando.app.ui.home.HomeScreen
import com.commando.app.ui.home.HomeTripUiState
import com.commando.app.ui.navigation.AppMode
import com.commando.app.ui.navigation.AppScaffold
import com.commando.app.ui.navigation.CommandoScreens
import com.commando.app.ui.theme.SampleAppTheme
import java.time.LocalDate
import java.time.ZoneId

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
