package com.android.sample.emulator.firestore.security

// AI assistance: Claude Code.
import com.android.sample.data.repository.OrderError
import com.android.sample.data.repository.OrderResult
import com.android.sample.data.repository.ShoppingChecklistRepositoryFirestore
import com.android.sample.model.ShoppingChecklistItem
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Source
import java.util.UUID
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Repository and direct SDK access to checklist progress against the production rules. */
@RunWith(RobolectricTestRunner::class)
class ShoppingChecklistFirestoreSecurityRulesEmulatorTest {
  private val milk = ShoppingChecklistItem("milk", "Milk", 3, purchased = false)
  private val bread = ShoppingChecklistItem("bread", "Bread", 1, purchased = false)

  @Test
  fun ownerProgressPersistsAcrossRepositories() = runTest {
    withClients("owner" to true) { (owner) ->
      val trip = publish(owner)
      val uid = owner.auth.currentUser!!.uid
      val repository = ShoppingChecklistRepositoryFirestore({ uid }, owner.firestore)
      assertEquals(OrderResult.Success(emptyMap<String, Long>()), get(repository, trip))

      assertEquals(OrderResult.Success(Unit), repository.setPurchased(trip, milk, true))
      repository.setPurchased(trip, bread, true)
      repository.setPurchased(trip, milk, false)

      // A new repository with SERVER reads proves the progress is stored, not cached.
      val fresh = ShoppingChecklistRepositoryFirestore({ uid }, owner.firestore)
      assertEquals(OrderResult.Success(mapOf("bread" to 1L)), get(fresh, trip))
    }
  }

  @Test
  fun onlyTheTripOwnerCanReadOrWriteProgress() = runTest {
    withClients("owner" to true, "other" to true, "signedOut" to false) { (owner, other, signedOut)
      ->
      val trip = publish(owner)
      val uid = owner.auth.currentUser!!.uid
      ShoppingChecklistRepositoryFirestore({ uid }, owner.firestore).setPurchased(trip, milk, true)
      val otherId = other.auth.currentUser!!.uid
      val otherRepository = ShoppingChecklistRepositoryFirestore({ otherId }, other.firestore)
      val denied = OrderResult.Error(OrderError.PermissionDenied)
      assertEquals(denied, get(otherRepository, trip))
      assertEquals(denied, otherRepository.setPurchased(trip, milk, false))
      for (client in listOf(other, signedOut)) {
        val document = client.firestore.collection(COLLECTION).document(trip)
        assertPermissionDenied("foreign read") { document.get(Source.SERVER).await() }
        assertPermissionDenied("foreign write") {
          document.set(valid(), SetOptions.merge()).await()
        }
        assertPermissionDenied("delete") { document.delete().await() }
      }
      val ownerCollection = owner.firestore.collection(COLLECTION)
      assertPermissionDenied("list") { ownerCollection.get(Source.SERVER).await() }
      assertPermissionDenied("owner delete") { ownerCollection.document(trip).delete().await() }
      val ownerRepository = ShoppingChecklistRepositoryFirestore({ uid }, owner.firestore)
      assertEquals(denied, get(ownerRepository, "missing-trip"))
    }
  }

  @Test
  fun malformedProgressIsRejectedOrSkippedAndEndedSessionsFail() = runTest {
    withClients("owner" to true) { (owner) ->
      val trip = publish(owner)
      val document = owner.firestore.collection(COLLECTION).document(trip)
      val invalid =
          listOf(
              valid() + ("ownerId" to "someone"),
              valid() + ("purchased" to "milk"),
              valid() + ("updatedAt" to Timestamp(0, 0)),
              mapOf("purchased" to mapOf("milk" to 1)),
              valid() + ("purchased" to (1..101).associate { "product-$it" to 1 }),
          )
      for (data in invalid) assertPermissionDenied("malformed progress") {
        document.set(data).await()
      }

      var uid: String? = owner.auth.currentUser!!.uid
      val repository = ShoppingChecklistRepositoryFirestore({ uid }, owner.firestore)
      assertEquals(OrderResult.Error(OrderError.InvalidData), get(repository, "a/b"))
      // Rules cannot check map values, so the repository skips the malformed ones.
      val stored = mapOf("milk" to "two", "eggs" to 0, "bread" to 2)
      document.set(valid() + ("purchased" to stored)).await()
      assertEquals(OrderResult.Success(mapOf("bread" to 2L)), get(repository, trip))
      val zero = milk.copy(requiredQuantity = 0)
      assertEquals(
          OrderResult.Error(OrderError.InvalidData),
          repository.setPurchased(trip, zero, true),
      )
      uid = null
      assertEquals(OrderResult.Error(OrderError.NotAuthenticated), get(repository, trip))
    }
  }

  private suspend fun get(repository: ShoppingChecklistRepositoryFirestore, trip: String) =
      repository.getPurchasedQuantities(trip)

  private fun valid() =
      mapOf("purchased" to mapOf("milk" to 1), "updatedAt" to FieldValue.serverTimestamp())

  /** Only the trip owner matters to the checklist rules, so other trip fields are omitted. */
  private fun publish(owner: EmulatorClient): String =
      "trip-${UUID.randomUUID()}"
          .also {
            createDocumentAsOwner("trips", it, mapOf("ownerId" to owner.auth.currentUser!!.uid))
          }

  private companion object {
    const val COLLECTION = "shoppingChecklists"
  }
}
