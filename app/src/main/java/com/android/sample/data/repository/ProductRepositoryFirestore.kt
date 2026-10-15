package com.android.sample.data.repository

// AI assistance: OpenAI Codex.
import com.android.sample.model.Product
import com.android.sample.model.SUPPORTED_STORE_IDS
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import java.io.IOException
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.tasks.await

class ProductRepositoryFirestore
internal constructor(private val dataSource: ProductFirestoreDataSource) : ProductRepository {

  constructor(
      firestore: FirebaseFirestore = FirebaseFirestore.getInstance()
  ) : this(FirebaseProductFirestoreDataSource(firestore))

  override suspend fun getProducts(storeId: String, limit: Int): ProductResult<List<Product>> {
    val normalizedStoreId = storeId.trim().lowercase(Locale.ROOT)
    if (normalizedStoreId !in SUPPORTED_STORE_IDS) {
      return ProductResult.Error(ProductError.InvalidStore)
    }
    if (limit !in 1..MAX_PRODUCT_QUERY_LIMIT) {
      return ProductResult.Error(ProductError.InvalidLimit)
    }

    return runProductRepositoryOperation {
      ProductResult.Success(
          dataSource.getProducts(normalizedStoreId, limit).mapNotNull {
            it.toProduct(normalizedStoreId)
          }
      )
    }
  }
}

internal data class ProductFirestoreDocument(val data: Map<String, Any?>)

internal fun interface ProductFirestoreDataSource {
  suspend fun getProducts(storeId: String, limit: Int): List<ProductFirestoreDocument>
}

internal class FirebaseProductFirestoreDataSource(private val firestore: FirebaseFirestore) :
    ProductFirestoreDataSource {
  override suspend fun getProducts(
      storeId: String,
      limit: Int,
  ): List<ProductFirestoreDocument> =
      firestore
          .collection(STORES_COLLECTION)
          .document(storeId)
          .collection(PRODUCTS_COLLECTION)
          // swiss-cli writes removed_at = null for listed products, so removed ones never use up
          // the limit. An equality filter ordered by document ID needs no composite index.
          .whereEqualTo(FIELD_REMOVED_AT, null)
          .orderBy(FieldPath.documentId(), Query.Direction.ASCENDING)
          .limit(limit.toLong())
          .get()
          .await()
          .documents
          .map { ProductFirestoreDocument(it.data.orEmpty()) }
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
private const val STORES_COLLECTION = "stores"
private const val PRODUCTS_COLLECTION = "products"
private const val FIELD_ID = "id"
private const val FIELD_NAME = "name"
private const val FIELD_PRICE = "price"
private const val FIELD_IMAGE_URL = "image_url"
private const val FIELD_REMOVED_AT = "removed_at"
