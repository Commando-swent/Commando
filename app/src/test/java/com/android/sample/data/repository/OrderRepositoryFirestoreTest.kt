package com.android.sample.data.repository

// AI assistance: OpenAI Codex.
import com.android.sample.model.*
import com.android.sample.model.OrderDecision.ACCEPT
import com.android.sample.model.OrderDecision.REJECT
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.FirebaseFirestoreException.Code
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

  @Test
  fun decisionsMapEveryTransitionWithoutRewritingFinalOrders() = runTest {
    val saved = (repository.createOrder(draft) as OrderResult.Success).data
    val original = source.documents.getValue("b")
    uid = "owner"
    for (status in OrderStatus.entries) for (decision in OrderDecision.entries) {
      source.documents["b"] = original.copy(data = original.data + ("status" to status.name))
      val before = source.writes
      val target = if (decision == ACCEPT) OrderStatus.ACCEPTED else OrderStatus.REJECTED
      val expected = saved.copy(status = if (status == OrderStatus.PENDING) target else status)
      assertEquals(
          if (expected.status == target) OrderResult.Success(expected)
          else error(OrderError.AlreadyDecided(expected)),
          decide(decision),
      )
      assertEquals(expected, source.documents.getValue("b").toOrder())
      assertEquals(before + if (status == OrderStatus.PENDING) 1 else 0, source.writes)
    }
  }

  @Test
  fun decisionsRejectInvalidMissingMalformedAndUnauthorizedData() = runTest {
    repository.createOrder(draft)
    val original = source.documents.getValue("b")
    uid = "owner"
    for (id in listOf("", " ", "a/b", ".", "..")) assertEquals(
        error(OrderError.InvalidData),
        decide(id = id),
    )
    assertEquals(error(OrderError.NotFound), decide(id = "missing"))
    for (data in listOf(original.data - "items", original.data + ("status" to "UNKNOWN"))) {
      source.documents["b"] = original.copy(data = data)
      assertEquals(error(OrderError.InvalidData), decide())
    }
    source.documents["b"] = original
    for (trip in listOf(null, mapOf("ownerId" to "other"))) {
      source.trip = trip
      assertEquals(error(OrderError.PermissionDenied), decide())
    }
    // A source must never expose malformed, unrelated or uncommitted results as success.
    val malformed = original.copy(data = emptyMap())
    val wrongId = original.copy(id = "other", data = original.data + ("status" to "ACCEPTED"))
    for (document in listOf(malformed, wrongId, original)) {
      source.decisionResult = document
      assertEquals(error(OrderError.InvalidData), decide())
    }
    uid = null
    assertEquals(error(OrderError.NotAuthenticated), decide())
    assertEquals(1, source.writes)
  }

  @Test
  fun suspendedDecisionsHandleSessionLossCancellationAndUncertainCommits() = runTest {
    val saved = (repository.createOrder(draft) as OrderResult.Success).data
    uid = "owner"
    val entered = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    source.beforeRead = {
      entered.complete(Unit)
      release.await()
    }
    val pending = async { decide() }
    entered.await()
    uid = null
    release.complete(Unit)
    assertEquals(error(OrderError.NotAuthenticated), pending.await())
    uid = "owner"
    val cancelEntered = CompletableDeferred<Unit>()
    source.beforeRead = {
      cancelEntered.complete(Unit)
      CompletableDeferred<Unit>().await()
    }
    val cancelled = async { decide() }
    cancelEntered.await()
    cancelled.cancelAndJoin()
    assertTrue(cancelled.isCancelled)
    source.beforeRead = {}
    val accepted = saved.copy(status = OrderStatus.ACCEPTED)
    assertEquals(OrderResult.Success(accepted), decide())
    assertEquals(error(OrderError.AlreadyDecided(accepted)), decide(REJECT))
    assertEquals(2, source.writes)
    source.documents["b"] =
        source.documents
            .getValue("b")
            .copy(data = source.documents.getValue("b").data + ("status" to "PENDING"))
    source.confirmationFails = true
    assertEquals(error(OrderError.Unknown), decide(REJECT))
    source.confirmationFails = false
    val rejected = saved.copy(status = OrderStatus.REJECTED)
    assertEquals(OrderResult.Success(rejected), decide(REJECT))
    assertEquals(error(OrderError.AlreadyDecided(rejected)), decide())
    assertEquals(3, source.writes)
  }

  private suspend fun decide(decision: OrderDecision = ACCEPT, id: String = "b") =
      repository.decideOrder(id, decision)

  private fun sourceError(code: Code): Nothing = throw FirebaseFirestoreException("test", code)

  private inner class MemorySource : OrderFirestoreDataSource {
    val documents = linkedMapOf<String, OrderFirestoreDocument>()
    var decisionResult: OrderFirestoreDocument? = null
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

    override suspend fun decide(
        id: String,
        ownerId: String,
        decision: OrderDecision,
    ): OrderFirestoreDocument {
      beforeRead()
      decisionResult?.let {
        return it
      }
      val document = documents[id] ?: sourceError(Code.NOT_FOUND)
      if (document.toOrder() == null) sourceError(Code.INVALID_ARGUMENT)
      if (trip?.get("ownerId") != ownerId || document.data["requesterId"] == ownerId) {
        sourceError(Code.PERMISSION_DENIED)
      }
      if (document.data["status"] != OrderStatus.PENDING.name) return document
      return document.copy(data = document.data + ("status" to decision.targetStatus().name)).also {
        documents[id] = it
        writes++
        if (confirmationFails) throw IllegalStateException("Confirmation unavailable")
      }
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
