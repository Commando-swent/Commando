package com.commando.app.ui.session

// AI assistance: OpenAI Codex.
import com.commando.app.model.authentication.AuthException
import com.commando.app.model.authentication.AuthUser

data class SessionUiState(
    val user: AuthUser? = null,
    val signOutError: AuthException? = null,
)
