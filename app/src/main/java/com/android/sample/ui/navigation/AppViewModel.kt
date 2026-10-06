package com.android.sample.ui.navigation

// AI assistance: OpenAI Codex.
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Shares the mode across authenticated screens and restores it when the App entry is recreated.
 * Signing out or changing accounts discards that entry, so a new session starts in Requester mode.
 */
class AppViewModel(private val savedStateHandle: SavedStateHandle) : ViewModel() {
  private val mutableState =
      MutableStateFlow(
          AppUiState(
              savedStateHandle.get<String>(MODE_KEY)?.let(AppMode::valueOf) ?: AppMode.Requester
          )
      )
  val uiState: StateFlow<AppUiState> = mutableState.asStateFlow()

  fun switchMode(mode: AppMode) {
    savedStateHandle[MODE_KEY] = mode.name
    mutableState.value = AppUiState(mode)
  }

  private companion object {
    const val MODE_KEY = "app_mode"
  }
}
