package com.android.sample.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.android.sample.model.authentication.AuthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Converts authentication updates into state for the Profile screen. The repository supplies user
 * information; the existing session ViewModel and app root remain responsible for navigation.
 */
class ProfileViewModel(private val repository: AuthRepository) : ViewModel() {
  // Keep state updates private and wait for the first repository emission before showing data.
  private val mutableState = MutableStateFlow<ProfileUiState>(ProfileUiState.Loading)

  // The screen can observe this state, but it cannot change it directly.
  val uiState: StateFlow<ProfileUiState> = mutableState.asStateFlow()

  init {
    // Observation stops automatically when this ViewModel is cleared.
    viewModelScope.launch {
      // Collect future changes as well as the initial user, rather than reading a single snapshot.
      repository.observeAuthState().collect { user ->
        mutableState.value =
            if (user == null) {
              // Replacing the state removes any information belonging to the previous user.
              ProfileUiState.MissingUser
            } else {
              // Build fresh state for each update; missing fields must not retain old values.
              ProfileUiState.Content(
                  // Treat empty or whitespace-only names as missing; preserve real names unchanged.
                  fullName = user.displayName?.takeUnless { it.isBlank() },
                  email = user.email,
              )
            }
      }
    }
  }
}
