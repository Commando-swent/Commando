package com.android.sample.ui.home

// AI assistance: OpenAI Codex.
enum class HomeMode {
  Requester,
  Commando,
}

data class HomeUiState(val mode: HomeMode = HomeMode.Requester)
