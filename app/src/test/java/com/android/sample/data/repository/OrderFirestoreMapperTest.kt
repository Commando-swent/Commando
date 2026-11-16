package com.android.sample.data.repository

// AI assistance: OpenAI Codex (#325).
import com.commando.app.model.*
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

/** Characterizes the persisted schema, including legacy and malformed inputs, without Firebase. */
class OrderFirestoreMapperTest {
  private val now = Instant.parse("2030-01-01T12:00:00.123456789Z")
  private val item = OrderItem("milk", "Milk", 220, 2)
  private val draft = OrderDraft("trip", listOf(item), 150)
  private val data = draft.firestoreFields("requester") + ("createdAt" to Timestamp(now))
  private val document = OrderFirestoreDocument("order", data)
  private val expected =
      Order("order", draft.tripId, "requester", draft.items, 150, OrderStatus.PENDING, now)

  @Test
  fun creationAndEditPayloadsRetainExactKeysAndIntegerTypes() {
    val items =
        listOf(
            mapOf(
                "productId" to item.productId,
                "name" to item.name,
                "unitPriceCents" to item.unitPriceCents,
                "quantity" to item.quantity,
            )
        )
    assertEquals(
        mapOf(
            "tripId" to draft.tripId,
            "requesterId" to "requester",
            "items" to items,
            "commissionCents" to draft.commissionCents,
            "status" to OrderStatus.PENDING.name,
            "createdAt" to FieldValue.serverTimestamp(),
        ),
        draft.firestoreFields("requester"),
    )
    assertEquals(
        mapOf("items" to items, "commissionCents" to draft.commissionCents, "revision" to 1),
        draft.firestoreEditFields(1),
    )
    assertTrue(draft.firestoreEditFields(1).getValue("revision") is Int)
    assertTrue(items.single().getValue("unitPriceCents") is Int)
    assertTrue(items.single().getValue("quantity") is Int)
  }

  @Test
  fun legacyZeroExplicitRevisionsAndEveryStatusDecodeWithoutLosingTimestampPrecision() {
    assertEquals(expected, document.toOrder())
    for (status in OrderStatus.entries) {
      for (revision in listOf(0, 1L, Int.MAX_VALUE.toLong())) {
        assertEquals(
            expected.copy(status = status, revision = (revision as Number).toInt()),
            document
                .copy(data = data + mapOf("status" to status.name, "revision" to revision))
                .toOrder(),
        )
      }
    }
    assertEquals(expected, document.copy(data = data + ("futureField" to true)).toOrder())
  }

  @Test
  fun missingOrIncorrectRequiredFieldsNeverBecomeDefaults() {
    val badFields =
        mapOf(
            "tripId" to listOf(null, "", "path/trip", 1),
            "requesterId" to listOf(null, " ", 1),
            "commissionCents" to listOf(null, -1, -1L, 1.0, Int.MAX_VALUE.toLong() + 1),
            "revision" to listOf(null, -1, -1L, 1.0, Int.MAX_VALUE.toLong() + 1, "1"),
            "status" to listOf(null, "UNKNOWN", OrderStatus.PENDING),
            "createdAt" to listOf(null, now, "now"),
            "items" to
                listOf(
                    null,
                    "items",
                    emptyList<Any>(),
                    listOf(null),
                    listOf("item"),
                    listOf(emptyMap<String, Any>()),
                ),
        )
    for ((field, values) in badFields) {
      if (field != "revision") assertNull(field, document.copy(data = data - field).toOrder())
      for (value in values) assertNull(
          "$field=$value",
          document.copy(data = data + (field to value)).toOrder(),
      )
    }
    for (id in listOf("", " ", "path/order")) assertNull(document.copy(id = id).toOrder())
  }

  @Test
  fun itemMappingRejectsMissingWrongTypedAndInvalidSnapshotValues() {
    val stored = draft.items.firestoreItems().single()
    val badFields =
        mapOf(
            "productId" to listOf(null, "", 1),
            "name" to listOf(null, " ", 1),
            "unitPriceCents" to listOf(null, -1, -1L, 1.0, Long.MAX_VALUE),
            "quantity" to listOf(null, 0, -1, -1L, 1.0, Long.MAX_VALUE),
        )
    for ((field, values) in badFields) {
      assertNull(document.copy(data = data + ("items" to listOf(stored - field))).toOrder())
      for (value in values) {
        assertNull(
            "$field=$value",
            document.copy(data = data + ("items" to listOf(stored + (field to value)))).toOrder(),
        )
      }
    }
    val longItem =
        stored +
            mapOf(
                "unitPriceCents" to item.unitPriceCents.toLong(),
                "quantity" to item.quantity.toLong(),
            )
    assertEquals(
        expected,
        document
            .copy(data = data + mapOf("items" to listOf(longItem), "commissionCents" to 150L))
            .toOrder(),
    )
    assertNull(document.copy(data = data + ("items" to listOf(stored, stored))).toOrder())
    assertNull(
        document
            .copy(
                data =
                    data +
                        ("items" to
                            List(MAX_ORDER_PRODUCTS + 1) {
                              stored + ("productId" to "product-$it")
                            })
            )
            .toOrder()
    )
    assertNull(
        document
            .copy(data = data + ("items" to listOf(stored + ("quantity" to Int.MAX_VALUE))))
            .toOrder()
    )
  }

  @Test
  fun maximumIntegerAndZeroPricesStayValidWithoutSharingMutableInputLists() {
    val free = item.copy(unitPriceCents = 0, quantity = 1)
    val items =
        mutableListOf<Map<String, Any>>(
            draft.copy(items = listOf(free)).items.firestoreItems().single()
        )
    val decoded =
        document
            .copy(
                data = data + mapOf("items" to items, "commissionCents" to Int.MAX_VALUE.toLong())
            )
            .toOrder()
    assertEquals(expected.copy(items = listOf(free), commissionCents = Int.MAX_VALUE), decoded)
    items.clear()
    assertEquals(listOf(free), decoded!!.items)
    assertEquals(
        expected.copy(commissionCents = 0),
        document.copy(data = data + ("commissionCents" to 0L)).toOrder(),
    )
  }
}
