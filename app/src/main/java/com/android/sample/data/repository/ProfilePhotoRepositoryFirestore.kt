package com.android.sample.data.repository

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import com.android.sample.model.ProfilePhotoSource
import com.android.sample.model.authentication.AuthRepository
import com.google.firebase.firestore.Blob
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/** Stores a bounded avatar, rather than the original photo, in the owner's private document. */
class ProfilePhotoRepositoryFirestore(
    private val resolver: ContentResolver,
    private val auth: AuthRepository,
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance(),
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
    val bytes = source?.let { withContext(Dispatchers.IO) { encode(it) } }
    checkSession(userId)
    if (bytes == null) document(userId).delete().await()
    else
        document(userId)
            .set(
                mapOf("photo" to Blob.fromBytes(bytes), "updatedAt" to FieldValue.serverTimestamp())
            )
            .await()
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
        Bitmap.createScaledBitmap(
            bitmap,
            maxOf(1, (bitmap.width * scale).toInt()),
            maxOf(1, (bitmap.height * scale).toInt()),
            true,
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
