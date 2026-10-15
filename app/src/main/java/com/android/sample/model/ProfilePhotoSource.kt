package com.android.sample.model

import android.graphics.Bitmap
import android.net.Uri

/** A selection from the system gallery or camera; null means remove the saved photo. */
sealed interface ProfilePhotoSource {
  data class Gallery(val uri: Uri) : ProfilePhotoSource

  data class Camera(val bitmap: Bitmap) : ProfilePhotoSource
}
