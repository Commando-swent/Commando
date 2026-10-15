package com.android.sample.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.android.sample.R
import com.android.sample.model.authentication.AuthException

@Composable
fun EditProfileScreen(
    state: ProfileEditUiState,
    onFullNameChange: (String) -> Unit,
    onEmailChange: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    avatarFullName: String?,
    modifier: Modifier = Modifier,
    photo: PhotoUiState? = null,
    onPhotoClick: (() -> Unit)? = null,
) {
  val enabled = !state.isSaving && !state.saved
  Column(
      modifier
          .fillMaxSize()
          .background(MaterialTheme.colorScheme.background)
          .testTag(ProfileEditTestTags.SCREEN)
          .imePadding()
          .verticalScroll(rememberScrollState())
          .padding(horizontal = 28.dp, vertical = 4.dp),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      val back = stringResource(R.string.nav_back)
      IconButton(
          onClick = onCancel,
          enabled = !state.isSaving,
          modifier = Modifier.testTag(ProfileEditTestTags.BACK),
      ) {
        Text(
            "←",
            Modifier.semantics { contentDescription = back },
            style = MaterialTheme.typography.titleLarge,
        )
      }
      Text(
          stringResource(R.string.profile_edit_title),
          style = MaterialTheme.typography.titleMedium,
      )
    }
    Box(
        Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
      val onPreview =
          ProfilePhotoPreview(profileInitials(avatarFullName), photo?.image, avatarFullName)
      ProfileAvatar(
          profileInitials(avatarFullName),
          showPhotoAction = onPhotoClick != null,
          photo = photo?.image,
          onPhotoClick = onPhotoClick,
          photoEnabled = photo?.busy != true,
          onAvatarClick = onPreview,
      )
    }
    Text(
        stringResource(R.string.profile_personal_information),
        Modifier.padding(start = 4.dp, bottom = 8.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall,
    )
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
      Column {
        ProfileEditField(
            state.fullName,
            onFullNameChange,
            R.string.profile_full_name,
            R.string.profile_full_name,
            ProfileEditTestTags.FULL_NAME,
            enabled,
            if (state.nameInvalid) R.string.profile_edit_name_required else null,
            false,
            onSave,
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        ProfileEditField(
            state.email,
            onEmailChange,
            R.string.profile_email,
            R.string.profile_edit_email_hint,
            ProfileEditTestTags.EMAIL,
            enabled,
            when {
              state.email.isBlank() -> R.string.profile_edit_email_required
              state.emailInvalid -> R.string.profile_edit_email_invalid
              else -> null
            },
            true,
            { if (state.canSave) onSave() },
        )
      }
    }
    val feedback =
        when {
          state.saved && state.pendingEmail != null ->
              stringResource(R.string.profile_edit_verification, state.pendingEmail)
          state.saved -> stringResource(R.string.profile_edit_saved)
          state.error != null ->
              stringResource(
                  when (state.error) {
                    is AuthException.Network -> R.string.profile_edit_network_error
                    is AuthException.EmailAlreadyInUse,
                    is AuthException.AccountConflict -> R.string.profile_edit_email_in_use
                    is AuthException.RequiresRecentLogin -> R.string.profile_edit_recent_login
                    is AuthException.SessionChanged -> R.string.profile_edit_session_changed
                    else -> R.string.profile_edit_unknown_error
                  }
              )
          else -> null
        }
    feedback?.let {
      Text(
          it,
          Modifier.padding(top = 12.dp).testTag(ProfileEditTestTags.FEEDBACK).semantics {
            liveRegion = LiveRegionMode.Polite
          },
          color =
              if (state.error != null) MaterialTheme.colorScheme.error
              else MaterialTheme.colorScheme.onSurface,
          style = MaterialTheme.typography.bodySmall,
      )
    }
    Button(
        onClick = if (state.saved) onCancel else onSave,
        enabled = state.saved || state.canSave,
        modifier =
            Modifier.fillMaxWidth()
                .padding(top = 16.dp)
                .heightIn(min = 48.dp)
                .testTag(ProfileEditTestTags.SAVE),
        shape = CircleShape,
    ) {
      if (state.isSaving) {
        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(8.dp))
      }
      Text(
          stringResource(
              when {
                state.saved -> R.string.profile_edit_done
                state.isSaving -> R.string.profile_edit_saving
                else -> R.string.profile_edit_save
              }
          )
      )
    }
    if (!state.saved)
        TextButton(
            onClick = onCancel,
            enabled = !state.isSaving,
            modifier =
                Modifier.align(Alignment.CenterHorizontally).testTag(ProfileEditTestTags.CANCEL),
        ) {
          Text(stringResource(R.string.profile_edit_cancel))
        }
  }
}

@Composable
private fun ProfileEditField(
    value: String,
    onChange: (String) -> Unit,
    label: Int,
    hint: Int,
    tag: String,
    enabled: Boolean,
    errorResource: Int?,
    email: Boolean,
    onSubmit: () -> Unit,
) {
  val colors = MaterialTheme.colorScheme
  val labelText = stringResource(label)
  val errorText = errorResource?.let { stringResource(it) }
  val focus = LocalFocusManager.current
  Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Text(
        labelText,
        color = if (errorText == null) colors.onSurfaceVariant else colors.error,
        style = MaterialTheme.typography.bodySmall,
    )
    BasicTextField(
        value,
        onChange,
        enabled = enabled,
        singleLine = true,
        modifier =
            Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(tag).semantics {
              contentDescription = labelText
              errorText?.let { error(it) }
            },
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.onSurface),
        cursorBrush = SolidColor(colors.primary),
        keyboardOptions =
            KeyboardOptions(
                keyboardType = if (email) KeyboardType.Email else KeyboardType.Text,
                imeAction = if (email) ImeAction.Done else ImeAction.Next,
            ),
        keyboardActions =
            KeyboardActions(
                onDone = { if (enabled) onSubmit() },
                onNext = { focus.moveFocus(FocusDirection.Down) },
            ),
        decorationBox = { inner ->
          Box(
              Modifier.background(colors.surfaceContainerHigh, CircleShape)
                  .border(
                      if (errorText == null) 1.dp else 1.5.dp,
                      if (errorText == null) colors.outlineVariant else colors.error,
                      CircleShape,
                  )
                  .padding(horizontal = 16.dp, vertical = 12.dp),
              contentAlignment = Alignment.CenterStart,
          ) {
            if (value.isEmpty()) Text(stringResource(hint), color = colors.onSurfaceVariant)
            inner()
          }
        },
    )
    errorText?.let { Text(it, color = colors.error, style = MaterialTheme.typography.bodySmall) }
  }
}
