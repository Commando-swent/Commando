package com.android.sample.data.repository

// AI assistance: OpenAI Codex.
import com.android.sample.model.*
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestoreException
import java.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class OrderRepositoryFirestoreTest {
  private val time = Instant.parse("2030-01-01T12:00:00Z")
  private val item = OrderItem("milk", "Milk", 220, 2)
  private val draft = OrderDraft("trip", listOf(item), 150)
  private var uid: String? = "alice"
  private val source = MemorySource()
  private val repository = OrderRepositoryFirestore(source, { uid }, { time }, { "b" })

  private fun error(error: OrderError) = OrderResult.Error(error)

  @Test
  fun mappingOrderingAndMalformedSnapshots() = runTest {
    val saved = (repository.createOrder(draft) as OrderResult.Success).data
    assertEquals(draft.items, saved.items)
    assertEquals("alice", saved.requesterId)
    val original = source.documents.getValue("b")
    source.documents["a"] = original.copy(id = "a")
    assertEquals(
        listOf("a", "b"),
        (repository.getMyOrders() as OrderResult.Success).data.map { it.id },
    )
    val malformed =
        listOf(
            original.data - "items",
            original.data + ("status" to "UNKNOWN"),
            original.data + ("commissionCents" to 1.5),
            original.data + ("commissionCents" to Long.MAX_VALUE),
            original.data +
                ("items" to
                    listOf(
                        mapOf(
                            "productId" to "milk",
                            "name" to "Milk",
                            "unitPriceCents" to 1L,
                            "quantity" to 0L,
                        )
                    )),
        )
    malformed.forEach { data -> assertNull(OrderFirestoreDocument("bad", data).toOrder()) }
    source.documents["b"] = original.copy(data = original.data + ("requesterId" to "bob"))
    assertEquals(error(OrderError.PermissionDenied), repository.getMyOrders())
  }

  @Test
  fun rejectsInvalidDraftsAndIneligibleTripsBeforeWriting() = runTest {
    listOf(
            draft.copy(items = emptyList()),
            draft.copy(items = listOf(item, item)),
            draft.copy(items = List(6) { item.copy(productId = "$it") }),
            draft.copy(commissionCents = -1),
            draft.copy(items = listOf(item.copy(quantity = Int.MAX_VALUE))),
            draft.copy(tripId = "path/trip"),
        )
        .forEach { assertEquals(error(OrderError.InvalidData), repository.createOrder(it)) }
    source.trip = mapOf("status" to "PUBLISHED", "scheduledAt" to Timestamp(time.epochSecond, 0))
    assertEquals(error(OrderError.InvalidData), repository.createOrder(draft))
    source.trip = null
    assertEquals(error(OrderError.NotFound), repository.createOrder(draft))
    assertTrue(source.documents.isEmpty())
  }

  @Test
  fun rejectsTripOwnerBeforeWritingButAllowsAnotherRequester() = runTest {
    uid = "owner"
    assertEquals(error(OrderError.InvalidData), repository.createOrder(draft))
    assertEquals(0, source.writes)
    assertTrue(source.documents.isEmpty())
    uid = "alice"
    assertTrue(repository.createOrder(draft) is OrderResult.Success)
    assertEquals(1, source.writes)
  }

  @Test
  fun sessionsAccessAndMissingDocuments() = runTest {
    repository.createOrder(draft)
    uid = "owner"
    assertTrue(repository.getOrderById("b") is OrderResult.Success)
    uid = "other"
    assertEquals(error(OrderError.PermissionDenied), repository.getOrderById("b"))
    assertEquals(error(OrderError.NotFound), repository.getOrderById("missing"))
    assertEquals(error(OrderError.InvalidData), repository.getOrderById("a/b"))
    uid = null
    assertEquals(error(OrderError.NotAuthenticated), repository.getMyOrders())
  }

  @Test
  fun discardsAccountChangesAndPropagatesCancellationAndErrors() = runTest {
    val started = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    source.beforeRead = {
      started.complete(Unit)
      release.await()
    }
    val pending = async { repository.createOrder(draft) }
    started.await()
    uid = "bob"
    release.complete(Unit)
    assertEquals(error(OrderError.NotAuthenticated), pending.await())
    assertTrue(source.documents.isEmpty())
    source.beforeRead = { uid = null }
    uid = "alice"
    assertEquals(error(OrderError.NotAuthenticated), repository.getMyOrders())
    uid = "alice"
    source.beforeRead = { throw CancellationException("cancelled") }
    try {
      repository.getMyOrders()
      fail("Cancellation must propagate")
    } catch (_: CancellationException) {}
    for ((code, expected) in
        listOf(
            FirebaseFirestoreException.Code.PERMISSION_DENIED to OrderError.PermissionDenied,
            FirebaseFirestoreException.Code.UNAUTHENTICATED to OrderError.NotAuthenticated,
            FirebaseFirestoreException.Code.UNAVAILABLE to OrderError.Unknown,
            FirebaseFirestoreException.Code.DATA_LOSS to OrderError.InvalidData,
        )) {
      source.beforeRead = { throw FirebaseFirestoreException("test", code) }
      assertEquals(error(expected), repository.getMyOrders())
    }
  }

  @Test
  fun retryRecoversCommittedOrderAfterConfirmationFailure() = runTest {
    source.confirmationFails = true
    assertEquals(error(OrderError.Unknown), repository.createOrder(draft))
    assertEquals(1, source.documents.size)
    // Eligibility may have changed after the write; recovery must not resubmit or revalidate it.
    source.trip = null
    source.confirmationFails = false
    val recovered = repository.createOrder(draft.copy(items = draft.items.toList()))
    assertEquals(OrderResult.Success(source.documents.getValue("b").toOrder()!!), recovered)
    assertEquals(1, source.writes)
  }

  @Test
  fun uncertainWritesKeepTheirIdButConfirmedPurchasesAndAccountsAreDistinct() = runTest {
    var sequence = 0
    val repo = OrderRepositoryFirestore(source, { uid }, { time }, { "order-${sequence++}" })
    source.confirmationFails = true
    assertEquals(error(OrderError.Unknown), repo.createOrder(draft))
    source.beforeRead = { throw IllegalStateException("Still offline") }
    assertEquals(error(OrderError.Unknown), repo.createOrder(draft))
    assertEquals(1, source.writes)
    source.beforeRead = {}
    source.confirmationFails = false
    uid = "bob"
    assertEquals("bob", (repo.createOrder(draft) as OrderResult.Success).data.requesterId)
    uid = "alice"
    assertEquals("order-0", (repo.createOrder(draft) as OrderResult.Success).data.id)
    assertEquals("order-2", (repo.createOrder(draft) as OrderResult.Success).data.id)
    assertEquals(3, source.writes)
  }

  private inner class MemorySource : OrderFirestoreDataSource {
    val documents = linkedMapOf<String, OrderFirestoreDocument>()
    var beforeRead: suspend () -> Unit = {}
    var confirmationFails = false
    var writes = 0
    var trip: Map<String, Any?>? =
        mapOf(
            "ownerId" to "owner",
            "status" to "PUBLISHED",
            "scheduledAt" to Timestamp(time.epochSecond + 3600, 0),
        )

    override suspend fun getTrip(id: String): Map<String, Any?>? {
      beforeRead()
      return trip
    }

    override suspend fun get(id: String): OrderFirestoreDocument? {
      beforeRead()
      return documents[id]
    }

    override suspend fun getMine(uid: String): List<OrderFirestoreDocument> {
      beforeRead()
      return documents.values.toList()
    }

    override suspend fun create(id: String, data: Map<String, Any?>) =
        OrderFirestoreDocument(id, data + ("createdAt" to Timestamp(time.epochSecond, 0))).also {
          check(!documents.containsKey(it.id)) { "Immutable order cannot be overwritten" }
          documents[it.id] = it
          writes++
          if (confirmationFails) throw IllegalStateException("Confirmation unavailable")
        }
  }
}
