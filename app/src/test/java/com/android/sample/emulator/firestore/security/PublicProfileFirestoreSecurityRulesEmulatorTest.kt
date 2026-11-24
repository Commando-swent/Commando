package com.android.sample.emulator.firestore.security

import com.android.sample.emulator.auth.FirebaseAuthEmulator
import com.commando.app.data.repository.PublicProfileRepositoryFirestore
import com.google.firebase.firestore.Source
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PublicProfileFirestoreSecurityRulesEmulatorTest {
  private fun EmulatorClient.publicProfile(uid: String = checkNotNull(auth.currentUser).uid) =
      firestore.collection("publicProfiles").document(uid)

  private fun EmulatorClient.publicNames() =
      PublicProfileRepositoryFirestore({ auth.currentUser?.uid }, firestore)

  @Test
  fun signedInViewerReadsOnlyTheOwnersPublishedName() = runTest {
    withClients("owner" to true, "viewer" to true) { (owner, viewer) ->
      val profile = owner.publicProfile()
      val published = mapOf("displayName" to "Alex")
      profile.set(published).await()

      val snapshot = viewer.publicProfile(profile.id).get(Source.SERVER).await()

      assertEquals(published, snapshot.data)
      assertEquals("Alex", viewer.publicNames().getDisplayName(profile.id))
    }
  }

  @Test
  fun missingLegacyProfileReturnsAMissingSnapshotToSignedInViewer() = runTest {
    withClients("legacyOwner" to true, "viewer" to true) { (owner, viewer) ->
      val snapshot = viewer.publicProfile(owner.publicProfile().id).get(Source.SERVER).await()

      assertFalse(snapshot.exists())
      assertNull(viewer.publicNames().getDisplayName(owner.publicProfile().id))
    }
  }

  @Test
  fun rejectedQueuedRemovalPreservesTheCommittedName() = FirebaseAuthEmulator.runTest {
    withClients("owner" to true, "viewer" to true) { (owner, viewer) ->
      val profile = owner.publicProfile()
      profile.set(mapOf("displayName" to "Alex")).await()
      val otherProfile = owner.publicProfile(checkNotNull(viewer.auth.currentUser).uid)
      owner.firestore.disableNetwork().await()
      val removal =
          try {
            val batch = owner.firestore.batch()
            batch.delete(profile)
            batch.set(otherProfile, mapOf("displayName" to "Forbidden"))
            val write = batch.commit()
            assertFalse(profile.get(Source.CACHE).await().exists())
            // Only the viewer is online. Its transaction finishes before the batch can settle.
            assertEquals("Alex", viewer.publicNames().getDisplayName(profile.id))
            assertFalse(write.isComplete)
            write
          } finally {
            owner.firestore.enableNetwork().await()
          }
      assertPermissionDenied { removal.await() }
      // This transaction uses the original writer after the rejected batch has rolled back.
      assertEquals("Alex", owner.publicNames().getDisplayName(profile.id))
    }
  }

  @Test
  fun signedOutViewerCannotReadPublishedName() = runTest {
    withClients("owner" to true, "signedOut" to false) { (owner, signedOut) ->
      val profile = owner.publicProfile()
      profile.set(mapOf("displayName" to "Alex")).await()

      assertPermissionDenied { signedOut.publicProfile(profile.id).get(Source.SERVER).await() }
    }
  }

  @Test
  fun signedInViewerCannotListPublicProfiles() = runTest {
    withClients("viewer" to true) { (viewer) ->
      assertPermissionDenied {
        viewer.firestore.collection("publicProfiles").limit(1).get(Source.SERVER).await()
      }
    }
  }

  @Test
  fun ownerCanRenamePublishedProfile() = runTest {
    withClients("owner" to true) { (owner) ->
      val profile = owner.publicProfile()
      profile.set(mapOf("displayName" to "Alex")).await()

      profile.update("displayName", "Sam").await()

      assertEquals("Sam", profile.get(Source.SERVER).await().getString("displayName"))
    }
  }

  @Test
  fun onlyOwnerCanRemovePublishedProfile() = runTest {
    withClients("owner" to true, "other" to true) { (owner, other) ->
      val profile = owner.publicProfile()
      profile.set(mapOf("displayName" to "Alex")).await()

      assertPermissionDenied { other.publicProfile(profile.id).delete().await() }
      profile.delete().await()

      assertFalse(profile.get(Source.SERVER).await().exists())
    }
  }

  @Test
  fun otherAccountCannotPublishOrReplaceOwnersName() = runTest {
    withClients("owner" to true, "other" to true) { (owner, other) ->
      val profile = owner.publicProfile()
      val foreignProfile = other.publicProfile(profile.id)
      val attempted = mapOf("displayName" to "Someone else")

      assertPermissionDenied { foreignProfile.set(attempted).await() }
      profile.set(mapOf("displayName" to "Alex")).await()
      assertPermissionDenied { foreignProfile.set(attempted).await() }
    }
  }

  @Test
  fun ownerCannotPublishInvalidDisplayName() = runTest {
    withClients("owner" to true) { (owner) ->
      val profile = owner.publicProfile()
      val invalidNames =
          listOf("", " \t ", "\u00A0", "\u0085\u2003\u202F", "\u001C", "Alex\nSam", "Alex\rSam", 42)
      for (name in invalidNames) {
        assertPermissionDenied { profile.set(mapOf("displayName" to name)).await() }
      }
    }
  }

  @Test
  fun publicNameRulesCountSupplementaryCharactersOnce() = runTest {
    withClients("owner" to true) { (owner) ->
      val profile = owner.publicProfile()
      for (character in listOf("a", "😀")) {
        val limit = character.repeat(80)
        profile.set(mapOf("displayName" to limit)).await()
        assertEquals(limit, profile.get(Source.SERVER).await().getString("displayName"))
        assertPermissionDenied { profile.update("displayName", limit + character).await() }
      }
    }
  }

  @Test
  fun ownerCannotCreatePublicProfileWithPrivateField() = runTest {
    withClients("owner" to true) { (owner) ->
      assertPermissionDenied {
        owner
            .publicProfile()
            .set(mapOf("displayName" to "Alex", "email" to "private@example.org"))
            .await()
      }
    }
  }

  @Test
  fun ownerCannotAddPrivateFieldToExistingPublicProfile() = runTest {
    withClients("owner" to true) { (owner) ->
      val profile = owner.publicProfile()
      profile.set(mapOf("displayName" to "Alex")).await()

      assertPermissionDenied { profile.update("photoUrl", "private-photo").await() }
    }
  }

  @Test
  fun malformedExistingProfileCannotBeRead() = runTest {
    withClients("owner" to true, "viewer" to true) { (owner, viewer) ->
      val profile = owner.publicProfile()
      createDocumentAsOwner(
          "publicProfiles",
          profile.id,
          mapOf("displayName" to "Alex", "email" to "private@example.org"),
      )

      assertPermissionDenied { viewer.publicProfile(profile.id).get(Source.SERVER).await() }
      assertPermissionDenied { profile.get(Source.SERVER).await() }
    }
  }

  @Test
  fun ownerCanReplaceMalformedProfileWithAValidPublicRecord() = runTest {
    withClients("owner" to true, "viewer" to true) { (owner, viewer) ->
      val profile = owner.publicProfile()
      createDocumentAsOwner("publicProfiles", profile.id, mapOf("email" to "private@example.org"))
      val replacement = mapOf("displayName" to "Sam")

      profile.set(replacement).await()

      assertEquals(replacement, viewer.publicProfile(profile.id).get(Source.SERVER).await().data)
    }
  }
}
