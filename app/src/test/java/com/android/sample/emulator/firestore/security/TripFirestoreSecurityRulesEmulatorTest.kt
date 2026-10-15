package com.android.sample.emulator.firestore.security

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.android.sample.data.repository.ProfilePhotoRepositoryFirestore
import com.android.sample.data.repository.TripRepositoryFirestore
import com.android.sample.data.repository.successData
import com.android.sample.model.Location
import com.android.sample.model.NewTrip
import com.android.sample.model.ProfilePhotoSource
import com.android.sample.model.TripStatus
import com.android.sample.model.authentication.AuthUser
import com.android.sample.model.authentication.FakeAuthRepository
import com.google.firebase.Timestamp
import com.google.firebase.firestore.Blob
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.Source
import java.io.File
import java.time.Instant
import java.util.UUID
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
class TripFirestoreSecurityRulesEmulatorTest {

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

  @Test
  fun create_requiresAuthenticationAndMatchingOwner() = runTest {
    withClients(
        "unauthenticated" to false,
        "owner" to true,
        "other" to true,
    ) { (unauthenticated, owner, other) ->
      val ownerId = checkNotNull(owner.auth.currentUser).uid
      val otherId = checkNotNull(other.auth.currentUser).uid
      val trips = owner.firestore.collection(TRIPS_COLLECTION)

      assertPermissionDenied {
        unauthenticated.firestore
            .collection(TRIPS_COLLECTION)
            .document("unauthenticated-${UUID.randomUUID()}")
            .set(storedTrip(ownerId))
            .await()
      }
      val publishedTrip =
          TripRepositoryFirestore(
                  authRepository = FakeAuthRepository(AuthUser(uid = ownerId)),
                  firestore = owner.firestore,
                  now = { NOW },
              )
              .publishTrip(
                  NewTrip(
                      store = Location("Migros", latitude = 46.5191, longitude = 6.6332),
                      scheduledAt = NOW.plusSeconds(3_600),
                      handoffLocation = Location("EPFL", latitude = 46.5188, longitude = 6.5665),
                  )
              )
              .successData()
      assertTrue(trips.document(publishedTrip.id).get().await().exists())
      assertPermissionDenied {
        owner.firestore
            .collection(TRIPS_COLLECTION)
            .document("spoofed-${UUID.randomUUID()}")
            .set(storedTrip(otherId))
            .await()
      }
    }
  }

  @Test
  fun create_rejectsOtherwiseValidNonPublishedTrip() = runTest {
    withClients("owner" to true) { (owner) ->
      val ownerId = checkNotNull(owner.auth.currentUser).uid

      assertPermissionDenied {
        owner.firestore
            .collection(TRIPS_COLLECTION)
            .document("non-published-${UUID.randomUUID()}")
            .set(storedTrip(ownerId, status = TripStatus.IN_PROGRESS.name))
            .await()
      }
    }
  }

  @Test
  fun create_rejectsDifferentCreationAndUpdateTimestamps() = runTest {
    withClients("owner" to true) { (owner) ->
      val ownerId = checkNotNull(owner.auth.currentUser).uid

      assertPermissionDenied {
        owner.firestore
            .collection(TRIPS_COLLECTION)
            .document("different-timestamps-${UUID.randomUUID()}")
            .set(storedTrip(ownerId, updatedAt = NOW.plusSeconds(1)))
            .await()
      }
    }
  }

  @Test
  fun read_requiresAuthenticationAndRespectsVisibility() = runTest {
    withClients(
        "unauthenticated" to false,
        "owner" to true,
        "other" to true,
    ) { (unauthenticated, owner, other) ->
      val ownerId = checkNotNull(owner.auth.currentUser).uid
      val trips = owner.firestore.collection(TRIPS_COLLECTION)
      val published = trips.document("published-${UUID.randomUUID()}")
      val nonPublished = trips.document("non-published-${UUID.randomUUID()}")

      published.set(storedTrip(ownerId)).await()
      val otherPublished = other.firestore.collection(TRIPS_COLLECTION).document(published.id)
      assertTrue(otherPublished.get().await().exists())
      assertPermissionDenied {
        unauthenticated.firestore.collection(TRIPS_COLLECTION).document(published.id).get().await()
      }

      nonPublished.set(storedTrip(ownerId)).await()
      nonPublished.update("status", TripStatus.IN_PROGRESS.name).await()
      assertTrue(nonPublished.get().await().exists())
      assertPermissionDenied {
        other.firestore.collection(TRIPS_COLLECTION).document(nonPublished.id).get().await()
      }
    }
  }

  @Test
  fun getUpcomingTrips_queryIsAuthorizedForAuthenticatedReaders() = runTest {
    withClients("owner" to true, "reader" to true) { (owner, reader) ->
      val ownerId = checkNotNull(owner.auth.currentUser).uid
      val readerId = checkNotNull(reader.auth.currentUser).uid
      val tripId = "upcoming-${UUID.randomUUID()}"
      owner.firestore
          .collection(TRIPS_COLLECTION)
          .document(tripId)
          .set(storedTrip(ownerId, scheduledAt = NOW.plusSeconds(3_600)))
          .await()
      val repository =
          TripRepositoryFirestore(
              authRepository = FakeAuthRepository(AuthUser(uid = readerId)),
              firestore = reader.firestore,
              now = { NOW },
          )

      val trips = repository.getUpcomingTrips().successData()

      assertTrue(trips.any { it.id == tripId })
    }
  }

  @Test
  fun getMyTrips_queryIsAuthorizedAndScopedToAuthenticatedOwner() = runTest {
    withClients("owner" to true, "other" to true) { (owner, other) ->
      val ownerId = checkNotNull(owner.auth.currentUser).uid
      val otherId = checkNotNull(other.auth.currentUser).uid
      val ownedTripId = "owned-${UUID.randomUUID()}"
      val otherTripId = "other-${UUID.randomUUID()}"
      val ownedTrip = owner.firestore.collection(TRIPS_COLLECTION).document(ownedTripId)
      ownedTrip.set(storedTrip(ownerId)).await()
      ownedTrip.update("status", TripStatus.IN_PROGRESS.name).await()
      val otherTrip = other.firestore.collection(TRIPS_COLLECTION).document(otherTripId)
      otherTrip.set(storedTrip(otherId)).await()
      otherTrip.update("status", TripStatus.CANCELLED.name).await()
      val repository =
          TripRepositoryFirestore(
              authRepository = FakeAuthRepository(AuthUser(uid = ownerId)),
              firestore = owner.firestore,
              now = { NOW },
          )

      val trips = repository.getMyTrips().successData()

      assertEquals(listOf(ownedTripId), trips.map { it.id })
    }
  }

  @Test
  fun update_requiresOwnerAndPreservesImmutableFields() = runTest {
    withClients("owner" to true, "other" to true) { (owner, other) ->
      val ownerId = checkNotNull(owner.auth.currentUser).uid
      val otherId = checkNotNull(other.auth.currentUser).uid
      val published =
          owner.firestore.collection(TRIPS_COLLECTION).document("update-${UUID.randomUUID()}")
      published.set(storedTrip(ownerId)).await()
      val otherPublished = other.firestore.collection(TRIPS_COLLECTION).document(published.id)

      assertPermissionDenied { otherPublished.update("status", "COMPLETED").await() }
      published.update("status", "COMPLETED").await()
      assertEquals("COMPLETED", published.get().await().getString("status"))
      assertPermissionDenied { published.update("ownerId", otherId).await() }
      assertPermissionDenied {
        published.update("createdAt", Timestamp(NOW.epochSecond + 1, NOW.nano)).await()
      }
    }
  }

  @Test
  fun delete_requiresOwner() = runTest {
    withClients("owner" to true, "other" to true) { (owner, other) ->
      val ownerId = checkNotNull(owner.auth.currentUser).uid
      val published =
          owner.firestore.collection(TRIPS_COLLECTION).document("delete-${UUID.randomUUID()}")
      published.set(storedTrip(ownerId)).await()
      published.update("status", TripStatus.CANCELLED.name).await()
      val otherPublished = other.firestore.collection(TRIPS_COLLECTION).document(published.id)

      assertPermissionDenied { otherPublished.delete().await() }
      published.delete().await()
    }
  }

  @Test
  fun productionRules_rejectMalformedTripDocuments() = runTest {
    withClients("malformed-owner" to true) { (owner) ->
      val ownerId = checkNotNull(owner.auth.currentUser).uid
      val validTrip = storedTrip(ownerId)
      val malformedTrips =
          listOf(
              "unexpected top-level field" to (validTrip + ("unexpected" to true)),
              "missing required top-level field" to (validTrip - "updatedAt"),
              "unexpected nested location field" to
                  storedTrip(ownerId, store = storeLocation() + ("unexpected" to true)),
              "missing required nested location field" to
                  storedTrip(ownerId, handoffLocation = handoffLocation() - "longitude"),
              "blank location name" to storedTrip(ownerId, store = storeLocation(name = "   ")),
              "latitude outside range" to
                  storedTrip(ownerId, store = storeLocation(latitude = 90.1)),
              "wrong coordinate type" to
                  storedTrip(ownerId, store = storeLocation() + ("latitude" to "north")),
              "longitude outside range" to
                  storedTrip(ownerId, handoffLocation = handoffLocation(longitude = -180.1)),
              "wrong timestamp type" to (validTrip + ("scheduledAt" to "not-a-timestamp")),
              "unknown status" to storedTrip(ownerId, status = "NOT_A_STATUS"),
          )

      malformedTrips.forEachIndexed { index, (description, data) ->
        assertPermissionDenied(description) {
          owner.firestore
              .collection(TRIPS_COLLECTION)
              .document("malformed-$index-${UUID.randomUUID()}")
              .set(data)
              .await()
        }
      }
    }
  }

  private fun storedTrip(
      ownerId: String,
      status: String = "PUBLISHED",
      scheduledAt: Instant = NOW.plusSeconds(3_600),
      store: Map<String, Any> = storeLocation(),
      handoffLocation: Map<String, Any> = handoffLocation(),
      updatedAt: Instant = NOW,
  ): Map<String, Any> =
      mapOf(
          "ownerId" to ownerId,
          "store" to store,
          "scheduledAt" to Timestamp(scheduledAt.epochSecond, scheduledAt.nano),
          "handoffLocation" to handoffLocation,
          "status" to status,
          "createdAt" to Timestamp(NOW.epochSecond, NOW.nano),
          "updatedAt" to Timestamp(updatedAt.epochSecond, updatedAt.nano),
      )

  private fun storeLocation(
      name: String = "Migros",
      latitude: Double = 46.5191,
      longitude: Double = 6.6332,
  ): Map<String, Any> = mapOf("name" to name, "latitude" to latitude, "longitude" to longitude)

  private fun handoffLocation(
      name: String = "EPFL",
      latitude: Double = 46.5188,
      longitude: Double = 6.5665,
  ): Map<String, Any> = mapOf("name" to name, "latitude" to latitude, "longitude" to longitude)

  private companion object {
    val NOW: Instant = Instant.parse("2026-10-04T12:00:00Z")
    const val TRIPS_COLLECTION = "trips"
  }
}
