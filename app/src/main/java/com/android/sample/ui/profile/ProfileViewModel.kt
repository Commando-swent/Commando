package com.android.sample.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.android.sample.data.repository.ProfilePhotoRepository
import com.android.sample.model.ProfilePhotoSource
import com.android.sample.model.authentication.AuthException
import com.android.sample.model.authentication.AuthRepository
import com.android.sample.model.authentication.AuthUser
import java.util.Base64
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
class ProfileViewModel(
    private val repository: AuthRepository,
    private val photos: ProfilePhotoRepository? = null,
) : ViewModel() {
  // Keep state updates private and wait for the first repository emission before showing data.
  private val mutableState = MutableStateFlow<ProfileUiState>(ProfileUiState.Loading)

  // The screen can observe this state, but it cannot change it directly.
  val uiState: StateFlow<ProfileUiState> = mutableState.asStateFlow()
  private val mutableEditState = MutableStateFlow<ProfileEditUiState?>(null)
  val editState = mutableEditState.asStateFlow()
  private var activeUser: AuthUser? = null
  private var saveJob: Job? = null
  private var photoJob: Job? = null
  private val mutablePhotoState = MutableStateFlow(PhotoUiState())
  val photoState = mutablePhotoState.asStateFlow()

  init {
    // Observation stops automatically when this ViewModel is cleared.
    viewModelScope.launch {
      // Collect future changes as well as the initial user, rather than reading a single snapshot.
      repository.observeAuthState().collect { user ->
        val accountChanged = activeUser?.uid != user?.uid
        activeUser = user
        if (accountChanged) {
          saveJob?.cancel()
          mutableEditState.value = null
          photoJob?.cancel()
          mutablePhotoState.value = PhotoUiState()
          if (user != null && photos != null) photoOperation(user.uid) { photos.load(user.uid) }
        }
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

  fun photoOwner(): String? = activeUser?.uid

  fun savePhoto(owner: String, source: ProfilePhotoSource?) {
    val photos = photos ?: return
    if (activeUser?.uid != owner || mutablePhotoState.value.busy) return
    photoOperation(owner) { photos.save(owner, source) }
  }

  private fun photoOperation(owner: String, operation: suspend () -> ByteArray?) {
    mutablePhotoState.value = mutablePhotoState.value.copy(busy = true)
    photoJob = viewModelScope.launch {
      try {
        val image = operation()?.let { Base64.getEncoder().encodeToString(it) }
        if (activeUser?.uid == owner) mutablePhotoState.value = PhotoUiState(image)
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        // Keep the last confirmed photo when loading, replacing or removing fails.
      } finally {
        if (activeUser?.uid == owner)
            mutablePhotoState.value = mutablePhotoState.value.copy(busy = false)
      }
    }
  }

  fun beginEditing() {
    val user = activeUser ?: return
    if (mutableEditState.value == null) {
      mutableEditState.value = ProfileEditUiState(user.displayName.orEmpty(), user.email.orEmpty())
    }
  }

  fun updateFullName(value: String) = edit { it.copy(fullName = value) }

  fun updateEmail(value: String) = edit { it.copy(email = value) }

  private fun edit(change: (ProfileEditUiState) -> ProfileEditUiState) {
    val draft = mutableEditState.value ?: return
    if (!draft.isSaving)
        mutableEditState.value =
            change(draft).copy(error = null, saved = false, pendingEmail = null)
  }

  fun cancelEditing() {
    if (mutableEditState.value?.isSaving != true) mutableEditState.value = null
  }

  fun saveProfile() {
    val user = activeUser ?: return
    val draft = mutableEditState.value ?: return
    if (!draft.canSave) return
    mutableEditState.value = draft.copy(isSaving = true, error = null)
    saveJob = viewModelScope.launch {
      try {
        val result = repository.updateProfile(draft.fullName.trim(), draft.email.trim())
        if (activeUser?.uid != user.uid) return@launch
        val current = mutableEditState.value ?: return@launch
        result.fold(
            onSuccess = { updated ->
              if (updated.user.uid == user.uid) {
                mutableState.value =
                    ProfileUiState.Content(updated.user.displayName, updated.user.email)
                mutableEditState.value =
                    current.copy(
                        isSaving = false,
                        saved = true,
                        pendingEmail = updated.pendingEmail,
                    )
              } else mutableEditState.value = null
            },
            onFailure = {
              mutableEditState.value =
                  current.copy(
                      isSaving = false,
                      error = it as? AuthException ?: AuthException.Unknown(it),
                  )
            },
        )
      } catch (error: CancellationException) {
        throw error
      } finally {
        if (activeUser?.uid == user.uid)
            mutableEditState.value = mutableEditState.value?.copy(isSaving = false)
      }
    }
  }
}
