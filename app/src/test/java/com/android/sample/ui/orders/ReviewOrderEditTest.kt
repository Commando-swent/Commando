package com.android.sample.ui.orders

// AI assistance: OpenAI Codex (#232).
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
@Config(sdk = [34], qualifiers = "en-rUS-w320dp-h640dp-mdpi")
class ReviewOrderEditTest {
  @get:Rule val compose = createComposeRule()
  private val now = Instant.parse("2030-01-01T10:00:00Z")
  private val location = Location("Migros", 46.5, 6.5)
  private val trip =
      Trip(
          "trip",
          "owner",
          location,
          now.plusSeconds(3600),
          location,
          TripStatus.PUBLISHED,
          now,
          now,
      )
  private val order =
      Order(
          "saved",
          "trip",
          "requester",
          listOf(OrderItem("milk", "Milk", 220, 2)),
          150,
          OrderStatus.PENDING,
          now,
          revision = 3,
      )
  private val shared = OrderDraftState("trip", OrderDraft("trip", order.items, 150), order.revision)
  private val fake =
      FakeOrderRepository(
          { "requester" },
          listOf(order),
          { now },
          tripRepository = FakeTripRepository("owner", initialTrips = listOf(trip)),
      )
  private lateinit var model: ReviewOrderViewModel
  private val opened = mutableListOf<String>()

  private fun show(repository: OrderRepository) {
    model = ReviewOrderViewModel(repository, shared, "saved")
    compose.setContent { SampleAppTheme { ReviewOrderRoute(model, trip, {}, {}, opened::add) } }
  }

  @Test
  fun editFailureKeepsDraftAndRetrySavesTheSameOrderOnce() {
    val gate = CompletableDeferred<OrderResult<Order>>()
    var calls = 0
    val repo =
        object : OrderRepository by fake {
          override suspend fun createOrder(draft: OrderDraft): OrderResult<Order> =
              error("Editing must not create")

          override suspend fun editOrder(
              orderId: String,
              draft: OrderDraft,
              expectedRevision: Int,
          ): OrderResult<Order> {
            assertEquals("saved", orderId)
            assertEquals(order.revision, expectedRevision)
            return if (++calls == 1) gate.await()
            else fake.editOrder(orderId, draft, expectedRevision)
          }
        }
    show(repo)
    compose.onNodeWithTag("increase_milk").performScrollTo().performClick()
    compose
        .onNodeWithTag(ReviewOrderTags.SUBMIT)
        .assertTextContains("Confirm changes")
        .performClick()
    compose.onNodeWithTag(ReviewOrderTags.SUBMIT).assertIsNotEnabled()
    compose.runOnIdle {
      model.submit()
      assertEquals(1, calls)
    }
    gate.complete(OrderResult.Error(OrderError.Unknown))
    compose.waitUntil { !model.uiState.value.saving }
    assertEquals(3, shared.draft.value.items.single().quantity)
    compose.onNodeWithTag(ReviewOrderTags.ERROR).assertIsDisplayed()
    compose.onNodeWithTag(ReviewOrderTags.SUBMIT).performClick()
    compose.waitUntil { model.uiState.value.order != null }
    val expected = order.copy(items = order.items.map { it.copy(quantity = 3) }, revision = 4)
    assertEquals(expected, model.uiState.value.order)
    assertEquals(2, calls)
    assertTrue(shared.draft.value.items.isEmpty())
    compose.onNodeWithTag(ReviewOrderTags.SUBMIT).performClick()
    assertEquals(listOf("saved"), opened)
  }

  @Test
  fun conflictShowsSavedOrderWithoutClearingOrResubmittingTheDraft() {
    var calls = 0
    val persisted = order.copy(commissionCents = 8000, revision = 4)
    show(
        object : OrderRepository by fake {
          override suspend fun editOrder(
              orderId: String,
              draft: OrderDraft,
              expectedRevision: Int,
          ): OrderResult<Order> {
            calls++
            return OrderResult.Error(OrderError.AlreadyDecided(persisted))
          }
        }
    )
    compose.onNodeWithTag(ReviewOrderTags.SUBMIT).performClick()
    compose
        .onNodeWithTag(ReviewOrderTags.ERROR)
        .assertTextContains("changed while you were editing", substring = true)
    assertEquals(order.items, shared.draft.value.items)
    compose.runOnIdle {
      model.submit()
      assertEquals(1, calls)
    }
    compose
        .onNodeWithTag(ReviewOrderTags.SUBMIT)
        .assertTextContains("View saved order")
        .performClick()
    assertEquals(listOf("saved"), opened)
  }
}
