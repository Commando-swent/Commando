package com.android.sample.ui.profile

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.sample.R
import com.android.sample.model.authentication.AuthException
import com.android.sample.model.authentication.AuthUser
import com.android.sample.model.authentication.FakeAuthRepository
import com.android.sample.ui.navigation.AppMode
import com.android.sample.ui.navigation.AppScaffold
import com.android.sample.ui.navigation.CommandoApp
import com.android.sample.ui.navigation.CommandoScreens
import com.android.sample.ui.navigation.NavigationTestTags
import com.android.sample.ui.theme.SampleAppTheme

// The avatar and field icons share this surface in the Profile Figma design.
private val ProfileIconSurface = Color(0xFF2F3A25)

/** Displays repository-backed state; navigation and sign-out remain owned by the app root. */
@Composable
fun ProfileScreen(
    uiState: ProfileUiState,
    onBack: () -> Unit,
    onSignOut: () -> Unit,
    signOutError: AuthException? = null,
    modifier: Modifier = Modifier,
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
        is ProfileUiState.Content -> ProfileContent(uiState)
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

@Composable
private fun ProfileContent(profile: ProfileUiState.Content) {
  val fullName = profile.fullName?.takeUnless { it.isBlank() }
  Column(
      Modifier.fillMaxWidth().heightIn(min = 111.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    ProfileAvatar(profileInitials(fullName))
    if (fullName != null) {
      Text(
          fullName,
          modifier = Modifier.padding(top = 7.5.dp),
          style =
              MaterialTheme.typography.bodyMedium.copy(
                  fontSize = 13.5.sp,
                  lineHeight = 19.sp,
                  fontWeight = FontWeight.Medium,
              ),
          textAlign = TextAlign.Center,
      )
    }
  }
  Row(
      Modifier.fillMaxWidth().padding(start = 3.dp, bottom = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
        stringResource(R.string.profile_personal_information),
        modifier = Modifier.weight(1f),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall.copy(fontSize = 8.25.sp, lineHeight = 13.sp),
    )
    // Editing belongs to the next sub-issue; retain its affordance without a fake action.
    TextButton(
        onClick = {},
        enabled = false,
        modifier = Modifier.height(21.dp).testTag(ProfileTestTags.EDIT),
        contentPadding = PaddingValues(horizontal = 3.dp),
        colors =
            ButtonDefaults.textButtonColors(
                disabledContentColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
            ),
    ) {
      Text(
          stringResource(R.string.profile_edit),
          style = MaterialTheme.typography.bodySmall.copy(fontSize = 8.25.sp, lineHeight = 13.sp),
      )
    }
  }
  Surface(
      modifier = Modifier.fillMaxWidth(),
      shape = RoundedCornerShape(13.5.dp),
      color = MaterialTheme.colorScheme.surfaceContainer,
      border = BorderStroke(0.75.dp, MaterialTheme.colorScheme.outlineVariant),
  ) {
    Column {
      ProfileField(
          R.string.profile_full_name,
          fullName,
          R.drawable.profile_person,
          ProfileTestTags.FULL_NAME,
      )
      HorizontalDivider(thickness = 0.75.dp, color = MaterialTheme.colorScheme.outlineVariant)
      ProfileField(
          R.string.profile_email,
          profile.email,
          R.drawable.profile_email,
          ProfileTestTags.EMAIL,
      )
    }
  }
}

@Composable
private fun ProfileAvatar(initials: String?) {
  val addPhotoDescription = stringResource(R.string.profile_add_photo_description)
  Box(Modifier.size(64.5.dp)) {
    Box(
        Modifier.size(60.dp)
            .background(ProfileIconSurface, CircleShape)
            .border(2.25.dp, MaterialTheme.colorScheme.primary, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
      if (initials == null) {
        Image(
            painterResource(R.drawable.profile_person),
            contentDescription = stringResource(R.string.profile_neutral_avatar),
            modifier = Modifier.size(28.dp).testTag(ProfileTestTags.NEUTRAL_AVATAR),
        )
      } else {
        Text(
            initials,
            modifier = Modifier.testTag(ProfileTestTags.INITIALS),
            color = MaterialTheme.colorScheme.primary,
            style =
                MaterialTheme.typography.titleMedium.copy(
                    fontSize = 19.5.sp,
                    fontWeight = FontWeight.Medium,
                ),
        )
      }
    }
    // This disabled badge has no touch target yet; keep its visual size equal to the design.
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
      FilledIconButton(
          onClick = {},
          enabled = false,
          modifier =
              Modifier.align(Alignment.BottomEnd)
                  .offset(x = (-2.25).dp, y = (-2.25).dp)
                  .size(21.dp)
                  .border(2.25.dp, MaterialTheme.colorScheme.background, CircleShape)
                  .semantics { contentDescription = addPhotoDescription }
                  .testTag(ProfileTestTags.ADD_PHOTO),
          colors =
              IconButtonDefaults.filledIconButtonColors(
                  disabledContainerColor = MaterialTheme.colorScheme.primary,
                  disabledContentColor = MaterialTheme.colorScheme.onPrimary,
              ),
      ) {
        val plusColor = MaterialTheme.colorScheme.onPrimary
        Canvas(Modifier.size(9.dp)) {
          val stroke = 1.5.dp.toPx()
          drawLine(plusColor, Offset(0f, center.y), Offset(size.width, center.y), stroke)
          drawLine(plusColor, Offset(center.x, 0f), Offset(center.x, size.height), stroke)
        }
      }
    }
  }
}

@Composable
private fun ProfileField(
    @StringRes label: Int,
    value: String?,
    @DrawableRes icon: Int,
    valueTag: String,
) {
  Row(
      Modifier.fillMaxWidth()
          .heightIn(min = 40.dp)
          .padding(horizontal = 10.5.dp, vertical = 8.25.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(7.5.dp),
  ) {
    Box(
        Modifier.size(21.dp).background(ProfileIconSurface, RoundedCornerShape(9.dp)),
        contentAlignment = Alignment.Center,
    ) {
      Image(painterResource(icon), contentDescription = null, modifier = Modifier.size(12.dp))
    }
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.5.dp)) {
      Text(
          stringResource(label),
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          style = MaterialTheme.typography.bodySmall.copy(fontSize = 8.25.sp, lineHeight = 10.sp),
      )
      Text(
          value?.takeUnless { it.isBlank() } ?: stringResource(R.string.profile_missing_value),
          modifier = Modifier.testTag(valueTag),
          style = MaterialTheme.typography.bodySmall.copy(fontSize = 9.75.sp, lineHeight = 12.sp),
      )
    }
  }
}

@Preview(name = "Home / Profile · demo account", widthDp = 412, heightDp = 830)
@Composable
private fun ProfilePreview() {
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
        CommandoApp(repository = previewRepository)
      }
    }
  }
}
