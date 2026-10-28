package com.android.sample.data.repository

// AI assistance: OpenAI Codex; trip-owner query tests (#147): Claude Code.
import com.android.sample.model.Location
import com.android.sample.model.Order
import com.android.sample.model.OrderDecision
import com.android.sample.model.OrderDecision.ACCEPT
import com.android.sample.model.OrderDecision.REJECT
import com.android.sample.model.OrderDraft
import com.android.sample.model.OrderItem
import com.android.sample.model.OrderStatus
import com.android.sample.model.Trip
import com.android.sample.model.TripStatus
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeOrderRepositoryTest {
  private val time = Instant.parse("2026-10-13T12:00:00Z")
  private val tripId = "trip-demo-1"
  private val item = OrderItem("oat-milk", "Oat milk", 220, 2)
  private val input = OrderDraft(tripId, listOf(item), 150)
  private var userId: String? = "requester-demo"

  @Test
  fun submissionIsVisibleThroughBothReadOperationsOnTheSameInstance() = runTest {
    val repository = fake()
    val order = repository.createOrder(input).data()

    assertEquals("order-demo-1", order.id)
    assertEquals("requester-demo", order.requesterId)
    assertEquals(tripId, order.tripId)
    assertEquals(listOf(item), order.items)
    assertEquals(150, order.commissionCents)
    assertEquals(OrderStatus.PENDING, order.status)
    assertEquals(time, order.createdAt)
    assertEquals(OrderResult.Success(order), repository.getOrderById(order.id))
    assertEquals(listOf(order), repository.getMyOrders().data())
    assertTrue(fake().getMyOrders().data().isEmpty())
  }

  @Test
  fun submissionUsesInjectedIdentityClockAndIds() = runTest {
    var idCounter = 0
    var clockReads = 0
    val repository =
        FakeOrderRepository(
            { userId },
            now = {
              clockReads++
              time
            },
            newId = { "order-${++idCounter}" },
        )
    val first = repository.createOrder(input).data()
    userId = "another-requester"
    val second = repository.createOrder(input).data()

    assertNotEquals(first.id, second.id)
    assertEquals("another-requester", second.requesterId)
    assertEquals(2, clockReads)
    assertEquals(listOf(second), repository.getMyOrders().data())
  }

  @Test
  fun missingSessionRejectsEveryOperationEvenWhenAnErrorIsForced() = runTest {
    val repository = fake(listOf(order()))
    repository.forcedError = OrderError.Unknown
    listOf(null, "", "  ").forEach { missing ->
      userId = missing
      assertEquals(OrderResult.Error(OrderError.NotAuthenticated), repository.createOrder(input))
      assertEquals(OrderResult.Error(OrderError.NotAuthenticated), repository.getMyOrders())
      assertEquals(
          OrderResult.Error(OrderError.NotAuthenticated),
          repository.getOrderById("order-demo-1"),
      )
    }
  }

  @Test
  fun requesterCanReadButOtherAccountsCannot() = runTest {
    val repository = fake(listOf(order()))
    assertEquals(OrderResult.Success(order()), repository.getOrderById("order-demo-1"))
    userId = "other-demo"
    assertEquals(
        OrderResult.Error(OrderError.PermissionDenied),
        repository.getOrderById("order-demo-1"),
    )
    assertTrue(repository.getMyOrders().data().isEmpty())
  }

  @Test
  fun listFiltersAccountsAndSortsNewestFirstWithStableTies() = runTest {
    val older = order("older", time.minusSeconds(1))
    val a = order("a")
    val b = order("b")
    val other = order("other").copy(requesterId = "other-demo")
    val repository = fake(listOf(b, older, other, a))
    assertEquals(listOf(a, b, older), repository.getMyOrders().data())
    userId = "other-demo"
    assertEquals(listOf(other), repository.getMyOrders().data())
  }

  @Test
  fun missingAndBlankIdsHaveDifferentErrors() = runTest {
    val repository = fake()
    assertEquals(OrderResult.Error(OrderError.NotFound), repository.getOrderById("missing"))
    assertEquals(OrderResult.Error(OrderError.InvalidData), repository.getOrderById("  "))
  }

  @Test
  fun invalidSubmissionsNeverInsertAnOrder() = runTest {
    val repository = fake()
    val invalidItems =
        listOf(
            item.copy(productId = " "),
            item.copy(name = " "),
            item.copy(quantity = 0),
            item.copy(quantity = -1),
            item.copy(unitPriceCents = -1),
        )
    val invalidInputs =
        invalidItems.map { input.copy(items = listOf(it)) } +
            listOf(
                input.copy(commissionCents = -1),
                input.copy(items = emptyList()),
                input.copy(items = listOf(item, item)),
                input.copy(tripId = " "),
            )
    invalidInputs.forEach {
      assertEquals(OrderResult.Error(OrderError.InvalidData), repository.createOrder(it))
    }
    assertTrue(repository.getMyOrders().data().isEmpty())
  }

  @Test
  fun freeProductsAndZeroCommissionAreValid() = runTest {
    val free =
        input.copy(
            items = listOf(item.copy(unitPriceCents = 0, quantity = 1)),
            commissionCents = 0,
        )
    assertEquals(0, fake().createOrder(free).data().commissionCents)
  }

  @Test
  fun monetaryOverflowIsRejectedWithoutWrapping() = runTest {
    val repository = fake()
    val huge = item.copy(unitPriceCents = Int.MAX_VALUE, quantity = 1)
    val overflowing =
        listOf(
            OrderDraft(tripId, listOf(huge.copy(quantity = 2))),
            OrderDraft(tripId, listOf(huge, item.copy(productId = "bread", quantity = 1))),
            OrderDraft(tripId, listOf(huge), 1),
        )
    overflowing.forEach {
      assertEquals(OrderResult.Error(OrderError.InvalidData), repository.createOrder(it))
    }
    assertEquals(
        Int.MAX_VALUE,
        repository
            .createOrder(OrderDraft(tripId, listOf(huge)))
            .data()
            .items
            .single()
            .unitPriceCents,
    )
  }

  @Test
  fun injectedFailurePreservesStorageAndRetryCreatesOneOrder() = runTest {
    val repository = fake()
    repository.forcedError = OrderError.Unknown
    assertEquals(OrderResult.Error(OrderError.Unknown), repository.createOrder(input))
    assertEquals(OrderResult.Error(OrderError.Unknown), repository.getMyOrders())
    assertEquals(OrderResult.Error(OrderError.Unknown), repository.getOrderById("missing"))
    repository.forcedError = null
    assertTrue(repository.getMyOrders().data().isEmpty())
    repository.createOrder(input)
    assertEquals(1, repository.getMyOrders().data().size)
  }

  @Test
  fun idCollisionAndBlankGeneratedIdCannotOverwriteOrInsert() = runTest {
    val stored = order()
    val repository = fake(listOf(stored))
    assertEquals(
        OrderResult.Error(OrderError.Unknown),
        repository.createOrder(input.copy(commissionCents = 900)),
    )
    assertEquals(listOf(stored), repository.getMyOrders().data())
    val blank = FakeOrderRepository({ userId }, newId = { " " })
    assertEquals(OrderResult.Error(OrderError.Unknown), blank.createOrder(input))
    assertTrue(blank.getMyOrders().data().isEmpty())
  }

  @Test
  fun callerMutationCannotChangeSeededSubmittedOrReturnedOrders() = runTest {
    val items = mutableListOf(item, item.copy(productId = "bread"))
    val seeded = fake(listOf(order().copy(items = items)))
    val submitted = fake()
    val result = submitted.createOrder(input.copy(items = items)).data()
    items.clear()
    assertEquals(2, seeded.getOrderById("order-demo-1").data().items.size)
    assertEquals(2, result.items.size)
    (result.items as MutableList).clear()
    val read = submitted.getOrderById(result.id).data()
    assertEquals(2, read.items.size)
    (read.items as MutableList).clear()
    assertEquals(2, submitted.getMyOrders().data().single().items.size)
  }

  @Test
  fun invalidFixturesFailAtSetupInsteadOfSilentlyReplacingData() {
    val invalid =
        listOf(
            order().copy(id = " "),
            order().copy(requesterId = " "),
            order().copy(items = emptyList()),
            order().copy(commissionCents = -1),
        )
    invalid.forEach { fixture ->
      assertThrows(IllegalArgumentException::class.java) { fake(listOf(fixture)) }
    }
    assertThrows(IllegalArgumentException::class.java) { fake(listOf(order(), order())) }
  }

  @Test
  fun concurrentCreatesKeepDistinctOrders() = runTest {
    var nextId = 0
    val repository =
        FakeOrderRepository({ "requester-demo" }, now = { time }, newId = { "order-${++nextId}" })
    val results =
        (1..40)
            .map { async(Dispatchers.Default) { repository.createOrder(input).data() } }
            .awaitAll()
    assertEquals(40, results.map { it.id }.distinct().size)
    assertEquals(40, repository.getMyOrders().data().size)
  }

  @Test
  fun cancellationPropagatesWithoutWritingAndReleasesTheLock() = runTest {
    var cancel = true
    val repository =
        FakeOrderRepository(
            { userId },
            now = { time },
            newId = {
              if (cancel) throw CancellationException("cancelled")
              "order-demo-1"
            },
        )
    try {
      repository.createOrder(input)
      org.junit.Assert.fail("Cancellation must propagate")
    } catch (_: CancellationException) {
      cancel = false
    }
    assertTrue(repository.getMyOrders().data().isEmpty())
    assertEquals("order-demo-1", repository.createOrder(input).data().id)
  }

  @Test
  fun creationRejectsSelfOrdersWithoutConsumingIdsOrChangingLegacyOrders() = runTest {
    var generatedIds = 0
    val legacy = order().copy(requesterId = "commando")
    val repository =
        FakeOrderRepository(
            { userId },
            listOf(legacy),
            now = { time },
            newId = { "new-${++generatedIds}" },
            tripRepository = trips(),
        )
    userId = "commando"
    assertEquals(failure(OrderError.InvalidData), repository.createOrder(input))
    assertEquals(0, generatedIds)
    assertEquals(listOf(legacy), repository.getMyOrders().data())
    assertEquals(OrderResult.Success(legacy), repository.getOrderById(legacy.id))
    userId = "requester-demo"
    val saved = repository.createOrder(input).data()
    assertEquals("new-1", saved.id)
    assertEquals(userId, saved.requesterId)
    assertEquals(listOf(saved), repository.getMyOrders().data())
  }

  @Test
  fun creationDoesNotInsertWhenInjectedTripLookupFailsOrReturnsAnotherTrip() = runTest {
    val source = trips()
    val repository = FakeOrderRepository({ userId }, tripRepository = source)
    for ((error, expected) in
        listOf(
            TripError.NotFound to OrderError.NotFound,
            TripError.PermissionDenied to OrderError.PermissionDenied,
            TripError.NetworkError to OrderError.Unknown,
            TripError.InvalidData to OrderError.Unknown,
            TripError.Unknown to OrderError.Unknown,
        )) {
      source.forcedError = error
      assertEquals(failure(expected), repository.createOrder(input))
      assertTrue(repository.getMyOrders().data().isEmpty())
    }
    source.forcedError = null
    val mismatched =
        object : TripRepository by source {
          override suspend fun getTripById(tripId: String): TripResult<Trip> =
              source.getTripById(this@FakeOrderRepositoryTest.tripId)
        }
    val wrong = FakeOrderRepository({ userId }, tripRepository = mismatched)
    assertEquals(failure(OrderError.InvalidData), wrong.createOrder(input.copy(tripId = "another")))
    assertTrue(wrong.getMyOrders().data().isEmpty())
  }

  @Test
  fun creationDiscardsAnAccountChangeDuringTripLookup() = runTest {
    val entered = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    val source = trips()
    val delayed =
        object : TripRepository by source {
          override suspend fun getTripById(tripId: String): TripResult<Trip> {
            entered.complete(Unit)
            release.await()
            return source.getTripById(tripId)
          }
        }
    val repository = FakeOrderRepository({ userId }, tripRepository = delayed)
    val pending = async { repository.createOrder(input) }
    entered.await()
    userId = "new-account"
    release.complete(Unit)
    assertEquals(failure(OrderError.NotAuthenticated), pending.await())
    assertTrue(repository.getMyOrders().data().isEmpty())
    assertTrue(repository.createOrder(input) is OrderResult.Success)
  }

  @Test
  fun creationLookupExceptionsAndCancellationLeaveStorageAndMutexUsable() = runTest {
    for (scenario in listOf("exception", "account-change", "cancellation")) {
      userId = "requester-demo"
      var failing = true
      val source = trips()
      val throwing =
          object : TripRepository by source {
            override suspend fun getTripById(tripId: String): TripResult<Trip> {
              if (failing) {
                if (scenario == "account-change") userId = "new-account"
                if (scenario == "cancellation") throw CancellationException("cancelled")
                error("Trip lookup failed")
              }
              return source.getTripById(tripId)
            }
          }
      val repository = FakeOrderRepository({ userId }, tripRepository = throwing)
      if (scenario == "cancellation") {
        try {
          repository.createOrder(input)
          org.junit.Assert.fail("Cancellation must propagate")
        } catch (_: CancellationException) {}
      } else {
        assertEquals(
            failure(
                if (scenario == "account-change") OrderError.NotAuthenticated
                else OrderError.Unknown
            ),
            repository.createOrder(input),
        )
      }
      assertTrue(repository.getMyOrders().data().isEmpty())
      failing = false
      assertTrue(repository.createOrder(input) is OrderResult.Success)
    }
  }

  private fun trips(ownerId: String = "commando") =
      FakeTripRepository(
          ownerId,
          initialTrips =
              listOf(
                  Trip(
                      tripId,
                      ownerId,
                      Location("Store", 46.5, 6.6),
                      time,
                      Location("Campus", 46.5, 6.6),
                      TripStatus.PUBLISHED,
                      time,
                      time,
                  )
              ),
      )

  @Test
  fun associatedTripOwnerCanReadDetailsButRequesterListStaysScoped() = runTest {
    val repository = FakeOrderRepository({ userId }, listOf(order()), tripRepository = trips())
    userId = "commando"
    assertEquals(OrderResult.Success(order()), repository.getOrderById("order-demo-1"))
    assertTrue(repository.getMyOrders().data().isEmpty())
    userId = "unrelated"
    assertEquals(
        OrderResult.Error(OrderError.PermissionDenied),
        repository.getOrderById("order-demo-1"),
    )
    userId = null
    assertEquals(
        OrderResult.Error(OrderError.NotAuthenticated),
        repository.getOrderById("order-demo-1"),
    )
  }

  @Test
  fun requesterDoesNotDependOnTripAvailabilityAndLookupFailureNeverGrantsAccess() = runTest {
    val trips = trips()
    val repository = FakeOrderRepository({ userId }, listOf(order()), tripRepository = trips)
    listOf(TripError.NotFound, TripError.NetworkError, TripError.PermissionDenied).forEach {
      trips.forcedError = it
      userId = "requester-demo"
      assertEquals(OrderResult.Success(order()), repository.getOrderById("order-demo-1"))
      userId = "commando"
      assertEquals(
          OrderResult.Error(OrderError.PermissionDenied),
          repository.getOrderById("order-demo-1"),
      )
    }
  }

  @Test
  fun ownerOfADifferentTripCannotReadThisOrder() = runTest {
    val repository =
        FakeOrderRepository(
            { userId },
            listOf(order().copy(tripId = "another-trip")),
            tripRepository = trips(),
        )
    userId = "commando"
    assertEquals(
        OrderResult.Error(OrderError.PermissionDenied),
        repository.getOrderById("order-demo-1"),
    )
  }

  @Test
  fun lookupReturningADifferentTripDoesNotGrantItsOwnerAccess() = runTest {
    val source = trips()
    val wrongTrip =
        object : TripRepository by source {
          override suspend fun getTripById(tripId: String): TripResult<Trip> =
              source.getTripById(this@FakeOrderRepositoryTest.tripId)
        }
    val repository =
        FakeOrderRepository(
            { userId },
            listOf(order().copy(tripId = "another-trip")),
            tripRepository = wrongTrip,
        )
    userId = "commando"
    assertEquals(
        OrderResult.Error(OrderError.PermissionDenied),
        repository.getOrderById("order-demo-1"),
    )
  }

  @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
  @Test
  fun accountChangeDuringLookupDeniesTheOldSession() = runTest {
    val gate = CompletableDeferred<Unit>()
    val source = trips()
    val delayed =
        object : TripRepository by source {
          override suspend fun getTripById(tripId: String): TripResult<Trip> {
            gate.await()
            return source.getTripById(tripId)
          }
        }
    val repository = FakeOrderRepository({ userId }, listOf(order()), tripRepository = delayed)
    userId = "commando"
    val read = async { repository.getOrderById("order-demo-1") }
    runCurrent()
    userId = "unrelated"
    gate.complete(Unit)
    assertEquals(OrderResult.Error(OrderError.PermissionDenied), read.await())
  }

  @Test
  fun lookupExceptionAndCancellationCannotExposeAnOrderOrKeepTheLock() = runTest {
    var cancelled = false
    val throwing =
        object : TripRepository by trips() {
          override suspend fun getTripById(tripId: String): TripResult<Trip> {
            if (cancelled) throw CancellationException("cancelled")
            error("lookup failed")
          }
        }
    val repository = FakeOrderRepository({ userId }, listOf(order()), tripRepository = throwing)
    userId = "commando"
    assertEquals(OrderResult.Error(OrderError.Unknown), repository.getOrderById("order-demo-1"))
    cancelled = true
    try {
      repository.getOrderById("order-demo-1")
      org.junit.Assert.fail("Cancellation must propagate")
    } catch (_: CancellationException) {}
    userId = "requester-demo"
    assertEquals(OrderResult.Success(order()), repository.getOrderById("order-demo-1"))
  }

  @Test
  fun tripQueryListsOnlyThatTripForItsOwnerNewestFirst() = runTest {
    val older = order("older", time.minusSeconds(1))
    val a = order("a")
    val b = order("b").copy(requesterId = "another-requester")
    val elsewhere = order("elsewhere").copy(tripId = "another-trip")
    val repository =
        FakeOrderRepository({ userId }, listOf(b, older, elsewhere, a), tripRepository = trips())
    userId = "commando"
    assertEquals(listOf(a, b, older), repository.getOrdersForTrip(tripId).data())
    assertEquals(OrderResult.Error(OrderError.InvalidData), repository.getOrdersForTrip(" "))
    // A trip the lookup cannot resolve is never treated as owned.
    assertEquals(
        OrderResult.Error(OrderError.PermissionDenied),
        repository.getOrdersForTrip("another-trip"),
    )
    // Placing an order on the trip does not grant access to the trip's other orders.
    listOf("requester-demo", "unrelated").forEach {
      userId = it
      assertEquals(
          OrderResult.Error(OrderError.PermissionDenied),
          repository.getOrdersForTrip(tripId),
      )
    }
    userId = null
    assertEquals(
        OrderResult.Error(OrderError.NotAuthenticated),
        repository.getOrdersForTrip(tripId),
    )
  }

  @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
  @Test
  fun tripQueryWithoutLookupOrAfterAnAccountChangeIsDenied() = runTest {
    userId = "commando"
    assertEquals(
        OrderResult.Error(OrderError.PermissionDenied),
        fake(listOf(order())).getOrdersForTrip(tripId),
    )
    val gate = CompletableDeferred<Unit>()
    val source = trips()
    val delayed =
        object : TripRepository by source {
          override suspend fun getTripById(tripId: String): TripResult<Trip> {
            gate.await()
            return source.getTripById(tripId)
          }
        }
    val repository = FakeOrderRepository({ userId }, listOf(order()), tripRepository = delayed)
    val read = async { repository.getOrdersForTrip(tripId) }
    runCurrent()
    userId = "unrelated"
    gate.complete(Unit)
    assertEquals(OrderResult.Error(OrderError.PermissionDenied), read.await())
  }

  @Test
  fun decisionsPreserveSnapshotsAndFinalOutcomes() = runTest {
    userId = "commando"
    for (selfOrder in listOf(false, true)) for (status in OrderStatus.entries) for (decision in
        OrderDecision.entries) {
      val stored =
          order()
              .copy(
                  status = status,
                  requesterId = if (selfOrder) "commando" else "requester-demo",
                  items = listOf(item, item.copy(productId = "bread")),
              )
      val orders = FakeOrderRepository({ userId }, listOf(stored), tripRepository = trips())
      val target = if (decision == ACCEPT) OrderStatus.ACCEPTED else OrderStatus.REJECTED
      val expected = stored.copy(status = if (status == OrderStatus.PENDING) target else status)
      val result = orders.decideOrder(stored.id, decision)
      if (selfOrder) {
        assertEquals(failure(OrderError.PermissionDenied), result)
        assertEquals(OrderResult.Success(stored), orders.getOrderById(stored.id))
        continue
      }
      assertEquals(
          if (expected.status == target) OrderResult.Success(expected)
          else failure(OrderError.AlreadyDecided(expected)),
          result,
      )
      val snapshot = result.persisted()
      (snapshot.items as MutableList).clear()
      assertEquals(OrderResult.Success(expected), orders.getOrderById(stored.id))
    }
  }

  @Test
  fun decisionsRequireOwnerSessionAndValidExistingOrder() = runTest {
    val orders = FakeOrderRepository({ userId }, listOf(order()), tripRepository = trips())
    for (caller in listOf("requester-demo", "outsider", null)) {
      userId = caller
      val expected =
          if (caller == null) OrderError.NotAuthenticated else OrderError.PermissionDenied
      assertEquals(failure(expected), orders.decideOrder(order().id, ACCEPT))
    }
    userId = "commando"
    for (id in listOf("", " ", "a/b", ".", "..")) {
      assertEquals(failure(OrderError.InvalidData), orders.decideOrder(id, ACCEPT))
    }
    assertEquals(failure(OrderError.NotFound), orders.decideOrder("missing", ACCEPT))
    assertEquals(OrderResult.Success(order()), orders.getOrderById(order().id))
  }

  @Test
  fun suspendedDecisionsDiscardChangedSessionsAndCancellationReleasesLock() = runTest {
    for (cancel in listOf(false, true)) {
      val entered = CompletableDeferred<Unit>()
      val release = CompletableDeferred<Unit>()
      val source = trips()
      val delayed =
          object : TripRepository by source {
            override suspend fun getTripById(tripId: String): TripResult<Trip> {
              entered.complete(Unit)
              release.await()
              return source.getTripById(tripId)
            }
          }
      userId = "commando"
      val orders = FakeOrderRepository({ userId }, listOf(order()), tripRepository = delayed)
      val pending = async { orders.decideOrder(order().id, ACCEPT) }
      entered.await()
      if (cancel) {
        pending.cancel()
        pending.join()
        assertTrue(pending.isCancelled)
      } else {
        userId = "outsider"
        release.complete(Unit)
        assertEquals(failure(OrderError.NotAuthenticated), pending.await())
      }
      userId = "requester-demo"
      assertEquals(OrderResult.Success(order()), orders.getOrderById(order().id))
      release.complete(Unit)
      userId = "commando"
      assertEquals(
          OrderResult.Success(order().copy(status = OrderStatus.REJECTED)),
          orders.decideOrder(order().id, REJECT),
      )
    }
  }

  @Test
  fun concurrentDecisionsCannotReplaceTheWinner() = runTest {
    userId = "commando"
    for (decisions in
        listOf(
            OrderDecision.entries.toList(),
            listOf(ACCEPT, ACCEPT),
        )) {
      val orders = FakeOrderRepository({ userId }, listOf(order()), tripRepository = trips())
      val gate = CompletableDeferred<Unit>()
      val results = decisions.map {
        async(Dispatchers.Default) {
          gate.await()
          orders.decideOrder(order().id, it)
        }
      }
      gate.complete(Unit)
      val outcomes = results.awaitAll()
      val persisted = orders.getOrderById(order().id).data()
      assertTrue(persisted.status != OrderStatus.PENDING)
      assertEquals(order().copy(status = persisted.status), persisted)
      assertEquals(
          if (decisions.distinct().size == 1) 2 else 1,
          outcomes.count { it is OrderResult.Success },
      )
      outcomes.forEach { assertEquals(persisted, it.persisted()) }
    }
  }

  private fun failure(error: OrderError) = OrderResult.Error(error)

  private fun OrderResult<Order>.persisted(): Order =
      when (this) {
        is OrderResult.Success -> data
        is OrderResult.Error -> (error as OrderError.AlreadyDecided).persistedOrder
      }

  private fun fake(initial: List<Order> = emptyList()) =
      FakeOrderRepository({ userId }, initial, { time }, { "order-demo-1" })

  private fun order(id: String = "order-demo-1", createdAt: Instant = time) =
      Order(id, tripId, "requester-demo", listOf(item), 150, OrderStatus.PENDING, createdAt)

  private fun <T> OrderResult<T>.data(): T =
      when (this) {
        is OrderResult.Success -> data
        is OrderResult.Error -> error("Expected success, got $error")
      }
}
