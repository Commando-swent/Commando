package com.commando.app.ui.session

// AI assistance: OpenAI Codex.
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.commando.app.data.repository.PublicProfileRepository
import com.commando.app.data.repository.isValidPublicName
import com.commando.app.model.authentication.AuthException
import com.commando.app.model.authentication.AuthRepository
import com.commando.app.ui.profile.PublicNameEditState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Session state consumed by the app's navigation root, independent of the login form. The
 * repository owns the session; navigation responds to [uiState] and clears its authenticated back
 * stack when the user becomes null. The explicitly public-name editor shares this lifetime so its
 * submitted writes survive replacement of an authenticated graph; private Profile stays separate.
 */
class SessionViewModel(private val repository: AuthRepository) : ViewModel() {
  private val mutableState = MutableStateFlow(SessionUiState(user = repository.currentUser))
  val uiState: StateFlow<SessionUiState> = mutableState.asStateFlow()
  private val publicNameWrites = mutableMapOf<String, Job>()
  private var publicNameSession = 0L
  private val mutablePublicName = MutableStateFlow<PublicNameEditState?>(null)
  /**
   * Explicit public-name editor state, cleared before publishing an account change to navigation.
   */
  val publicName = mutablePublicName.asStateFlow()
  private var publicNameRead: Job? = null
  private var publicProfiles: Lazy<PublicProfileRepository>? = null
  private var publicNameEditorOwner: Any? = null

  /**
   * Claims one entry's [editorOwner] by reference, so old entries cannot affect its replacement.
   */
  fun beginPublicNameEditor(editorOwner: Any) {
    if (publicNameEditorOwner !== editorOwner) {
      closePublicNameEditor()
      publicNameEditorOwner = editorOwner
    }
  }

  /**
   * Closes only the matching entry and cancels its read; submitted writes remain tracked by UID.
   */
  fun closePublicNameEditor(editorOwner: Any? = publicNameEditorOwner) {
    if (editorOwner !== publicNameEditorOwner) return
    publicNameRead?.cancel()
    mutablePublicName.value = null
    publicProfiles = null
    publicNameEditorOwner = null
  }

  /**
   * Reads the claimed entry's committed name after outstanding writes; also retries read errors.
   */
  fun loadPublicName(
      profiles: Lazy<PublicProfileRepository>,
      editorOwner: Any? = publicNameEditorOwner,
  ) {
    if (editorOwner == null || editorOwner !== publicNameEditorOwner) return
    if (mutablePublicName.value?.loaded == true) return
    publicProfiles = profiles
    publicNameOperation { source, uid -> source.getDisplayName(uid) }
  }

  /** Changes only draft input. Typing never publishes private authentication information. */
  fun editPublicName(name: String, editorOwner: Any? = publicNameEditorOwner) {
    val state = mutablePublicName.value ?: return
    if (editorOwner !== publicNameEditorOwner) return
    if (state.loaded && !state.busy && repository.currentUser?.uid == mutableState.value.user?.uid)
        mutablePublicName.value = state.copy(draft = name, failed = false)
  }

  /**
   * Publishes/updates [name] unchanged, or removes it when null. Failed writes retain the draft.
   */
  fun savePublicName(name: String?, editorOwner: Any? = publicNameEditorOwner) {
    val state = mutablePublicName.value ?: return
    if (editorOwner !== publicNameEditorOwner || !state.loaded || state.busy) return
    if (name != null) {
      editPublicName(name, editorOwner)
      if (editorOwner !== publicNameEditorOwner) return
      if (!isValidPublicName(name) || name == state.confirmed) return
    } else if (state.confirmed == null) return
    publicNameOperation(write = true) { source, _ ->
      if (name == null) source.removeDisplayName() else source.publishDisplayName(name)
      name
    }
  }

  private fun publicNameOperation(
      write: Boolean = false,
      action: suspend (PublicProfileRepository, String) -> String?,
  ) {
    val profiles = publicProfiles ?: return
    val uid = mutableState.value.user?.uid?.takeUnless(String::isBlank) ?: return
    val previous = mutablePublicName.value ?: PublicNameEditState()
    if (previous.busy || repository.currentUser?.uid != uid) return
    val session = publicNameSession
    val pending = previous.copy(busy = true, failed = false)
    val job =
        viewModelScope.launch(start = CoroutineStart.LAZY) {
          fun finish(state: PublicNameEditState) {
            if (
                session == publicNameSession &&
                    repository.currentUser?.uid == uid &&
                    mutablePublicName.value === pending
            )
                mutablePublicName.value = state
          }
          try {
            if (!write) publicNameWrites[uid]?.join()
            ensureActive()
            if (session == publicNameSession && repository.currentUser?.uid == uid) {
              val name = action(profiles.value, uid)
              ensureActive()
              finish(PublicNameEditState(loaded = true, confirmed = name, draft = name.orEmpty()))
            }
          } catch (cancelled: CancellationException) {
            if (isActive) finish(previous)
            throw cancelled
          } catch (_: Exception) {
            ensureActive()
            finish(previous.copy(failed = true))
          }
        }
    if (write) {
      publicNameWrites.entries.removeAll { it.value.isCompleted }
      publicNameWrites[uid] = job
    } else publicNameRead = job
    mutablePublicName.value = pending
    job.start()
  }

  init {
    viewModelScope.launch {
      repository.observeAuthState().collect { user ->
        if (mutableState.value.user?.uid != user?.uid) {
          publicNameSession++
          closePublicNameEditor()
        }
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
