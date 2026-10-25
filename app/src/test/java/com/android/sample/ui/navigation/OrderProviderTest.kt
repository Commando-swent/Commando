package com.android.sample.ui.navigation

// AI assistance: OpenAI Codex.
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.android.sample.data.repository.FakeTripRepository
import com.android.sample.model.authentication.AuthUser
import com.android.sample.model.authentication.FakeAuthRepository
import com.android.sample.ui.auth.GoogleCredentialClient
import com.android.sample.ui.orders.MyOrdersTestTags
import com.android.sample.ui.theme.SampleAppTheme
import com.google.android.gms.tasks.Tasks
import com.google.firebase.Timestamp
import com.google.firebase.firestore.*
import io.mockk.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w412dp-h917dp-mdpi")
class OrderProviderTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

  @Test
  fun defaultAppProviderLoadsServerOrdersAndDoesNotDisplayTheDemoBanner() {
    val firestore = mockk<FirebaseFirestore>()
    val collection = mockk<CollectionReference>()
    val query = mockk<Query>()
    val snapshot = mockk<QuerySnapshot>()
    val document = mockk<DocumentSnapshot>()
    every { firestore.collection("orders") } returns collection
    every { collection.whereEqualTo("requesterId", "alice") } returns query
    every { query.get(Source.SERVER) } returns Tasks.forResult(snapshot)
    every { snapshot.documents } returns listOf(document)
    every { document.id } returns "persistent-order"
    every { document.data } returns
        mapOf(
            "tripId" to "trip",
            "requesterId" to "alice",
            "items" to
                listOf(
                    mapOf(
                        "productId" to "milk",
                        "name" to "Milk",
                        "unitPriceCents" to 220L,
                        "quantity" to 2L,
                    )
                ),
            "commissionCents" to 150L,
            "status" to "PENDING",
            "createdAt" to Timestamp(100, 0),
        )
    val google =
        object : GoogleCredentialClient {
          override suspend fun request(context: Context) = null

          override suspend fun clearSession() {}
        }
    mockkStatic(FirebaseFirestore::class)
    try {
      every { FirebaseFirestore.getInstance() } returns firestore
      compose.setContent {
        SampleAppTheme {
          CommandoApp(FakeAuthRepository(AuthUser("alice")), FakeTripRepository("alice"), google)
        }
      }
      compose.onNodeWithTag(AppTestTags.ORDERS_BUTTON).performClick()
      compose
          .onNodeWithTag(MyOrdersTestTags.ORDER_CARD_PREFIX + "persistent-order")
          .assertIsDisplayed()
      compose.onNodeWithText("Demo orders", substring = true).assertDoesNotExist()
      compose.runOnIdle { verify(atLeast = 1) { query.get(Source.SERVER) } }
    } finally {
      unmockkStatic(FirebaseFirestore::class)
    }
  }
}
