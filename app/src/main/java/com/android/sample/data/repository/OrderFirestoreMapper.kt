package com.android.sample.data.repository

// AI assistance: OpenAI Codex (#325).
import com.commando.app.model.*
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue

/** Encodes a new order without generating an ID, reading a session or performing any write. */
internal fun OrderDraft.firestoreFields(requesterId: String): Map<String, Any?> =
    mapOf(
        "tripId" to tripId,
        "requesterId" to requesterId,
        "items" to items.firestoreItems(),
        "commissionCents" to commissionCents,
        "status" to OrderStatus.PENDING.name,
        "createdAt" to FieldValue.serverTimestamp(),
    )

/** Encodes only editable fields; revision selection and authorization belong to the caller. */
internal fun OrderDraft.firestoreEditFields(revision: Int): Map<String, Any> =
    mapOf(
        "items" to items.firestoreItems(),
        "commissionCents" to commissionCents,
        "revision" to revision,
    )

/** Missing revision means legacy zero; malformed or overflowing revisions fail decoding. */
internal fun OrderFirestoreDocument.revisionOrNull(): Int? =
    if ("revision" in data) data["revision"].toNonNegativeIntOrNull() else 0

/** Reuses the stored product-snapshot schema for creation and edits. */
internal fun List<OrderItem>.firestoreItems(): List<Map<String, Any>> = map {
  mapOf(
      "productId" to it.productId,
      "name" to it.name,
      "unitPriceCents" to it.unitPriceCents,
      "quantity" to it.quantity,
  )
}

private fun Any?.toNonNegativeIntOrNull(): Int? =
    when (this) {
      is Long -> takeIf { it in 0..Int.MAX_VALUE.toLong() }?.toInt()
      is Int -> takeIf { it >= 0 }
      else -> null
    }

/**
 * Decodes an untrusted snapshot with the existing bounded order validation. Missing revision alone
 * defaults to legacy zero; no identity, money, timestamp or status field is repaired or coerced.
 */
internal fun OrderFirestoreDocument.toOrder(): Order? {
  val fields: Map<String, Any?> = data
  val trip = fields["tripId"] as? String ?: return null
  val requester = fields["requesterId"] as? String ?: return null
  val fee = fields["commissionCents"].toNonNegativeIntOrNull() ?: return null
  val revision = revisionOrNull() ?: return null
  val status = OrderStatus.entries.find { it.name == fields["status"] } ?: return null
  val created = fields["createdAt"] as? Timestamp ?: return null
  val items =
      (fields["items"] as? List<*>)?.map { entry ->
        val item = entry as? Map<*, *> ?: return null
        OrderItem(
            item["productId"] as? String ?: return null,
            item["name"] as? String ?: return null,
            item["unitPriceCents"].toNonNegativeIntOrNull() ?: return null,
            item["quantity"].toNonNegativeIntOrNull() ?: return null,
        )
      } ?: return null
  if (
      !id.isDocumentId() ||
          !trip.isDocumentId() ||
          requester.isBlank() ||
          !OrderDraft(trip, items, fee).isValid()
  )
      return null
  return Order(id, trip, requester, items.toList(), fee, status, created.toInstant(), revision)
}
