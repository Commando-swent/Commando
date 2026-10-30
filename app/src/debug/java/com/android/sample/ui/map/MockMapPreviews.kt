package com.android.sample.ui.map

// AI assistance: OpenAI Codex.
import androidx.compose.runtime.*
import androidx.compose.ui.tooling.preview.*
import com.android.sample.ui.theme.SampleAppTheme

class MockMapPreviewStates : PreviewParameterProvider<MockMapUiState> {
  override val values =
      sequenceOf(
          MockMapUiState(),
          MockMapUiState(selectedTripId = "sample-migros"),
          MockMapUiState(selectedTripId = "sample-denner"),
          MockMapUiState(locationOff = true),
          MockMapUiState(status = MockMapStatus.Loading),
          MockMapUiState(trips = emptyList()),
          MockMapUiState(status = MockMapStatus.Error),
      )
}

@Preview(name = "Map prototype", widthDp = 412, heightDp = 917)
@Composable
internal fun MockMapPreview(@PreviewParameter(MockMapPreviewStates::class) state: MockMapUiState) {
  val model =
      remember(state) {
        MockMapViewModel(state.trips, state.status, state.locationOff).apply {
          state.selectedTripId?.let(::selectTrip)
        }
      }
  SampleAppTheme { MockMapScreen(model, {}) }
}
