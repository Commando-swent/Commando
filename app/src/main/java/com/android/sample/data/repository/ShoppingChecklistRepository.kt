package com.android.sample.data.repository

// AI assistance: Claude Code.
import com.android.sample.model.ShoppingChecklistItem

/**
 * Persists the purchase progress of a trip's shopping checklist. Products and quantities are not
 * stored here: they come from the trip's orders through [OrderRepository]. Only the authenticated
 * owner of the trip may read or change its progress; results of an ended session are discarded.
 */
interface ShoppingChecklistRepository {
  /**
   * Quantity each product of [tripId] was checked for, by product ID; empty when nothing is
   * checked.
   */
  suspend fun getPurchasedQuantities(tripId: String): OrderResult<Map<String, Long>>

  /**
   * Checks [item] of [tripId] for its current required quantity when [purchased] is true, or
   * unchecks it. Other items keep their progress.
   */
  suspend fun setPurchased(
      tripId: String,
      item: ShoppingChecklistItem,
      purchased: Boolean,
  ): OrderResult<Unit>
}
