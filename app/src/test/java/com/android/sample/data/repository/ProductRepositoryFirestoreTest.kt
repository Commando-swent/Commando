package com.android.sample.data.repository

// AI assistance: OpenAI Codex.
import com.android.sample.model.Product
import com.android.sample.model.ProductPage
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.FirebaseFirestoreException.Code
import java.io.IOException
import java.util.concurrent.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
            productDocument("coop:4446521", name = "Example product", removedAt = REMOVED_AT),
            productDocument(
                "coop:42",
                name = "  Whole milk  ",
                price = 3L,
                imageUrl = " https://e.test/m.png ",
            ),
        )

    val products = ProductRepositoryFirestore(dataSource).getProducts(" COOP ", 3).successData()

    // Document IDs are ordered as strings: "42" precedes "4446520".
    assertEquals(
        listOf(
            Product("coop:42", "coop", "Whole milk", 300, "https://e.test/m.png"),
            Product(
                "coop:4446520",
                "coop",
                "Example product",
                395,
                "https://example.com/product.jpg",
            ),
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
    // Each malformed `id` belongs to its own document, as Firestore document IDs are unique.
    dataSource.documentsByStore["coop"] =
        invalidIds.mapIndexed { index, document ->
          document.copy(documentId = "${4446521 + index}")
        } + valid

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
            productDocument("migros:missing").let { it.copy(data = it.data - "price") },
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
    val documents =
        listOf(
            productDocument("denner:removed", removedAt = Timestamp(1_760_000_000, 0)),
            productDocument("denner:blank-name", name = "  "),
            productDocument("denner:bad-image", imageUrl = 123),
            productDocument("denner:bad-removed", removedAt = "2026-01-01T00:00:00Z"),
            productDocument("denner:valid"),
        )
    // Unlike the Firestore query, this source returns removed documents for the mapper to skip.
    val repository = ProductRepositoryFirestore { _, _, _, limit -> documents.take(limit) }

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

  @Test
  fun productSearchTerms_normalizeFrenchAccentsLigaturesAndPunctuation() {
    // Shared vectors with the catalogue importer's (#153) searchTerms; keep both lists in sync.
    assertEquals(listOf("creme", "brulee"), productSearchTerms("  Crème BRÛLÉE "))
    assertEquals(listOf("oeufs", "plein", "air"), productSearchTerms("Œufs plein-air"))
    assertEquals(listOf("eau", "minerale", "5l"), productSearchTerms("l'eau minérale, 1 x 5l"))
    assertEquals(listOf("huile", "hiver"), productSearchTerms("Huile d’hiver"))
    assertEquals(listOf("straciatellaama"), productSearchTerms("Straciatellaamaretto"))
    assertEquals(listOf("lait"), productSearchTerms("lait LAIT Lait"))
    assertEquals(listOf("aa", "bb", "cc", "dd", "ee"), productSearchTerms("aa bb cc dd ee ff"))
    assertEquals(emptyList<String>(), productSearchTerms("   "))
    assertNull(productSearchTerms("a ! é"))
    assertNull(productSearchTerms("x".repeat(101)))
  }

  @Test
  fun searchProducts_requiresEveryNormalizedWordAsANamePrefix() = runTest {
    val dataSource = InMemoryProductFirestoreDataSource()
    dataSource.documentsByStore["migros"] =
        listOf(
            productDocument("migros:1", name = "Crème brûlée"),
            productDocument("migros:2", name = "Crémant 55%"),
            productDocument("migros:3", name = "Lait entier UHT"),
            productDocument("migros:4", name = "Lait demi-écrémé"),
            productDocument("migros:5", name = "Crème entière", removedAt = REMOVED_AT),
        )
    val repository = ProductRepositoryFirestore(dataSource)
    suspend fun idsFor(query: String) =
        repository.searchProducts("migros", query).successData().products.map(Product::id)

    assertEquals(listOf("migros:1", "migros:2"), idsFor("CRÉ"))
    assertEquals(listOf("migros:1"), idsFor("creme"))
    assertEquals(listOf("migros:3"), idsFor("entier lait"))
    assertEquals(listOf("entier", "lait"), dataSource.lastTerms)
    assertEquals(listOf("migros:4"), idsFor("lait écrémé"))
    assertEquals(emptyList<String>(), idsFor("lait chocolat"))
  }

  @Test
  fun searchProducts_matchesTheLastCategorySegmentButNotParentCategories() = runTest {
    val dataSource = InMemoryProductFirestoreDataSource()
    dataSource.documentsByStore["migros"] =
        listOf(
            productDocument("migros:1", "Nature", category = "Produits laitiers > Yogourts nature"),
            productDocument("migros:2", "Noisettes", category = "Tablettes de chocolat > Lait"),
            productDocument("migros:3", "Lait entier UHT"),
        )
    val repository = ProductRepositoryFirestore(dataSource)
    suspend fun idsFor(query: String) =
        repository.searchProducts("migros", query).successData().products.map(Product::id)

    assertEquals(listOf("migros:1"), idsFor("yogourt nature"))
    assertEquals(listOf("migros:2", "migros:3"), idsFor("lait"))
    assertEquals(emptyList<String>(), idsFor("produits laitiers"))
  }

  @Test
  fun searchProducts_pagesWithCursorsAndKeepsStoresIsolated() = runTest {
    val dataSource = InMemoryProductFirestoreDataSource()
    dataSource.documentsByStore["migros"] =
        (1..5).map { productDocument("migros:$it", name = "Milk $it") }
    dataSource.documentsByStore["coop"] = listOf(productDocument("coop:9"))
    val repository = ProductRepositoryFirestore(dataSource)

    val first = repository.searchProducts("migros", "milk", limit = 2).successData()
    val second = repository.searchProducts("migros", "milk", first.nextCursor, 2).successData()
    val third = repository.searchProducts("migros", "milk", second.nextCursor, 2).successData()

    assertEquals(listOf("migros:1", "migros:2"), first.products.map(Product::id))
    assertEquals("2", first.nextCursor)
    assertEquals(listOf("migros:3", "migros:4"), second.products.map(Product::id))
    assertEquals(ProductPage(listOf(productOf("migros:5", "Milk 5")), null), third)
    assertEquals("4", dataSource.lastCursor)
    assertEquals(
        ProductPage(emptyList(), null),
        repository.searchProducts("aldi", "milk").successData(),
    )
  }

  @Test
  fun searchProducts_cursorOfADeletedProductStillPagesForward() = runTest {
    val dataSource = InMemoryProductFirestoreDataSource()
    dataSource.documentsByStore["migros"] =
        (1..4).map { productDocument("migros:$it", name = "Milk $it") }
    val repository = ProductRepositoryFirestore(dataSource)

    val first = repository.searchProducts("migros", "milk", limit = 2).successData()
    dataSource.documentsByStore["migros"] =
        dataSource.documentsByStore.getValue("migros").filterNot { it.documentId == "2" }
    val second = repository.searchProducts("migros", "milk", first.nextCursor, 2).successData()

    assertEquals("2", first.nextCursor)
    assertEquals(listOf("migros:3", "migros:4"), second.products.map(Product::id))
  }

  @Test
  fun searchProducts_removedProductsDoNotUseUpThePageLimit() = runTest {
    val removed = setOf(2, 4, 5, 8)
    val dataSource = InMemoryProductFirestoreDataSource()
    dataSource.documentsByStore["migros"] =
        (1..8).map { id ->
          productDocument("migros:$id", "Milk $id", removedAt = REMOVED_AT.takeIf { id in removed })
        }
    val repository = ProductRepositoryFirestore(dataSource)

    val first = repository.searchProducts("migros", "milk", limit = 3).successData()
    val second = repository.searchProducts("migros", "milk", first.nextCursor, 3).successData()

    assertEquals(
        ProductPage(listOf(1, 3, 6).map { productOf("migros:$it", "Milk $it") }, "6"),
        first,
    )
    // The trailing removed product "8" leaves a short page, so paging ends without a duplicate.
    assertEquals(ProductPage(listOf(productOf("migros:7", "Milk 7")), null), second)
  }

  @Test
  fun searchProducts_fullPageCursorAdvancesPastMalformedDocuments() = runTest {
    val dataSource = InMemoryProductFirestoreDataSource()
    dataSource.documentsByStore["coop"] =
        listOf(
            productDocument("coop:1"),
            productDocument("coop:2", price = -1),
            productDocument("coop:3"),
        )
    val repository = ProductRepositoryFirestore(dataSource)

    val page = repository.searchProducts("coop", "", limit = 2).successData()

    assertEquals(ProductPage(listOf(productOf("coop:1")), "2"), page)
    assertEquals(
        ProductPage(listOf(productOf("coop:3")), null),
        repository.searchProducts("coop", "", page.nextCursor, 2).successData(),
    )
  }

  @Test
  fun searchProducts_rejectsInvalidArgumentsWithoutQuerying() = runTest {
    val dataSource = InMemoryProductFirestoreDataSource()
    val repository = ProductRepositoryFirestore(dataSource)

    assertProductError(ProductError.InvalidStore, repository.searchProducts("carrefour", "milk"))
    assertProductError(
        ProductError.InvalidLimit,
        repository.searchProducts("coop", "milk", limit = 0),
    )
    listOf("a", "?!", "é", "x".repeat(101)).forEach { query ->
      assertProductError(ProductError.InvalidQuery, repository.searchProducts("coop", query), query)
    }
    listOf("", " ", " 2", "a/b", ".", "..", "x".repeat(1_501)).forEach { cursor ->
      assertProductError(
          ProductError.InvalidCursor,
          repository.searchProducts("coop", "milk", cursor),
          cursor,
      )
    }
    assertEquals(0, dataSource.queryCalls)
  }

  @Test
  fun searchProducts_mapsFailuresAndPreservesCancellation() = runTest {
    val dataSource =
        InMemoryProductFirestoreDataSource().apply { failure = firebaseException(Code.UNAVAILABLE) }
    val repository = ProductRepositoryFirestore(dataSource)

    assertProductError(ProductError.NetworkError, repository.searchProducts("coop", "milk", "1"))
    dataSource.failure = CancellationException()
    try {
      repository.searchProducts("coop", "milk")
      fail("Expected CancellationException")
    } catch (_: CancellationException) {
      // Expected: callers must retain structured cancellation.
    }
  }

  private fun productOf(id: String, name: String = "Milk") =
      Product(id, id.substringBefore(':'), name, 250)

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
      category: String = "",
  ) =
      ProductFirestoreDocument(
          documentId = (id as? String)?.substringAfter(':') ?: "$id",
          data =
              mapOf(
                  "id" to id,
                  "name" to name,
                  "price" to price,
                  "image_url" to imageUrl,
                  "removed_at" to removedAt,
                  "search_prefixes" to productSearchPrefixes(name, category),
              ),
      )

  private companion object {
    val REMOVED_AT = Timestamp(1_760_000_000, 0)
  }

  private class InMemoryProductFirestoreDataSource : ProductFirestoreDataSource {
    val documentsByStore = mutableMapOf<String, List<ProductFirestoreDocument>>()
    var failure: Exception? = null
    var queryCalls = 0
    var lastStoreId: String? = null
    var lastLimit: Int? = null
    var lastTerms: List<String>? = null
    var lastCursor: String? = null

    /**
     * Mirrors the Firestore query: `removed_at == null` (the field must be present) and every term
     * prefix are filtered before the limit, in document ID order. As in Firestore, the cursor is a
     * position: it need not be a stored document.
     */
    override suspend fun queryProducts(
        storeId: String,
        terms: List<String>,
        afterDocumentId: String?,
        limit: Int,
    ): List<ProductFirestoreDocument> {
      queryCalls++
      lastStoreId = storeId
      lastLimit = limit
      lastTerms = terms
      lastCursor = afterDocumentId
      failure?.let { throw it }
      return documentsByStore[storeId]
          .orEmpty()
          .sortedBy { it.documentId }
          .filter { afterDocumentId == null || it.documentId > afterDocumentId }
          .filter { "removed_at" in it.data && it.data["removed_at"] == null }
          .filter { document ->
            val prefixes = document.data["search_prefixes"] as? Map<*, *>
            terms.all { prefixes?.get(it) == true }
          }
          .take(limit)
    }
  }
}
