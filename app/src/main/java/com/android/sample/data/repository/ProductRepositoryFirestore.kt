package com.android.sample.data.repository

// AI assistance: OpenAI Codex and Claude Code.
import com.android.sample.model.FavouriteProducts
import com.android.sample.model.Product
import com.android.sample.model.ProductPage
import com.android.sample.model.SUPPORTED_STORE_IDS
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.Source
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.tasks.await

class ProductRepositoryFirestore
internal constructor(
    private val dataSource: ProductFirestoreDataSource,
    private val currentUserId: () -> String? = { null },
) : ProductRepository {
  /** Stores known to be ranked; an unranked one is read again, so a migration applies at once. */
  private val rankedStores = ConcurrentHashMap.newKeySet<String>()

  constructor(
      firestore: FirebaseFirestore = FirebaseFirestore.getInstance(),
      currentUserId: () -> String? = { FirebaseAuth.getInstance(firestore.app).currentUser?.uid },
  ) : this(FirebaseProductFirestoreDataSource(firestore), currentUserId)

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
      category: String?,
  ): ProductResult<ProductPage> {
    val normalizedStoreId = storeId.trim().lowercase(Locale.ROOT)
    if (normalizedStoreId !in SUPPORTED_STORE_IDS) {
      return ProductResult.Error(ProductError.InvalidStore)
    }
    if (limit !in 1..MAX_PRODUCT_QUERY_LIMIT) {
      return ProductResult.Error(ProductError.InvalidLimit)
    }
    val terms = productSearchTerms(query) ?: return ProductResult.Error(ProductError.InvalidQuery)
    val normalizedCategory = category?.trim()
    if (normalizedCategory?.let { it.isEmpty() || it.length > MAX_CATEGORY_LENGTH } == true) {
      return ProductResult.Error(ProductError.InvalidCategory)
    }
    // A ranked search's cursor is "<tier>/<document ID>"; any other is a plain document ID.
    val rankedCursor = cursor?.takeIf { '/' in it }?.let(::parseRankedCursor)
    val rankedCursorInvalid =
        cursor != null && '/' in cursor && (rankedCursor == null || terms.isEmpty())
    if (rankedCursorInvalid || cursor != null && '/' !in cursor && !cursor.isValidDocumentId()) {
      return ProductResult.Error(ProductError.InvalidCursor)
    }

    return runProductRepositoryOperation {
      if (
          terms.isNotEmpty() &&
              (rankedCursor != null || cursor == null && isRanked(normalizedStoreId))
      ) {
        return@runProductRepositoryOperation queryRanked(
            normalizedStoreId,
            terms,
            normalizedCategory,
            rankedCursor,
            limit,
        )
      }
      val documents =
          dataSource.queryProducts(normalizedStoreId, terms, normalizedCategory, cursor, limit)
      ProductResult.Success(
          ProductPage(
              products = documents.mapNotNull { it.toProduct(normalizedStoreId) },
              // A full page may be followed by more documents, malformed ones included.
              nextCursor = documents.takeIf { it.size == limit }?.last()?.id,
          )
      )
    }
  }

  private suspend fun isRanked(storeId: String): Boolean {
    if (storeId in rankedStores) return true
    val version =
        try {
          dataSource.getStore(storeId)?.data?.get(FIELD_SEARCH_RANK_VERSION)
        } catch (cancelled: CancellationException) {
          throw cancelled
        } catch (_: Exception) {
          currentCoroutineContext().ensureActive()
          return false
        }
    val ranked = (version as? Number)?.toDouble() == SEARCH_RANK_VERSION.toDouble()
    if (ranked) rankedStores += storeId
    return ranked
  }

  /** The first term's tiers 1 to 7 in order, each by document ID; a page continues across tiers. */
  private suspend fun queryRanked(
      storeId: String,
      terms: List<String>,
      category: String?,
      start: RankedCursor?,
      limit: Int,
  ): ProductResult<ProductPage> {
    val documents = mutableListOf<ProductFirestoreDocument>()
    var tier = start?.tier ?: 1
    var afterDocumentId = start?.documentId
    var nextCursor: String? = null
    while (tier <= LAST_SEARCH_TIER && nextCursor == null) {
      val wanted = limit - documents.size
      val rank = SearchRank(terms.first(), tier)
      val page = dataSource.queryProducts(storeId, terms, category, afterDocumentId, wanted, rank)
      documents += page
      if (page.size == wanted) nextCursor = "$tier/${page.last().id}"
      tier++
      afterDocumentId = null
    }
    return ProductResult.Success(
        ProductPage(documents.mapNotNull { it.toProduct(storeId) }, nextCursor)
    )
  }

  override suspend fun getCategories(storeId: String): ProductResult<List<String>> {
    val normalizedStoreId = storeId.trim().lowercase(Locale.ROOT)
    if (normalizedStoreId !in SUPPORTED_STORE_IDS) {
      return ProductResult.Error(ProductError.InvalidStore)
    }
    return runProductRepositoryOperation {
      val fields: Map<String, Any?> = dataSource.getStore(normalizedStoreId)?.data.orEmpty()
      val stored = fields[FIELD_CATEGORIES] as? List<*>
      // Like a malformed product, a malformed entry is skipped rather than failing the list.
      ProductResult.Success(
          stored.orEmpty().filterIsInstance<String>().filter {
            it.isNotEmpty() && it == it.trim() && it.length <= MAX_CATEGORY_LENGTH
          }
      )
    }
  }

  /**
   * One server read per favourite, all started in parallel: O(N) reads and memory for N favourites
   * of the store. Fine for a hand-picked list; past roughly a page (50) of favourites, cap the list
   * or hydrate it page by page like [searchProducts].
   */
  override suspend fun getFavouriteProducts(storeId: String): ProductResult<FavouriteProducts> {
    val normalizedStoreId = storeId.trim().lowercase(Locale.ROOT)
    if (normalizedStoreId !in FAVOURITE_STORE_IDS) {
      return ProductResult.Error(ProductError.InvalidStore)
    }
    return runFavouriteOperation { userId ->
      // Like malformed products, a malformed reference is skipped rather than failing the list.
      val ids =
          dataSource
              .queryFavourites(userId, normalizedStoreId)
              .mapNotNull { it.toFavouriteProductId(normalizedStoreId) }
              .distinct()
      // toFavouriteProductId accepted only IDs whose store number is a valid document ID.
      val documents =
          dataSource.getProductsByDocumentIds(normalizedStoreId, ids.map { it.substringAfter(':') })
      val products =
          documents.mapNotNull { it.toProduct(normalizedStoreId) }.associateBy(Product::id)
      ProductResult.Success(
          FavouriteProducts(
              products = ids.mapNotNull(products::get),
              unavailableProductIds = ids.filterNot(products::containsKey),
          )
      )
    }
  }

  override suspend fun setFavourite(
      storeId: String,
      productId: String,
      favourite: Boolean,
  ): ProductResult<Unit> {
    val normalizedStoreId = storeId.trim().lowercase(Locale.ROOT)
    if (normalizedStoreId !in FAVOURITE_STORE_IDS) {
      return ProductResult.Error(ProductError.InvalidStore)
    }
    if (productId.productDocumentIdOf(normalizedStoreId) == null) {
      return ProductResult.Error(ProductError.InvalidData)
    }
    return runFavouriteOperation { userId ->
      dataSource.setFavourite(userId, normalizedStoreId, productId, favourite)
      ProductResult.Success(Unit)
    }
  }

  private suspend fun <T> runFavouriteOperation(
      operation: suspend (String) -> ProductResult<T>
  ): ProductResult<T> {
    currentCoroutineContext().ensureActive()
    val userId =
        currentUserId()?.takeIf(String::isValidDocumentId)
            ?: return ProductResult.Error(ProductError.PermissionDenied)
    val result = runProductRepositoryOperation { operation(userId) }
    return if (currentUserId() == userId) result
    else ProductResult.Error(ProductError.PermissionDenied)
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

/** The relevance rank a ranked query keeps for [term], from the importer's `search_rank` map. */
internal data class SearchRank(val term: String, val tier: Int)

private data class RankedCursor(val tier: Int, val documentId: String)

private fun parseRankedCursor(cursor: String): RankedCursor? {
  val tier = cursor.substringBefore('/').toIntOrNull() ?: return null
  val documentId = cursor.substringAfter('/')
  val valid = tier in 1..LAST_SEARCH_TIER && documentId.isValidDocumentId()
  return RankedCursor(tier, documentId).takeIf { valid }
}

/**
 * Raw product transport; [FirestoreDocument.id] is the pagination cursor, distinct from the stored
 * global product ID in its fields. Product decoding remains local to this repository.
 */
internal typealias ProductFirestoreDocument = FirestoreDocument

/**
 * Product-query boundary; raw pages include malformed records so cursor advancement stays stable.
 */
internal interface ProductFirestoreDataSource {
  /** Active products of [storeId] matching every term, [category] and [rank], after a document. */
  suspend fun queryProducts(
      storeId: String,
      terms: List<String>,
      category: String?,
      afterDocumentId: String?,
      limit: Int,
      rank: SearchRank? = null,
  ): List<ProductFirestoreDocument>

  /** The `stores/{storeId}` metadata document, or null before the store's first import. */
  suspend fun getStore(storeId: String): FirestoreDocument?

  suspend fun queryFavourites(userId: String, storeId: String): List<FirestoreDocument>

  suspend fun getProductsByDocumentIds(
      storeId: String,
      documentIds: List<String>,
  ): List<ProductFirestoreDocument>

  suspend fun setFavourite(userId: String, storeId: String, productId: String, favourite: Boolean)
}

/**
 * Server-only active-product queries; cached pages must not masquerade as the end of the catalogue.
 */
internal class FirebaseProductFirestoreDataSource(private val firestore: FirebaseFirestore) :
    ProductFirestoreDataSource {
  override suspend fun queryProducts(
      storeId: String,
      terms: List<String>,
      category: String?,
      afterDocumentId: String?,
      limit: Int,
      rank: SearchRank?,
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
    val ranked =
        rank?.let {
          filtered.whereEqualTo(FieldPath.of(FIELD_SEARCH_RANK, it.term), it.tier.toLong())
        } ?: filtered
    val categorized = category?.let { ranked.whereEqualTo(FIELD_CATEGORY, it) } ?: ranked
    val ordered = categorized.orderBy(FieldPath.documentId(), Query.Direction.ASCENDING)
    // Offline, a cached page can be short and would read as the end of the catalogue: fail instead.
    return (afterDocumentId?.let { ordered.startAfter(it) } ?: ordered)
        .limit(limit.toLong())
        .get(Source.SERVER)
        .await()
        .documents
        .map { it.toFirestoreDocument() }
  }

  override suspend fun getStore(storeId: String): FirestoreDocument? =
      firestore
          .collection(STORES_COLLECTION)
          .document(storeId)
          .get(Source.SERVER)
          .await()
          .takeIf { it.exists() }
          ?.toFirestoreDocument()

  override suspend fun queryFavourites(userId: String, storeId: String): List<FirestoreDocument> =
      firestore
          .collection(USERS_COLLECTION)
          .document(userId)
          .collection(FAVOURITES_COLLECTION)
          .whereEqualTo(FIELD_STORE_ID, storeId)
          .get(Source.SERVER)
          .await()
          .documents
          .sortedBy { it.id }
          .map { it.toFirestoreDocument() }

  override suspend fun getProductsByDocumentIds(
      storeId: String,
      documentIds: List<String>,
  ): List<ProductFirestoreDocument> {
    val products =
        firestore.collection(STORES_COLLECTION).document(storeId).collection(PRODUCTS_COLLECTION)
    // Every read starts before the first is awaited, so they run in parallel.
    return documentIds
        .map { id -> products.document(id).get(Source.SERVER) }
        .mapNotNull { read -> read.await().takeIf { it.exists() }?.toFirestoreDocument() }
  }

  override suspend fun setFavourite(
      userId: String,
      storeId: String,
      productId: String,
      favourite: Boolean,
  ) {
    val reference =
        firestore
            .collection(USERS_COLLECTION)
            .document(userId)
            .collection(FAVOURITES_COLLECTION)
            .document(productId)
    if (favourite) {
      reference.set(mapOf(FIELD_PRODUCT_ID to productId, FIELD_STORE_ID to storeId)).await()
    } else {
      reference.delete().await()
    }
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
      // Display only: a missing or malformed size hides the size, never the product.
      sizeText = (data[FIELD_SIZE_TEXT] as? String)?.trim()?.takeIf(String::isNotEmpty),
      category = (data[FIELD_CATEGORY] as? String)?.trim()?.takeIf(String::isNotEmpty),
      categoryPath = (data[FIELD_CATEGORY_PATH] as? String)?.trim()?.takeIf(String::isNotEmpty),
  )
}

private fun FirestoreDocument.toFavouriteProductId(storeId: String): String? {
  val productId = data[FIELD_PRODUCT_ID] as? String ?: return null
  return productId.takeIf {
    id == productId &&
        data.keys == setOf(FIELD_PRODUCT_ID, FIELD_STORE_ID) &&
        data[FIELD_STORE_ID] == storeId &&
        productId.productDocumentIdOf(storeId) != null
  }
}

/** swiss-cli product IDs are globally unique: `<store>:<store product number>`. */
private fun String.isGlobalProductIdOf(storeId: String): Boolean =
    this == trim() && startsWith("$storeId:") && substringAfter(':').isNotBlank()

private fun String.productDocumentIdOf(storeId: String): String? = takeIf {
  it.isGlobalProductIdOf(storeId)
}
    ?.substringAfter(':')
    ?.takeIf(String::isValidDocumentId)

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
    runFirestoreOperation(onFailure = { ProductResult.Error(it.toProductError()) }, operation)

private fun FirestoreFailure.toProductError(): ProductError =
    when (this) {
      FirestoreFailure.PERMISSION_DENIED,
      FirestoreFailure.UNAUTHENTICATED -> ProductError.PermissionDenied
      FirestoreFailure.NETWORK_UNAVAILABLE -> ProductError.NetworkError
      FirestoreFailure.INVALID_DATA -> ProductError.InvalidData
      // Product reads are queries, which report no missing document: NOT_FOUND is unexpected.
      FirestoreFailure.NOT_FOUND,
      FirestoreFailure.UNKNOWN -> ProductError.Unknown
    }

internal const val MAX_PRODUCT_QUERY_LIMIT = 50
internal const val MAX_SEARCH_QUERY_LENGTH = 100
private const val MAX_CATEGORY_LENGTH = 200
private const val MAX_SEARCH_TERMS = 5
internal const val MIN_SEARCH_TERM_LENGTH = 2
private const val MAX_SEARCH_TERM_LENGTH = 15
private const val MAX_CURSOR_LENGTH = 1_500
/** The `search_rank` maps this app reads; stores/{store}.search_rank_version names them. */
private const val SEARCH_RANK_VERSION = 1L
private const val LAST_SEARCH_TIER = 7
private val FAVOURITE_STORE_IDS = setOf("migros", "coop")
private val NON_SEARCHABLE_CHARACTERS = Regex("[^a-z0-9]+")
private val COMBINING_MARKS = Regex("\\p{M}+")
private const val STORES_COLLECTION = "stores"
private const val PRODUCTS_COLLECTION = "products"
private const val USERS_COLLECTION = "users"
private const val FAVOURITES_COLLECTION = "favourites"
private const val FIELD_ID = "id"
private const val FIELD_NAME = "name"
private const val FIELD_PRICE = "price"
private const val FIELD_IMAGE_URL = "image_url"
private const val FIELD_REMOVED_AT = "removed_at"
private const val FIELD_SEARCH_PREFIXES = "search_prefixes"
private const val FIELD_SEARCH_RANK = "search_rank"
private const val FIELD_SEARCH_RANK_VERSION = "search_rank_version"
private const val FIELD_SIZE_TEXT = "size_text"
private const val FIELD_CATEGORY = "category"
private const val FIELD_CATEGORY_PATH = "category_path"
private const val FIELD_CATEGORIES = "categories"
private const val FIELD_PRODUCT_ID = "productId"
private const val FIELD_STORE_ID = "storeId"
