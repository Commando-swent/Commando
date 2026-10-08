package com.android.sample.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.android.sample.R
import com.android.sample.model.authentication.AuthException

@Composable
internal fun AuthField(
    value: String,
    onChange: (String) -> Unit,
    label: Int,
    hint: Int,
    tag: String,
    fieldError: FieldError?,
    enabled: Boolean,
    password: Boolean,
    done: Boolean,
    keyboard: KeyboardOptions =
        KeyboardOptions(keyboardType = if (password) KeyboardType.Password else KeyboardType.Email),
    onSubmit: () -> Unit,
) {
  val colors = MaterialTheme.colorScheme
  val labelText = stringResource(label)
  val errorText = fieldError?.let {
    stringResource(
        when (it) {
          FieldError.REQUIRED -> R.string.auth_required
          FieldError.INVALID_EMAIL -> R.string.auth_invalid_email
          FieldError.PASSWORD_MISMATCH -> R.string.auth_password_mismatch
        }
    )
  }
  val focus = LocalFocusManager.current
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text(labelText, color = colors.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
    BasicTextField(
        value,
        onChange,
        enabled = enabled,
        singleLine = true,
        modifier =
            Modifier.fillMaxWidth().heightIn(min = 58.dp).testTag(tag).semantics {
              contentDescription = labelText
              errorText?.let { error(it) }
            },
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
        cursorBrush = SolidColor(colors.primary),
        visualTransformation =
            if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = keyboard.copy(imeAction = if (done) ImeAction.Done else ImeAction.Next),
        keyboardActions =
            KeyboardActions(
                onDone = { if (enabled) onSubmit() },
                onNext = { focus.moveFocus(androidx.compose.ui.focus.FocusDirection.Down) },
            ),
        decorationBox = { inner ->
          Box(
              Modifier.background(colors.surface, RoundedCornerShape(16.dp))
                  .border(
                      1.dp,
                      if (errorText != null) colors.error else colors.outlineVariant,
                      RoundedCornerShape(16.dp),
                  )
                  .padding(horizontal = 18.dp, vertical = 16.dp),
              contentAlignment = Alignment.CenterStart,
          ) {
            if (value.isEmpty()) Text(stringResource(hint), color = colors.outline)
            inner()
          }
        },
    )
    errorText?.let { AuthError(it) }
  }
}

@Composable
internal fun AuthError(message: String) {
  Text(
      message,
      color = MaterialTheme.colorScheme.error,
      style = MaterialTheme.typography.bodySmall,
      modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
  )
}

internal fun AuthException.messageResource() =
    when (this) {
      is AuthException.InvalidEmail -> R.string.auth_invalid_email
      is AuthException.EmailAlreadyInUse -> R.string.auth_email_used
      is AuthException.AccountConflict -> R.string.auth_account_conflict
      is AuthException.InvalidCredentials -> R.string.auth_invalid_credentials
      is AuthException.InvalidGoogleCredential -> R.string.auth_google_error
      is AuthException.Network -> R.string.auth_network_error
      is AuthException.TooManyRequests -> R.string.auth_too_many_requests
      is AuthException.InvalidName,
      is AuthException.RequiresRecentLogin,
      is AuthException.SessionChanged -> R.string.auth_unknown_error
      is AuthException.Unknown -> R.string.auth_unknown_error
    }
