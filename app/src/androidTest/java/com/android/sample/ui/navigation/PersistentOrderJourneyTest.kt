package com.android.sample.ui.navigation

// AI assistance: OpenAI Codex.
import android.content.Context
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.android.sample.R
import com.android.sample.data.repository.*
import com.android.sample.model.*
import com.android.sample.model.authentication.AuthRepositoryFirebase
import com.android.sample.ui.auth.GoogleCredentialClient
import com.android.sample.ui.catalogue.CatalogueTestTags
import com.android.sample.ui.orders.*
import com.android.sample.ui.theme.SampleAppTheme
import com.android.sample.ui.trips.AvailableTripsScreenTestTags
import com.google.firebase.firestore.*
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Opt in with firebaseEmulatorHost=10.0.2.2; never contacts a production project. */
@RunWith(AndroidJUnit4::class)
class PersistentOrderJourneyTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

  @Test
  fun confirmedOrderSurvivesFreshFirebaseClient() = runBlocking {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val host = InstrumentationRegistry.getArguments().getString("firebaseEmulatorHost")
    assumeTrue(
        "Requires running Auth/Firestore emulators and firebaseEmulatorHost",
        !host.isNullOrBlank(),
    )
    val clients = mutableListOf<OrderJourneyFirebaseClient>()
    try {
      val first = orderJourneyClient(host!!).also(clients::add)
      val email = "android-${UUID.randomUUID()}@example.test"
      val password = "emulator-password"
      first.auth.createUserWithEmailAndPassword(email, password).await()
      val auth = AuthRepositoryFirebase(first.auth)
      val uid = auth.currentUser!!.uid
      val trips = TripRepositoryFirestore(auth, first.firestore)
      val owner = orderJourneyClient(host).also(clients::add)
      owner.auth.signInAnonymously().await()
      val ownerTrips = TripRepositoryFirestore(AuthRepositoryFirebase(owner.auth), owner.firestore)
      val place = Location("Migros ${uid.take(8)}", 46.52, 6.57)
      val trip =
          (ownerTrips.publishTrip(NewTrip(place, Instant.now().plusSeconds(600), place))
                  as TripResult.Success)
              .data
      assertNotEquals(uid, trip.ownerId)
      val milk = Product("milk", "migros", "Milk", 220)
      val products =
          object : ProductRepository {
            override suspend fun getProducts(storeId: String, limit: Int) =
                ProductResult.Success(listOf(milk))

            override suspend fun searchProducts(
                storeId: String,
                query: String,
                cursor: String?,
                limit: Int,
                category: String?,
            ) = ProductResult.Success(ProductPage(listOf(milk), null))

            override suspend fun getFavouriteProducts(storeId: String) =
                ProductResult.Success(FavouriteProducts(emptyList(), emptyList()))

            override suspend fun getCategories(storeId: String) =
                ProductResult.Success(emptyList<String>())

            override suspend fun setFavourite(
                storeId: String,
                productId: String,
                favourite: Boolean,
            ) = ProductResult.Success(Unit)
          }
      val google =
          object : GoogleCredentialClient {
            override suspend fun request(context: Context) = null

            override suspend fun clearSession() {}
          }
      compose.setContent {
        SampleAppTheme {
          CommandoApp(
              auth,
              trips,
              google,
              productRepository = products,
              orderRepositoryFactory = { OrderRepositoryFirestore(it, first.firestore) },
          )
        }
      }
      compose.onNodeWithTag(AppTestTags.TRIPS_BUTTON).performClick()
      val tripCard = hasTestTag(AvailableTripsScreenTestTags.tripCard(trip.id))
      // The list appears before the trips load, so scroll again until the new trip is listed.
      compose.waitUntil(ORDER_JOURNEY_WAIT_MILLIS) {
        runCatching { compose.onNode(hasScrollToIndexAction()).performScrollToNode(tripCard) }
            .isSuccess
      }
      compose.onNode(tripCard).performClick()
      awaitText(instrumentation.targetContext.getString(R.string.trip_details_add_items))
      compose
          .onNodeWithText(instrumentation.targetContext.getString(R.string.trip_details_add_items))
          .performScrollTo()
          .performClick()
      compose.waitUntil(ORDER_JOURNEY_WAIT_MILLIS) {
        compose.onAllNodesWithTag(CatalogueTestTags.add(milk.id)).fetchSemanticsNodes().isNotEmpty()
      }
      compose.onNodeWithTag(CatalogueTestTags.add(milk.id)).performClick()
      compose.onNodeWithTag(CatalogueTestTags.CONTINUE).performClick()
      awaitText(instrumentation.targetContext.getString(R.string.order_review))
      compose.onNodeWithTag(ReviewOrderTags.SUBMIT).performClick()
      awaitText(instrumentation.targetContext.getString(R.string.order_sent))
      compose
          .onNodeWithText(instrumentation.targetContext.getString(R.string.order_view))
          .performClick()
      val saved =
          (OrderRepositoryFirestore({ uid }, first.firestore).getMyOrders() as OrderResult.Success)
              .data
              .single()
      compose.waitUntil(ORDER_JOURNEY_WAIT_MILLIS) {
        compose
            .onAllNodesWithTag(MyOrdersTestTags.ORDER_CARD_PREFIX + saved.id)
            .fetchSemanticsNodes()
            .isNotEmpty()
      }
      compose.onNodeWithTag(MyOrdersTestTags.ORDER_CARD_PREFIX + saved.id).performClick()
      awaitText(instrumentation.targetContext.getString(R.string.order_details_title))
      awaitText(milk.name, substring = true)
      compose.onNodeWithText(milk.name, substring = true).assertExists()
      instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
        File(instrumentation.targetContext.cacheDir, "persistent-order.png").outputStream().use {
          bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
      }
      val fresh = orderJourneyClient(host).also(clients::add)
      fresh.auth.signInWithEmailAndPassword(email, password).await()
      val reloaded = OrderRepositoryFirestore({ fresh.auth.currentUser?.uid }, fresh.firestore)
      assertEquals(OrderResult.Success(saved), reloaded.getOrderById(saved.id))
      assertEquals(OrderResult.Success(listOf(saved)), reloaded.getMyOrders())
      fresh.auth.signOut()
      fresh.auth.signInAnonymously().await()
      assertEquals(OrderResult.Success(emptyList<Order>()), reloaded.getMyOrders())
      assertEquals(OrderResult.Error(OrderError.PermissionDenied), reloaded.getOrderById(saved.id))
    } finally {
      compose.runOnIdle { compose.activity.setContent {} }
      for (client in clients.asReversed()) {
        client.auth.signOut()
        client.firestore.terminate().await()
        client.app.delete()
      }
    }
  }

  private fun awaitText(text: String, substring: Boolean = false) =
      compose.waitUntil(ORDER_JOURNEY_WAIT_MILLIS) {
        compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
      }
}
