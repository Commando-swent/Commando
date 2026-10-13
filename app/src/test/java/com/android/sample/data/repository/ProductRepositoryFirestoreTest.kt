package com.android.sample.data.repository

// AI assistance: OpenAI Codex.
import com.android.sample.model.Product
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.FirebaseFirestoreException.Code
import java.io.IOException
import java.util.concurrent.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ProductRepositoryFirestoreTest {

  @Test
  fun getProducts_mapsSwissCliDocumentsAndExcludesRemovedOnes() = runTest {
    val example =
        productDocument(
            "coop:4446520",
            name = "Example product",
            price = 3.95,
            imageUrl = "https://example.com/product.jpg",
            removedAt = null,
        )
    val dataSource = InMemoryProductFirestoreDataSource()
    dataSource.documentsByStore["coop"] =
        listOf(
            example,
            example.copy(data = example.data + ("removed_at" to Timestamp(1_760_000_000, 0))),
            productDocument(
                "coop:42",
                name = "  Whole milk  ",
                price = 3L,
                imageUrl = " https://e.test/m.png ",
            ),
        )

    val products = ProductRepositoryFirestore(dataSource).getProducts(" COOP ", 3).successData()

    assertEquals(
        listOf(
            Product(
                "coop:4446520",
                "coop",
                "Example product",
                395,
                "https://example.com/product.jpg",
            ),
            Product("coop:42", "coop", "Whole milk", 300, "https://e.test/m.png"),
        ),
        products,
    )
    assertEquals("coop", dataSource.lastStoreId)
    assertEquals(3, dataSource.lastLimit)
  }

  @Test
  fun getProducts_requiresAGlobalIdBelongingToTheRequestedStore() = runTest {
    val valid = productDocument("coop:4446520")
    val invalidIds =
        listOf(
            valid.copy(data = valid.data - "id"),
            productDocument(null),
            productDocument("  "),
            productDocument(4446520L),
            productDocument("4446520"),
            productDocument("migros:4446520"),
            productDocument("COOP:4446520"),
            productDocument("coop:"),
            productDocument(" coop:4446520"),
        )
    val dataSource = InMemoryProductFirestoreDataSource()
    dataSource.documentsByStore["coop"] = invalidIds + valid

    val products =
        ProductRepositoryFirestore(dataSource)
            .getProducts("coop", invalidIds.size + 1)
            .successData()

    assertEquals(listOf("coop:4446520"), products.map(Product::id))
  }

  @Test
  fun getProducts_filtersMissingNegativeInvalidNonFiniteAndOverflowingPrices() = runTest {
    val valid = productDocument("migros:valid", price = 1.25)
    val invalidPrices =
        listOf(
            valid.copy(data = valid.data - "price"),
            productDocument("migros:negative", price = -0.01),
            productDocument("migros:invalid", price = "2.50"),
            productDocument("migros:nan", price = Double.NaN),
            productDocument("migros:infinite", price = Double.POSITIVE_INFINITY),
            productDocument("migros:overflow", price = 21_474_836.48),
            productDocument("migros:sub-cent", price = 1.001),
        )
    val dataSource = InMemoryProductFirestoreDataSource()
    dataSource.documentsByStore["migros"] = invalidPrices + valid

    val products =
        ProductRepositoryFirestore(dataSource)
            .getProducts("migros", invalidPrices.size + 1)
            .successData()

    assertEquals(listOf("migros:valid"), products.map(Product::id))
  }

  @Test
  fun getProducts_filtersRemovedAndMalformedDocumentsAfterTheBoundedQuery() = runTest {
    val dataSource = InMemoryProductFirestoreDataSource()
    dataSource.documentsByStore["denner"] =
        listOf(
            productDocument("denner:removed", removedAt = Timestamp(1_760_000_000, 0)),
            productDocument("denner:blank-name", name = "  "),
            productDocument("denner:bad-image", imageUrl = 123),
            productDocument("denner:bad-removed", removedAt = "2026-01-01T00:00:00Z"),
            productDocument("denner:valid"),
        )
    val repository = ProductRepositoryFirestore(dataSource)

    assertTrue(repository.getProducts("denner", 1).successData().isEmpty())
    assertEquals(
        listOf("denner:valid"),
        repository.getProducts("denner", 5).successData().map(Product::id),
    )
  }

  @Test
  fun getProducts_emptyCatalogueReturnsEmptySuccess() = runTest {
    val result =
        ProductRepositoryFirestore(InMemoryProductFirestoreDataSource()).getProducts("aldi")

    assertEquals(ProductResult.Success(emptyList<Product>()), result)
  }

  @Test
  fun getProducts_rejectsMalformedAndUnsupportedStoresWithoutQuerying() = runTest {
    val dataSource = InMemoryProductFirestoreDataSource()
    val repository = ProductRepositoryFirestore(dataSource)

    listOf("", "   ", "carrefour", "migros/products", "../coop").forEach { storeId ->
      assertProductError(ProductError.InvalidStore, repository.getProducts(storeId), storeId)
    }

    assertEquals(0, dataSource.queryCalls)
  }

  @Test
  fun getProducts_rejectsLimitsOutsideOneToFiftyWithoutQuerying() = runTest {
    val dataSource = InMemoryProductFirestoreDataSource()
    val repository = ProductRepositoryFirestore(dataSource)

    listOf(Int.MIN_VALUE, -1, 0, 51, Int.MAX_VALUE).forEach { limit ->
      assertProductError(ProductError.InvalidLimit, repository.getProducts("lidl", limit))
    }

    assertEquals(0, dataSource.queryCalls)
  }

  @Test
  fun getProducts_queriesOnlyTheRequestedStoreAndHonorsMaximumLimit() = runTest {
    val dataSource = InMemoryProductFirestoreDataSource()
    dataSource.documentsByStore["migros"] = listOf(productDocument("migros:milk"))
    dataSource.documentsByStore["coop"] = listOf(productDocument("coop:milk"))

    val products =
        ProductRepositoryFirestore(dataSource)
            .getProducts("migros", MAX_PRODUCT_QUERY_LIMIT)
            .successData()

    assertEquals(listOf("migros:milk"), products.map(Product::id))
    assertEquals("migros", dataSource.lastStoreId)
    assertEquals(MAX_PRODUCT_QUERY_LIMIT, dataSource.lastLimit)
  }

  @Test
  fun getProducts_preservesCoroutineCancellation() = runTest {
    val dataSource =
        InMemoryProductFirestoreDataSource().apply { failure = CancellationException() }

    try {
      ProductRepositoryFirestore(dataSource).getProducts("migros")
      fail("Expected CancellationException")
    } catch (_: CancellationException) {
      // Expected: callers must retain structured cancellation.
    }
  }

  @Test
  fun getProducts_mapsFirestoreAndNetworkFailures() = runTest {
    val cases =
        listOf(
            firebaseException(Code.PERMISSION_DENIED) to ProductError.PermissionDenied,
            firebaseException(Code.UNAUTHENTICATED) to ProductError.PermissionDenied,
            firebaseException(Code.UNAVAILABLE) to ProductError.NetworkError,
            firebaseException(Code.DEADLINE_EXCEEDED) to ProductError.NetworkError,
            firebaseException(Code.INVALID_ARGUMENT) to ProductError.InvalidData,
            firebaseException(Code.OUT_OF_RANGE) to ProductError.InvalidData,
            firebaseException(Code.DATA_LOSS) to ProductError.InvalidData,
            IOException("offline") to ProductError.NetworkError,
            firebaseException(Code.NOT_FOUND) to ProductError.Unknown,
            IllegalStateException("unexpected") to ProductError.Unknown,
        )

    cases.forEach { (failure, expected) ->
      val dataSource = InMemoryProductFirestoreDataSource().apply { this.failure = failure }
      assertProductError(expected, ProductRepositoryFirestore(dataSource).getProducts("coop"))
    }
  }

  private fun firebaseException(code: Code) = FirebaseFirestoreException(code.name, code)

  private fun assertProductError(
      expected: ProductError,
      actual: ProductResult<*>,
      message: String = "Unexpected ProductResult",
  ) = assertEquals(message, ProductResult.Error(expected), actual)

  /** Mirrors a swiss-cli product document, whose global `id` is `"{store}:{number}"`. */
  private fun productDocument(
      id: Any?,
      name: String = "Milk",
      price: Any? = 2.5,
      imageUrl: Any? = null,
      removedAt: Any? = null,
  ) =
      ProductFirestoreDocument(
          data =
              mapOf(
                  "id" to id,
                  "name" to name,
                  "price" to price,
                  "image_url" to imageUrl,
                  "removed_at" to removedAt,
              ),
      )

  private class InMemoryProductFirestoreDataSource : ProductFirestoreDataSource {
    val documentsByStore = mutableMapOf<String, List<ProductFirestoreDocument>>()
    var failure: Exception? = null
    var queryCalls = 0
    var lastStoreId: String? = null
    var lastLimit: Int? = null

    override suspend fun getProducts(
        storeId: String,
        limit: Int,
    ): List<ProductFirestoreDocument> {
      queryCalls++
      lastStoreId = storeId
      lastLimit = limit
      failure?.let { throw it }
      return documentsByStore[storeId].orEmpty().take(limit)
    }
  }
}
