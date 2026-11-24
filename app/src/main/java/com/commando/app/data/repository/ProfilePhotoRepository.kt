package com.commando.app.data.repository

import com.commando.app.model.ProfilePhotoSource

interface ProfilePhotoRepository {
  suspend fun load(userId: String): ByteArray?

  suspend fun save(userId: String, source: ProfilePhotoSource?): ByteArray?
}
