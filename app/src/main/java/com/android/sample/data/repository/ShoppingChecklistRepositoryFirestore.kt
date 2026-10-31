package com.android.sample.data.repository

// AI assistance: Claude Code.
import com.android.sample.model.ShoppingChecklistItem
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.tasks.await

/**
 * Stores one document per checked product in `shoppingChecklists/{tripId}/items/{productId}`, with
 * the checked `quantity` and `updatedAt`; unchecking deletes it. One document per product keeps the
 * progress unbounded like the aggregation. The rules allow only the owner of `trips/{tripId}`.
 * [currentUserId] is the session-bound identity, e.g. from SessionOrderViewModel.
 */
class ShoppingChecklistRepositoryFirestore(
    private val currentUserId: () -> String?,
    private val instance: FirebaseFirestore? = null,
) : ShoppingChecklistRepository {
  private fun items(tripId: String) =
      (instance ?: FirebaseFirestore.getInstance())
          .collection(COLLECTION)
          .document(tripId)
          .collection(ITEMS_COLLECTION)

  override suspend fun getPurchasedQuantities(tripId: String) =
      session(tripId) {
        val documents = items(tripId).get(Source.SERVER).await().documents
        // Documents written outside the app bypass the rules, so malformed ones are skipped.
        val quantities = documents.mapNotNull { document ->
          (document.get(QUANTITY_FIELD) as? Long)?.takeIf { it > 0 }?.let { document.id to it }
        }
        OrderResult.Success(quantities.toMap())
      }

  override suspend fun setPurchased(
      tripId: String,
      item: ShoppingChecklistItem,
      purchased: Boolean,
  ) =
      session(tripId) {
        if (!item.productId.isDocumentId() || item.requiredQuantity <= 0) {
          return@session OrderResult.Error(OrderError.InvalidData)
        }
        val document = items(tripId).document(item.productId)
        if (purchased) {
          val data =
              mapOf(
                  QUANTITY_FIELD to item.requiredQuantity,
                  UPDATED_AT_FIELD to FieldValue.serverTimestamp(),
              )
          document.set(data).await()
        } else {
          // Deleting a missing document succeeds, so unchecking before any save is a no-op.
          document.delete().await()
        }
        OrderResult.Success(Unit)
      }

  private suspend fun <T> session(
      tripId: String,
      action: suspend () -> OrderResult<T>,
  ): OrderResult<T> {
    currentCoroutineContext().ensureActive()
    val uid =
        currentUserId()?.takeIf { it.isNotBlank() }
            ?: return OrderResult.Error(OrderError.NotAuthenticated)
    if (!tripId.isDocumentId()) return OrderResult.Error(OrderError.InvalidData)
    val result =
        try {
          action()
        } catch (cancelled: CancellationException) {
          throw cancelled
        } catch (exception: Exception) {
          OrderResult.Error(exception.toOrderError())
        }
    currentCoroutineContext().ensureActive()
    return if (currentUserId() == uid) result else OrderResult.Error(OrderError.NotAuthenticated)
  }

  private companion object {
    const val COLLECTION = "shoppingChecklists"
    const val ITEMS_COLLECTION = "items"
    const val QUANTITY_FIELD = "quantity"
    const val UPDATED_AT_FIELD = "updatedAt"
  }
}
