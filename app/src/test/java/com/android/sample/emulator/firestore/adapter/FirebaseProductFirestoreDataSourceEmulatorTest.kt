package com.android.sample.emulator.firestore.adapter

// AI assistance: OpenAI Codex.
import com.android.sample.data.repository.ProductRepositoryFirestore
import com.android.sample.data.repository.parseEmulatorHostAndPort
import com.android.sample.data.repository.productSearchPrefixes
import com.android.sample.data.repository.successData
import com.android.sample.model.Product
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.MemoryCacheSettings
import java.util.UUID
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class FirebaseProductFirestoreDataSourceEmulatorTest {

  @Test
  fun concreteAdapter_scopesOrdersAndLimitsTheStoreQueryToActiveProducts() = runTest {
    withFirestoreEmulator { firestore ->
      val stores = firestore.collection("stores")
      val migros = stores.document("migros").collection("products")
      migros.document("100014192").set(storedProduct("migros:100014192", "Apple", 1.25)).await()
      migros.document("200").set(storedProduct("migros:200", "Gone", 2L, REMOVED_AT)).await()
      migros.document("300").set(storedProduct("migros:300", "Zopf", 3.5)).await()
      // swiss-cli always writes removed_at; a document without it does not match the query.
      migros
          .document("150")
          .set(storedProduct("migros:150", "No removal field", 1.0) - "removed_at")
          .await()
      val coop = stores.document("coop").collection("products")
      coop.document("4446520").set(storedProduct("coop:4446520", "Example product", 3.95)).await()
      coop.document("4446521").set(storedProduct("coop:4446521", "Gone", 1.0, REMOVED_AT)).await()
      coop.document("4446522").set(storedProduct("migros:4446522", "Wrong store", 1.0)).await()

      val repository = ProductRepositoryFirestore(firestore)
      val products = repository.getProducts("migros", limit = 2).successData()

      // The removed product "200" sorts between them but does not use up the limit.
      assertEquals(listOf("migros:100014192", "migros:300"), products.map(Product::id))
      assertEquals(listOf(125, 350), products.map(Product::priceCents))
      assertEquals(
          listOf(
              Product(
                  "coop:4446520",
                  "coop",
                  "Example product",
                  395,
                  "https://example.com/product.jpg",
              )
          ),
          repository.getProducts("coop").successData(),
      )
    }
  }

  @Test
  fun concreteAdapter_searchesAccentedMultiwordNamesAndPagesByDocumentId() = runTest {
    withFirestoreEmulator { firestore ->
      val stores = firestore.collection("stores")
      val migros = stores.document("migros").collection("products")
      listOf(
              "103" to "Lait entier UHT",
              "101" to "Crème brûlée",
              "104" to "Crémant 55%",
              "102" to "Lait demi-écrémé",
              "105" to "Crème entière",
          )
          .forEach { (id, name) ->
            migros.document(id).set(storedProduct("migros:$id", name, 2.5)).await()
          }
      migros.document("100").set(storedProduct("migros:100", "Crème gone", 1.0, REMOVED_AT)).await()
      stores
          .document("coop")
          .collection("products")
          .document("1")
          .set(storedProduct("coop:1", "Crème coop", 1.0))
          .await()
      val repository = ProductRepositoryFirestore(firestore)
      suspend fun ids(query: String, cursor: String? = null, limit: Int = 50) =
          repository.searchProducts("migros", query, cursor, limit).successData()

      assertEquals(
          listOf("migros:101", "migros:104", "migros:105"),
          ids("CRÉ").products.map(Product::id),
      )
      assertEquals(listOf("migros:105"), ids("entière crème").products.map(Product::id))
      assertEquals(listOf("migros:102"), ids("lait ecreme").products.map(Product::id))
      val first = ids("cr", limit = 2)
      assertEquals(listOf("migros:101", "migros:104"), first.products.map(Product::id))
      assertEquals("104", first.nextCursor)
      // A cursor is a position, so it still works once its product is deleted.
      migros.document("104").delete().await()
      val second = ids("cr", first.nextCursor, limit = 2)
      assertEquals(listOf("migros:105"), second.products.map(Product::id))
      assertEquals(null, second.nextCursor)
    }
  }

  private suspend fun withFirestoreEmulator(test: suspend (FirebaseFirestore) -> Unit) {
    val emulatorAddress = System.getenv("FIRESTORE_EMULATOR_HOST")
    assumeTrue(
        "Set FIRESTORE_EMULATOR_HOST to run Firestore adapter tests",
        !emulatorAddress.isNullOrBlank(),
    )
    val (host, port) = checkNotNull(emulatorAddress).parseEmulatorHostAndPort()
    val app = createFirebaseApp()
    val firestore = FirebaseFirestore.getInstance(app)
    firestore.firestoreSettings =
        FirebaseFirestoreSettings.Builder()
            .setLocalCacheSettings(MemoryCacheSettings.newBuilder().build())
            .build()
    firestore.useEmulator(host, port)
    try {
      test(firestore)
    } finally {
      firestore.terminate().await()
      app.delete()
    }
  }

  private fun createFirebaseApp(): FirebaseApp {
    val options =
        FirebaseOptions.Builder()
            .setApplicationId("1:123456789:android:product-repository-test")
            .setApiKey("fake-api-key")
            .setProjectId("product-${UUID.randomUUID().toString().take(20)}")
            .build()
    return FirebaseApp.initializeApp(
        RuntimeEnvironment.getApplication(),
        options,
        "product-repository-test-${UUID.randomUUID()}",
    )
  }

  private fun storedProduct(id: String, name: String, price: Number, removedAt: Timestamp? = null) =
      mapOf(
          "id" to id,
          "name" to name,
          "price" to price,
          "image_url" to "https://example.com/product.jpg",
          "removed_at" to removedAt,
          "search_prefixes" to productSearchPrefixes(name),
      )

  private companion object {
    val REMOVED_AT = Timestamp(1_760_000_000, 0)
  }
}
