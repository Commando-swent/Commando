package com.android.sample.emulator.firestore.security

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.android.sample.data.repository.ProfilePhotoRepositoryFirestore
import com.android.sample.model.ProfilePhotoSource
import com.android.sample.model.authentication.AuthUser
import com.android.sample.model.authentication.FakeAuthRepository
import com.google.firebase.firestore.Blob
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.Source
import java.io.File
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProfilePhotoFirestoreSecurityRulesEmulatorTest {
  @Test
  fun profilePhotoIsPrivateBoundedAndPersistsUntilRemoval() = runTest {
    withClients("owner" to true, "other" to true, "anonymous" to false) { (owner, other, anonymous)
      ->
      val uid = checkNotNull(owner.auth.currentUser).uid
      val auth = FakeAuthRepository(AuthUser(uid))
      val context = owner.app.applicationContext
      val repository =
          ProfilePhotoRepositoryFirestore(context.contentResolver, auth, owner.firestore)
      assertEquals(null, repository.load(uid))
      val bitmap = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888)
      val file = File.createTempFile("avatar", ".jpg")
      file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it) }
      val gallery = repository.save(uid, ProfilePhotoSource.Gallery(Uri.fromFile(file)))!!
      assertTrue(BitmapFactory.decodeByteArray(gallery, 0, gallery.size).width <= 512)
      file.delete()
      val bytes = repository.save(uid, ProfilePhotoSource.Camera(bitmap))!!
      assertTrue(bytes.size < ProfilePhotoRepositoryFirestore.MAX_BYTES)
      assertTrue(bytes.contentEquals(repository.load(uid)))
      val document = owner.firestore.collection("profilePhotos").document(uid)
      val otherPhoto = other.firestore.collection("profilePhotos").document(uid)
      val anonymousPhoto = anonymous.firestore.collection("profilePhotos").document(uid)
      assertPermissionDenied { otherPhoto.get(Source.SERVER).await() }
      assertPermissionDenied { anonymousPhoto.get(Source.SERVER).await() }
      assertPermissionDenied { otherPhoto.delete().await() }
      for (invalid in
          listOf(Blob.fromBytes(ByteArray(0)), Blob.fromBytes(ByteArray(204801)), "not bytes")) {
        val data = mapOf("photo" to invalid, "updatedAt" to FieldValue.serverTimestamp())
        assertPermissionDenied { document.set(data).await() }
      }
      assertEquals(null, repository.save(uid, null))
      assertEquals(null, repository.load(uid))
      auth.signOut()
      assertTrue(runCatching { repository.load(uid) }.exceptionOrNull() is IllegalStateException)
    }
  }
}
