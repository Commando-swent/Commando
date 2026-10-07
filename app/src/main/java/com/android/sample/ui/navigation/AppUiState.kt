package com.android.sample.ui.navigation

// AI assistance: OpenAI Codex.
enum class AppMode {
  Requester,
  Commando,
}

data class AppUiState(val mode: AppMode = AppMode.Requester)
