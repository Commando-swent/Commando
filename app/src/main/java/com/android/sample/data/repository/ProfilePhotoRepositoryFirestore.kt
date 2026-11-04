package com.android.sample.data.repository

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import androidx.core.graphics.scale
import com.android.sample.model.ProfilePhotoSource
import com.android.sample.model.authentication.AuthRepository
import com.google.firebase.firestore.Blob
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/** Private Firestore photos with image encoding delegated to the injected IO dispatcher. */
class ProfilePhotoRepositoryFirestore(
    private val resolver: ContentResolver,
    private val auth: AuthRepository,
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ProfilePhotoRepository {
  private fun document(userId: String) = firestore.collection("profilePhotos").document(userId)

  private fun checkSession(userId: String) = check(auth.currentUser?.uid == userId)

  override suspend fun load(userId: String): ByteArray? {
    checkSession(userId)
    val bytes = document(userId).get(Source.SERVER).await().getBlob("photo")?.toBytes()
    checkSession(userId)
    return bytes
  }

  override suspend fun save(userId: String, source: ProfilePhotoSource?): ByteArray? {
    checkSession(userId)
    val bytes = source?.let { withContext(ioDispatcher) { encode(it) } }
    checkSession(userId)
    val document = document(userId)
    if (bytes == null) document.delete().await()
    else {
      val data =
          mapOf("photo" to Blob.fromBytes(bytes), "updatedAt" to FieldValue.serverTimestamp())
      document.set(data).await()
    }
    checkSession(userId)
    return bytes
  }

  internal fun encode(source: ProfilePhotoSource): ByteArray {
    val bitmap =
        when (source) {
          is ProfilePhotoSource.Camera -> source.bitmap
          is ProfilePhotoSource.Gallery -> {
            val input = ImageDecoder.createSource(resolver, source.uri)
            ImageDecoder.decodeBitmap(input) { decoder, info, _ ->
              val longest = maxOf(info.size.width, info.size.height)
              decoder.setTargetSampleSize((longest + MAX_SIDE - 1) / MAX_SIDE)
              decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
          }
        }
    val scale = minOf(1f, MAX_SIDE.toFloat() / maxOf(bitmap.width, bitmap.height))
    val resized =
        bitmap.scale(
            maxOf(1, (bitmap.width * scale).toInt()),
            maxOf(1, (bitmap.height * scale).toInt()),
            filter = true,
        )
    val output = ByteArrayOutputStream()
    check(resized.compress(Bitmap.CompressFormat.JPEG, 80, output))
    return output.toByteArray().also { require(it.size in 1..MAX_BYTES) }
  }

  companion object {
    const val MAX_BYTES = 200 * 1024
    private const val MAX_SIDE = 512
  }
}
