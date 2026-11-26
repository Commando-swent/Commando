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
import com.commando.app.model.*
import com.commando.app.model.authentication.AuthRepositoryFirebase
import com.commando.app.ui.auth.WelcomeTestTags
import com.commando.app.ui.orders.MyOrdersTestTags
import com.commando.app.ui.orders.orderStatusLabel
import com.commando.app.ui.theme.SampleAppTheme
import com.google.firebase.firestore.Source
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Two independent SDK clients. The owner can change server state while the requester is offline.
 */
internal class OfflineOrderAccounts(
    val requester: OrderJourneyFirebaseClient,
    val owner: OrderJourneyFirebaseClient,
) {
  val auth = AuthRepositoryFirebase(requester.auth)
  val trips = TripRepositoryFirestore(auth, requester.firestore)
  val orders = OrderRepositoryFirestore({ requester.auth.currentUser?.uid }, requester.firestore)
  val ownerOrders = OrderRepositoryFirestore({ owner.auth.currentUser?.uid }, owner.firestore)

  suspend fun placeOrder(): Order {
    val ownerTrips = TripRepositoryFirestore(AuthRepositoryFirebase(owner.auth), owner.firestore)
    val trip =
        (ownerTrips.publishTrip(
                NewTrip(
                    TripLocations.stores.first(),
                    Instant.now().plus(1, ChronoUnit.DAYS),
                    TripLocations.handoffPoints.first(),
                )
            ) as TripResult.Success)
            .data
    val draft = OrderDraft(trip.id, listOf(OrderItem("milk", "Milk", 220, 2)), 150)
    return (orders.createOrder(draft) as OrderResult.Success).data
  }
}

internal suspend fun withOfflineOrderAccounts(block: suspend (OfflineOrderAccounts) -> Unit) {
  val host = InstrumentationRegistry.getArguments().getString("firebaseEmulatorHost")
  assumeTrue("Requires the Auth and Firestore emulators", !host.isNullOrBlank())
  val requester = orderJourneyClient(host!!)
  val owner = orderJourneyClient(host)
  try {
    requester.auth.signInAnonymously().await()
    owner.auth.signInAnonymously().await()
    block(OfflineOrderAccounts(requester, owner))
  } finally {
    for (client in listOf(owner, requester)) {
      client.auth.signOut()
      client.firestore.terminate().await()
      client.app.delete()
    }
  }
}

@RunWith(AndroidJUnit4::class)
class OfflineOrderHistoryJourneyTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

  @Test
  fun warmSdkHistoryRequiresServerConfirmationAfterReconnect() = runBlocking {
    withOfflineOrderAccounts { accounts ->
      val order = accounts.placeOrder()
      val db = accounts.requester.firestore
      val ref = db.collection("orders").document(order.id)
      val pinned = ref.addSnapshotListener { _, _ -> }
      try {
        assertEquals(OrderResult.Success(listOf(order)), accounts.orders.getMyOrders())
        db.disableNetwork().await()
        try {
          val local = ref.get(Source.CACHE).await()
          assertTrue(local.metadata.isFromCache)
          assertEquals(OrderStatus.PENDING.name, local.getString("status"))
          val accepted =
              accounts.ownerOrders.decideOrder(order.id, OrderDecision.ACCEPT, order.revision)
          assertTrue(accepted is OrderResult.Success)
          assertEquals(OrderResult.Error(OrderError.NetworkError), accounts.orders.getMyOrders())
          assertEquals(
              OrderResult.Error(OrderError.Unknown),
              accounts.orders.getOrderById(order.id),
          )
        } finally {
          db.enableNetwork().await()
        }
        val confirmed = order.copy(status = OrderStatus.ACCEPTED)
        assertEquals(OrderResult.Success(listOf(confirmed)), accounts.orders.getMyOrders())
        assertEquals(OrderResult.Success(confirmed), accounts.orders.getOrderById(order.id))
      } finally {
        pinned.remove()
      }
    }
  }

  @Test
  fun switchingAccountsOfflineDropsTheSavedListAndReconnectConfirmsAnEmptyHistory() = runBlocking {
    withOfflineOrderAccounts { accounts ->
      val order = accounts.placeOrder()
      val network = FakeConnectivityMonitor()
      val context = InstrumentationRegistry.getInstrumentation().targetContext
      val unavailable = context.getString(R.string.offline_no_copy)
      val empty = context.getString(R.string.my_orders_empty)
      val card = hasTestTag(MyOrdersTestTags.ORDER_CARD_PREFIX + order.id)
      compose.setContent {
        SampleAppTheme {
          CommandoApp(
              accounts.auth,
              accounts.trips,
              connectivityMonitor = network,
              orderRepositoryFactory = {
                OrderRepositoryFirestore(it, accounts.requester.firestore)
              },
          )
        }
      }
      try {
        fun waitFor(matcher: SemanticsMatcher) =
            compose.waitUntil(ORDER_JOURNEY_WAIT_MILLIS) {
              compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()
            }
        val ordersTab = hasTestTag(AppTestTags.ORDERS_BUTTON)
        waitFor(ordersTab)
        compose.onNode(ordersTab).performClick()
        waitFor(card)
        compose.runOnIdle { network.setOnline(false) }
        compose.onNode(card).assertExists()
        accounts.requester.auth.signOut()
        waitFor(hasTestTag(com.commando.app.ui.auth.WelcomeTestTags.SCREEN))
        accounts.requester.auth.signInAnonymously().await()
        assertNotEquals(order.requesterId, accounts.requester.auth.currentUser!!.uid)
        waitFor(ordersTab)
        compose.onNode(ordersTab).performClick()
        waitFor(hasText(unavailable))
        compose.onNode(card).assertDoesNotExist()
        compose.runOnIdle { network.setOnline(true) }
        waitFor(hasTestTag(MyOrdersTestTags.EMPTY))
        compose.onNode(card).assertDoesNotExist()
        compose.runOnIdle { network.setOnline(false) }
        waitFor(hasText(empty))
        compose.onNodeWithText(unavailable).assertDoesNotExist()
        compose.onNode(card).assertDoesNotExist()
      } finally {
        compose.runOnIdle { compose.activity.setContent {} }
      }
    }
  }

  @Test
  fun unavailableFirestoreKeepsSavedHistoryAndSuccessfulRetryUpdatesTheList() = runBlocking {
    withOfflineOrderAccounts { accounts ->
      val order = accounts.placeOrder()
      val card = hasTestTag(MyOrdersTestTags.ORDER_CARD_PREFIX + order.id)
      val db = accounts.requester.firestore
      val network = FakeConnectivityMonitor()
      showHistory(accounts, network) {
        waitFor(card and text(orderStatusLabel(OrderStatus.PENDING)))

        // A device can report connectivity while its Firestore transport is unavailable.
        db.disableNetwork().await()
        try {
          compose.runOnIdle { network.setOnline(false) }
          val original = savedConfirmation()
          val rejected =
              accounts.ownerOrders.decideOrder(order.id, OrderDecision.REJECT, order.revision)
          assertEquals(OrderResult.Success(order.copy(status = OrderStatus.REJECTED)), rejected)

          compose.runOnIdle { network.setOnline(true) }
          compose.waitForIdle()
          assertEquals(original, savedConfirmation())
          compose.onNode(card and text(orderStatusLabel(OrderStatus.PENDING))).assertIsDisplayed()
          compose.onNode(card and text(orderStatusLabel(OrderStatus.REJECTED))).assertDoesNotExist()
        } finally {
          db.enableNetwork().await()
        }

        compose.runOnIdle { network.setOnline(false) }
        compose.onNode(hasTestTag(AppTestTags.HOME_BUTTON)).performClick()
        openHistory()
        assertNotNull(savedConfirmation())
        compose.runOnIdle { network.setOnline(true) }
        waitFor(card and text(orderStatusLabel(OrderStatus.REJECTED)))
        compose.onNode(savedCopyLabel()).assertDoesNotExist()

        compose.runOnIdle { network.setOnline(false) }
        waitFor(savedCopyLabel())
        compose.onNode(card and text(orderStatusLabel(OrderStatus.REJECTED))).assertIsDisplayed()
      }
    }
  }

  @Test
  fun signingBackIntoTheSameAccountOfflineRequiresANewConfirmation() = runBlocking {
    withOfflineOrderAccounts { accounts ->
      val auth = accounts.requester.auth
      auth.signOut()
      val email = "offline-history-${UUID.randomUUID()}@example.test"
      val password = UUID.randomUUID().toString()
      val uid = auth.createUserWithEmailAndPassword(email, password).await().user!!.uid
      val order = accounts.placeOrder()
      val card = hasTestTag(MyOrdersTestTags.ORDER_CARD_PREFIX + order.id)
      val network = FakeConnectivityMonitor()
      showHistory(accounts, network) {
        waitFor(card)
        compose.runOnIdle { network.setOnline(false) }
        waitFor(savedCopyLabel())
        auth.signOut()
        waitFor(hasTestTag(WelcomeTestTags.SCREEN))
        val second = auth.signInWithEmailAndPassword(email, password).await().user
        assertEquals(uid, second?.uid)

        openHistory()
        waitFor(text(R.string.offline_no_copy))
        compose.onNode(card).assertDoesNotExist()
        compose.onNode(savedCopyLabel()).assertDoesNotExist()

        compose.runOnIdle { network.setOnline(true) }
        waitFor(card)
        compose.onNodeWithText(context.getString(R.string.offline_no_copy)).assertDoesNotExist()
        compose.runOnIdle { network.setOnline(false) }
        waitFor(savedCopyLabel())
        compose.onNode(card).assertIsDisplayed()
      }
    }
  }

  private suspend fun showHistory(
      accounts: OfflineOrderAccounts,
      network: FakeConnectivityMonitor,
      block: suspend () -> Unit,
  ) {
    compose.setContent {
      SampleAppTheme {
        CommandoApp(
            accounts.auth,
            accounts.trips,
            connectivityMonitor = network,
            orderRepositoryFactory = { OrderRepositoryFirestore(it, accounts.requester.firestore) },
        )
      }
    }
    try {
      openHistory()
      block()
    } finally {
      compose.runOnIdle { compose.activity.setContent {} }
    }
  }

  private val context
    get() = InstrumentationRegistry.getInstrumentation().targetContext

  private fun openHistory() {
    val ordersTab = hasTestTag(AppTestTags.ORDERS_BUTTON)
    waitFor(ordersTab)
    compose.onNode(ordersTab).performClick()
  }

  private fun waitFor(matcher: SemanticsMatcher) {
    compose.waitUntil(ORDER_JOURNEY_WAIT_MILLIS) {
      compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()
    }
  }

  private fun text(@StringRes id: Int) = hasText(context.getString(id))

  private fun savedCopyLabel() =
      hasText(context.getString(R.string.offline_confirmed_time, "").trim(), substring = true)

  private fun savedConfirmation(): String {
    val savedCopy = savedCopyLabel()
    waitFor(savedCopy)
    return compose
        .onNode(savedCopy)
        .fetchSemanticsNode()
        .config[androidx.compose.ui.semantics.SemanticsProperties.Text]
        .single()
        .text
  }
}
