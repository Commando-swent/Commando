package com.android.sample.emulator.firestore.security

// AI assistance: Claude Code.
import com.android.sample.data.repository.ProductError
import com.android.sample.data.repository.ProductRepositoryFirestore
import com.android.sample.data.repository.ProductResult
import com.android.sample.data.repository.successData
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

  private companion object {
    const val MIGROS_PRODUCTS = "stores/migros/products"
  }
}
