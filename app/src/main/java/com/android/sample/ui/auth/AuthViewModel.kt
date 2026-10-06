package com.android.sample.ui.auth

import android.util.Patterns
import androidx.credentials.Credential
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.android.sample.model.authentication.AuthException
import com.android.sample.model.authentication.AuthRepository
import com.android.sample.model.authentication.AuthUser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class AuthMode {
  LOGIN,
  SIGN_UP,
}

enum class FieldError {
  REQUIRED,
  INVALID_EMAIL,
  PASSWORD_MISMATCH,
}

data class AuthUiState(
    val mode: AuthMode = AuthMode.LOGIN,
    val email: String = "",
    val password: String = "",
    val confirmation: String = "",
    val emailError: FieldError? = null,
    val passwordError: FieldError? = null,
    val confirmationError: FieldError? = null,
    val isLoading: Boolean = false,
    val authError: AuthException? = null,
    val user: AuthUser? = null,
)

/** Form state shared by login and sign-up; the repository owns authentication and sessions. */
class AuthViewModel(private val repository: AuthRepository) : ViewModel() {
  private val mutableState = MutableStateFlow(AuthUiState())
  val uiState = mutableState.asStateFlow()

  fun updateEmail(email: String) = edit { copy(email = email, emailError = null) }

  fun updatePassword(password: String) = edit {
    copy(password = password, passwordError = null, confirmationError = null)
  }

  fun updateConfirmation(confirmation: String) = edit {
    copy(confirmation = confirmation, confirmationError = null)
  }

  fun switchMode(mode: AuthMode) {
    if (mode != uiState.value.mode) {
      edit { AuthUiState(mode = mode, email = email) }
    }
  }

  private fun edit(change: AuthUiState.() -> AuthUiState) {
    val state = uiState.value
    if (!state.isLoading && state.user == null) {
      mutableState.value = state.change().copy(authError = null)
    }
  }

  fun submitEmail() {
    val state = uiState.value
    if (state.isLoading || state.user != null) return
    val email = state.email.trim()
    val checked =
        state.copy(
            emailError =
                when {
                  email.isBlank() -> FieldError.REQUIRED
                  !Patterns.EMAIL_ADDRESS.matcher(email).matches() -> FieldError.INVALID_EMAIL
                  else -> null
                },
            passwordError = if (state.password.isBlank()) FieldError.REQUIRED else null,
            confirmationError =
                when {
                  state.mode == AuthMode.LOGIN -> null
                  state.confirmation.isBlank() -> FieldError.REQUIRED
                  state.confirmation != state.password -> FieldError.PASSWORD_MISMATCH
                  else -> null
                },
            authError = null,
        )
    mutableState.value = checked
    if (
        checked.emailError != null ||
            checked.passwordError != null ||
            checked.confirmationError != null
    )
        return
    authenticate {
      if (state.mode == AuthMode.SIGN_UP) {
        repository.signUpWithEmail(email, state.password).getOrThrow()
      } else {
        repository.signInWithEmail(email, state.password).getOrThrow()
      }
    }
  }

  /** Credential acquisition is injected by the UI; null means the account picker was dismissed. */
  fun signInWithGoogle(requestCredential: suspend () -> Credential?) {
    authenticate { requestCredential()?.let { repository.signInWithGoogle(it).getOrThrow() } }
  }

  private fun authenticate(operation: suspend () -> AuthUser?) {
    val state = uiState.value
    if (state.isLoading || state.user != null) return
    // Set this before launching so even two clicks in the same UI frame cannot submit twice.
    mutableState.value = state.copy(isLoading = true, authError = null)
    viewModelScope.launch {
      try {
        val user = operation()
        if (user != null) {
          mutableState.value = uiState.value.copy(user = user, password = "", confirmation = "")
        }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (error: Exception) {
        mutableState.value =
            uiState.value.copy(authError = error as? AuthException ?: AuthException.Unknown(error))
      } finally {
        mutableState.value = uiState.value.copy(isLoading = false)
      }
    }
  }
}
