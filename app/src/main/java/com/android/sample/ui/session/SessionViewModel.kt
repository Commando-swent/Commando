package com.android.sample.ui.session

// AI assistance: OpenAI Codex.
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.android.sample.model.authentication.AuthException
import com.android.sample.model.authentication.AuthRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Session state consumed by the app's navigation root, independent of the login form. The
 * repository owns the session; navigation responds to [uiState] and clears its authenticated back
 * stack when the user becomes null.
 */
class SessionViewModel(private val repository: AuthRepository) : ViewModel() {
  private val mutableState = MutableStateFlow(SessionUiState(user = repository.currentUser))
  val uiState: StateFlow<SessionUiState> = mutableState.asStateFlow()

  init {
    viewModelScope.launch {
      repository.observeAuthState().collect { user ->
        mutableState.update { state ->
          state.copy(
              user = user,
              signOutError = if (user != state.user) null else state.signOutError,
          )
        }
      }
    }
  }

  /** Returns success so provider cleanup runs only after repository sign-out succeeds. */
  fun signOut(): Boolean {
    mutableState.update { it.copy(signOutError = null) }
    return try {
      repository
          .signOut()
          .onFailure { error ->
            mutableState.update {
              it.copy(signOutError = error as? AuthException ?: AuthException.Unknown(error))
            }
          }
          .isSuccess
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (error: Exception) {
      mutableState.update {
        it.copy(signOutError = error as? AuthException ?: AuthException.Unknown(error))
      }
      false
    }
  }

  fun clearSignOutError() {
    mutableState.update { it.copy(signOutError = null) }
  }
}
