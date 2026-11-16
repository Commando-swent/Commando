package com.android.sample.data.repository

// AI assistance: OpenAI Codex (#325).
import com.commando.app.model.*
import com.google.firebase.Timestamp
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

/** Pure policy matrix; authorization and atomicity remain covered by repository/emulator suites. */
class OrderFirestoreDecisionsTest {
  private val now = Instant.parse("2030-01-01T12:00:00Z")
  private val draft = OrderDraft("trip", listOf(OrderItem("milk", "Milk", 220, 2)), 150)
  private val order =
      Order(
          "order",
          "trip",
          "requester",
          draft.items,
          draft.commissionCents,
          OrderStatus.PENDING,
          now,
      )
  private val actions =
      OwnerOrderTransition.entries.map { it.source to it.target } +
          listOf(
              OrderStatus.PENDING to OrderStatus.CANCELLED,
              OrderStatus.READY_FOR_HANDOFF to OrderStatus.DELIVERED,
          )

  @Test
  fun allStatusesAndActionsPreserveWriteEligibilityIdempotenceAndPersistedConflicts() {
    for ((from, target) in actions) for (status in OrderStatus.entries) {
      val stored = order.copy(status = status, revision = 2)
      val document = document(stored)
      for (expected in listOf(null, stored.revision, stored.revision - 1)) {
        val unchanged = status == from && (expected == null || expected == stored.revision)
        val result =
            when {
              unchanged -> OrderResult.Error(OrderError.InvalidData)
              status == target -> OrderResult.Success(stored)
              else -> OrderResult.Error(OrderError.AlreadyDecided(stored))
            }
        assertEquals(
            "$status -> $target @ $expected",
            result,
            stored.transitionResult(from, target, expected),
        )
        assertEquals(unchanged, document.canTransition(from, expected))
      }
    }
  }

  @Test
  fun legacyAndMalformedRevisionsKeepTheirExistingConflictRules() {
    val legacy = document(order).copy(data = document(order).data - "revision")
    assertEquals(0, legacy.revisionOrNull())
    assertFalse(legacy.staleRevision(null))
    assertFalse(legacy.staleRevision(0))
    assertTrue(legacy.staleRevision(1))
    assertFalse(legacy.hasEditConflict(0))
    for (revision in listOf(null, "0", -1L)) {
      val malformed = legacy.copy(data = legacy.data + ("revision" to revision))
      assertFalse(malformed.staleRevision(null))
      assertTrue(malformed.staleRevision(0))
      assertTrue(malformed.hasEditConflict(0))
    }
    for (status in OrderStatus.entries) {
      val stored = document(order.copy(status = status, revision = 1))
      assertEquals(status != OrderStatus.PENDING, stored.hasEditConflict(1))
      assertTrue(stored.hasEditConflict(0))
    }
  }

  @Test
  fun editResultsDistinguishSameContentStaleContentAndTerminalStatus() {
    for (status in OrderStatus.entries) for (revision in listOf(0, 1)) {
      val stored = order.copy(status = status, revision = revision)
      assertEquals(
          if (status == OrderStatus.PENDING) OrderResult.Success(stored)
          else OrderResult.Error(OrderError.AlreadyDecided(stored)),
          stored.editResult(draft, 0),
      )
      for (changed in
          listOf(
              draft.copy(commissionCents = 250),
              draft.copy(items = listOf(draft.items.single().copy(quantity = 3))),
          )) {
        val error =
            if (status != OrderStatus.PENDING || revision != 0) OrderError.AlreadyDecided(stored)
            else OrderError.InvalidData
        assertEquals(OrderResult.Error(error), stored.editResult(changed, 0))
      }
    }
  }

  @Test
  fun identicalEditsAreReadOnlyAndChangedEditsAdvanceWithoutOverflow() {
    for (revision in listOf(0, 1, Int.MAX_VALUE)) {
      val stored = order.copy(revision = revision)
      assertEquals(OrderResult.Success<Int?>(null), stored.nextEditRevision(draft))
      val result =
          if (revision == Int.MAX_VALUE) OrderResult.Error(OrderError.InvalidData)
          else OrderResult.Success(revision + 1)
      assertEquals(result, stored.nextEditRevision(draft.copy(commissionCents = 250)))
      assertEquals(
          result,
          stored.nextEditRevision(
              draft.copy(items = listOf(draft.items.single().copy(quantity = 3)))
          ),
      )
    }
  }

  @Test
  fun tripEligibilityKeepsSelfOrderStatusTimeAndMalformedFieldBehavior() {
    val fields =
        mapOf(
            "ownerId" to "owner",
            "status" to TripStatus.PUBLISHED.name,
            "scheduledAt" to Timestamp(now.plusSeconds(1)),
        )
    assertTrue(fields.isEditableOrderTrip("requester", now))
    assertTrue((fields - "ownerId").isEditableOrderTrip("requester", now))
    assertFalse(fields.isEditableOrderTrip("owner", now))
    assertFalse((fields - "scheduledAt").isEditableOrderTrip("requester", now))
    for (departure in listOf(now, now.minusSeconds(1))) {
      assertFalse(
          (fields + ("scheduledAt" to Timestamp(departure))).isEditableOrderTrip("requester", now)
      )
    }
    for (status in TripStatus.entries.filter { it != TripStatus.PUBLISHED }) {
      assertFalse(
          (fields + mapOf("status" to status.name, "scheduledAt" to "bad")).isEditableOrderTrip(
              "requester",
              now,
          )
      )
    }
    assertFalse((fields + ("scheduledAt" to "bad")).isEditableOrderTrip("owner", now))
    assertFalse((fields - "status").isEditableOrderTrip("requester", now))
    for ((field, value) in listOf("ownerId" to 1, "status" to 1, "scheduledAt" to "bad")) {
      val error =
          assertThrows(ClassCastException::class.java) {
            (fields + (field to value)).isEditableOrderTrip("requester", now)
          }
      assertEquals(OrderError.Unknown, error.toOrderError())
    }
  }

  private fun document(order: Order) =
      OrderFirestoreDocument(
          order.id,
          draft.firestoreFields(order.requesterId) +
              mapOf(
                  "createdAt" to Timestamp(order.createdAt),
                  "status" to order.status.name,
                  "revision" to order.revision,
              ),
      )
}
