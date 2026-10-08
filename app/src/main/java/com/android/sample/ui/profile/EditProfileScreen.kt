package com.android.sample.ui.profile

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.sample.R
import com.android.sample.ui.theme.SampleAppTheme

/** Renders an editable draft; persistence and navigation are provided by named callbacks. */
@Composable
fun EditProfileScreen(
    uiState: ProfileEditUiState,
    onFullNameChange: (String) -> Unit,
    onEmailChange: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    avatarFullName: String? = uiState.fullName,
) {
  val enabled = !uiState.isSaving && !uiState.saved
  val backDescription = stringResource(R.string.nav_back)
  val nameError =
      if (uiState.fullNameError || uiState.fullName.isBlank()) {
        stringResource(R.string.profile_edit_name_required)
      } else null
  val emailError =
      when {
        uiState.email.isBlank() -> stringResource(R.string.profile_edit_email_required)
        uiState.emailError -> stringResource(R.string.profile_edit_email_invalid)
        else -> null
      }
  Column(
      modifier
          .fillMaxSize()
          .background(MaterialTheme.colorScheme.background)
          .testTag(ProfileEditTestTags.SCREEN)
          .imePadding()
          .verticalScroll(rememberScrollState())
          .padding(horizontal = 12.dp, vertical = 4.dp),
  ) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      IconButton(
          onClick = onCancel,
          enabled = !uiState.isSaving,
          modifier = Modifier.testTag(ProfileEditTestTags.BACK),
      ) {
        Text(
            "←",
            modifier = Modifier.semantics { contentDescription = backDescription },
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
      Box(
          Modifier.size(96.dp)
              .background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape)
              .border(3.dp, MaterialTheme.colorScheme.primary, CircleShape),
          contentAlignment = Alignment.Center,
      ) {
        val initials = profileInitials(avatarFullName)
        if (initials != null) {
          Text(
              initials,
              modifier = Modifier.testTag(ProfileEditTestTags.INITIALS),
              color = MaterialTheme.colorScheme.primary,
              style = MaterialTheme.typography.headlineSmall,
          )
        } else {
          Image(
              painterResource(R.drawable.profile_person),
              contentDescription = stringResource(R.string.profile_neutral_avatar),
              modifier = Modifier.size(28.dp),
          )
        }
      }
    }
    Text(
        stringResource(R.string.profile_edit_personal_information),
        modifier = Modifier.padding(start = 3.dp, bottom = 8.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall,
    )
    Surface(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
      Column {
        EditProfileField(
            value = uiState.fullName,
            onChange = onFullNameChange,
            label = R.string.profile_full_name,
            placeholder = R.string.profile_full_name,
            tag = ProfileEditTestTags.FULL_NAME,
            errorTag = ProfileEditTestTags.FULL_NAME_ERROR,
            errorMessage = nameError,
            enabled = enabled,
            email = false,
            onSubmit = { /* The name field advances focus through the Next IME action. */ },
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 1.dp)
        EditProfileField(
            value = uiState.email,
            onChange = onEmailChange,
            label = R.string.profile_email,
            placeholder = R.string.profile_edit_email_hint,
            tag = ProfileEditTestTags.EMAIL,
            errorTag = ProfileEditTestTags.EMAIL_ERROR,
            errorMessage = emailError,
            enabled = enabled,
            email = true,
            onSubmit = { if (uiState.canSave) onSave() },
        )
      }
    }
    uiState.error?.let { problem ->
      Text(
          stringResource(problem.messageResource()),
          modifier =
              Modifier.padding(top = 12.dp).testTag(ProfileEditTestTags.FEEDBACK).semantics {
                liveRegion = LiveRegionMode.Polite
              },
          color = MaterialTheme.colorScheme.error,
          style = MaterialTheme.typography.bodySmall,
      )
    }
    if (uiState.saved) {
      Text(
          if (uiState.pendingEmail != null) {
            stringResource(R.string.profile_edit_email_verification, uiState.pendingEmail)
          } else stringResource(R.string.profile_edit_saved),
          modifier =
              Modifier.padding(top = 16.dp).testTag(ProfileEditTestTags.FEEDBACK).semantics {
                liveRegion = LiveRegionMode.Polite
              },
          style = MaterialTheme.typography.bodySmall,
      )
      Button(
          onClick = onCancel,
          modifier =
              Modifier.fillMaxWidth()
                  .padding(top = 16.dp)
                  .heightIn(min = 48.dp)
                  .testTag(ProfileEditTestTags.DONE),
          shape = CircleShape,
      ) {
        Text(stringResource(R.string.profile_edit_done))
      }
    } else {
      Button(
          onClick = onSave,
          enabled = uiState.canSave,
          modifier =
              Modifier.fillMaxWidth()
                  .padding(top = 16.dp)
                  .heightIn(min = 48.dp)
                  .testTag(ProfileEditTestTags.SAVE),
          shape = CircleShape,
      ) {
        if (uiState.isSaving) {
          CircularProgressIndicator(
              Modifier.size(16.dp).testTag(ProfileEditTestTags.SAVING),
              strokeWidth = 2.dp,
          )
          Spacer(Modifier.width(8.dp))
        }
        Text(
            stringResource(
                if (uiState.isSaving) R.string.profile_edit_saving else R.string.profile_edit_save
            ),
            style = MaterialTheme.typography.labelSmall,
        )
      }
      TextButton(
          onClick = onCancel,
          enabled = !uiState.isSaving,
          modifier =
              Modifier.align(Alignment.CenterHorizontally).testTag(ProfileEditTestTags.CANCEL),
      ) {
        Text(
            stringResource(R.string.profile_edit_cancel),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
      }
    }
  }
}

@Composable
private fun EditProfileField(
    value: String,
    onChange: (String) -> Unit,
    @StringRes label: Int,
    @StringRes placeholder: Int,
    tag: String,
    errorTag: String,
    errorMessage: String?,
    enabled: Boolean,
    email: Boolean,
    onSubmit: () -> Unit,
) {
  val colors = MaterialTheme.colorScheme
  val labelText = stringResource(label)
  val focus = LocalFocusManager.current
  Column(
      Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
      verticalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    Text(
        labelText,
        color =
            if (errorMessage != null) MaterialTheme.colorScheme.error else colors.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall,
    )
    BasicTextField(
        value = value,
        onValueChange = onChange,
        enabled = enabled,
        singleLine = true,
        modifier =
            Modifier.fillMaxWidth().testTag(tag).semantics {
              contentDescription = labelText
              errorMessage?.let { error(it) }
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
                onNext = { focus.moveFocus(FocusDirection.Down) },
                onDone = {
                  if (enabled) {
                    focus.clearFocus()
                    onSubmit()
                  }
                },
            ),
        decorationBox = { inner ->
          Box(
              Modifier.fillMaxWidth()
                  .heightIn(min = 48.dp)
                  .background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape)
                  .border(
                      if (errorMessage != null) 1.5.dp else 1.dp,
                      if (errorMessage != null) MaterialTheme.colorScheme.error
                      else colors.outlineVariant,
                      CircleShape,
                  )
                  .padding(horizontal = 16.dp, vertical = 12.dp),
              contentAlignment = Alignment.CenterStart,
          ) {
            if (value.isEmpty()) {
              Text(
                  stringResource(placeholder),
                  color = colors.onSurfaceVariant,
                  style = MaterialTheme.typography.bodyMedium,
              )
            }
            inner()
          }
        },
    )
    errorMessage?.let { message ->
      Text(
          message,
          modifier =
              Modifier.padding(start = 16.dp, top = 2.dp).testTag(errorTag).semantics {
                liveRegion = LiveRegionMode.Polite
              },
          color = MaterialTheme.colorScheme.error,
          style = MaterialTheme.typography.bodySmall,
      )
    }
  }
}

@StringRes
private fun ProfileSaveError.messageResource(): Int =
    when (this) {
      ProfileSaveError.NETWORK -> R.string.profile_edit_network_error
      ProfileSaveError.EMAIL_IN_USE -> R.string.profile_edit_email_in_use
      ProfileSaveError.RECENT_LOGIN_REQUIRED -> R.string.profile_edit_recent_login
      ProfileSaveError.SESSION_CHANGED -> R.string.profile_edit_session_changed
      ProfileSaveError.UNKNOWN -> R.string.profile_edit_unknown_error
    }

@Preview(name = "Edit Profile", widthDp = 412, heightDp = 830)
@Composable
private fun EditProfilePreview() {
  SampleAppTheme {
    EditProfileScreen(
        uiState = ProfileEditUiState(fullName = "Alex Morgan", email = "alex@example.com"),
        onFullNameChange = {},
        onEmailChange = {},
        onSave = {},
        onCancel = {},
    )
  }
}
