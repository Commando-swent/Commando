package com.android.sample.ui.auth

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.credentials.Credential
import com.android.sample.R
import com.android.sample.model.authentication.AuthUser

/** The navigation owner supplies the ViewModel, Google account picker and destination callbacks. */
@Composable
fun AuthRoute(
    viewModel: AuthViewModel,
    requestGoogleCredential: suspend () -> Credential?,
    onAuthenticated: (AuthUser) -> Unit,
    onBack: () -> Unit,
) {
  val state by viewModel.uiState.collectAsState()
  val currentOnAuthenticated by rememberUpdatedState(onAuthenticated)
  LaunchedEffect(state.user) { state.user?.let { currentOnAuthenticated(it) } }
  BackHandler { if (!state.isLoading && state.user == null) onBack() }
  AuthScreen(
      state,
      viewModel::updateEmail,
      viewModel::updatePassword,
      viewModel::updateConfirmation,
      viewModel::switchMode,
      viewModel::submitEmail,
      { viewModel.signInWithGoogle(requestGoogleCredential) },
      onBack,
  )
}

@Composable
internal fun AuthScreen(
    state: AuthUiState,
    onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onConfirmationChange: (String) -> Unit,
    onModeChange: (AuthMode) -> Unit,
    onSubmit: () -> Unit,
    onGoogle: () -> Unit,
    onBack: () -> Unit,
) = AuthTheme {
  val signup = state.mode == AuthMode.SIGN_UP
  val enabled = !state.isLoading && state.user == null
  val colors = MaterialTheme.colorScheme
  val focus = LocalFocusManager.current
  val submit = {
    focus.clearFocus()
    onSubmit()
  }
  Box(
      Modifier.fillMaxSize().background(colors.background).safeDrawingPadding().imePadding(),
      contentAlignment = Alignment.TopCenter,
  ) {
    Column(
        Modifier.widthIn(max = 460.dp)
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Image(painterResource(R.drawable.auth_logo), null, Modifier.size(32.dp))
        Text(stringResource(R.string.auth_brand), style = MaterialTheme.typography.titleMedium)
      }
      TextButton(
          onClick = onBack,
          enabled = enabled,
          modifier = Modifier.testTag("auth_back"),
          contentPadding = PaddingValues(0.dp),
      ) {
        Text(
            stringResource(R.string.auth_back),
            color = colors.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
        )
      }
      Text(
          stringResource(if (signup) R.string.auth_signup_title else R.string.auth_login_title),
          style = MaterialTheme.typography.headlineLarge,
      )
      Text(
          stringResource(
              if (signup) R.string.auth_signup_subtitle else R.string.auth_login_subtitle
          ),
          color = colors.onSurfaceVariant,
      )
      Row(
          Modifier.fillMaxWidth()
              .height(48.dp)
              .background(colors.surface, RoundedCornerShape(100.dp))
              .padding(4.dp),
          horizontalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        AuthMode.entries.forEach { mode ->
          val selected = state.mode == mode
          Box(
              Modifier.weight(1f)
                  .fillMaxHeight()
                  .background(
                      if (selected) colors.secondaryContainer else colors.surface,
                      RoundedCornerShape(100.dp),
                  )
                  .selectable(
                      selected,
                      enabled = enabled,
                      role = Role.Tab,
                      onClick = { onModeChange(mode) },
                  )
                  .testTag(if (mode == AuthMode.LOGIN) "auth_tab_login" else "auth_tab_signup"),
              contentAlignment = Alignment.Center,
          ) {
            Text(
                stringResource(
                    if (mode == AuthMode.LOGIN) R.string.auth_login else R.string.auth_signup
                ),
                style = MaterialTheme.typography.labelMedium,
            )
          }
        }
      }
      Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AuthField(
            state.email,
            onEmailChange,
            R.string.auth_email,
            R.string.auth_email_hint,
            "auth_email",
            state.emailError,
            enabled,
            password = false,
            done = false,
            submit,
        )
        AuthField(
            state.password,
            onPasswordChange,
            R.string.auth_password,
            R.string.auth_password_hint,
            "auth_password",
            state.passwordError,
            enabled,
            password = true,
            done = !signup,
            submit,
        )
        if (signup)
            AuthField(
                state.confirmation,
                onConfirmationChange,
                R.string.auth_confirmation,
                R.string.auth_confirmation_hint,
                "auth_confirmation",
                state.confirmationError,
                enabled,
                password = true,
                done = true,
                submit,
            )
        state.authError?.let { AuthError(stringResource(it.messageResource())) }
        Button(
            onClick = submit,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().height(56.dp).testTag("auth_submit"),
        ) {
          if (state.isLoading) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
          }
          Text(
              stringResource(
                  if (state.isLoading) R.string.auth_loading
                  else if (signup) R.string.auth_create else R.string.auth_login_action
              )
          )
        }
      }
      Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        HorizontalDivider(Modifier.weight(1f), color = colors.outlineVariant)
        Text(
            stringResource(R.string.auth_or),
            color = colors.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        HorizontalDivider(Modifier.weight(1f), color = colors.outlineVariant)
      }
      OutlinedButton(
          onClick = onGoogle,
          enabled = enabled,
          modifier = Modifier.fillMaxWidth().height(56.dp).testTag("auth_google"),
          colors =
              ButtonDefaults.outlinedButtonColors(
                  containerColor = colors.surface,
                  contentColor = colors.onSurface,
              ),
          border = androidx.compose.foundation.BorderStroke(1.dp, colors.outline),
      ) {
        Text("G")
        Spacer(Modifier.width(12.dp))
        Text(stringResource(R.string.auth_google))
      }
      TextButton(
          onClick = { onModeChange(if (signup) AuthMode.LOGIN else AuthMode.SIGN_UP) },
          enabled = enabled,
          contentPadding = PaddingValues(0.dp),
          modifier = Modifier.testTag("auth_switch"),
      ) {
        Text(
            stringResource(if (signup) R.string.auth_login_footer else R.string.auth_signup_footer),
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
        )
      }
    }
  }
}

@Preview(name = "Log in", widthDp = 412, heightDp = 915)
@Composable
private fun LoginPreview() = AuthPreview(AuthMode.LOGIN)

@Preview(name = "Sign up", widthDp = 412, heightDp = 915)
@Composable
private fun SignupPreview() = AuthPreview(AuthMode.SIGN_UP)

@Composable
private fun AuthPreview(mode: AuthMode) {
  var state by remember { mutableStateOf(AuthUiState(mode = mode)) }
  AuthScreen(
      state,
      { state = state.copy(email = it) },
      { state = state.copy(password = it) },
      { state = state.copy(confirmation = it) },
      { state = AuthUiState(mode = it) },
      {},
      {},
      {},
  )
}
