package com.android.sample.model

import android.graphics.Bitmap
import android.net.Uri

sealed interface ProfilePhotoSource {
  data class Gallery(val uri: Uri) : ProfilePhotoSource

  data class Camera(val bitmap: Bitmap) : ProfilePhotoSource
}
