package com.android.sample.emulator.firestore.security

// AI assistance: Claude Code.
import com.android.sample.data.repository.OrderError
import com.android.sample.data.repository.OrderResult
import com.android.sample.data.repository.ShoppingChecklistRepositoryFirestore
import com.android.sample.data.repository.documentKey
import com.android.sample.model.Order
import com.android.sample.model.OrderItem
import com.android.sample.model.OrderStatus
import com.android.sample.model.ShoppingChecklistItem
import com.android.sample.model.buildShoppingChecklist
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.Source
import java.time.Instant
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
  fun everyAggregatedProductCanBeCheckedAndUncheckingFirstIsANoOp() = runTest {
    withClients("owner" to true) { (owner) ->
      val trip = publish(owner)
      val uid = owner.auth.currentUser!!.uid
      val repository = ShoppingChecklistRepositoryFirestore({ uid }, owner.firestore)
      // Unchecking before any progress exists succeeds and stores nothing.
      assertEquals(OrderResult.Success(Unit), repository.setPurchased(trip, milk, false))
      assertEquals(OrderResult.Success(emptyMap<String, Long>()), get(repository, trip))

      // 21 valid accepted orders of five distinct products each aggregate to 105 products.
      val orders =
          (1..21).map { n ->
            val products = (1..5).map { OrderItem("product-$n-$it", "Product $n.$it", 100, 1) }
            Order("order-$n", trip, "requester", products, 0, OrderStatus.ACCEPTED, Instant.EPOCH)
          }
      val items = buildShoppingChecklist(trip, orders, emptyMap())
      assertEquals(105, items.size)
      for (item in items) {
        assertEquals(OrderResult.Success(Unit), repository.setPurchased(trip, item, true))
      }
      val all = items.associate { it.productId to 1L }
      assertEquals(OrderResult.Success(all), get(repository, trip))
    }
  }

  @Test
  fun productIdsAcceptedByOrderRulesCanBeCheckedAndUnchecked() = runTest {
    withClients("owner" to true) { (owner) ->
      val trip = publish(owner)
      val uid = owner.auth.currentUser!!.uid
      val repository = ShoppingChecklistRepositoryFirestore({ uid }, owner.firestore)
      // Order rules accept these IDs, but none of them is a valid Firestore document ID.
      val ids = listOf("a/b", ".", "..", "__name__", "x".repeat(2000))
      val items = ids.map { ShoppingChecklistItem(it, "Product", 2, purchased = false) }
      for (item in items) {
        assertEquals(OrderResult.Success(Unit), repository.setPurchased(trip, item, true))
      }
      assertEquals(OrderResult.Success(ids.associateWith { 2L }), get(repository, trip))
      for (item in items) {
        assertEquals(OrderResult.Success(Unit), repository.setPurchased(trip, item, false))
      }
      assertEquals(OrderResult.Success(emptyMap<String, Long>()), get(repository, trip))
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
      val milkKey = documentKey("milk")
      for (client in listOf(other, signedOut)) {
        val items = items(client, trip)
        assertPermissionDenied("foreign list") { items.get(Source.SERVER).await() }
        assertPermissionDenied("foreign read") {
          items.document(milkKey).get(Source.SERVER).await()
        }
        assertPermissionDenied("foreign write") { items.document(milkKey).set(valid()).await() }
        assertPermissionDenied("foreign delete") { items.document(milkKey).delete().await() }
      }
      val checklists = owner.firestore.collection(COLLECTION)
      assertPermissionDenied("checklist list") { checklists.get(Source.SERVER).await() }
      val ownerRepository = ShoppingChecklistRepositoryFirestore({ uid }, owner.firestore)
      assertEquals(denied, get(ownerRepository, "missing-trip"))
    }
  }

  @Test
  fun malformedProgressIsRejectedOrSkippedAndEndedSessionsFail() = runTest {
    withClients("owner" to true) { (owner) ->
      val trip = publish(owner)
      val document = items(owner, trip).document(documentKey("milk"))
      val invalid =
          listOf(
              valid() + ("ownerId" to "someone"),
              valid() + ("productId" to "bread"),
              valid() + ("productId" to 1),
              valid() - "productId",
              valid() + ("quantity" to "two"),
              valid() + ("quantity" to 0),
              valid() + ("updatedAt" to Timestamp(0, 0)),
              mapOf("quantity" to 1),
          )
      for (data in invalid) assertPermissionDenied("malformed progress") {
        document.set(data).await()
      }

      var uid: String? = owner.auth.currentUser!!.uid
      val repository = ShoppingChecklistRepositoryFirestore({ uid }, owner.firestore)
      assertEquals(OrderResult.Error(OrderError.InvalidData), get(repository, "a/b"))
      // Documents written outside the rules are skipped when malformed.
      val itemsPath = "$COLLECTION/$trip/items"
      createDocumentAsOwner(itemsPath, "eggs", mapOf("productId" to "eggs", "quantity" to "two"))
      createDocumentAsOwner(itemsPath, "cheese", mapOf("quantity" to 1))
      createDocumentAsOwner(itemsPath, "bread", mapOf("productId" to "bread", "quantity" to 2))
      assertEquals(OrderResult.Success(mapOf("bread" to 2L)), get(repository, trip))
      val invalidItems = listOf(milk.copy(requiredQuantity = 0), milk.copy(productId = " "))
      for (item in invalidItems) {
        assertEquals(
            OrderResult.Error(OrderError.InvalidData),
            repository.setPurchased(trip, item, true),
        )
      }
      uid = null
      assertEquals(OrderResult.Error(OrderError.NotAuthenticated), get(repository, trip))
    }
  }

  private suspend fun get(repository: ShoppingChecklistRepositoryFirestore, trip: String) =
      repository.getPurchasedQuantities(trip)

  private fun valid() =
      mapOf("productId" to "milk", "quantity" to 1, "updatedAt" to FieldValue.serverTimestamp())

  private fun items(client: EmulatorClient, trip: String) =
      client.firestore.collection(COLLECTION).document(trip).collection("items")

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
