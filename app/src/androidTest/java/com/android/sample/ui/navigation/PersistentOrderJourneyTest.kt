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
import com.android.sample.data.repository.*
import com.android.sample.model.*
import com.android.sample.model.authentication.AuthRepositoryFirebase
import com.android.sample.ui.auth.GoogleCredentialClient
import com.android.sample.ui.catalogue.CatalogueTestTags
import com.android.sample.ui.orders.*
import com.android.sample.ui.theme.SampleAppTheme
import com.google.firebase.*
import com.google.firebase.auth.FirebaseAuth
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
    val clients = mutableListOf<Client>()
    try {
      val first = client(host!!).also(clients::add)
      val email = "android-${UUID.randomUUID()}@example.test"
      val password = "emulator-password"
      first.auth.createUserWithEmailAndPassword(email, password).await()
      val auth = AuthRepositoryFirebase(first.auth)
      val uid = auth.currentUser!!.uid
      val trips = TripRepositoryFirestore(auth, first.firestore)
      val place = Location("Migros ${uid.take(8)}", 46.52, 6.57)
      val trip =
          (trips.publishTrip(NewTrip(place, Instant.now().plusSeconds(600), place))
                  as TripResult.Success)
              .data
      val milk = Product("milk", "migros", "Milk", 220)
      val products =
          object : ProductRepository {
            override suspend fun getProducts(storeId: String, limit: Int) =
                ProductResult.Success(listOf(milk))
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
      awaitText(trip.store.name)
      compose.onNodeWithText(trip.store.name).performClick()
      awaitText("Add items to this run")
      compose.onNodeWithText("Add items to this run").performScrollTo().performClick()
      compose.waitUntil(15000) {
        compose.onAllNodesWithTag(CatalogueTestTags.add(milk.id)).fetchSemanticsNodes().isNotEmpty()
      }
      compose.onNodeWithTag(CatalogueTestTags.add(milk.id)).performClick()
      compose.onNodeWithTag(CatalogueTestTags.CONTINUE).performClick()
      awaitText("Review order")
      compose.onNodeWithTag(ReviewOrderTags.SUBMIT).performClick()
      awaitText("Your order is sent")
      compose.onNodeWithText("View my orders").performClick()
      val saved =
          (OrderRepositoryFirestore({ uid }, first.firestore).getMyOrders() as OrderResult.Success)
              .data
              .single()
      compose.waitUntil(15000) {
        compose
            .onAllNodesWithTag(MyOrdersTestTags.ORDER_CARD_PREFIX + saved.id)
            .fetchSemanticsNodes()
            .isNotEmpty()
      }
      compose.onNodeWithTag(MyOrdersTestTags.ORDER_CARD_PREFIX + saved.id).performClick()
      awaitText("Order details")
      awaitText("Milk", substring = true)
      compose.onNodeWithText("Milk", substring = true).assertExists()
      instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
        File(instrumentation.targetContext.cacheDir, "persistent-order.png").outputStream().use {
          bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
      }
      val fresh = client(host).also(clients::add)
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
      compose.waitUntil(15000) {
        compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
      }

  private data class Client(
      val app: FirebaseApp,
      val auth: FirebaseAuth,
      val firestore: FirebaseFirestore,
  )

  private fun client(host: String): Client {
    val app =
        FirebaseApp.initializeApp(
            InstrumentationRegistry.getInstrumentation().targetContext,
            FirebaseOptions.Builder()
                .setProjectId("demo-commando")
                .setApplicationId("1:123456789:android:orders-emulator")
                .setApiKey("fake-api-key")
                .build(),
            "orders-${UUID.randomUUID()}",
        )
    val auth = FirebaseAuth.getInstance(app).apply { useEmulator(host, 9099) }
    val firestore =
        FirebaseFirestore.getInstance(app).apply {
          firestoreSettings =
              FirebaseFirestoreSettings.Builder()
                  .setLocalCacheSettings(MemoryCacheSettings.newBuilder().build())
                  .build()
          useEmulator(host, 8080)
        }
    return Client(app, auth, firestore)
  }
}
