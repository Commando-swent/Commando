package com.android.sample.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.android.sample.model.authentication.AuthException
import com.android.sample.model.authentication.AuthRepository
import com.android.sample.model.authentication.AuthUser
import com.android.sample.model.authentication.ProfileValidation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
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

  private val mutableEditState = MutableStateFlow<ProfileEditUiState?>(null)
  val editState: StateFlow<ProfileEditUiState?> = mutableEditState.asStateFlow()
  private var activeUser: AuthUser? = null
  private var saveJob: Job? = null

  init {
    // Observation stops automatically when this ViewModel is cleared.
    viewModelScope.launch {
      // Collect future changes as well as the initial user, rather than reading a single snapshot.
      repository.observeAuthState().collect { user ->
        if (user?.uid != activeUser?.uid) {
          saveJob?.cancel()
          mutableEditState.value = null
        }
        activeUser = user
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

  fun beginEditing() {
    val user = activeUser ?: return
    if (mutableEditState.value?.isSaving == true) return
    mutableEditState.value =
        ProfileEditUiState(
            fullName = user.displayName.orEmpty(),
            email = user.email.orEmpty(),
            fullNameError = user.displayName.isNullOrBlank(),
            emailError = !ProfileValidation.isValidEmail(user.email.orEmpty()),
        )
  }

  fun updateFullName(value: String) {
    val draft = mutableEditState.value ?: return
    if (draft.isSaving) return
    mutableEditState.value =
        draft.copy(
            fullName = value,
            fullNameError = value.isBlank(),
            error = null,
            saved = false,
            pendingEmail = null,
        )
  }

  fun updateEmail(value: String) {
    val draft = mutableEditState.value ?: return
    if (draft.isSaving) return
    mutableEditState.value =
        draft.copy(
            email = value,
            emailError = !ProfileValidation.isValidEmail(value),
            error = null,
            saved = false,
            pendingEmail = null,
        )
  }

  fun cancelEditing() {
    if (mutableEditState.value?.isSaving != true) mutableEditState.value = null
  }

  fun saveProfile() {
    val user = activeUser ?: return
    val draft = mutableEditState.value ?: return
    if (draft.isSaving || draft.saved) return
    val invalidName = draft.fullName.isBlank()
    val invalidEmail = !ProfileValidation.isValidEmail(draft.email)
    if (invalidName || invalidEmail) {
      mutableEditState.value = draft.copy(fullNameError = invalidName, emailError = invalidEmail)
      return
    }
    // Set this before launching so two clicks in the same frame cannot start duplicate writes.
    mutableEditState.value = draft.copy(isSaving = true, error = null)
    saveJob = viewModelScope.launch {
      try {
        val result = repository.updateProfile(draft.fullName.trim(), draft.email.trim())
        if (activeUser?.uid != user.uid) return@launch
        val currentDraft = mutableEditState.value ?: return@launch
        result.fold(
            onSuccess = { updated ->
              if (updated.user.uid == user.uid) {
                mutableState.value =
                    ProfileUiState.Content(updated.user.displayName, updated.user.email)
                mutableEditState.value =
                    currentDraft.copy(
                        isSaving = false,
                        saved = true,
                        pendingEmail = updated.pendingEmail,
                    )
              } else {
                mutableEditState.value = null
              }
            },
            onFailure = { error ->
              mutableEditState.value =
                  currentDraft.copy(
                      isSaving = false,
                      error = error.toProfileSaveError(),
                      emailError = error is AuthException.InvalidEmail,
                  )
            },
        )
      } catch (error: CancellationException) {
        throw error
      } finally {
        if (activeUser?.uid == user.uid) {
          mutableEditState.value = mutableEditState.value?.copy(isSaving = false)
        }
      }
    }
  }

  private fun Throwable.toProfileSaveError(): ProfileSaveError =
      when (this) {
        is AuthException.Network -> ProfileSaveError.NETWORK
        is AuthException.EmailAlreadyInUse,
        is AuthException.AccountConflict -> ProfileSaveError.EMAIL_IN_USE
        is AuthException.RequiresRecentLogin -> ProfileSaveError.RECENT_LOGIN_REQUIRED
        is AuthException.SessionChanged -> ProfileSaveError.SESSION_CHANGED
        else -> ProfileSaveError.UNKNOWN
      }
}
