package com.android.sample

// AI assistance: OpenAI Codex (review screenshot capture).

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.android.sample.data.repository.*
import com.android.sample.model.*
import com.android.sample.model.authentication.*
import com.android.sample.ui.orders.*
import com.android.sample.ui.theme.SampleAppTheme
import java.io.File
import java.time.Instant
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Manual screenshot fixture using isolated in-memory order data. */
@RunWith(AndroidJUnit4::class)
class EditOrderReviewCaptureTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
  private val now = Instant.parse("2030-01-01T10:00:00Z")
  private val place = Location("Migros EPFL", 46.5, 6.5)
  private val trip = Trip("trip", "owner", place, now.plusSeconds(3600), Location("Rolex Learning Center", 46.5, 6.5), TripStatus.PUBLISHED, now, now)
  private val order = Order("saved", "trip", "requester", listOf(OrderItem("milk", "Milk", 220, 2)), 150, OrderStatus.PENDING, now, 3)
  private val trips = FakeTripRepository("owner", initialTrips = listOf(trip))

  private fun capture(name: String) {
    compose.waitForIdle()
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    instrumentation.waitForIdleSync()
    val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
    File(instrumentation.targetContext.filesDir, "$name.png").outputStream().use {
      check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
    }
  }

  @Test fun editButton() {
    compose.setContent { SampleAppTheme {
      OrderDetailsScreen(OrderDetailsUiState.Content(order, trip, viewerId = "requester"), {}, {}, onEdit = {})
    } }
    compose.onNodeWithTag(OrderDetailsTestTags.EDIT).assertIsDisplayed()
    capture("edit-button")
  }

  @Test fun review() {
    val repo = FakeOrderRepository({ "requester" }, listOf(order), { now }, tripRepository = trips)
    val draft = OrderDraftState(trip.id, OrderDraft(trip.id, order.items, 150), order.revision)
    val model = ReviewOrderViewModel(repo, draft, order.id)
    compose.setContent { SampleAppTheme { ReviewOrderRoute(model, trip, {}, {}, {}) } }
    compose.onNodeWithTag("increase_milk").performScrollTo().performClick()
    compose.onNodeWithTag(ReviewOrderTags.SUBMIT).assertTextContains("Confirm changes")
    capture("edit-review")
  }

  @Test fun conflict() {
    val fake = FakeOrderRepository({ "requester" }, listOf(order), { now }, tripRepository = trips)
    val repo = object : OrderRepository by fake {
      override suspend fun editOrder(orderId: String, draft: OrderDraft, expectedRevision: Int): OrderResult<Order> =
        OrderResult.Error(OrderError.AlreadyDecided(order.copy(revision = 4)))
    }
    val model = ReviewOrderViewModel(repo, OrderDraftState(trip.id, OrderDraft(trip.id, order.items, 150), order.revision), order.id)
    compose.setContent { SampleAppTheme { ReviewOrderRoute(model, trip, {}, {}, {}) } }
    compose.onNodeWithTag(ReviewOrderTags.SUBMIT).performClick()
    compose.onNodeWithTag(ReviewOrderTags.ERROR).assertIsDisplayed()
    capture("edit-conflict")
  }

  @Test fun unavailable() {
    val fake = FakeOrderRepository({ "requester" }, listOf(order.copy(status = OrderStatus.CANCELLED)), { now }, tripRepository = trips)
    val session = SessionOrderViewModel("requester", FakeAuthRepository(AuthUser("requester"))) { fake }
    compose.setContent { SampleAppTheme { OrderFlowDraft(session, trips, trip.id, order.id, {}) {} } }
    compose.onNodeWithText("This order can no longer be edited. Only pending orders on open trips can be changed.").assertIsDisplayed()
    capture("edit-unavailable")
  }
}
