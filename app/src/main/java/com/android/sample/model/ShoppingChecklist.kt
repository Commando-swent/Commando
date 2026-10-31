package com.android.sample.model

// AI assistance: Claude Code.

/**
 * Order statuses whose products the trip owner still has to buy. Only ACCEPTED for now; the Sprint
 * 4 handoff statuses are added here once their contract is agreed.
 */
val SHOPPING_ELIGIBLE_STATUSES: Set<OrderStatus> = setOf(OrderStatus.ACCEPTED)

/**
 * One product to buy for a trip. All orders of a trip target the trip's store, so [productId] alone
 * identifies the product; two products with the same name but different IDs stay separate.
 *
 * @property name name stored in the oldest eligible order containing the product.
 * @property requiredQuantity sum of the quantities of this product in the eligible orders.
 * @property purchased true when the owner checked the item for at least [requiredQuantity] units.
 */
data class ShoppingChecklistItem(
    val productId: String,
    val name: String,
    val requiredQuantity: Long,
    val purchased: Boolean,
)

/**
 * Builds the shopping checklist of [tripId] from its [orders]: eligible orders only, items grouped
 * by product ID, quantities summed. [purchasedQuantities] maps a product ID to the quantity it was
 * checked for; an item stays purchased only while that quantity covers the requirement, so a newly
 * accepted order adding more of a product unchecks only that product. The result does not depend on
 * the order of [orders] and is sorted by name, then product ID.
 */
fun buildShoppingChecklist(
    tripId: String,
    orders: List<Order>,
    purchasedQuantities: Map<String, Long>,
): List<ShoppingChecklistItem> =
    orders
        .filter { it.tripId == tripId && it.status in SHOPPING_ELIGIBLE_STATUSES }
        .sortedWith(compareBy<Order> { it.createdAt }.thenBy { it.id })
        .flatMap { it.items }
        .groupBy { it.productId }
        .map { (productId, items) ->
          val required = items.sumOf { it.quantity.toLong() }
          ShoppingChecklistItem(
              productId = productId,
              name = items.first().name,
              requiredQuantity = required,
              purchased = (purchasedQuantities[productId] ?: 0L) >= required,
          )
        }
        .sortedWith(
            compareBy<ShoppingChecklistItem> { it.name.lowercase() }.thenBy { it.productId }
        )
