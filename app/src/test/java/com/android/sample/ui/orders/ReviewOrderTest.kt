package com.android.sample.ui.orders

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.android.sample.data.repository.*
import com.android.sample.model.*
import com.android.sample.ui.theme.SampleAppTheme
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w412dp-h917dp-mdpi")
class ReviewOrderTest {
  @get:Rule val compose = createComposeRule()
  private val shared = OrderDraftState("trip-1")
  private val fake = FakeOrderRepository({ "requester" }, newId = { "order-1" })
  private lateinit var vm: ReviewOrderViewModel
  private val events = mutableListOf<String>()
  private val store = Location("Migros", 46.52, 6.57)
  private val spot = Location("EPFL", 46.52, 6.56)
  private val time = Instant.EPOCH
  private val trip = Trip("trip-1", "owner", store, time, spot, TripStatus.PUBLISHED, time, time)

  private fun show(repository: OrderRepository = fake, displayedTrip: Trip = trip) {
    shared.addItem(OrderItem("milk", "Milk", 220, 2))
    shared.updateCommission(150)
    vm = ReviewOrderViewModel(repository, shared)
    compose.setContent {
      SampleAppTheme {
        ReviewOrderRoute(
            vm,
            displayedTrip,
            { events.add("back") },
            { events.add("catalogue") },
            events::add,
        )
      }
    }
  }

  @Test
  fun editingAndBackUseTheCatalogueDraftAndCheckedTotals() {
    show()
    compose.onNodeWithTag("increase_milk").performClick()
    compose.onNodeWithTag(ReviewOrderTags.TOTAL).assertTextContains("CHF 8.10")
    compose.onNodeWithContentDescription("Back").performClick()
    compose.runOnIdle { assertEquals(3, shared.draft.value.items.single().quantity) }
    val field = compose.onNodeWithTag(ReviewOrderTags.COMMISSION)
    listOf("-1", "", "1.501", "2147483648").forEach {
      field.performTextReplacement(it)
      compose.onNodeWithTag(ReviewOrderTags.SUBMIT).assertIsNotEnabled()
    }
    field.performTextReplacement("1,50")
    compose.onNodeWithTag(ReviewOrderTags.SUBMIT).assertIsEnabled()
    compose.runOnIdle { shared.updateQuantity("milk", Int.MAX_VALUE) }
    compose.onNodeWithTag(ReviewOrderTags.SUBMIT).assertIsNotEnabled()
    compose
        .onNodeWithText("The order total is invalid or too large. Reduce the quantities.")
        .assertExists()
  }

  @Test
  fun errorKeepsDraftAndRetryClearsOnlyAfterSaveThenReturnsId() {
    fake.forcedError = OrderError.Unknown
    show()
    compose.onNodeWithTag(ReviewOrderTags.SUBMIT).performClick()
    compose.onNodeWithTag(ReviewOrderTags.ERROR).assertExists()
    compose.runOnIdle {
      assertEquals(2, shared.draft.value.items.single().quantity)
      fake.forcedError = null
    }
    compose.onNodeWithTag(ReviewOrderTags.SUBMIT).performClick()
    compose.onNodeWithText("Your order is sent").assertExists()
    compose.onNodeWithTag(ReviewOrderTags.TOTAL).assertTextContains("CHF 5.90")
    compose.onNodeWithTag(ReviewOrderTags.SUBMIT).performClick()
    compose.runOnIdle {
      assertTrue(shared.draft.value.items.isEmpty())
      assertEquals(listOf("order-1"), events)
    }
  }

  @Test
  fun pendingSaveBlocksDuplicateWritesAndDoesNotClearAnotherTrip() {
    val gate = CompletableDeferred<Unit>()
    var calls = 0
    val repo =
        object : OrderRepository by fake {
          override suspend fun createOrder(draft: OrderDraft): OrderResult<Order> {
            calls++
            gate.await()
            return fake.createOrder(draft)
          }
        }
    show(repo)
    val submitted = shared.draft.value
    compose.onNodeWithTag(ReviewOrderTags.SUBMIT).performClick().assertIsNotEnabled()
    compose.onNodeWithTag(ReviewOrderTags.COMMISSION).assertIsNotEnabled()
    compose.runOnIdle {
      vm.submit()
      vm.changeQuantity("milk", 1)
      vm.updateCommission("9")
      assertEquals(1, calls)
      assertEquals(2, shared.draft.value.items.single().quantity)
      shared.startTrip("trip-2")
      shared.addItem(OrderItem("bread", "Bread", 340, 1))
      gate.complete(Unit)
    }
    compose.waitUntil { vm.uiState.value.order != null && !vm.uiState.value.saving }
    compose.onNodeWithText("Your order is sent").assertExists()
    compose.onNodeWithTag(ReviewOrderTags.ERROR).assertDoesNotExist()
    compose.onNodeWithTag(ReviewOrderTags.SUBMIT).assertIsEnabled()
    compose.runOnIdle {
      val saved = checkNotNull(vm.uiState.value.order)
      assertEquals(submitted.tripId, saved.tripId)
      assertEquals(submitted.items, saved.items)
      assertEquals(submitted.commissionCents, saved.commissionCents)
      assertEquals("trip-2", shared.draft.value.tripId)
      assertEquals(listOf(OrderItem("bread", "Bread", 340, 1)), shared.draft.value.items)
      assertEquals(0, shared.draft.value.commissionCents)
    }
  }

  @Test
  fun switchedEmptyTripBlocksCatalogueAndSubmission() {
    show()
    compose.runOnIdle { shared.startTrip("trip-2") }
    compose.onNodeWithTag(ReviewOrderTags.ERROR).assertExists()
    compose.onNodeWithTag(ReviewOrderTags.SUBMIT).assertIsNotEnabled().performClick()
    compose.runOnIdle {
      vm.submit()
      assertTrue(events.isEmpty())
      assertNull(vm.uiState.value.order)
    }
  }

  @Test
  fun mismatchedHostTripBlocksSubmissionAndEdits() {
    show(displayedTrip = trip.copy(id = "wrong-trip"))
    compose.onNodeWithTag(ReviewOrderTags.ERROR).assertExists()
    compose.onNodeWithTag(ReviewOrderTags.SUBMIT).assertIsNotEnabled().performClick()
    compose.onNodeWithTag(ReviewOrderTags.COMMISSION).assertIsNotEnabled()
    compose.onNodeWithTag("increase_milk").assertIsNotEnabled()
    compose.runOnIdle { assertNull(vm.uiState.value.order) }
  }

  @Test
  fun removalAndEmptyStateReturnToCatalogue() {
    show()
    compose.onNodeWithTag("decrease_milk").performClick().performClick()
    compose.onNodeWithText("Your order is empty").assertExists()
    compose.onNodeWithTag(ReviewOrderTags.SUBMIT).performClick()
    compose.runOnIdle {
      assertEquals(listOf("catalogue"), events)
      assertNull(vm.uiState.value.order)
    }
  }
}
