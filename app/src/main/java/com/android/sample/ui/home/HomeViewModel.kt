package com.android.sample.ui.home

// AI assistance: OpenAI Codex.
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The mode belongs to this Home entry, independently of the authentication session. */
class HomeViewModel(private val savedStateHandle: SavedStateHandle) : ViewModel() {
  private val mutableState =
      MutableStateFlow(
          HomeUiState(
              savedStateHandle.get<String>(MODE_KEY)?.let(HomeMode::valueOf) ?: HomeMode.Requester
          )
      )
  val uiState: StateFlow<HomeUiState> = mutableState.asStateFlow()

  fun switchMode(mode: HomeMode) {
    savedStateHandle[MODE_KEY] = mode.name
    mutableState.value = HomeUiState(mode)
  }

  private companion object {
    const val MODE_KEY = "home_mode"
  }
}
