package com.android.sample.data.repository

import com.android.sample.model.ProfilePhotoSource

interface ProfilePhotoRepository {
  suspend fun load(userId: String): ByteArray?

  suspend fun save(userId: String, source: ProfilePhotoSource?): ByteArray?
}
