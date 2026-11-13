package com.android.sample.ui.navigation

// AI assistance: OpenAI Codex.
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.StringRes
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.android.sample.R
import com.android.sample.data.repository.*
import com.android.sample.model.*
import com.android.sample.model.authentication.AuthRepositoryFirebase
import com.android.sample.ui.auth.GoogleCredentialClient
import com.android.sample.ui.auth.WelcomeTestTags
import com.android.sample.ui.catalogue.CatalogueTestTags
import com.android.sample.ui.home.HomeTestTags
import com.android.sample.ui.orders.*
import com.android.sample.ui.theme.SampleAppTheme
import com.android.sample.ui.trips.*
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Source
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Two real emulator accounts drive the production graph/repositories from publication to receipt.
 * Only accounts and importer-owned catalogue data are fixtures; no trip/order transition is seeded.
 * Opt in through firebaseEmulatorHost as in CI. This is not a production Google-login/device trial.
 */
@RunWith(AndroidJUnit4::class)
class OrderLifecycleJourneyTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
  private val context
    get() = InstrumentationRegistry.getInstrumentation().targetContext

  private val store = TripLocations.stores.first { storeIdFor(it.name) != null }
  private val handoff = TripLocations.handoffPoints.first()

  @Test
  fun publishedTripOrderIsAcceptedHandedOffAndPersistedAsDelivered() = runBlocking {
    val host = InstrumentationRegistry.getArguments().getString("firebaseEmulatorHost")
    assumeTrue("Requires Auth/Firestore emulators and firebaseEmulatorHost", !host.isNullOrBlank())
    val clients = mutableListOf<OrderJourneyFirebaseClient>()
    val client = orderJourneyClient(host!!).also(clients::add)
    val password = UUID.randomUUID().toString()
    val ownerEmail = "owner-${UUID.randomUUID()}@example.test"
    val requesterEmail = "requester-${UUID.randomUUID()}@example.test"
    val storeId = requireNotNull(storeIdFor(store.name))
    val documentId = "lifecycle-${UUID.randomUUID()}"
    val product = Product("$storeId:$documentId", storeId, documentId, PRODUCT_PRICE_CENTS)
    try {
      val requesterId =
          client.auth.createUserWithEmailAndPassword(requesterEmail, password).await().user!!.uid
      client.auth.signOut()
      val ownerId =
          client.auth.createUserWithEmailAndPassword(ownerEmail, password).await().user!!.uid
      client.auth.signOut()
      catalogueFixture(host, storeId, documentId, product)
      val auth = AuthRepositoryFirebase(client.auth)
      val trips = TripRepositoryFirestore(auth, client.firestore)
      val orders = OrderRepositoryFirestore({ client.auth.currentUser?.uid }, client.firestore)
      val google =
          object : GoogleCredentialClient {
            override suspend fun request(context: Context) = null

            override suspend fun clearSession() = Unit
          }
      compose.setContent {
        SampleAppTheme {
          CommandoApp(
              auth,
              trips,
              google,
              productRepository = ProductRepositoryFirestore(client.firestore),
              orderRepositoryFactory = { OrderRepositoryFirestore(it, client.firestore) },
              checklistRepositoryFactory = {
                ShoppingChecklistRepositoryFirestore(it, client.firestore)
              },
          )
        }
      }
      signIn(ownerEmail, password)
      click(AppTestTags.COMMANDO_MODE)
      click(HomeTestTags.TRIP_ACTION)
      publishTrip()
      awaitTag(HomeTestTags.CURRENT_TRIP)
      val trip = (trips.getMyTrips() as TripResult.Success).data.single()
      assertEquals(ownerId, trip.ownerId)
      assertEquals(store, trip.store)
      assertEquals(handoff, trip.handoffLocation)
      signOut()

      signIn(requesterEmail, password)
      click(AppTestTags.TRIPS_BUTTON)
      val tripCard = AvailableTripsScreenTestTags.tripCard(trip.id)
      compose.waitUntil(ORDER_JOURNEY_WAIT_MILLIS) {
        compose.onAllNodes(hasScrollToIndexAction()).fetchSemanticsNodes().isNotEmpty()
      }
      compose.waitUntil(ORDER_JOURNEY_WAIT_MILLIS) {
        // The signed-in feed can replace the guest list after it first becomes scrollable.
        try {
          compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag(tripCard))
          compose.onNode(hasTestTag(tripCard)).isDisplayed()
        } catch (_: AssertionError) {
          false
        }
      }
      click(tripCard)
      clickText(R.string.trip_details_add_items)
      awaitTag(CatalogueTestTags.SEARCH)
      compose.onNodeWithTag(CatalogueTestTags.SEARCH).performTextReplacement(product.name)
      // Old rows stay visible during debounce; wait for the result of this exact query.
      val results =
          context.resources.getQuantityString(
              R.plurals.catalogue_results,
              1,
              1,
              product.name.uppercase(),
          )
      compose.waitUntil(ORDER_JOURNEY_WAIT_MILLIS) {
        compose.onAllNodesWithText(results).fetchSemanticsNodes().isNotEmpty()
      }
      click(CatalogueTestTags.add(product.id))
      repeat(ORDER_QUANTITY - 1) { click(CatalogueTestTags.increase(product.id)) }
      click(CatalogueTestTags.CONTINUE)
      awaitTag(ReviewOrderTags.SUBMIT)
      click(ReviewOrderTags.SUBMIT)
      awaitText(R.string.order_sent)
      click(ReviewOrderTags.SUBMIT)
      val pending = (orders.getMyOrders() as OrderResult.Success).data.single()
      assertEquals(trip.id, pending.tripId)
      assertEquals(requesterId, pending.requesterId)
      assertEquals(OrderStatus.PENDING, pending.status)
      assertEquals(
          listOf(OrderItem(product.id, product.name, product.priceCents, ORDER_QUANTITY)),
          pending.items,
      )
      openRequesterOrder(pending.id)
      assertStatus(OrderStatus.PENDING)
      assertActions(OrderDetailsTestTags.EDIT, OrderDetailsTestTags.CANCEL)
      assertEquals(
          OrderResult.Error(OrderError.PermissionDenied),
          orders.decideOrder(pending.id, OrderDecision.ACCEPT, pending.revision),
      )
      assertEquals(
          OrderResult.Error(OrderError.PermissionDenied),
          orders.markReadyForHandoff(pending.id),
      )
      assertDeniedStatusWrite(client, pending.id, OrderStatus.ACCEPTED)
      back() // My Orders exposes the Profile tab.
      signOut()

      signIn(ownerEmail, password)
      click(AppTestTags.COMMANDO_MODE)
      click(AppTestTags.TRIPS_BUTTON)
      val ownedTrip = MyTripsTestTags.TRIP_CARD_PREFIX + trip.id
      awaitTag(ownedTrip)
      click(ownedTrip)
      clickText(R.string.trip_details_view_orders)
      val incoming = IncomingOrdersTestTags.ORDER_CARD_PREFIX + pending.id
      awaitTag(incoming)
      click(incoming)
      assertActions(OrderDetailsTestTags.ACCEPT, OrderDetailsTestTags.REJECT)
      assertEquals(
          OrderResult.Error(OrderError.AlreadyDecided(pending)),
          orders.markReadyForHandoff(pending.id),
      )
      assertDeniedStatusWrite(client, pending.id, OrderStatus.READY_FOR_HANDOFF)
      click(OrderDetailsTestTags.ACCEPT)
      assertStatus(OrderStatus.ACCEPTED)
      assertEquals(
          pending.copy(status = OrderStatus.ACCEPTED),
          (orders.getOrderById(pending.id) as OrderResult.Success).data,
      )
      assertActions(OrderDetailsTestTags.MARK_READY_FOR_HANDOFF)
      back() // Incoming Orders owns the shopping-checklist entry point.
      click(IncomingOrdersTestTags.OPEN_CHECKLIST)
      val purchasedTag = ShoppingChecklistTestTags.ITEM_PREFIX + product.id
      awaitTag(purchasedTag)
      compose.onNodeWithTag(purchasedTag).assertIsOff()
      click(purchasedTag)
      compose.waitUntil(ORDER_JOURNEY_WAIT_MILLIS) {
        compose.onAllNodes(hasTestTag(purchasedTag) and isOn()).fetchSemanticsNodes().isNotEmpty()
      }
      assertEquals(
          OrderResult.Success(mapOf(product.id to ORDER_QUANTITY.toLong())),
          ShoppingChecklistRepositoryFirestore({ client.auth.currentUser?.uid }, client.firestore)
              .getPurchasedQuantities(trip.id),
      )
      back()
      click(incoming)
      click(OrderDetailsTestTags.MARK_READY_FOR_HANDOFF)
      assertStatus(OrderStatus.READY_FOR_HANDOFF)
      assertActions()
      val ready = pending.copy(status = OrderStatus.READY_FOR_HANDOFF)
      assertEquals(OrderResult.Success(ready), orders.getOrderById(pending.id))
      back() // Incoming Orders
      back() // Trip Details
      back() // My Trips exposes the Profile tab.
      signOut()

      signIn(requesterEmail, password)
      openRequesterOrder(pending.id)
      assertStatus(OrderStatus.READY_FOR_HANDOFF)
      assertActions(OrderDetailsTestTags.CONFIRM_RECEIPT)
      assertEquals(OrderResult.Success(ready), orders.getOrderById(pending.id))
      click(OrderDetailsTestTags.CONFIRM_RECEIPT)
      assertStatus(OrderStatus.DELIVERED)
      back()
      openRequesterOrder(pending.id)
      assertStatus(OrderStatus.DELIVERED)
      assertActions()
      val delivered = pending.copy(status = OrderStatus.DELIVERED)
      assertEquals(OrderResult.Success(delivered), orders.getOrderById(pending.id))
      // An independent client excludes an optimistic UI or cached-only persistence success.
      val fresh = orderJourneyClient(host).also(clients::add)
      fresh.auth.signInWithEmailAndPassword(requesterEmail, password).await()
      val persisted = OrderRepositoryFirestore({ fresh.auth.currentUser?.uid }, fresh.firestore)
      assertEquals(OrderResult.Success(delivered), persisted.getOrderById(pending.id))
      assertEquals(OrderResult.Success(listOf(delivered)), persisted.getMyOrders())
      assertEquals(
          TripResult.Success(trip),
          TripRepositoryFirestore(AuthRepositoryFirebase(fresh.auth), fresh.firestore)
              .getTripById(trip.id),
      )
      back()
      signOut()
      signIn(ownerEmail, password)
      click(AppTestTags.COMMANDO_MODE)
      click(AppTestTags.TRIPS_BUTTON)
      click(ownedTrip)
      clickText(R.string.trip_details_view_orders)
      click(incoming)
      assertStatus(OrderStatus.DELIVERED)
      assertActions()
      assertEquals(OrderResult.Success(delivered), orders.getOrderById(pending.id))
      assertEquals(TripResult.Success(trip), trips.getTripById(trip.id))

      assertEquals(
          OrderStatus.DELIVERED.name,
          fresh.firestore
              .collection("orders")
              .document(pending.id)
              .get(Source.SERVER)
              .await()
              .getString("status"),
      )
    } finally {
      compose.runOnIdle { compose.activity.setContent {} }
      catalogueFixture(host, storeId, documentId, null)
      for (entry in clients.asReversed()) {
        entry.auth.signOut()
        entry.firestore.terminate().await()
        entry.app.delete()
      }
    }
  }

  /** Assert both allowed controls and the absence of controls for other roles/states. */
  private fun assertActions(vararg allowed: String) {
    val actions =
        listOf(
            OrderDetailsTestTags.ACCEPT,
            OrderDetailsTestTags.REJECT,
            OrderDetailsTestTags.EDIT,
            OrderDetailsTestTags.CANCEL,
            OrderDetailsTestTags.MARK_READY_FOR_HANDOFF,
            OrderDetailsTestTags.CONFIRM_RECEIPT,
        )
    // Opening details and observing a status write both load asynchronously from Firestore.
    compose.waitUntil(ORDER_JOURNEY_WAIT_MILLIS) {
      actions.all { tag ->
        val exists = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        exists == (tag in allowed)
      }
    }
    for (tag in actions) {
      val node = compose.onNodeWithTag(tag)
      if (tag in allowed) node.assertIsEnabled() else node.assertDoesNotExist()
    }
  }

  /** A forged SDK write must also be rejected by the checked-in Firestore rules. */
  private suspend fun assertDeniedStatusWrite(
      client: OrderJourneyFirebaseClient,
      id: String,
      status: OrderStatus,
  ) {
    try {
      client.firestore.collection("orders").document(id).update("status", status.name).await()
      fail("Unauthorized transition to $status committed")
    } catch (error: FirebaseFirestoreException) {
      assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, error.code)
    }
  }

  private fun signIn(email: String, password: String) {
    awaitTag(WelcomeTestTags.LOGIN)
    click(WelcomeTestTags.LOGIN)
    compose
        .onNodeWithContentDescription(context.getString(R.string.auth_email))
        .performTextReplacement(email)
    compose
        .onNodeWithContentDescription(context.getString(R.string.auth_password))
        .performTextReplacement(password)
    compose
        .onNodeWithContentDescription(context.getString(R.string.auth_password))
        .performImeAction()
    awaitTag(NavigationTestTags.HOME_SCREEN)
  }

  private fun signOut() {
    click(AppTestTags.PROFILE_BUTTON)
    click(NavigationTestTags.SIGN_OUT_BUTTON)
    awaitTag(WelcomeTestTags.SCREEN)
  }

  private fun publishTrip() {
    click(CreateTripScreenTestTags.STORE_FIELD)
    click(CreateTripScreenTestTags.locationOption(store.name))
    click(CreateTripScreenTestTags.DATE_FIELD)
    compose
        .onNodeWithContentDescription(
            context.getString(
                androidx.compose.material3.R.string.m3c_date_picker_switch_to_input_mode
            )
        )
        .performClick()
    val locale = context.resources.configuration.locales[0]
    val pattern =
        android.text.format.DateFormat.getDateFormatOrder(context).joinToString("") {
          when (it) {
            'd' -> "dd"
            'M' -> "MM"
            else -> "yyyy"
          }
        }
    compose
        .onNode(hasSetTextAction())
        .performTextReplacement(
            LocalDate.now()
                .plusDays(TRIP_DAYS_AHEAD)
                .format(DateTimeFormatter.ofPattern(pattern, locale))
        )
    clickText(R.string.create_trip_dialog_ok)
    click(CreateTripScreenTestTags.TIME_FIELD)
    clickText(R.string.create_trip_dialog_ok)
    click(CreateTripScreenTestTags.HANDOFF_LOCATION_FIELD)
    click(CreateTripScreenTestTags.locationOption(handoff.name))
    click(CreateTripScreenTestTags.PUBLISH_BUTTON)
  }

  private fun openRequesterOrder(id: String) {
    click(AppTestTags.ORDERS_BUTTON)
    val tag = MyOrdersTestTags.ORDER_CARD_PREFIX + id
    awaitTag(tag)
    click(tag)
  }

  private fun assertStatus(status: OrderStatus) = awaitText(orderStatusLabel(status))

  private fun awaitTag(tag: String) =
      compose.waitUntil(ORDER_JOURNEY_WAIT_MILLIS) {
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
      }

  private fun awaitText(@StringRes text: Int) =
      compose.waitUntil(ORDER_JOURNEY_WAIT_MILLIS) {
        compose.onAllNodesWithText(context.getString(text)).fetchSemanticsNodes().isNotEmpty()
      }

  private fun click(tag: String) {
    awaitTag(tag)
    val node = compose.onNodeWithTag(tag)
    if (!node.isDisplayed()) node.performScrollTo()
    node.performClick()
  }

  private fun clickText(@StringRes text: Int) {
    awaitText(text)
    val node = compose.onNodeWithText(context.getString(text))
    if (!node.isDisplayed()) node.performScrollTo()
    node.performClick()
  }

  private fun back() {
    compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
    compose.waitForIdle()
  }

  /** Admin access is restricted to importer fixtures on the explicitly opted-in demo emulator. */
  private fun catalogueFixture(host: String, storeId: String, id: String, product: Product?) {
    val url =
        URL(
            "http://$host:$ORDER_JOURNEY_FIRESTORE_PORT/v1/projects/$ORDER_JOURNEY_PROJECT/databases/(default)/documents/stores/$storeId/products/$id"
        )
    val connection = url.openConnection() as HttpURLConnection
    try {
      connection.requestMethod = if (product == null) "DELETE" else "PATCH"
      connection.setRequestProperty("Authorization", "Bearer owner")
      connection.connectTimeout = ORDER_JOURNEY_WAIT_MILLIS.toInt()
      connection.readTimeout = ORDER_JOURNEY_WAIT_MILLIS.toInt()
      if (product != null) {
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        val fields =
            JSONObject()
                .put("id", JSONObject().put("stringValue", product.id))
                .put("name", JSONObject().put("stringValue", product.name))
                .put(
                    "price",
                    JSONObject()
                        .put(
                            "doubleValue",
                            product.priceCents
                                .toBigDecimal()
                                .movePointLeft(CURRENCY_SCALE)
                                .toDouble(),
                        ),
                )
                .put("removed_at", JSONObject().put("nullValue", JSONObject.NULL))
                .put(
                    "search_prefixes",
                    JSONObject()
                        .put(
                            "mapValue",
                            JSONObject()
                                .put(
                                    "fields",
                                    JSONObject().apply {
                                      requireNotNull(productSearchTerms(product.name)).forEach {
                                        put(it, JSONObject().put("booleanValue", true))
                                      }
                                    },
                                ),
                        ),
                )
        connection.outputStream.use {
          it.write(JSONObject().put("fields", fields).toString().toByteArray())
        }
      }
      assertTrue(
          "Catalogue fixture request failed: ${connection.responseCode}",
          connection.responseCode in 200..299,
      )
    } finally {
      connection.disconnect()
    }
  }

  private companion object {
    const val CURRENCY_SCALE = 2
    const val PRODUCT_PRICE_CENTS = 225
    const val ORDER_QUANTITY = 2
    const val TRIP_DAYS_AHEAD = 2L
  }
}
