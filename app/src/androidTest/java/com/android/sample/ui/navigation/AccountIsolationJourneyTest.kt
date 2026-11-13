package com.android.sample.ui.navigation

// AI assistance: Claude Code.
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
import com.android.sample.ui.auth.AuthTestTags
import com.android.sample.ui.auth.GoogleCredentialClient
import com.android.sample.ui.auth.WelcomeTestTags
import com.android.sample.ui.catalogue.CatalogueTestTags
import com.android.sample.ui.orders.MyOrdersTestTags
import com.android.sample.ui.orders.ReviewOrderTags
import com.android.sample.ui.profile.ProfileTestTags
import com.android.sample.ui.theme.SampleAppTheme
import com.android.sample.ui.trips.AvailableTripsScreenTestTags
import com.android.sample.ui.trips.CreateTripScreenTestTags
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Authentication E2E on the Auth/Firestore emulators through the real graph and repositories:
 * sign-up, sign-out, a wrong password, a dismissed Google picker, switching accounts and restoring
 * the session after the app is reopened. One account's profile, orders, basket and filters must
 * never appear for the other. Opt in through firebaseEmulatorHost as in CI.
 */
@RunWith(AndroidJUnit4::class)
class AccountIsolationJourneyTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
  private val context
    get() = InstrumentationRegistry.getInstrumentation().targetContext

  private val store = TripLocations.stores.first { storeIdFor(it.name) != null }
  private val handoff = TripLocations.handoffPoints.first()

  /** Returning no credential is what a dismissed account picker does. */
  private val dismissedGoogle =
      object : GoogleCredentialClient {
        override suspend fun request(context: Context) = null

        override suspend fun clearSession() = Unit
      }

  @Test
  fun accountsStayIsolatedAcrossSignOutSwitchAndRelaunch() = runBlocking {
    val host = InstrumentationRegistry.getArguments().getString("firebaseEmulatorHost")
    assumeTrue("Requires Auth/Firestore emulators and firebaseEmulatorHost", !host.isNullOrBlank())
    val run = UUID.randomUUID().toString()
    val alice = Person("Alice $run".take(MAX_NAME_LENGTH), "alice-$run@example.test")
    val bob = Person("Bob $run".take(MAX_NAME_LENGTH), "bob-$run@example.test")
    val password = UUID.randomUUID().toString()
    val appName = "accounts-$run"
    val clients = mutableListOf(orderJourneyClient(host!!, appName))
    val storeId = requireNotNull(storeIdFor(store.name))
    val documentId = "isolation-$run"
    val product = Product("$storeId:$documentId", storeId, documentId, PRODUCT_PRICE_CENTS)
    try {
      // Fixtures: a trip by a third account and an importer-owned product.
      val owner = orderJourneyClient(host).also(clients::add)
      owner.auth.signInAnonymously().await()
      val trip =
          (TripRepositoryFirestore(AuthRepositoryFirebase(owner.auth), owner.firestore)
                  .publishTrip(NewTrip(store, Instant.now().plus(1, ChronoUnit.DAYS), handoff))
                  as TripResult.Success)
              .data
      catalogueFixture(host, storeId, documentId, product)
      launchApp(clients.first())

      // Alice signs up, orders, then leaves a basket and a location filter behind.
      signUp(alice, password)
      assertProfileName(alice)
      openCatalogue(trip.id)
      addToBasket(product, quantity = ORDERED_QUANTITY)
      click(CatalogueTestTags.CONTINUE)
      click(ReviewOrderTags.SUBMIT)
      awaitText(R.string.order_sent)
      // The confirmation reuses the submit slot for "View my orders".
      click(ReviewOrderTags.SUBMIT)
      awaitTagPrefix(MyOrdersTestTags.ORDER_CARD_PREFIX)
      click(AppTestTags.TRIPS_BUTTON)
      click(AvailableTripsScreenTestTags.LOCATION_FIELD)
      click(CreateTripScreenTestTags.locationOption(handoff.name))
      openCatalogue(trip.id)
      addToBasket(product, quantity = 1)
      signOut()

      // Signing out removes the authenticated graph: back from Login returns to Welcome.
      assertSignedOut()
      click(WelcomeTestTags.LOGIN)
      awaitTag(NavigationTestTags.LOGIN_SCREEN)
      back()
      awaitTag(WelcomeTestTags.SCREEN)
      assertSignedOut()

      // A wrong password and a dismissed Google picker leave the form usable.
      click(WelcomeTestTags.LOGIN)
      typeCredentials(alice.email, "wrong-$password")
      click(AuthTestTags.SUBMIT)
      awaitText(R.string.auth_invalid_credentials)
      awaitEnabled(AuthTestTags.SUBMIT)
      click(AuthTestTags.GOOGLE)
      awaitEnabled(AuthTestTags.GOOGLE)
      awaitEnabled(AuthTestTags.SUBMIT)
      compose.onNodeWithTag(NavigationTestTags.HOME_SCREEN).assertDoesNotExist()

      // Bob signs up on the same device and sees nothing of Alice.
      click(AuthTestTags.TAB_SIGNUP)
      fillSignUp(bob, password)
      awaitTag(NavigationTestTags.HOME_SCREEN)
      assertBobSeesOnlyHisOwnData(bob, trip.id, product)

      // Reopening the app restores Bob's session without signing in again.
      compose.runOnIdle {
        compose.activity.setContent {}
        // A new process starts without the previous session's ViewModels.
        compose.activity.viewModelStore.clear()
      }
      clients.removeAt(0).close()
      launchApp(orderJourneyClient(host, appName).also { clients.add(0, it) })
      // Bob lands back in the signed-in app, never on Welcome.
      awaitTag(AppTestTags.APP_SCAFFOLD)
      compose.onNodeWithTag(WelcomeTestTags.SCREEN).assertDoesNotExist()
      assertProfileName(bob)
      signOut()
      assertSignedOut()

      // Firestore holds Alice's single order and nothing for Bob.
      val aliceOrders = ordersOf(host, alice.email, password, clients)
      assertEquals(
          listOf(OrderItem(product.id, product.name, product.priceCents, ORDERED_QUANTITY)),
          aliceOrders.single().items,
      )
      assertEquals(emptyList<Order>(), ordersOf(host, bob.email, password, clients))
    } finally {
      compose.runOnIdle { compose.activity.setContent {} }
      catalogueFixture(host, storeId, documentId, null)
      clients.asReversed().forEach {
        it.auth.signOut()
        it.close()
      }
    }
  }

  private data class Person(val name: String, val email: String)

  /** Like an app process ending: the signed-in user stays persisted for the same app name. */
  private suspend fun OrderJourneyFirebaseClient.close() {
    firestore.terminate().await()
    app.delete()
  }

  /** Replaces the activity's content, so a second call starts the app again as a relaunch does. */
  private fun launchApp(client: OrderJourneyFirebaseClient) {
    val auth = AuthRepositoryFirebase(client.auth)
    compose.runOnUiThread {
      compose.activity.setContent {
        SampleAppTheme {
          CommandoApp(
              auth,
              TripRepositoryFirestore(auth, client.firestore),
              dismissedGoogle,
              productRepository = ProductRepositoryFirestore(client.firestore),
              orderRepositoryFactory = { OrderRepositoryFirestore(it, client.firestore) },
          )
        }
      }
    }
    compose.waitForIdle()
  }

  private suspend fun ordersOf(
      host: String,
      email: String,
      password: String,
      clients: MutableList<OrderJourneyFirebaseClient>,
  ): List<Order> {
    val client = orderJourneyClient(host).also(clients::add)
    client.auth.signInWithEmailAndPassword(email, password).await()
    val orders = OrderRepositoryFirestore({ client.auth.currentUser?.uid }, client.firestore)
    return (orders.getMyOrders() as OrderResult.Success).data
  }

  private fun assertBobSeesOnlyHisOwnData(bob: Person, tripId: String, product: Product) {
    assertProfileName(bob)
    click(AppTestTags.ORDERS_BUTTON)
    awaitTag(MyOrdersTestTags.EMPTY)
    compose.onAllNodes(hasTestTagPrefix(MyOrdersTestTags.ORDER_CARD_PREFIX)).assertCountEquals(0)
    click(AppTestTags.TRIPS_BUTTON)
    awaitTag(AvailableTripsScreenTestTags.LOCATION_FIELD)
    compose.onNodeWithTag(AvailableTripsScreenTestTags.CLEAR_LOCATION).assertDoesNotExist()
    openCatalogue(tripId)
    awaitTag(CatalogueTestTags.add(product.id))
    compose.onNodeWithTag(CatalogueTestTags.quantity(product.id)).assertDoesNotExist()
    compose.onNodeWithTag(CatalogueTestTags.CONTINUE).assertIsNotEnabled()
  }

  private fun signUp(person: Person, password: String) {
    click(WelcomeTestTags.CREATE_ACCOUNT)
    fillSignUp(person, password)
    awaitTag(NavigationTestTags.HOME_SCREEN)
  }

  private fun fillSignUp(person: Person, password: String) {
    awaitTag(NavigationTestTags.SIGN_UP_SCREEN)
    compose.onNodeWithTag(AuthTestTags.FULL_NAME).performTextReplacement(person.name)
    typeCredentials(person.email, password)
    compose.onNodeWithTag(AuthTestTags.CONFIRMATION).performTextReplacement(password)
    click(AuthTestTags.SUBMIT)
  }

  private fun typeCredentials(email: String, password: String) {
    awaitTag(AuthTestTags.EMAIL)
    compose.onNodeWithTag(AuthTestTags.EMAIL).performTextReplacement(email)
    compose.onNodeWithTag(AuthTestTags.PASSWORD).performTextReplacement(password)
  }

  /** Detail screens hide the bottom bar, so go back until its tabs are reachable. */
  private fun backToTabs() =
      repeat(MAX_BACK_STEPS) {
        if (compose.onAllNodesWithTag(AppTestTags.PROFILE_BUTTON).fetchSemanticsNodes().isEmpty()) {
          back()
        }
      }

  private fun signOut() {
    backToTabs()
    click(AppTestTags.PROFILE_BUTTON)
    click(NavigationTestTags.SIGN_OUT_BUTTON)
    awaitTag(WelcomeTestTags.SCREEN)
  }

  private fun assertSignedOut() {
    listOf(
            NavigationTestTags.HOME_SCREEN,
            AppTestTags.PROFILE_BUTTON,
            AppTestTags.ORDERS_BUTTON,
            AppTestTags.TRIPS_BUTTON,
        )
        .forEach { compose.onNodeWithTag(it).assertDoesNotExist() }
  }

  private fun assertProfileName(person: Person) {
    backToTabs()
    click(AppTestTags.PROFILE_BUTTON)
    compose.waitUntil(ORDER_JOURNEY_WAIT_MILLIS) {
      compose
          .onAllNodes(hasTestTag(ProfileTestTags.FULL_NAME) and hasText(person.name))
          .fetchSemanticsNodes()
          .isNotEmpty()
    }
  }

  private fun openCatalogue(tripId: String) {
    click(AppTestTags.TRIPS_BUTTON)
    val card = AvailableTripsScreenTestTags.tripCard(tripId)
    compose.waitUntil(ORDER_JOURNEY_WAIT_MILLIS) {
      runCatching { compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag(card)) }
          .isSuccess
    }
    click(card)
    clickText(R.string.trip_details_add_items)
  }

  private fun addToBasket(product: Product, quantity: Int) {
    awaitTag(CatalogueTestTags.SEARCH)
    compose.onNodeWithTag(CatalogueTestTags.SEARCH).performTextReplacement(product.name)
    click(CatalogueTestTags.add(product.id))
    repeat(quantity - 1) { click(CatalogueTestTags.increase(product.id)) }
    compose
        .onNodeWithTag(CatalogueTestTags.quantity(product.id))
        .assertTextEquals(quantity.toString())
  }

  private fun awaitTag(tag: String) =
      compose.waitUntil(ORDER_JOURNEY_WAIT_MILLIS) {
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
      }

  private fun awaitTagPrefix(prefix: String) =
      compose.waitUntil(ORDER_JOURNEY_WAIT_MILLIS) {
        compose.onAllNodes(hasTestTagPrefix(prefix)).fetchSemanticsNodes().isNotEmpty()
      }

  private fun awaitEnabled(tag: String) =
      compose.waitUntil(ORDER_JOURNEY_WAIT_MILLIS) {
        compose.onAllNodes(hasTestTag(tag) and isEnabled()).fetchSemanticsNodes().isNotEmpty()
      }

  private fun awaitText(@StringRes text: Int) =
      compose.waitUntil(ORDER_JOURNEY_WAIT_MILLIS) {
        compose.onAllNodesWithText(context.getString(text)).fetchSemanticsNodes().isNotEmpty()
      }

  /** Retries while a list reloads under the node (e.g. search results replacing a row). */
  private fun click(tag: String) =
      compose.waitUntil(ORDER_JOURNEY_WAIT_MILLIS) {
        runCatching {
          val node = compose.onNodeWithTag(tag)
          if (!node.isDisplayed()) node.performScrollTo()
          node.performClick()
        }
            .isSuccess
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

  private fun hasTestTagPrefix(prefix: String) =
      SemanticsMatcher("Test tag starts with $prefix") {
        it.config
            .getOrElse(androidx.compose.ui.semantics.SemanticsProperties.TestTag) { "" }
            .startsWith(prefix)
      }

  private companion object {
    const val PRODUCT_PRICE_CENTS = 340
    const val ORDERED_QUANTITY = 2
    const val MAX_BACK_STEPS = 4
    const val MAX_NAME_LENGTH = 40
  }
}
