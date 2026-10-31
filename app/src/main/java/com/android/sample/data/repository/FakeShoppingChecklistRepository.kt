package com.android.sample.data.repository

// AI assistance: Claude Code.
import com.android.sample.model.ShoppingChecklistItem
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * In-memory progress for tests and demo sessions; never persists data or checks trip ownership.
 * Progress is kept per user, so another account never sees it.
 */
class FakeShoppingChecklistRepository(private val currentUserId: () -> String?) :
    ShoppingChecklistRepository {
  private val mutex = Mutex()
  private val progress = mutableMapOf<Pair<String, String>, MutableMap<String, Long>>()

  /** Deterministic failure injection: authenticated operations fail before accessing storage. */
  @Volatile var forcedError: OrderError? = null

  override suspend fun getPurchasedQuantities(tripId: String) =
      withSession(tripId) { key -> OrderResult.Success(progress[key].orEmpty().toMap()) }

  override suspend fun setPurchased(
      tripId: String,
      item: ShoppingChecklistItem,
      purchased: Boolean,
  ) =
      withSession(tripId) { key ->
        if (item.productId.isBlank() || item.requiredQuantity <= 0) {
          return@withSession OrderResult.Error(OrderError.InvalidData)
        }
        val quantities = progress.getOrPut(key) { mutableMapOf() }
        if (purchased) quantities[item.productId] = item.requiredQuantity
        else quantities.remove(item.productId)
        OrderResult.Success(Unit)
      }

  private suspend fun <T> withSession(
      tripId: String,
      action: (Pair<String, String>) -> OrderResult<T>,
  ): OrderResult<T> = mutex.withLock {
    val userId = currentUserId()
    val error = forcedError
    when {
      userId.isNullOrBlank() -> OrderResult.Error(OrderError.NotAuthenticated)
      !tripId.isDocumentId() -> OrderResult.Error(OrderError.InvalidData)
      error != null -> OrderResult.Error(error)
      else -> action(userId to tripId)
    }
  }
}
