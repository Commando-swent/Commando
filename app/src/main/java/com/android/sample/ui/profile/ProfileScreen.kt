package com.android.sample.ui.profile

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.credentials.Credential
import com.android.sample.R
import com.android.sample.data.repository.FakeTripRepository
import com.android.sample.model.authentication.AuthException
import com.android.sample.model.authentication.AuthUser
import com.android.sample.model.authentication.FakeAuthRepository
import com.android.sample.ui.auth.GoogleCredentialClient
import com.android.sample.ui.navigation.AppMode
import com.android.sample.ui.navigation.AppScaffold
import com.android.sample.ui.navigation.CommandoApp
import com.android.sample.ui.navigation.CommandoScreens
import com.android.sample.ui.navigation.NavigationTestTags
import com.android.sample.ui.theme.SampleAppTheme

/** Displays repository-backed state; navigation and sign-out remain owned by the app root. */
@Composable
fun ProfileScreen(
    uiState: ProfileUiState,
    onBack: () -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
    signOutError: AuthException? = null,
) {
  Column(
      modifier
          .fillMaxSize()
          .background(MaterialTheme.colorScheme.background)
          .testTag(NavigationTestTags.PROFILE_SCREEN)
          .padding(horizontal = 36.dp)
          .padding(top = 4.dp, bottom = 24.dp),
  ) {
    Row(
        Modifier.fillMaxWidth().height(40.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
          stringResource(R.string.nav_profile),
          modifier = Modifier.weight(1f).testTag(NavigationTestTags.SCREEN_TITLE),
          style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.5.sp),
      )
      TextButton(onClick = onBack, modifier = Modifier.testTag(NavigationTestTags.BACK_BUTTON)) {
        Text(stringResource(R.string.nav_back), style = MaterialTheme.typography.labelSmall)
      }
    }
    Column(
        Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      when (uiState) {
        ProfileUiState.Loading -> {
          Column(
              Modifier.fillMaxWidth().padding(vertical = 48.dp).testTag(ProfileTestTags.LOADING),
              horizontalAlignment = Alignment.CenterHorizontally,
              verticalArrangement = Arrangement.spacedBy(16.dp),
          ) {
            CircularProgressIndicator(Modifier.size(32.dp))
            Text(
                stringResource(R.string.profile_loading),
                style = MaterialTheme.typography.bodyMedium,
            )
          }
        }
        ProfileUiState.MissingUser -> {
          Text(
              stringResource(R.string.profile_missing_user),
              modifier = Modifier.padding(vertical = 48.dp).testTag(ProfileTestTags.MISSING_USER),
              style = MaterialTheme.typography.bodyMedium,
              textAlign = TextAlign.Center,
          )
        }
        is ProfileUiState.Content -> ProfileInformation(uiState)
      }
    }
    if (signOutError != null) {
      // Provider details are never displayed, even when an exception contains a private cause.
      Text(
          stringResource(R.string.nav_sign_out_error),
          modifier = Modifier.padding(vertical = 12.dp).testTag(NavigationTestTags.SIGN_OUT_ERROR),
          color = MaterialTheme.colorScheme.error,
          style = MaterialTheme.typography.bodySmall,
      )
    }
    OutlinedButton(
        onClick = onSignOut,
        modifier =
            Modifier.fillMaxWidth()
                .heightIn(min = 48.dp)
                .testTag(NavigationTestTags.SIGN_OUT_BUTTON),
        shape = CircleShape,
        border = BorderStroke(0.75.dp, MaterialTheme.colorScheme.outlineVariant),
        colors =
            ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
    ) {
      Text(stringResource(R.string.nav_sign_out), style = MaterialTheme.typography.labelSmall)
    }
  }
}

@Preview(name = "Home / Profile · demo account", widthDp = 412, heightDp = 830)
@Composable
internal fun ProfilePreview() {
  val demoUser = remember {
    AuthUser(uid = "profile-preview", displayName = "Alex Morgan", email = "alex@example.com")
  }
  SampleAppTheme {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
      if (LocalInspectionMode.current) {
        // The editor renders state directly because ViewModels are unavailable in static previews.
        AppScaffold(
            currentScreen = CommandoScreens.Profile,
            mode = AppMode.Requester,
            onSwitchMode = {},
            onHome = {},
            onProfile = {},
        ) { padding ->
          ProfileScreen(
              uiState = ProfileUiState.Content(demoUser.displayName, demoUser.email),
              onBack = {},
              onSignOut = {},
              modifier = Modifier.padding(padding),
          )
        }
      } else {
        // Running this Preview on a device uses real navigation with a demo session only.
        val previewRepository = remember { FakeAuthRepository(demoUser) }
        val previewTrips = remember { FakeTripRepository(currentUserId = demoUser.uid) }
        val previewCredentials = remember {
          object : GoogleCredentialClient {
            override suspend fun request(context: Context): Credential? = null

            override suspend fun clearSession() {
              // The isolated demo preview has no Google credential session to clear.
            }
          }
        }
        CommandoApp(
            repository = previewRepository,
            tripRepository = previewTrips,
            googleCredentials = previewCredentials,
        )
      }
    }
  }
}
