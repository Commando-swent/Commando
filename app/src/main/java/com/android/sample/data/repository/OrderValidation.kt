package com.android.sample.data.repository

import com.android.sample.model.OrderDraft

/** Shared input and decoded-snapshot validation; CHF arithmetic must fit in Int cents. */
internal fun OrderDraft.isValid(): Boolean {
  if (tripId.isBlank() || commissionCents < 0) return false
  if (items.isEmpty() || items.map { it.productId }.distinct().size != items.size) return false
  if (
      items.any {
        it.productId.isBlank() || it.name.isBlank() || it.quantity <= 0 || it.unitPriceCents < 0
      }
  )
      return false
  return try {
    val productTotal =
        items.fold(0) { total, item ->
          Math.addExact(
              total,
              Math.multiplyExact(item.unitPriceCents, item.quantity),
          )
        }
    Math.addExact(productTotal, commissionCents)
    true
  } catch (_: ArithmeticException) {
    false
  }
}
