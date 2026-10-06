package com.android.sample.ui.navigation

// AI assistance: OpenAI Codex.
import androidx.lifecycle.SavedStateHandle
import org.junit.Assert.assertEquals
import org.junit.Test

class AppViewModelTest {
  @Test
  fun startsInRequesterModeAndCanSwitchInBothDirections() {
    val viewModel = AppViewModel(SavedStateHandle())
    assertEquals(AppMode.Requester, viewModel.uiState.value.mode)
    viewModel.switchMode(AppMode.Commando)
    assertEquals(AppMode.Commando, viewModel.uiState.value.mode)
    viewModel.switchMode(AppMode.Requester)
    assertEquals(AppMode.Requester, viewModel.uiState.value.mode)
  }

  @Test
  fun savedModeSurvivesViewModelRecreationAndNewEntriesStartFresh() {
    val handle = SavedStateHandle()
    AppViewModel(handle).switchMode(AppMode.Commando)
    val restoredHandle = SavedStateHandle(mapOf("app_mode" to handle.get<String>("app_mode")))
    assertEquals(AppMode.Commando, AppViewModel(restoredHandle).uiState.value.mode)
    assertEquals(AppMode.Requester, AppViewModel(SavedStateHandle()).uiState.value.mode)
  }
}
