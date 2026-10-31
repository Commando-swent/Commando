package com.android.sample.data.repository

// AI assistance: Claude Code.
import com.android.sample.model.ShoppingChecklistItem
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class FakeShoppingChecklistRepositoryTest {
  private var userId: String? = "owner"
  private val repository = FakeShoppingChecklistRepository { userId }
  private val milk = ShoppingChecklistItem("milk", "Milk", 3, purchased = false)
  private val bread = ShoppingChecklistItem("bread", "Bread", 1, purchased = false)

  @Test
  fun checkingAndUncheckingKeepsOtherItems() = runTest {
    assertEquals(OrderResult.Success(emptyMap<String, Long>()), progress())

    assertEquals(OrderResult.Success(Unit), repository.setPurchased("trip", milk, true))
    repository.setPurchased("trip", bread, true)
    assertEquals(OrderResult.Success(mapOf("milk" to 3L, "bread" to 1L)), progress())

    repository.setPurchased("trip", milk, false)
    assertEquals(OrderResult.Success(mapOf("bread" to 1L)), progress())
    assertEquals(OrderResult.Success(emptyMap<String, Long>()), progress("other-trip"))
  }

  @Test
  fun progressBelongsToTheSignedInUser() = runTest {
    repository.setPurchased("trip", milk, true)

    userId = "someone-else"
    assertEquals(OrderResult.Success(emptyMap<String, Long>()), progress())
    userId = null
    assertEquals(OrderResult.Error(OrderError.NotAuthenticated), progress())
    userId = "owner"
    assertEquals(OrderResult.Success(mapOf("milk" to 3L)), progress())
  }

  @Test
  fun rejectsInvalidInputAndReportsForcedErrors() = runTest {
    val invalid = OrderResult.Error(OrderError.InvalidData)
    assertEquals(invalid, progress("a/b"))
    assertEquals(invalid, repository.setPurchased("trip", milk.copy(productId = " "), true))
    assertEquals(invalid, repository.setPurchased("trip", milk.copy(requiredQuantity = 0), true))

    repository.forcedError = OrderError.Unknown
    assertEquals(OrderResult.Error(OrderError.Unknown), progress())
    assertEquals(OrderResult.Error(OrderError.Unknown), repository.setPurchased("trip", milk, true))
  }

  private suspend fun progress(tripId: String = "trip") = repository.getPurchasedQuantities(tripId)
}
