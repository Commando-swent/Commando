package com.android.sample.ui.profile

import android.graphics.BitmapFactory
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.android.sample.R
import java.util.Base64

@Composable
internal fun ProfileInformation(
    profile: ProfileUiState.Content,
    modifier: Modifier = Modifier,
    onEdit: (() -> Unit)? = null,
    photo: PhotoUiState? = null,
    onPhotoClick: (() -> Unit)? = null,
) {
  Column(modifier) {
    val fullName = profile.fullName?.takeUnless { it.isBlank() }
    val onPreview = ProfilePhotoPreview(profileInitials(fullName), photo?.image, fullName)
    Column(
        Modifier.fillMaxWidth().heightIn(min = 148.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      ProfileAvatar(
          profileInitials(fullName),
          photo = photo?.image,
          onPhotoClick = onPhotoClick,
          photoEnabled = photo?.busy != true,
          onAvatarClick = onPreview,
      )
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
internal fun ProfileAvatar(
    initials: String?,
    showPhotoAction: Boolean = true,
    photo: String? = null,
    onPhotoClick: (() -> Unit)? = null,
    photoEnabled: Boolean = true,
    diameter: Dp = 80.dp,
    onAvatarClick: (() -> Unit)? = null,
) {
  val addPhotoDescription = stringResource(R.string.profile_add_photo_description)
  val image =
      remember(photo) {
        photo
            ?.let { Base64.getDecoder().decode(it) }
            ?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
      }
  Box(Modifier.size(diameter + 6.dp)) {
    Box(
        Modifier.size(diameter)
            .clickable(enabled = onAvatarClick != null, onClick = onAvatarClick ?: {})
            .testTag(
                if (image != null) ProfileTestTags.PHOTO_IMAGE
                else if (initials == null) ProfileTestTags.NEUTRAL_AVATAR
                else ProfileTestTags.INITIALS
            )
            .background(
                if (diameter > 80.dp) MaterialTheme.colorScheme.surfaceContainerHigh
                else MaterialTheme.colorScheme.surfaceContainerHighest,
                CircleShape,
            )
            .border(
                if (diameter > 80.dp) diameter * 0.0154f else 3.dp,
                MaterialTheme.colorScheme.primary,
                CircleShape,
            ),
        contentAlignment = Alignment.Center,
    ) {
      if (image != null) {
        Image(
            image,
            stringResource(R.string.profile_photo_description),
            Modifier.size(diameter - 6.dp).clip(CircleShape),
            contentScale = ContentScale.Crop,
        )
      } else if (initials == null) {
        Image(
            painterResource(R.drawable.profile_person),
            contentDescription = stringResource(R.string.profile_neutral_avatar),
            modifier = Modifier.size(diameter * 0.45f),
        )
      } else {
        Text(
            initials,
            color = MaterialTheme.colorScheme.primary,
            style =
                MaterialTheme.typography.headlineSmall.copy(
                    fontSize = if (diameter == 80.dp) 24.sp else (diameter.value * 0.277f).sp,
                    fontWeight = if (diameter > 80.dp) FontWeight.Medium else FontWeight.Normal,
                ),
        )
      }
    }
    if (!showPhotoAction) return@Box
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
      FilledIconButton(
          onClick = onPhotoClick ?: {},
          enabled = onPhotoClick != null && photoEnabled,
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

@Composable
internal fun ProfilePhotoPreview(initials: String?, photo: String?, name: String?): () -> Unit {
  var open by remember { mutableStateOf(false) }
  if (open)
      Dialog(
          onDismissRequest = { open = false },
          properties = DialogProperties(usePlatformDefaultWidth = false),
      ) {
        BoxWithConstraints(
            Modifier.fillMaxSize()
                .background(MaterialTheme.colorScheme.background.copy(alpha = 0.85f))
                .testTag(ProfileTestTags.PHOTO_PREVIEW)
        ) {
          val diameter = maxWidth * 0.73f
          Column(
              Modifier.fillMaxWidth().padding(top = (maxHeight - diameter) / 4),
              horizontalAlignment = Alignment.CenterHorizontally,
              verticalArrangement = Arrangement.spacedBy(24.dp),
          ) {
            val close = stringResource(R.string.profile_photo_close)
            IconButton(
                onClick = { open = false },
                modifier =
                    Modifier.align(Alignment.End)
                        .padding(end = 12.dp)
                        .testTag(ProfileTestTags.PHOTO_CLOSE)
                        .semantics { contentDescription = close },
            ) {
              Text("✕", style = MaterialTheme.typography.headlineSmall)
            }
            ProfileAvatar(initials, showPhotoAction = false, photo = photo, diameter = diameter)
            name?.let {
              Text(
                  it,
                  style = MaterialTheme.typography.titleMedium,
                  fontSize = (diameter.value * 0.077f).sp,
                  fontWeight = FontWeight.Medium,
              )
            }
          }
        }
      }
  return { open = true }
}
