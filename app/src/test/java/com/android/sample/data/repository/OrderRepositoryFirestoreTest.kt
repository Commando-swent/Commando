package com.android.sample.data.repository

// AI assistance: OpenAI Codex; trip-owner query tests (#147): Claude Code.
import com.android.sample.model.*
import com.android.sample.model.OrderDecision.ACCEPT
import com.android.sample.model.OrderDecision.REJECT
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.FirebaseFirestoreException.Code
import java.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
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
    // An otherwise eligible trip must be published and have a usable departure timestamp.
    val eligible =
        mapOf(
            "ownerId" to "owner",
            "status" to "PUBLISHED",
            "scheduledAt" to Timestamp(time.epochSecond + 3600, 0),
        )
    for (trip in listOf(eligible + ("status" to "DRAFT"), eligible - "scheduledAt")) {
      source.trip = trip
      assertEquals(error(OrderError.InvalidData), repository.createOrder(draft))
    }
    source.trip = null
    assertEquals(error(OrderError.NotFound), repository.createOrder(draft))
    assertTrue(source.documents.isEmpty())
    assertEquals(0, source.writes)
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

  @Test
  fun tripQueryChecksTheOwnerBeforeQueryingAndSortsNewestFirst() = runTest {
    repository.createOrder(draft)
    val original = source.documents.getValue("b")
    source.documents["a"] = original.copy(id = "a")
    source.documents["c"] =
        original.copy(
            id = "c",
            data = original.data + ("createdAt" to Timestamp(time.epochSecond + 1, 0)),
        )
    uid = "owner"
    assertEquals(
        listOf("c", "a", "b"),
        (repository.getOrdersForTrip("trip") as OrderResult.Success).data.map { it.id },
    )
    // The requester ("alice") and an unrelated user are denied without issuing the list query.
    for (user in listOf("alice", "other")) {
      uid = user
      assertEquals(error(OrderError.PermissionDenied), repository.getOrdersForTrip("trip"))
    }
    uid = "owner"
    assertEquals(error(OrderError.InvalidData), repository.getOrdersForTrip("a/b"))
    source.trip = null
    assertEquals(error(OrderError.PermissionDenied), repository.getOrdersForTrip("trip"))
    assertEquals(1, source.tripQueries)
    uid = null
    assertEquals(error(OrderError.NotAuthenticated), repository.getOrdersForTrip("trip"))
  }

  @Test
  fun tripQueryRejectsForeignOrMalformedResultsAndAccountChanges() = runTest {
    repository.createOrder(draft)
    val original = source.documents.getValue("b")
    uid = "owner"
    source.documents["x"] =
        original.copy(id = "x", data = original.data + ("tripId" to "elsewhere"))
    assertEquals(error(OrderError.PermissionDenied), repository.getOrdersForTrip("trip"))
    source.documents["x"] = original.copy(id = "x", data = original.data - "items")
    assertEquals(error(OrderError.InvalidData), repository.getOrdersForTrip("trip"))
    source.documents.remove("x")
    // The account changes while the trip is being read: no query is sent for the old session.
    source.beforeRead = { uid = "other" }
    assertEquals(error(OrderError.NotAuthenticated), repository.getOrdersForTrip("trip"))
    assertEquals(2, source.tripQueries)
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
  fun requesterCancellationIsIdempotentAndPreservesFinalOrders() = runTest {
    val saved = (repository.createOrder(draft) as OrderResult.Success).data
    val cancelled = saved.copy(status = OrderStatus.CANCELLED)
    assertEquals(OrderResult.Success(cancelled), repository.cancelOrder(saved.id))
    assertEquals(OrderResult.Success(cancelled), repository.cancelOrder(saved.id))
    assertEquals(cancelled, source.documents.getValue(saved.id).toOrder())
    assertEquals(2, source.writes)

    for (status in listOf(OrderStatus.ACCEPTED, OrderStatus.REJECTED)) {
      val persisted = saved.copy(status = status)
      source.documents[saved.id] =
          source.documents
              .getValue(saved.id)
              .copy(data = source.documents.getValue(saved.id).data + ("status" to status.name))
      assertEquals(
          error(OrderError.AlreadyDecided(persisted)),
          repository.cancelOrder(saved.id),
      )
    }
  }

  @Test
  fun cancellationRejectsInvalidMissingAndUnauthorizedRequests() = runTest {
    repository.createOrder(draft)
    for (id in listOf("", " ", "a/b", ".", "..")) {
      assertEquals(error(OrderError.InvalidData), repository.cancelOrder(id))
    }
    assertEquals(error(OrderError.NotFound), repository.cancelOrder("missing"))
    uid = "owner"
    assertEquals(error(OrderError.PermissionDenied), repository.cancelOrder("b"))
    uid = null
    assertEquals(error(OrderError.NotAuthenticated), repository.cancelOrder("b"))
  }

  @Test
  @OptIn(ExperimentalCoroutinesApi::class)
  fun cancellationRecoversUncertainCommitAndDiscardsAnEndedSession() = runTest {
    val saved = (repository.createOrder(draft) as OrderResult.Success).data
    source.confirmationFails = true
    assertEquals(error(OrderError.Unknown), repository.cancelOrder(saved.id))
    source.confirmationFails = false
    val writes = source.writes
    assertEquals(
        OrderResult.Success(saved.copy(status = OrderStatus.CANCELLED)),
        OrderRepositoryFirestore(source, { uid }).cancelOrder(saved.id),
    )
    assertEquals(writes, source.writes)
    val gate = CompletableDeferred<Unit>()
    source.beforeRead = { gate.await() }
    val pending = async { repository.cancelOrder(saved.id) }
    runCurrent()
    uid = "another-requester"
    gate.complete(Unit)
    assertEquals(error(OrderError.NotAuthenticated), pending.await())
    assertEquals(writes, source.writes)
  }

  @Test
  fun cancellationRejectsMalformedOrInconsistentSourceResponses() = runTest {
    repository.createOrder(draft)
    val document = source.documents.getValue("b")
    for (response in
        listOf(
            document,
            document.copy(id = "other"),
            document.copy(data = document.data - "status"),
        )) {
      source.decisionResult = response
      assertEquals(error(OrderError.InvalidData), repository.cancelOrder("b"))
    }
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

  @Test
  @OptIn(ExperimentalCoroutinesApi::class)
  fun readinessUsesPersistedSnapshotsAndAuthorizesBeforeReadOnlyOutcomes() = runTest {
    val saved = (repository.createOrder(draft) as OrderResult.Success).data
    val original = source.documents.getValue("b")
    for (status in OrderStatus.entries) {
      val stored = saved.copy(status = status)
      source.documents["b"] = original.copy(data = original.data + ("status" to status.name))
      val before = source.writes
      for (caller in listOf("alice", "outsider", null)) {
        uid = caller
        val denied =
            if (caller == null) OrderError.NotAuthenticated else OrderError.PermissionDenied
        assertEquals(error(denied), repository.markReadyForHandoff("b"))
      }
      uid = "owner"
      val expected = saved.copy(status = OrderStatus.READY_FOR_HANDOFF)
      val result =
          if (status in listOf(OrderStatus.ACCEPTED, OrderStatus.READY_FOR_HANDOFF))
              OrderResult.Success(expected)
          else error(OrderError.AlreadyDecided(stored))
      repeat(2) { assertEquals(result, repository.markReadyForHandoff("b")) }
      assertEquals(before + if (status == OrderStatus.ACCEPTED) 1 else 0, source.writes)
      assertEquals(
          if (result is OrderResult.Success) expected else stored,
          source.documents.getValue("b").toOrder(),
      )
    }
    source.documents["b"] = original.copy(data = original.data + ("status" to "ACCEPTED"))
    val gate = CompletableDeferred<Unit>()
    source.beforeRead = { gate.await() }
    val pending = async { repository.markReadyForHandoff("b") }
    runCurrent()
    uid = "outsider"
    gate.complete(Unit)
    assertEquals(error(OrderError.NotAuthenticated), pending.await())
    source.beforeRead = {}
    uid = "owner"
    val ready = saved.copy(status = OrderStatus.READY_FOR_HANDOFF)
    assertEquals(OrderResult.Success(ready), repository.getOrderById("b"))
    source.documents["uncertain"] =
        original.copy(id = "uncertain", data = original.data + ("status" to "ACCEPTED"))
    source.confirmationFails = true
    assertEquals(error(OrderError.Unknown), repository.markReadyForHandoff("uncertain"))
    source.confirmationFails = false
    val before = source.writes
    val fresh = OrderRepositoryFirestore(source, { uid })
    assertEquals(
        OrderResult.Success(ready.copy(id = "uncertain")),
        fresh.markReadyForHandoff("uncertain"),
    )
    assertEquals(before, source.writes)
  }

  private fun sourceError(code: Code): Nothing = throw FirebaseFirestoreException("test", code)

  @Test
  @OptIn(ExperimentalCoroutinesApi::class)
  fun receiptUsesAuthorizedPersistedResultsAndRecoversUncertainCommits() = runTest {
    val saved = (repository.createOrder(draft) as OrderResult.Success).data
    val original = source.documents.getValue("b")
    for ((status, succeeds) in
        listOf(
            OrderStatus.PENDING to false,
            OrderStatus.ACCEPTED to false,
            OrderStatus.REJECTED to false,
            OrderStatus.READY_FOR_HANDOFF to true,
            OrderStatus.DELIVERED to true,
        )) {
      source.documents["b"] = original.copy(data = original.data + ("status" to status.name))
      val before = source.writes
      for (caller in listOf("owner", "other", null)) {
        uid = caller
        assertEquals(
            error(if (caller == null) OrderError.NotAuthenticated else OrderError.PermissionDenied),
            repository.confirmReceipt("b"),
        )
      }
      uid = "alice"
      val expected =
          if (succeeds) OrderResult.Success(saved.copy(status = OrderStatus.DELIVERED))
          else error(OrderError.AlreadyDecided(saved.copy(status = status)))
      repeat(2) { assertEquals(expected, repository.confirmReceipt("b")) }
      assertEquals(before + if (status == OrderStatus.READY_FOR_HANDOFF) 1 else 0, source.writes)
    }
    for (id in listOf("", "a/b", ".", "..")) assertEquals(
        error(OrderError.InvalidData),
        repository.confirmReceipt(id),
    )
    assertEquals(error(OrderError.NotFound), repository.confirmReceipt("missing"))
    source.documents["b"] = original.copy(data = original.data + ("status" to "READY_FOR_HANDOFF"))
    source.confirmationFails = true
    assertEquals(error(OrderError.Unknown), repository.confirmReceipt("b"))
    source.confirmationFails = false
    val before = source.writes
    assertEquals(
        OrderResult.Success(saved.copy(status = OrderStatus.DELIVERED)),
        OrderRepositoryFirestore(source, { uid }).confirmReceipt("b"),
    )
    assertEquals(before, source.writes)
    val gate = CompletableDeferred<Unit>()
    source.beforeRead = { gate.await() }
    val pending = async { repository.confirmReceipt("b") }
    runCurrent()
    uid = "other"
    gate.complete(Unit)
    assertEquals(error(OrderError.NotAuthenticated), pending.await())
  }

  @Test
  fun editDecodesThePersistedResultAndRejectsStaleOrMalformedResults() = runTest {
    val saved = (repository.createOrder(draft) as OrderResult.Success).data
    val original = source.documents.getValue(saved.id)
    val edited = draft.copy(commissionCents = 250)
    source.decisionResult = original.copy(data = original.data + ("commissionCents" to 250L))
    assertEquals(
        OrderResult.Success(saved.copy(commissionCents = 250)),
        repository.editOrder(saved.id, edited),
    )
    source.decisionResult = original
    assertEquals(error(OrderError.InvalidData), repository.editOrder(saved.id, edited))
    source.decisionResult = original.copy(data = original.data + ("status" to "ACCEPTED"))
    assertEquals(
        error(OrderError.AlreadyDecided(saved.copy(status = OrderStatus.ACCEPTED))),
        repository.editOrder(saved.id, edited),
    )
    source.decisionResult = original.copy(data = original.data - "items")
    assertEquals(error(OrderError.InvalidData), repository.editOrder(saved.id, edited))
    assertEquals(error(OrderError.InvalidData), repository.editOrder("a/b", edited))
    assertEquals(
        error(OrderError.InvalidData),
        repository.editOrder(saved.id, edited.copy(items = emptyList())),
    )
  }

  @Test
  fun editRejectsMismatchedIdentityTripAndItemResults() = runTest {
    val saved = (repository.createOrder(draft) as OrderResult.Success).data
    val original = source.documents.getValue(saved.id)
    for (response in
        listOf(
            original.copy(id = "other"),
            original.copy(data = original.data + ("requesterId" to "other")),
            original.copy(data = original.data + ("tripId" to "other")),
            original.copy(
                data =
                    original.data +
                        ("items" to
                            listOf(
                                mapOf(
                                    "productId" to "bread",
                                    "name" to "Bread",
                                    "unitPriceCents" to 100L,
                                    "quantity" to 1L,
                                )
                            ))
            ),
        )) {
      source.decisionResult = response
      assertEquals(error(OrderError.InvalidData), repository.editOrder(saved.id, draft))
    }
    assertEquals(
        error(OrderError.InvalidData),
        repository.editOrder(saved.id, draft.copy(tripId = "a/b")),
    )
  }

  @Test
  fun staleDecisionReturnsTheLatestPendingOrderAndInvalidRevisionsAreRejected() = runTest {
    val saved = (repository.createOrder(draft) as OrderResult.Success).data
    val document = source.documents.getValue(saved.id)
    source.decisionResult = document.copy(data = document.data + ("revision" to 1L))
    assertEquals(
        error(OrderError.AlreadyDecided(saved.copy(revision = 1))),
        repository.editOrder(saved.id, draft.copy(commissionCents = 999)),
    )
    uid = "owner"
    assertEquals(
        error(OrderError.AlreadyDecided(saved.copy(revision = 1))),
        repository.decideOrder(saved.id, OrderDecision.ACCEPT, 0),
    )
    assertEquals(
        error(OrderError.InvalidData),
        repository.decideOrder(saved.id, OrderDecision.ACCEPT, -1),
    )
    for (revision in listOf(-1L, null, "1", 2147483648L)) {
      assertNull(document.copy(data = document.data + ("revision" to revision)).toOrder())
    }
  }

  @Test
  fun editDiscardsACommittedResultWhenTheSessionChanges() = runTest {
    val saved = (repository.createOrder(draft) as OrderResult.Success).data
    source.decisionResult = source.documents.getValue(saved.id)
    source.beforeRead = { uid = "other" }
    assertEquals(error(OrderError.NotAuthenticated), repository.editOrder(saved.id, draft))
  }

  private inner class MemorySource : OrderFirestoreDataSource {
    override suspend fun edit(
        id: String,
        requesterId: String,
        draft: OrderDraft,
        expectedRevision: Int,
    ): OrderFirestoreDocument {
      beforeRead()
      return checkNotNull(decisionResult)
    }

    val documents = linkedMapOf<String, OrderFirestoreDocument>()
    var decisionResult: OrderFirestoreDocument? = null
    var beforeRead: suspend () -> Unit = {}
    var confirmationFails = false
    var writes = 0
    var tripQueries = 0
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

    override suspend fun getForTrip(tripId: String): List<OrderFirestoreDocument> {
      tripQueries++
      beforeRead()
      return documents.values.toList()
    }

    override suspend fun decide(
        id: String,
        ownerId: String,
        decision: OrderDecision,
        expectedRevision: Int,
    ): OrderFirestoreDocument = transition(id, ownerId, "PENDING", decision.targetStatus().name)

    override suspend fun markReadyForHandoff(id: String, ownerId: String): OrderFirestoreDocument =
        transition(id, ownerId, "ACCEPTED", "READY_FOR_HANDOFF")

    override suspend fun confirmReceipt(id: String, requesterId: String): OrderFirestoreDocument {
      beforeRead()
      val document = documents[id] ?: sourceError(Code.NOT_FOUND)
      if (document.data["requesterId"] != requesterId) sourceError(Code.PERMISSION_DENIED)
      if (document.data["status"] != "READY_FOR_HANDOFF") return document
      return document.copy(data = document.data + ("status" to "DELIVERED")).also {
        documents[id] = it
        writes++
        if (confirmationFails) error("Confirmation unavailable")
      }
    }

    private suspend fun transition(
        id: String,
        ownerId: String,
        from: String,
        target: String,
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
      if (document.data["status"] != from) return document
      return document.copy(data = document.data + ("status" to target)).also {
        documents[id] = it
        writes++
        if (confirmationFails) throw IllegalStateException("Confirmation unavailable")
      }
    }

    override suspend fun cancel(id: String, requesterId: String): OrderFirestoreDocument {
      beforeRead()
      decisionResult?.let {
        return it
      }
      val document = documents[id] ?: sourceError(Code.NOT_FOUND)
      val order = document.toOrder() ?: sourceError(Code.INVALID_ARGUMENT)
      if (order.requesterId != requesterId) sourceError(Code.PERMISSION_DENIED)
      if (order.status != OrderStatus.PENDING) return document
      return document.copy(data = document.data + ("status" to OrderStatus.CANCELLED.name)).also {
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
