package com.android.sample.ui.profile

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.android.sample.R
import com.android.sample.model.ProfilePhotoSource

/** Platform launchers stay in the route; screens only receive state and named actions. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfilePhotoActions(model: ProfileViewModel): Pair<PhotoUiState, () -> Unit> {
  val state by model.photoState.collectAsState()
  var open by remember { mutableStateOf(false) }
  var owner by rememberSaveable { mutableStateOf<String?>(null) }
  fun acceptPhoto(source: ProfilePhotoSource?) {
    val selectedBy = owner
    owner = null
    if (source != null && selectedBy != null) model.savePhoto(selectedBy, source)
  }
  val gallery =
      rememberLauncherForActivityResult(PickVisualMedia()) {
        acceptPhoto(it?.let(ProfilePhotoSource::Gallery))
      }
  val camera =
      rememberLauncherForActivityResult(TakePicturePreview()) {
        acceptPhoto(it?.let(ProfilePhotoSource::Camera))
      }
  fun launch(cameraSelected: Boolean) {
    open = false
    owner = model.photoOwner() ?: return
    try {
      if (cameraSelected) camera.launch(null)
      else gallery.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly))
    } catch (_: ActivityNotFoundException) {
      owner = null
    } catch (_: SecurityException) {
      owner = null
    }
  }
  if (open)
      ModalBottomSheet(
          onDismissRequest = { open = false },
          containerColor = MaterialTheme.colorScheme.surfaceContainer,
          shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
      ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Text(
              stringResource(R.string.profile_photo_title),
              style = MaterialTheme.typography.titleMedium,
          )
          PhotoOption(R.string.profile_photo_camera, R.drawable.profile_camera, { launch(true) })
          PhotoOption(R.string.profile_photo_gallery, R.drawable.profile_gallery, { launch(false) })
          PhotoOption(
              R.string.profile_photo_remove,
              R.drawable.profile_remove,
              {
                open = false
                model.photoOwner()?.let { model.savePhoto(it, null) }
              },
              enabled = state.image != null,
          )
          TextButton(onClick = { open = false }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.profile_edit_cancel))
          }
        }
      }
  return state to { if (!state.busy) open = true }
}

@Composable
private fun PhotoOption(label: Int, icon: Int, onClick: () -> Unit, enabled: Boolean = true) {
  val colors = MaterialTheme.colorScheme
  val tint = if (label == R.string.profile_photo_remove) colors.error else colors.onSurface
  OutlinedButton(
      onClick = onClick,
      enabled = enabled,
      modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
      shape = RoundedCornerShape(14.dp),
      contentPadding = PaddingValues(14.dp),
      border = BorderStroke(1.dp, colors.outlineVariant),
      colors =
          ButtonDefaults.outlinedButtonColors(
              contentColor = tint,
              disabledContentColor = tint.copy(alpha = 0.4f),
          ),
  ) {
    Image(painterResource(icon), null, Modifier.size(24.dp).alpha(if (enabled) 1f else 0.4f))
    Spacer(Modifier.width(12.dp))
    Text(stringResource(label), Modifier.weight(1f))
  }
}

object ProfilePhotoTestTags {
  const val IMAGE = "profile_photo_image"
  const val PREVIEW = "profile_photo_preview"
  const val CLOSE = "profile_photo_close"
}
