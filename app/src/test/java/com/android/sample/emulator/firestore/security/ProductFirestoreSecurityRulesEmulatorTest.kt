package com.android.sample.emulator.firestore.security

// AI assistance: Claude Code.
import com.android.sample.data.repository.ProductError
import com.android.sample.data.repository.ProductRepositoryFirestore
import com.android.sample.data.repository.ProductResult
import com.android.sample.data.repository.productSearchPrefixes
import com.android.sample.data.repository.successData
import com.android.sample.model.Product
import com.android.sample.model.ProductPage
import com.google.firebase.firestore.FieldPath
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ProductFirestoreSecurityRulesEmulatorTest {

  @Test
  fun favourites_arePrivateStoreScopedReferencesWithUnavailableProducts() = runTest {
    withClients("alice" to true, "bob" to true, "anonymous" to false) { (alice, bob, anonymous) ->
      val number = "favourite-${System.nanoTime()}"
      val activeId = "migros:$number-active"
      val removedId = "migros:$number-removed"
      val missingId = "migros:$number-missing"
      val coopId = "coop:$number-active"
      createDocumentAsOwner(
          MIGROS_PRODUCTS,
          "$number-active",
          storedProduct(activeId, "Migros milk"),
      )
      createDocumentAsOwner(
          MIGROS_PRODUCTS,
          "$number-removed",
          storedProduct(removedId, "Removed milk", REMOVED_AT),
      )
      createDocumentAsOwner(COOP_PRODUCTS, "$number-active", storedProduct(coopId, "Coop milk"))
      try {
        val aliceRepository = ProductRepositoryFirestore(alice.firestore)
        for (product in listOf(activeId, removedId, missingId, coopId)) {
          assertEquals(
              ProductResult.Success(Unit),
              aliceRepository.setFavourite(product.substringBefore(':'), product, true),
          )
        }
        // Repeating an add updates the same reference, never a second favourite.
        assertEquals(
            ProductResult.Success(Unit),
            aliceRepository.setFavourite("migros", activeId, true),
        )
        val migrosFavourites = aliceRepository.getFavouriteProducts("migros").successData()
        assertEquals(listOf(activeId), migrosFavourites.products.map(Product::id))
        // A delisted product and a deleted document are both reported, never hidden.
        assertEquals(listOf(missingId, removedId), migrosFavourites.unavailableProductIds)
        assertEquals(
            listOf(coopId),
            aliceRepository.getFavouriteProducts("coop").successData().products.map(Product::id),
        )
        // Signed out, the production repository never queries anyone's favourites.
        assertEquals(
            ProductResult.Error(ProductError.PermissionDenied),
            ProductRepositoryFirestore(anonymous.firestore).getFavouriteProducts("migros"),
        )

        val aliceId = alice.auth.currentUser!!.uid
        val aliceFavourites =
            bob.firestore.collection("users").document(aliceId).collection("favourites")
        assertPermissionDenied("another user's favourites") {
          aliceFavourites.limit(10).get().await()
        }
        val own = "users/$aliceId/favourites/$activeId"
        val valid = mapOf("productId" to activeId, "storeId" to "migros")
        for ((client, path, data) in
            listOf(
                Triple(anonymous, own, valid),
                Triple(bob, own, valid),
                Triple(alice, "users/$aliceId/favourites/migros:other", valid),
                Triple(alice, own, valid + ("storeId" to "coop")),
                Triple(alice, own, valid + ("note" to "extra")),
            )) {
          assertPermissionDenied("favourite $data at $path") {
            client.firestore.document(path).set(data).await()
          }
        }
        assertPermissionDenied("deleting another user's favourite") {
          bob.firestore.document(own).delete().await()
        }
        // A favourite stays removable once its product is unavailable.
        assertEquals(
            ProductResult.Success(Unit),
            aliceRepository.setFavourite("migros", removedId, false),
        )
      } finally {
        deleteDocumentAsOwner("$MIGROS_PRODUCTS/$number-active")
        deleteDocumentAsOwner("$MIGROS_PRODUCTS/$number-removed")
        deleteDocumentAsOwner("$COOP_PRODUCTS/$number-active")
      }
    }
  }

  @Test
  fun favouriteIds_matchTheRepositoryDocumentIdValidation() = runTest {
    withClients("alice" to true) { (alice) ->
      val favourites = "users/${alice.auth.currentUser!!.uid}/favourites"
      val repository = ProductRepositoryFirestore(alice.firestore)
      val valid = listOf("migros:100014192", "coop:4446520", "migros:a b", "migros:a:b", "coop:...")
      val invalid =
          listOf("migros:.", "migros:..", "coop:.", "coop:..", "migros:", "migros: 1", "coop:1 ") +
              listOf("migros:\t1", " migros:1", "Migros:1", "lidl:1", "migros1")
      for (id in valid + invalid) {
        val storeId = id.trim().substringBefore(':').lowercase()
        val write = alice.firestore.document("$favourites/$id")
        val data = mapOf("productId" to id, "storeId" to storeId)
        // The repository rejects exactly the IDs the rules reject, before any request.
        assertEquals(
            id,
            id in valid,
            repository.setFavourite(storeId, id, true) is ProductResult.Success,
        )
        if (id in valid) write.set(data).await()
        else assertPermissionDenied("favourite '$id'") { write.set(data).await() }
      }
    }
  }

  @Test
  fun authenticatedReads_allowGetsAndListsOfAtMostFiftyProducts() = runTest {
    withClients("authenticated" to true) { (authenticated) ->
      val products = authenticated.firestore.collection(MIGROS_PRODUCTS)

      assertFalse(products.document("100014192").get().await().exists())
      assertTrue(products.limit(50).get().await().isEmpty)
      assertPermissionDenied("a list of 51 products") { products.limit(51).get().await() }
      assertPermissionDenied("an unbounded list") { products.get().await() }
    }
  }

  @Test
  fun unauthenticatedReads_areDenied() = runTest {
    withClients("unauthenticated" to false) { (unauthenticated) ->
      val products = unauthenticated.firestore.collection(MIGROS_PRODUCTS)

      assertPermissionDenied("an unauthenticated get") {
        products.document("100014192").get().await()
      }
      assertPermissionDenied("an unauthenticated list") { products.limit(1).get().await() }
    }
  }

  @Test
  fun clientWrites_areDeniedEvenWhenAuthenticated() = runTest {
    withClients("authenticated" to true) { (authenticated) ->
      val product = authenticated.firestore.collection(MIGROS_PRODUCTS).document("100014192")

      assertPermissionDenied("a create") {
        product
            .set(mapOf("id" to "migros:100014192", "name" to "Milk", "removed_at" to null))
            .await()
      }
      assertPermissionDenied("an update") { product.update("name", "Changed").await() }
      assertPermissionDenied("a delete") { product.delete().await() }
    }
  }

  @Test
  fun repositoryQueries_areAcceptedOnlyForAuthenticatedClients() = runTest {
    withClients("authenticated" to true, "unauthenticated" to false) {
        (authenticated, unauthenticated) ->
      assertTrue(
          ProductRepositoryFirestore(authenticated.firestore)
              .getProducts("migros", limit = 50)
              .successData()
              .isEmpty()
      )
      assertEquals(
          ProductResult.Error(ProductError.PermissionDenied),
          ProductRepositoryFirestore(unauthenticated.firestore).getProducts("migros"),
      )
    }
  }

  @Test
  fun searchQueries_areAcceptedOnlyWhenAuthenticatedAndBounded() = runTest {
    withClients("authenticated" to true, "unauthenticated" to false) {
        (authenticated, unauthenticated) ->
      assertEquals(
          ProductResult.Success(ProductPage(emptyList(), null)),
          ProductRepositoryFirestore(authenticated.firestore)
              .searchProducts("migros", "crème entière", cursor = "100014192", limit = 50),
      )
      assertEquals(
          ProductResult.Error(ProductError.PermissionDenied),
          ProductRepositoryFirestore(unauthenticated.firestore).searchProducts("migros", "lait"),
      )
      val search =
          authenticated.firestore
              .collection(MIGROS_PRODUCTS)
              .whereEqualTo("removed_at", null)
              .whereEqualTo(FieldPath.of("search_prefixes", "lait"), true)
      assertPermissionDenied("an unbounded search") { search.get().await() }
      assertPermissionDenied("a search of 51 products") { search.limit(51).get().await() }
    }
  }

  @Test
  fun searchQueries_returnAnExistingMatchingProductToAuthenticatedClients() = runTest {
    withClients("authenticated" to true) { (authenticated) ->
      // Clients may not write products, so the importer's document is created as the owner.
      createDocumentAsOwner(
          MIGROS_PRODUCTS,
          "100014100",
          mapOf(
              "id" to "migros:100014100",
              "name" to "Crème entière",
              "price" to 2.5,
              "image_url" to null,
              "removed_at" to null,
              "search_prefixes" to productSearchPrefixes("Crème entière"),
          ),
      )
      try {
        assertEquals(
            ProductResult.Success(
                ProductPage(
                    listOf(Product("migros:100014100", "migros", "Crème entière", 250)),
                    null,
                )
            ),
            ProductRepositoryFirestore(authenticated.firestore)
                .searchProducts("migros", "creme ENTIÈRE", limit = 50),
        )
      } catch (failure: Throwable) {
        // A cleanup failure must not hide why the test failed.
        runCatching { deleteDocumentAsOwner("$MIGROS_PRODUCTS/100014100") }
            .exceptionOrNull()
            ?.let(failure::addSuppressed)
        throw failure
      }
      // The other tests expect an empty catalogue.
      deleteDocumentAsOwner("$MIGROS_PRODUCTS/100014100")
    }
  }

  private companion object {
    const val MIGROS_PRODUCTS = "stores/migros/products"
    const val COOP_PRODUCTS = "stores/coop/products"
    val REMOVED_AT = com.google.firebase.Timestamp(1_760_000_000, 0)

    fun storedProduct(id: String, name: String, removedAt: com.google.firebase.Timestamp? = null) =
        mapOf(
            "id" to id,
            "name" to name,
            "price" to 2.5,
            "image_url" to null,
            "removed_at" to removedAt,
            "search_prefixes" to productSearchPrefixes(name),
        )
  }
}
