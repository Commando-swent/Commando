package com.android.sample.data.repository

// AI assistance: OpenAI Codex.
import com.android.sample.model.Product
import com.android.sample.model.ProductPage
import com.android.sample.model.SUPPORTED_STORE_IDS
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import java.io.IOException
import java.text.Normalizer
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.tasks.await

class ProductRepositoryFirestore
internal constructor(private val dataSource: ProductFirestoreDataSource) : ProductRepository {

  constructor(
      firestore: FirebaseFirestore = FirebaseFirestore.getInstance()
  ) : this(FirebaseProductFirestoreDataSource(firestore))

  override suspend fun getProducts(storeId: String, limit: Int): ProductResult<List<Product>> =
      when (val result = searchProducts(storeId, query = "", limit = limit)) {
        is ProductResult.Success -> ProductResult.Success(result.data.products)
        is ProductResult.Error -> result
      }

  override suspend fun searchProducts(
      storeId: String,
      query: String,
      cursor: String?,
      limit: Int,
  ): ProductResult<ProductPage> {
    val normalizedStoreId = storeId.trim().lowercase(Locale.ROOT)
    if (normalizedStoreId !in SUPPORTED_STORE_IDS) {
      return ProductResult.Error(ProductError.InvalidStore)
    }
    if (limit !in 1..MAX_PRODUCT_QUERY_LIMIT) {
      return ProductResult.Error(ProductError.InvalidLimit)
    }
    val terms = productSearchTerms(query) ?: return ProductResult.Error(ProductError.InvalidQuery)
    if (cursor != null && !cursor.isValidDocumentId()) {
      return ProductResult.Error(ProductError.InvalidCursor)
    }

    return runProductRepositoryOperation {
      val documents = dataSource.queryProducts(normalizedStoreId, terms, cursor, limit)
      ProductResult.Success(
          ProductPage(
              products = documents.mapNotNull { it.toProduct(normalizedStoreId) },
              // A full page may be followed by more documents, malformed ones included.
              nextCursor = documents.takeIf { it.size == limit }?.last()?.documentId,
          )
      )
    }
  }
}

/**
 * The words of [query] as the Migros catalogue importer (#153) stores them in `search_prefixes`, or
 * null when a non-blank query has no searchable word or is too long. A blank query has no terms and
 * lists everything.
 */
internal fun productSearchTerms(query: String): List<String>? {
  if (query.length > MAX_SEARCH_QUERY_LENGTH) return null
  if (query.isBlank()) return emptyList()
  return normalizeProductSearchText(query)
      .split(NON_SEARCHABLE_CHARACTERS)
      .filter { it.length >= MIN_SEARCH_TERM_LENGTH }
      .map { it.take(MAX_SEARCH_TERM_LENGTH) }
      .distinct()
      .take(MAX_SEARCH_TERMS)
      .ifEmpty { null }
}

/** Must stay identical to `normalizeSearchText` in the Migros catalogue importer (#153). */
internal fun normalizeProductSearchText(text: String): String =
    Normalizer.normalize(
            text.lowercase(Locale.ROOT).replace("œ", "oe").replace("æ", "ae").replace("ß", "ss"),
            Normalizer.Form.NFD,
        )
        .replace(COMBINING_MARKS, "")

internal data class ProductFirestoreDocument(val documentId: String, val data: Map<String, Any?>)

internal fun interface ProductFirestoreDataSource {
  /** Active products of [storeId] whose search text holds every term, after [afterDocumentId]. */
  suspend fun queryProducts(
      storeId: String,
      terms: List<String>,
      afterDocumentId: String?,
      limit: Int,
  ): List<ProductFirestoreDocument>
}

internal class FirebaseProductFirestoreDataSource(private val firestore: FirebaseFirestore) :
    ProductFirestoreDataSource {
  override suspend fun queryProducts(
      storeId: String,
      terms: List<String>,
      afterDocumentId: String?,
      limit: Int,
  ): List<ProductFirestoreDocument> {
    // swiss-cli writes removed_at = null for listed products, so removed ones never use up the
    // limit. Firestore merges the single-field indexes of these equality filters, ordered by
    // document ID, so no composite index is needed (none could cover dynamic map keys).
    val filtered =
        terms.fold(
            firestore
                .collection(STORES_COLLECTION)
                .document(storeId)
                .collection(PRODUCTS_COLLECTION)
                .whereEqualTo(FIELD_REMOVED_AT, null)
        ) { query, term ->
          query.whereEqualTo(FieldPath.of(FIELD_SEARCH_PREFIXES, term), true)
        }
    val ordered = filtered.orderBy(FieldPath.documentId(), Query.Direction.ASCENDING)
    return (afterDocumentId?.let { ordered.startAfter(it) } ?: ordered)
        .limit(limit.toLong())
        .get()
        .await()
        .documents
        .map { ProductFirestoreDocument(it.id, it.data.orEmpty()) }
  }
}

private fun ProductFirestoreDocument.toProduct(storeId: String): Product? {
  val id = (data[FIELD_ID] as? String)?.takeIf { it.isGlobalProductIdOf(storeId) } ?: return null
  val name = (data[FIELD_NAME] as? String)?.trim()?.takeIf(String::isNotEmpty) ?: return null
  val priceCents = (data[FIELD_PRICE] as? Number)?.toPriceCents() ?: return null
  // The query already skips removed products; this check keeps the mapper safe on its own. A
  // Timestamp marks a removed product and any other non-null value is malformed.
  if (data[FIELD_REMOVED_AT] != null) return null
  val imageUrl =
      when (val value = data[FIELD_IMAGE_URL]) {
        null -> null
        is String -> value.trim().takeIf(String::isNotEmpty)
        else -> return null
      }
  return Product(
      id = id,
      storeId = storeId,
      name = name,
      priceCents = priceCents,
      imageUrl = imageUrl,
  )
}

/** swiss-cli product IDs are globally unique: `<store>:<store product number>`. */
private fun String.isGlobalProductIdOf(storeId: String): Boolean =
    this == trim() && startsWith("$storeId:") && substringAfter(':').isNotBlank()

private fun String.isValidDocumentId(): Boolean =
    isNotBlank() &&
        this == trim() &&
        '/' !in this &&
        this != "." &&
        this != ".." &&
        length <= MAX_CURSOR_LENGTH

private fun Number.toPriceCents(): Int? {
  if (this is Double && !isFinite() || this is Float && !isFinite()) return null
  val price = toString().toBigDecimalOrNull() ?: return null
  if (price.signum() < 0) return null
  return try {
    price.movePointRight(2).intValueExact()
  } catch (_: ArithmeticException) {
    null
  }
}

private suspend fun <T> runProductRepositoryOperation(
    operation: suspend () -> ProductResult<T>
): ProductResult<T> =
    try {
      operation()
    } catch (exception: CancellationException) {
      throw exception
    } catch (exception: Exception) {
      ProductResult.Error(exception.toProductError())
    }

private fun Exception.toProductError(): ProductError =
    when (this) {
      is FirebaseFirestoreException ->
          when (code) {
            FirebaseFirestoreException.Code.PERMISSION_DENIED,
            FirebaseFirestoreException.Code.UNAUTHENTICATED -> ProductError.PermissionDenied
            FirebaseFirestoreException.Code.UNAVAILABLE,
            FirebaseFirestoreException.Code.DEADLINE_EXCEEDED -> ProductError.NetworkError
            FirebaseFirestoreException.Code.INVALID_ARGUMENT,
            FirebaseFirestoreException.Code.OUT_OF_RANGE,
            FirebaseFirestoreException.Code.DATA_LOSS -> ProductError.InvalidData
            else -> ProductError.Unknown
          }
      is IOException -> ProductError.NetworkError
      else -> ProductError.Unknown
    }

internal const val MAX_PRODUCT_QUERY_LIMIT = 50
private const val MAX_SEARCH_QUERY_LENGTH = 100
private const val MAX_SEARCH_TERMS = 5
private const val MIN_SEARCH_TERM_LENGTH = 2
private const val MAX_SEARCH_TERM_LENGTH = 15
private const val MAX_CURSOR_LENGTH = 1_500
private val NON_SEARCHABLE_CHARACTERS = Regex("[^a-z0-9]+")
private val COMBINING_MARKS = Regex("\\p{M}+")
private const val STORES_COLLECTION = "stores"
private const val PRODUCTS_COLLECTION = "products"
private const val FIELD_ID = "id"
private const val FIELD_NAME = "name"
private const val FIELD_PRICE = "price"
private const val FIELD_IMAGE_URL = "image_url"
private const val FIELD_REMOVED_AT = "removed_at"
private const val FIELD_SEARCH_PREFIXES = "search_prefixes"
