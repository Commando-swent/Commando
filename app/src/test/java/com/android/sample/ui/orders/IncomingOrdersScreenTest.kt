package com.android.sample.ui.orders

// AI assistance: Claude Code.
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.android.sample.data.repository.OrderError
import com.android.sample.model.Order
import com.android.sample.model.OrderItem
import com.android.sample.model.OrderStatus
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-rUS")
class IncomingOrdersScreenTest {
  @get:Rule val compose = createComposeRule()
  private val time = Instant.parse("2026-10-06T12:00:00Z")
  private val milk = OrderItem("milk", "Milk", unitPriceCents = 220, quantity = 2)
  private val bread = OrderItem("bread", "Bread", unitPriceCents = 180, quantity = 1)
  private val pending =
      Order("order-1", "trip-1", "bob", listOf(milk, bread), 150, OrderStatus.PENDING, time)
  private val accepted =
      Order("order-2", "trip-1", "carol", listOf(bread), 0, OrderStatus.ACCEPTED, time)

  private fun show(
      state: IncomingOrdersUiState,
      onRefresh: () -> Unit = {},
      onOrderClick: (String) -> Unit = {},
  ) {
    compose.setContent { MaterialTheme { IncomingOrdersScreen(state, onRefresh, onOrderClick) } }
  }

  private fun card(order: Order) =
      compose.onNodeWithTag(IncomingOrdersTestTags.ORDER_CARD_PREFIX + order.id)

  @Test
  fun contentShowsEachOrderSummaryAndOpensItsExactId() {
    val opened = mutableListOf<String>()
    val rows = listOf(IncomingOrderRow(pending), IncomingOrderRow(accepted))
    show(IncomingOrdersUiState.Content(rows), onOrderClick = { opened += it })
    compose.onNodeWithText("Incoming orders").assertIsDisplayed()
    // 2 x 2.20 + 1 x 1.80 + 1.50 commission.
    card(pending)
        .assertIsDisplayed()
        .assert(hasText("Order order-1"))
        .assert(hasText("PENDING"))
        .assert(hasText("3 items · CHF 7.70"))
    card(accepted).assert(hasText("ACCEPTED")).assert(hasText("1 item · CHF 1.80"))
    card(accepted).performClick()
    compose.runOnIdle { assertEquals(listOf(accepted.id), opened) }
  }

  @Test
  fun loadingShowsAnAccessiblePlaceholder() {
    show(IncomingOrdersUiState.Loading)
    compose.onNodeWithTag(IncomingOrdersTestTags.LOADING).assertIsDisplayed()
    compose.onNodeWithContentDescription("Loading incoming orders").assertExists()
  }

  @Test
  fun emptyStateRefreshesTheList() {
    var reloads = 0
    show(IncomingOrdersUiState.Empty, onRefresh = { reloads++ })
    compose.onNodeWithText("No orders on this trip yet.").assertIsDisplayed()
    compose.onNodeWithTag(IncomingOrdersTestTags.REFRESH).performClick()
    compose.runOnIdle { assertEquals(1, reloads) }
  }

  @Test
  fun errorStateRetries() {
    var retries = 0
    show(IncomingOrdersUiState.Error(OrderError.Unknown), onRefresh = { retries++ })
    compose.onNodeWithText("Something went wrong while loading the orders.").assertIsDisplayed()
    compose.onNodeWithTag(IncomingOrdersTestTags.RETRY).performClick()
    compose.runOnIdle { assertEquals(1, retries) }
  }
}
