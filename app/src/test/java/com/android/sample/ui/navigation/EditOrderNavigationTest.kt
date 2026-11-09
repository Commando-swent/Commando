package com.android.sample.ui.navigation

// AI assistance: OpenAI Codex (#232).
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.credentials.Credential
import com.android.sample.data.repository.*
import com.android.sample.model.*
import com.android.sample.model.authentication.*
import com.android.sample.ui.auth.GoogleCredentialClient
import com.android.sample.ui.catalogue.CatalogueTestTags
import com.android.sample.ui.catalogue.StubProductRepository
import com.android.sample.ui.orders.*
import com.android.sample.ui.theme.SampleAppTheme
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-rUS-w412dp-h915dp-mdpi")
class EditOrderNavigationTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
  private val now = Instant.now()
  private val spot = Location("Migros EPFL", 46.5, 6.5)
  private val trip =
      Trip(
          "trip",
          "owner",
          spot,
          now.plusSeconds(3600),
          spot,
          TripStatus.PUBLISHED,
          now,
          now,
      )
  private val order =
      Order(
          "saved % order",
          "trip",
          "requester",
          listOf(OrderItem("milk", "Milk", 220, 2)),
          150,
          OrderStatus.PENDING,
          now,
          revision = 3,
      )
  private val auth = FakeAuthRepository(AuthUser("requester"))
  private val trips = FakeTripRepository("owner", initialTrips = listOf(trip))
  private val products =
      StubProductRepository(ProductResult.Success(listOf(Product("milk", "migros", "Milk", 220))))
  private val repo =
      FakeOrderRepository({ auth.currentUser?.uid }, listOf(order), tripRepository = trips)
  private val credentials =
      object : GoogleCredentialClient {
        override suspend fun request(context: Context): Credential? = null

        override suspend fun clearSession() {}
      }
  private val content: @Composable () -> Unit = {
    SampleAppTheme {
      CommandoApp(
          auth,
          trips,
          credentials,
          productRepository = products,
          orderRepositoryFactory = { repo },
      )
    }
  }

  private fun click(tag: String) {
    val node = compose.onNodeWithTag(tag)
    if (tag == OrderDetailsTestTags.EDIT) node.performScrollTo()
    node.performClick()
  }

  private fun openEdit() {
    compose.setContent(content)
    compose.onNodeWithTag(AppTestTags.ORDERS_BUTTON).performClick()
    compose.onNodeWithTag(MyOrdersTestTags.ORDER_CARD_PREFIX + order.id).performClick()
    click(OrderDetailsTestTags.EDIT)
    click(CatalogueTestTags.CONTINUE)
  }

  @Test
  fun editReusesCatalogueAndReviewThenReloadsDetailsAndHistory() {
    openEdit()
    compose.onNodeWithTag("increase_milk").performScrollTo().performClick()
    compose
        .onNodeWithTag(ReviewOrderTags.COMMISSION)
        .performScrollTo()
        .performTextReplacement("2.00")
    compose.onNodeWithTag(ReviewOrderTags.SUBMIT).performClick()
    compose
        .onNodeWithTag(ReviewOrderTags.SUBMIT)
        .assertTextContains("View saved order")
        .performClick()
    compose.onNodeWithTag(OrderDetailsTestTags.EDIT).assertExists()
    val stored = runBlocking { (repo.getOrderById(order.id) as OrderResult.Success).data }
    assertEquals(
        order.copy(
            items = order.items.map { it.copy(quantity = 3) },
            commissionCents = 200,
            revision = 4,
        ),
        stored,
    )
    assertEquals(1, runBlocking { (repo.getMyOrders() as OrderResult.Success).data.size })
    compose.onNodeWithContentDescription("Back").performClick()
    compose.onNodeWithTag(AppTestTags.ORDERS_BUTTON).performClick()
    compose.onNodeWithText("Total: CHF 8.60").assertIsDisplayed()
  }

  @Test
  fun cancellationAfterOpeningDetailsBlocksEntryIntoTheEditBasket() {
    compose.setContent(content)
    compose.onNodeWithTag(AppTestTags.ORDERS_BUTTON).performClick()
    compose.onNodeWithTag(MyOrdersTestTags.ORDER_CARD_PREFIX + order.id).performClick()
    compose.onNodeWithTag(OrderDetailsTestTags.EDIT).assertExists()
    runBlocking { repo.cancelOrder(order.id) }
    click(OrderDetailsTestTags.EDIT)
    compose
        .onNodeWithText(
            "This order can no longer be edited. Only pending orders on open trips can be changed."
        )
        .assertIsDisplayed()
    compose.onNodeWithTag(CatalogueTestTags.CONTINUE).assertDoesNotExist()
  }

  @Test
  fun activityRecreationRetainsEditIdentityAndAbandonDoesNotWrite() {
    openEdit()
    compose.onNodeWithTag("increase_milk").performScrollTo().performClick()
    compose.activityRule.scenario.recreate()
    compose.activityRule.scenario.onActivity { it.setContent(content = content) }
    compose.onNodeWithTag(ReviewOrderTags.SUBMIT).assertTextContains("Confirm changes")
    compose.onNodeWithContentDescription("Back").performClick()
    compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
    compose.onNodeWithTag(OrderDetailsTestTags.EDIT).performScrollTo().performClick()
    click(CatalogueTestTags.CONTINUE)
    compose.onNodeWithText("2").assertExists()
    assertEquals(OrderResult.Success(order), runBlocking { repo.getOrderById(order.id) })
  }
}
