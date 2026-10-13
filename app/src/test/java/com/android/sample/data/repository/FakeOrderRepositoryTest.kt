package com.android.sample.data.repository

// AI assistance: OpenAI Codex.
import com.android.sample.model.Order
import com.android.sample.model.OrderDraft
import com.android.sample.model.OrderItem
import com.android.sample.model.OrderStatus
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
