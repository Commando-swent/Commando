package com.commando.app.ui.navigation

// AI assistance: OpenAI Codex.
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.StringRes
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.commando.app.R
import com.commando.app.data.connectivity.FakeConnectivityMonitor
import com.commando.app.data.repository.*
import com.commando.app.model.Order
import com.commando.app.model.OrderDecision
import com.commando.app.model.OrderStatus
import com.commando.app.ui.orders.MyOrdersTestTags
import com.commando.app.ui.orders.OrderDetailsTestTags
import com.commando.app.ui.orders.orderStatusLabel
import com.commando.app.ui.theme.SampleAppTheme
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OfflineOrderDetailsJourneyTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
  private val network = FakeConnectivityMonitor()
  private val context
    get() = InstrumentationRegistry.getInstrumentation().targetContext

  /**
   * Start in My Orders, with real repositories bound to the requester's isolated emulator client.
   */
  private suspend fun withHistory(block: suspend (OfflineOrderAccounts, Order) -> Unit) {
    withOfflineOrderAccounts { accounts ->
      val order = accounts.placeOrder()
      compose.setContent {
        SampleAppTheme {
          CommandoApp(
              accounts.auth,
              accounts.trips,
              connectivityMonitor = network,
              orderRepositoryFactory = {
                OrderRepositoryFirestore(it, accounts.requester.firestore)
              },
              ratingRepositoryFactory = {
                RatingRepositoryFirestore(it, accounts.requester.firestore)
              },
          )
        }
      }
      try {
        click(hasTestTag(AppTestTags.ORDERS_BUTTON))
        waitFor(card(order))
        block(accounts, order)
      } finally {
        compose.runOnIdle { compose.activity.setContent {} }
      }
    }
  }

  @Test
  fun ownerDecisionDuringDisconnectChangesBothScreensAndRemovesPendingActions() = runBlocking {
    withHistory { accounts, order ->
      click(card(order))
      waitFor(hasTestTag(OrderDetailsTestTags.EDIT))
      waitFor(status(OrderStatus.PENDING))
      accounts.requester.firestore.disableNetwork().await()
      try {
        compose.runOnIdle { network.setOnline(false) }
        waitForSavedCopy()
        assertNoPendingActions()
        val result =
            accounts.ownerOrders.decideOrder(order.id, OrderDecision.REJECT, order.revision)
        assertEquals(OrderResult.Success(order.copy(status = OrderStatus.REJECTED)), result)
        back()
        waitFor(card(order) and hasText(context.getString(orderStatusLabel(OrderStatus.PENDING))))
        click(card(order))
        waitForSavedCopy()
        waitFor(status(OrderStatus.PENDING))
        assertNoPendingActions()
      } finally {
        accounts.requester.firestore.enableNetwork().await()
      }
      compose.runOnIdle { network.setOnline(true) }
      waitFor(status(OrderStatus.REJECTED))
      assertNoPendingActions()
      back()
      val rejectedRow =
          card(order) and hasText(context.getString(orderStatusLabel(OrderStatus.REJECTED)))
      waitFor(rejectedRow)
      compose.runOnIdle { network.setOnline(false) }
      waitForSavedCopy()
      click(rejectedRow)
      waitFor(status(OrderStatus.REJECTED))
      assertNoPendingActions()
    }
  }

  @Test
  fun revokedRequesterLosesDetailsAndCannotReopenThemFromSavedHistory() = runBlocking {
    withHistory { accounts, order ->
      click(card(order))
      waitFor(hasTestTag(OrderDetailsTestTags.EDIT))
      compose.runOnIdle { network.setOnline(false) }
      waitForSavedCopy()
      reassignOrderOnEmulator(order.id, accounts.owner.auth.currentUser!!.uid)
      assertEquals(
          OrderResult.Error(OrderError.PermissionDenied),
          accounts.orders.getOrderById(order.id),
      )
      waitFor(status(OrderStatus.PENDING))
      compose.runOnIdle { network.setOnline(true) }
      waitFor(message(R.string.order_details_permission_denied))
      assertNoPendingActions()
      compose.onNode(status(OrderStatus.PENDING)).assertDoesNotExist()
      compose.runOnIdle { network.setOnline(false) }
      waitFor(message(R.string.offline_no_copy))
      compose.runOnIdle { network.setOnline(true) }
      waitFor(message(R.string.order_details_permission_denied))
      back()
      waitFor(hasTestTag(MyOrdersTestTags.EMPTY))
      compose.onNode(card(order)).assertDoesNotExist()
      compose.runOnIdle { network.setOnline(false) }
      waitForSavedCopy()
      compose.onNode(card(order)).assertDoesNotExist()
      click(hasTestTag(AppTestTags.HOME_BUTTON))
      click(hasTestTag(AppTestTags.ORDERS_BUTTON))
      waitForSavedCopy()
      compose.onNode(card(order)).assertDoesNotExist()
    }
  }

  @Test
  fun cachedHistoryDoesNotGrantDetailAccessBeforeTheFirstConfirmedDetailRead() = runBlocking {
    withHistory { _, order ->
      compose.runOnIdle { network.setOnline(false) }
      waitForSavedCopy()
      click(card(order))
      waitFor(message(R.string.offline_no_copy))
      assertNoPendingActions()
      compose.onNode(status(OrderStatus.PENDING)).assertDoesNotExist()
      compose.runOnIdle { network.setOnline(true) }
      waitFor(status(OrderStatus.PENDING))
      waitFor(hasTestTag(OrderDetailsTestTags.EDIT))
      compose.runOnIdle { network.setOnline(false) }
      waitForSavedCopy()
      waitFor(status(OrderStatus.PENDING))
      assertNoPendingActions()
    }
  }

  private fun assertNoPendingActions() {
    compose.onNodeWithTag(OrderDetailsTestTags.EDIT).assertDoesNotExist()
    compose.onNodeWithTag(OrderDetailsTestTags.CANCEL).assertDoesNotExist()
  }

  private fun card(order: Order) = hasTestTag(MyOrdersTestTags.ORDER_CARD_PREFIX + order.id)

  private fun message(@StringRes id: Int) = hasText(context.getString(id))

  private fun status(value: OrderStatus) = message(orderStatusLabel(value))

  private fun waitForSavedCopy() {
    val labelPrefix = context.getString(R.string.offline_confirmed_time, "").trim()
    waitFor(hasText(labelPrefix, substring = true))
  }

  private fun waitFor(matcher: SemanticsMatcher) {
    compose.waitUntil(ORDER_JOURNEY_WAIT_MILLIS) {
      compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()
    }
  }

  private fun click(matcher: SemanticsMatcher) {
    waitFor(matcher)
    compose.onNode(matcher).performClick()
  }

  private fun back() {
    compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
    compose.waitForIdle()
  }

  /** Model an access correction by the administrator while keeping the requester signed in. */
  private fun reassignOrderOnEmulator(id: String, requesterId: String) {
    val host =
        requireNotNull(InstrumentationRegistry.getArguments().getString("firebaseEmulatorHost"))
    val address =
        URL(
            "http://$host:$ORDER_JOURNEY_FIRESTORE_PORT/v1/projects/$ORDER_JOURNEY_PROJECT/databases/(default)/documents/orders/$id?updateMask.fieldPaths=requesterId"
        )
    val connection = address.openConnection() as HttpURLConnection
    try {
      connection.requestMethod = "PATCH"
      connection.setRequestProperty("Authorization", "Bearer owner")
      connection.setRequestProperty("Content-Type", "application/json")
      connection.connectTimeout = ORDER_JOURNEY_WAIT_MILLIS.toInt()
      connection.readTimeout = ORDER_JOURNEY_WAIT_MILLIS.toInt()
      connection.doOutput = true
      val fields = JSONObject().put("requesterId", JSONObject().put("stringValue", requesterId))
      connection.outputStream.use {
        it.write(JSONObject().put("fields", fields).toString().toByteArray())
      }
      assertEquals(HttpURLConnection.HTTP_OK, connection.responseCode)
    } finally {
      connection.disconnect()
    }
  }
}
