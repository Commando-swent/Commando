package com.android.sample.ui.profile

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.android.sample.R

/** Shows confirmed profile information; editing and photo actions belong to follow-up tasks. */
@Composable
internal fun ProfileInformation(
    profile: ProfileUiState.Content,
    modifier: Modifier = Modifier,
    onEdit: (() -> Unit)? = null,
) {
  Column(modifier) {
    val fullName = profile.fullName?.takeUnless { it.isBlank() }
    Column(
        Modifier.fillMaxWidth().heightIn(min = 148.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      ProfileAvatar(profileInitials(fullName))
      if (fullName != null) {
        Text(
            fullName,
            modifier = Modifier.padding(top = 10.dp),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
      }
    }
    Row(
        Modifier.fillMaxWidth().padding(start = 4.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
          stringResource(R.string.profile_personal_information),
          modifier = Modifier.weight(1f),
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          style = MaterialTheme.typography.bodySmall,
      )
      // Editing belongs to the next sub-issue; retain its affordance without a fake action.
      TextButton(
          onClick = { onEdit?.invoke() },
          enabled = onEdit != null,
          modifier = Modifier.heightIn(min = 48.dp).testTag(ProfileTestTags.EDIT),
          contentPadding = PaddingValues(horizontal = 4.dp),
          colors =
              ButtonDefaults.textButtonColors(
                  disabledContentColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
              ),
      ) {
        Text(
            stringResource(R.string.profile_edit),
            style = MaterialTheme.typography.bodySmall,
        )
      }
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
      Column {
        ProfileField(
            R.string.profile_full_name,
            fullName,
            R.drawable.profile_person,
            ProfileTestTags.FULL_NAME,
        )
        HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
        ProfileField(
            R.string.profile_email,
            profile.email,
            R.drawable.profile_email,
            ProfileTestTags.EMAIL,
        )
      }
    }
  }
}

@Composable
private fun ProfileAvatar(initials: String?) {
  val addPhotoDescription = stringResource(R.string.profile_add_photo_description)
  Box(Modifier.size(86.dp)) {
    Box(
        Modifier.size(80.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape)
            .border(3.dp, MaterialTheme.colorScheme.primary, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
      if (initials == null) {
        Image(
            painterResource(R.drawable.profile_person),
            contentDescription = stringResource(R.string.profile_neutral_avatar),
            modifier = Modifier.size(36.dp).testTag(ProfileTestTags.NEUTRAL_AVATAR),
        )
      } else {
        Text(
            initials,
            modifier = Modifier.testTag(ProfileTestTags.INITIALS),
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.headlineSmall,
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
                  .offset(x = (-3).dp, y = (-3).dp)
                  .size(28.dp)
                  .border(3.dp, MaterialTheme.colorScheme.background, CircleShape)
                  .semantics { contentDescription = addPhotoDescription }
                  .testTag(ProfileTestTags.ADD_PHOTO),
          colors =
              IconButtonDefaults.filledIconButtonColors(
                  disabledContainerColor = MaterialTheme.colorScheme.primary,
                  disabledContentColor = MaterialTheme.colorScheme.onPrimary,
              ),
      ) {
        val plusColor = MaterialTheme.colorScheme.onPrimary
        Canvas(Modifier.size(12.dp)) {
          val stroke = 2.dp.toPx()
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
      Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 14.dp, vertical = 11.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Box(
        Modifier.size(28.dp)
            .background(
                MaterialTheme.colorScheme.surfaceContainerHighest,
                RoundedCornerShape(12.dp),
            ),
        contentAlignment = Alignment.Center,
    ) {
      Image(painterResource(icon), contentDescription = null, modifier = Modifier.size(16.dp))
    }
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
      Text(
          stringResource(label),
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          style = MaterialTheme.typography.bodySmall,
      )
      Text(
          value?.takeUnless { it.isBlank() } ?: stringResource(R.string.profile_missing_value),
          modifier = Modifier.testTag(valueTag),
          style = MaterialTheme.typography.bodyMedium,
      )
    }
  }
}
