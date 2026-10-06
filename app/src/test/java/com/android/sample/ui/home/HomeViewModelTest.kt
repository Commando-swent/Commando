package com.android.sample.ui.home

// AI assistance: OpenAI Codex.
import androidx.lifecycle.SavedStateHandle
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeViewModelTest {
  @Test
  fun startsInRequesterModeAndCanSwitchInBothDirections() {
    val viewModel = HomeViewModel(SavedStateHandle())
    assertEquals(HomeMode.Requester, viewModel.uiState.value.mode)
    viewModel.switchMode(HomeMode.Commando)
    assertEquals(HomeMode.Commando, viewModel.uiState.value.mode)
    viewModel.switchMode(HomeMode.Requester)
    assertEquals(HomeMode.Requester, viewModel.uiState.value.mode)
  }

  @Test
  fun savedModeSurvivesViewModelRecreationAndNewEntriesStartFresh() {
    val handle = SavedStateHandle()
    HomeViewModel(handle).switchMode(HomeMode.Commando)
    val restoredHandle = SavedStateHandle(mapOf("home_mode" to handle.get<String>("home_mode")))
    assertEquals(HomeMode.Commando, HomeViewModel(restoredHandle).uiState.value.mode)
    assertEquals(HomeMode.Requester, HomeViewModel(SavedStateHandle()).uiState.value.mode)
  }
}
