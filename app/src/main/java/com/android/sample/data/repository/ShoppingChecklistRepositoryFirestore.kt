package com.android.sample.data.repository

// AI assistance: Claude Code.
import com.android.sample.model.ShoppingChecklistItem
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Source
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.tasks.await

/**
 * Stores progress in `shoppingChecklists/{tripId}` as `purchased` (product ID to checked quantity)
 * and `updatedAt`. The rules allow only the owner of `trips/{tripId}`. [currentUserId] is the
 * session-bound identity, e.g. from SessionOrderViewModel.
 */
class ShoppingChecklistRepositoryFirestore(
    private val currentUserId: () -> String?,
    private val instance: FirebaseFirestore? = null,
) : ShoppingChecklistRepository {
  private fun document(tripId: String) =
      (instance ?: FirebaseFirestore.getInstance()).collection(COLLECTION).document(tripId)

  override suspend fun getPurchasedQuantities(tripId: String) =
      session(tripId) {
        val stored = document(tripId).get(Source.SERVER).await().get(PURCHASED_FIELD) as? Map<*, *>
        // Rules cannot check map values, so malformed entries are skipped rather than trusted.
        val quantities =
            stored.orEmpty().entries.mapNotNull { (productId, quantity) ->
              if (productId is String && quantity is Long && quantity > 0) productId to quantity
              else null
            }
        OrderResult.Success(quantities.toMap())
      }

  override suspend fun setPurchased(
      tripId: String,
      item: ShoppingChecklistItem,
      purchased: Boolean,
  ) =
      session(tripId) {
        if (item.productId.isBlank() || item.requiredQuantity <= 0) {
          return@session OrderResult.Error(OrderError.InvalidData)
        }
        val quantity: Any = if (purchased) item.requiredQuantity else FieldValue.delete()
        // A merge write changes one product only, so concurrent toggles of other items are kept.
        val update =
            mapOf(
                PURCHASED_FIELD to mapOf(item.productId to quantity),
                UPDATED_AT_FIELD to FieldValue.serverTimestamp(),
            )
        document(tripId).set(update, SetOptions.merge()).await()
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
    const val PURCHASED_FIELD = "purchased"
    const val UPDATED_AT_FIELD = "updatedAt"
  }
}
