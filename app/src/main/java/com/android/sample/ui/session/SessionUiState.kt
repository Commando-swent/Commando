package com.android.sample.ui.session

// AI assistance: OpenAI Codex.
import com.android.sample.model.authentication.AuthException
import com.android.sample.model.authentication.AuthUser

data class SessionUiState(
    val user: AuthUser? = null,
    val signOutError: AuthException? = null,
)
