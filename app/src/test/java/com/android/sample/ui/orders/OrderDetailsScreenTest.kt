package com.android.sample.ui.orders

// AI assistance: OpenAI Codex.

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.android.sample.data.repository.OrderError
import com.android.sample.data.repository.TripError
import com.android.sample.model.*
import com.android.sample.ui.theme.SampleAppTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-rUS")
class OrderDetailsScreenTest {
  @get:Rule val compose = createComposeRule()
  private lateinit var originalTimeZone: TimeZone
  private val time = Instant.parse("2026-10-13T16:30:00Z")
  private val trip =
      Trip(
          "trip",
          "commando",
          Location("Stored store", 46.52, 6.57),
          time,
          Location("Stored handoff", 46.51, 6.58),
          TripStatus.COMPLETED,
          time,
          time,
      )
  private val order =
      Order(
          "order",
          trip.id,
          "requester",
          listOf(
              OrderItem("milk", "Stored oat milk", 220, 2),
              OrderItem("bread", "Stored bread", 180, 3),
          ),
          405,
          OrderStatus.PENDING,
          time,
      )
  private val state = mutableStateOf<OrderDetailsUiState>(OrderDetailsUiState.Content(order, trip))
  private var backs = 0
  private var retries = 0
  private var readyRequests = 0
  private var cancellations = 0
  private val decisions = mutableListOf<OrderDecision>()

  @Before
  fun setUp() {
    originalTimeZone = TimeZone.getDefault()
    TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
  }

  @Test
  fun cancellationRequiresConfirmationAndCanBeDismissed() {
    show(OrderDetailsUiState.Content(order, trip, viewerId = order.requesterId))
    compose.onNodeWithTag(OrderDetailsTestTags.CANCEL).performScrollTo().performClick()
    compose.onNodeWithText("Cancel this order?").assertIsDisplayed()
    compose.onNodeWithTag(OrderDetailsTestTags.KEEP_ORDER).performClick()
    compose.runOnIdle { assertEquals(0, cancellations) }
    compose.onNodeWithTag(OrderDetailsTestTags.CANCEL).performClick()
    compose.onNodeWithTag(OrderDetailsTestTags.CONFIRM_CANCEL).performClick()
    compose.runOnIdle { assertEquals(1, cancellations) }
    compose.onNodeWithText("Cancel this order?").assertDoesNotExist()
  }

  @Test
  @Config(qualifiers = "en-rUS-w320dp-h320dp-mdpi")
  fun cancellationRemainsReachableWithLargeTextAndUsesConfirmedStatus() {
    val content = OrderDetailsUiState.Content(order, trip, order.requesterId)
    show(content, fontScale = 1.5f)
    compose.onRoot().assertWidthIsEqualTo(320.dp).assertHeightIsEqualTo(320.dp)
    val cancel = compose.onNodeWithTag(OrderDetailsTestTags.CANCEL)
    cancel.performScrollTo().assertIsDisplayed().performClick()
    compose.onNodeWithTag(OrderDetailsTestTags.CONFIRM_CANCEL).assertIsDisplayed().performClick()
    compose.runOnIdle {
      assertEquals(1, cancellations)
      state.value = content.copy(mutation = OrderMutationState.Saving)
    }
    cancel.performScrollTo().assertIsNotEnabled().performClick()
    compose.runOnIdle {
      assertEquals(1, cancellations)
      state.value =
          content.copy(
              order = order.copy(status = OrderStatus.CANCELLED),
              mutation = OrderMutationState.Saved,
          )
    }
    cancel.assertDoesNotExist()
    compose.onNodeWithText("CANCELLED").assertIsDisplayed()
    compose.onNodeWithText("Order status updated.").performScrollTo().assertIsDisplayed()
  }

  @Test
  fun cancellationIsAvailableOnlyToThePendingOrdersRequester() {
    show()
    for (viewer in listOf(null, trip.ownerId, "outsider", order.requesterId)) for (status in
        OrderStatus.entries) {
      compose.runOnIdle {
        state.value = OrderDetailsUiState.Content(order.copy(status = status), trip, viewer)
      }
      val action = compose.onNodeWithTag(OrderDetailsTestTags.CANCEL)
      if (viewer == order.requesterId && status == OrderStatus.PENDING) action.assertExists()
      else action.assertDoesNotExist()
    }
  }

  @Test
  fun interactiveCancellationPreviewSupportsKeepingAndConfirmingWithoutFirebase() {
    compose.setContent { OrderCancellationPendingPreview() }
    val cancel = compose.onNodeWithTag(OrderDetailsTestTags.CANCEL)
    cancel.performScrollTo().performClick()
    compose.onNodeWithTag(OrderDetailsTestTags.KEEP_ORDER).performClick()
    compose.onNodeWithText("PENDING").assertIsDisplayed()
    cancel.performScrollTo().performClick()
    compose.onNodeWithTag(OrderDetailsTestTags.CONFIRM_CANCEL).performClick()
    compose.onNodeWithText("CANCELLED").assertIsDisplayed()
    compose.onNodeWithText("Order status updated.").performScrollTo().assertIsDisplayed()
    cancel.assertDoesNotExist()
  }

  @Test
  fun cancelledPreviewKeepsTheOrderContentAndOffersNoFurtherCancellation() {
    compose.setContent { OrderCancellationConfirmedPreview() }
    compose.onNodeWithText("CANCELLED").assertIsDisplayed()
    compose.onNodeWithText("Migros EPFL").assertIsDisplayed()
    compose.onNodeWithText("2 × Milk").performScrollTo().assertIsDisplayed()
    compose.onNodeWithTag(OrderDetailsTestTags.CANCEL).assertDoesNotExist()
  }

  @Test
  fun sessionEndRemovesAnOpenCancellationDialogWithoutSubmitting() {
    show(OrderDetailsUiState.Content(order, trip, order.requesterId))
    compose.onNodeWithTag(OrderDetailsTestTags.CANCEL).performScrollTo().performClick()
    compose.onNodeWithTag(OrderDetailsTestTags.CONFIRM_CANCEL).assertIsDisplayed()
    compose.runOnIdle { state.value = OrderDetailsUiState.SessionEnded }
    compose.onNodeWithTag(OrderDetailsTestTags.CONFIRM_CANCEL).assertDoesNotExist()
    compose.onNodeWithTag(OrderDetailsTestTags.CANCEL).assertDoesNotExist()
    compose.runOnIdle { assertEquals(0, cancellations) }
  }

  @After
  fun tearDown() {
    TimeZone.setDefault(originalTimeZone)
  }

  private fun departure() =
      DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
          .withLocale(Locale.US)
          .withZone(ZoneId.of("UTC"))
          .format(time)

  private fun show(initial: OrderDetailsUiState = state.value, fontScale: Float? = null) {
    state.value = initial
    compose.setContent {
      val density = LocalDensity.current
      CompositionLocalProvider(
          LocalDensity provides Density(density.density, fontScale ?: density.fontScale)
      ) {
        SampleAppTheme {
          OrderDetailsScreen(
              state.value,
              onBack = { backs++ },
              onRetry = { retries++ },
              onDecision = { decisions += it },
              onMarkReadyForHandoff = { readyRequests++ },
              onCancel = { cancellations++ },
          )
        }
      }
    }
  }

  private fun backWorks() {
    compose
        .onNodeWithContentDescription("Back")
        .assertIsDisplayed()
        .assertHasClickAction()
        .performClick()
    compose.runOnIdle { assertEquals(1, backs) }
  }

  private fun assertProtectedContentAbsent() {
    for (text in
        listOf(
            "Stored oat milk",
            "Stored bread",
            "Stored store",
            "Stored handoff",
            departure(),
            "CHF 4.05",
            "PENDING",
        )) {
      compose.onAllNodesWithText(text, substring = true).assertCountEquals(0)
    }
  }

  @Test
  fun ownerActionsDispatchBothDecisionsAndSavingDisablesThem() {
    val content = OrderDetailsUiState.Content(order, trip, viewerId = trip.ownerId)
    show(content)
    compose.onNodeWithTag(OrderDetailsTestTags.MARK_READY_FOR_HANDOFF).assertDoesNotExist()
    compose.onNodeWithText("Accept").performScrollTo().assertIsEnabled().performClick()
    compose.onNodeWithText("Reject").performScrollTo().assertIsEnabled().performClick()
    compose.runOnIdle {
      assertEquals(listOf(OrderDecision.ACCEPT, OrderDecision.REJECT), decisions)
      state.value = content.copy(mutation = OrderMutationState.Saving)
    }
    compose.onNodeWithText("Accept").performScrollTo().assertIsNotEnabled()
    compose.onNodeWithText("Reject").performScrollTo().assertIsNotEnabled()
    compose.onNodeWithText("Saving order status\u2026").performScrollTo().assertIsDisplayed()
    compose.runOnIdle {
      state.value =
          content.copy(
              order = order.copy(status = OrderStatus.ACCEPTED),
              mutation = OrderMutationState.Saved,
          )
    }
    compose.onNodeWithText("Accept").assertDoesNotExist()
    compose.onNodeWithText("Reject").assertDoesNotExist()
    compose.onNodeWithText("Order status updated.").performScrollTo().assertIsDisplayed()
    compose.onNodeWithText("ACCEPTED").assertExists()
  }

  @Test
  fun requesterUnrelatedAndFinalizedOwnerContentHaveNoDecisionActions() {
    show()
    for (content in
        listOf(
            OrderDetailsUiState.Content(order, trip, "requester"),
            OrderDetailsUiState.Content(order, trip, "stranger"),
            OrderDetailsUiState.Content(
                order.copy(status = OrderStatus.ACCEPTED),
                trip,
                "requester",
            ),
            OrderDetailsUiState.Content(
                order.copy(status = OrderStatus.ACCEPTED),
                trip,
                "stranger",
            ),
            OrderDetailsUiState.Content(
                order.copy(status = OrderStatus.REJECTED),
                trip,
                trip.ownerId,
            ),
            OrderDetailsUiState.Content(order.copy(requesterId = trip.ownerId), trip, trip.ownerId),
        )) {
      compose.runOnIdle { state.value = content }
      compose.onNodeWithText("Accept").assertDoesNotExist()
      compose.onNodeWithText("Reject").assertDoesNotExist()
      compose.onNodeWithTag(OrderDetailsTestTags.MARK_READY_FOR_HANDOFF).assertDoesNotExist()
    }
  }

  @Test
  fun conflictShowsLatestStatusAndFeedbackWhileFailureKeepsActions() {
    val content = OrderDetailsUiState.Content(order, trip, trip.ownerId)
    show(content.copy(mutation = OrderMutationState.Failed(OrderError.Unknown)))
    compose
        .onNodeWithText("Could not update the order status. Check your connection and try again.")
        .performScrollTo()
        .assertIsDisplayed()
    compose.onNodeWithText("Accept").performScrollTo().assertIsEnabled()
    val persisted = order.copy(status = OrderStatus.REJECTED)
    compose.runOnIdle {
      state.value =
          content.copy(
              order = persisted,
              mutation = OrderMutationState.Failed(OrderError.AlreadyDecided(persisted)),
          )
    }
    compose
        .onNodeWithText("This action is no longer available. The latest order status is shown.")
        .performScrollTo()
        .assertIsDisplayed()
    compose.onNodeWithText("REJECTED").assertExists()
    compose.onNodeWithText("Accept").assertDoesNotExist()
  }

  @Test
  fun contentDisplaysStoredFieldsAndHasOnlyBackAsAnAction() {
    assertReadOnlyContent(OrderStatus.PENDING, "PENDING")
  }

  @Test
  fun acceptedContentDisplaysStoredFieldsAndHasOnlyBackAsAnAction() {
    assertReadOnlyContent(OrderStatus.ACCEPTED, "ACCEPTED")
  }

  @Test
  fun rejectedContentDisplaysStoredFieldsAndHasOnlyBackAsAnAction() {
    assertReadOnlyContent(OrderStatus.REJECTED, "REJECTED")
  }

  @Test
  fun acceptedOwnerCanRequestReadinessAndSavingDisablesTheAction() {
    val content =
        OrderDetailsUiState.Content(order.copy(status = OrderStatus.ACCEPTED), trip, trip.ownerId)
    show(content)
    val ready = compose.onNodeWithTag(OrderDetailsTestTags.MARK_READY_FOR_HANDOFF)
    ready.performScrollTo().assertIsDisplayed().assertIsEnabled().performClick()
    compose.runOnIdle {
      assertEquals(1, readyRequests)
      state.value = content.copy(mutation = OrderMutationState.Saving)
    }
    ready.performScrollTo().assertIsNotEnabled()
    compose.onNodeWithTag(OrderDetailsTestTags.ACCEPT).assertDoesNotExist()
    compose.onNodeWithTag(OrderDetailsTestTags.REJECT).assertDoesNotExist()
  }

  @Test
  fun readyForHandoffOwnerContentDisplaysStatusWithoutDecisionActions() {
    show(
        OrderDetailsUiState.Content(
            order.copy(status = OrderStatus.READY_FOR_HANDOFF),
            trip,
            viewerId = trip.ownerId,
        )
    )
    compose.onNodeWithText("READY FOR HANDOFF").assertIsDisplayed()
    compose.onAllNodes(hasClickAction()).assertCountEquals(1)
    compose.onNodeWithText("Accept").assertDoesNotExist()
    compose.onNodeWithText("Reject").assertDoesNotExist()
  }

  @Test
  @Config(qualifiers = "fr-rFR")
  fun readyForHandoffStatusUsesFrenchLabel() {
    show(OrderDetailsUiState.Content(order.copy(status = OrderStatus.ACCEPTED), trip, trip.ownerId))
    compose
        .onNodeWithText("Marquer comme prête à être remise")
        .performScrollTo()
        .assertIsDisplayed()
    compose.runOnIdle {
      state.value =
          OrderDetailsUiState.Content(order.copy(status = OrderStatus.READY_FOR_HANDOFF), trip)
    }
    compose.onNodeWithText("PRÊTE À ÊTRE REMISE").assertIsDisplayed()
  }

  private fun assertReadOnlyContent(status: OrderStatus, label: String) {
    show(OrderDetailsUiState.Content(order.copy(status = status), trip))
    for (text in
        listOf(
            "Stored store",
            departure(),
            "Stored handoff",
            "2 × Stored oat milk",
            "3 × Stored bread",
            "CHF 4.05",
        )) {
      compose.onNodeWithText(text).performScrollTo().assertIsDisplayed()
    }
    compose.onAllNodesWithText(label).assertCountEquals(1)
    compose.onNodeWithText(label).assertIsDisplayed()
    // Exhaustive action checks also exclude editing, Accept/Reject and submission controls.
    compose.onAllNodes(hasClickAction()).assertCountEquals(1)
    compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.SetText)).assertCountEquals(0)
    backWorks()
  }

  @Test
  fun loadingHasAccessibleProgressWithoutProtectedContent() {
    show(OrderDetailsUiState.Loading)
    compose
        .onNodeWithContentDescription("Loading order details")
        .assertIsDisplayed()
        .assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.ProgressBarRangeInfo,
                ProgressBarRangeInfo.Indeterminate,
            )
        )
        .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
    assertProtectedContentAbsent()
    compose.onNodeWithText("Retry").assertDoesNotExist()
    backWorks()
  }

  @Test
  fun notFoundOffersBackWithoutRetryOrProtectedContent() {
    show(OrderDetailsUiState.NotFound)
    compose.onNodeWithText("This order could not be found.").assertIsDisplayed()
    compose.onNodeWithText("Retry").assertDoesNotExist()
    assertProtectedContentAbsent()
    backWorks()
  }

  @Test
  fun endedSessionRemovesSavingContentAndOffersOnlyBack() {
    show(
        OrderDetailsUiState.Content(order, trip, trip.ownerId, mutation = OrderMutationState.Saving)
    )
    compose.onNodeWithText("Saving order status\u2026").performScrollTo().assertIsDisplayed()
    compose.runOnIdle { state.value = OrderDetailsUiState.SessionEnded }
    compose
        .onNodeWithText("This session has ended. Sign in again to view your orders.")
        .performScrollTo()
        .assertIsDisplayed()
    assertProtectedContentAbsent()
    compose.onNodeWithText("Accept").assertDoesNotExist()
    compose.onNodeWithText("Reject").assertDoesNotExist()
    compose.onNodeWithText("Saving order status\u2026").assertDoesNotExist()
    compose.onNodeWithText("Retry").assertDoesNotExist()
    compose.onAllNodes(hasClickAction()).assertCountEquals(1)
    backWorks()
  }

  @Test
  fun authenticationFailureExplainsHowToRecoverWithoutProtectedContent() {
    show(OrderDetailsUiState.Error.OrderFailure(OrderError.NotAuthenticated))
    compose
        .onNodeWithText("Authentication is required. Sign in again to view this order.")
        .performScrollTo()
        .assertIsDisplayed()
    assertProtectedContentAbsent()
    compose.onNodeWithText("Retry").assertDoesNotExist()
    compose.onAllNodes(hasClickAction()).assertCountEquals(1)
    backWorks()
  }

  @Test
  fun orderFailureOffersWorkingRetryAndBack() {
    assertErrorActions(
        OrderDetailsUiState.Error.OrderFailure(OrderError.PermissionDenied),
        "You don't have permission to access or modify this order.",
    )
  }

  @Test
  fun unknownOrderFailureKeepsWorkingRetry() {
    assertErrorActions(OrderDetailsUiState.Error.OrderFailure(OrderError.Unknown))
  }

  @Test
  @Config(qualifiers = "fr-rFR")
  fun permissionFailureUsesGeneralFrenchMessage() {
    show(OrderDetailsUiState.Error.OrderFailure(OrderError.PermissionDenied))
    compose
        .onNodeWithText(
            "Vous n'avez pas l'autorisation de consulter ou de modifier cette commande."
        )
        .performScrollTo()
        .assertIsDisplayed()
    assertProtectedContentAbsent()
  }

  @Test
  fun tripFailureOffersWorkingRetryAndBack() {
    assertErrorActions(OrderDetailsUiState.Error.TripFailure(TripError.NetworkError))
  }

  private fun assertErrorActions(
      error: OrderDetailsUiState.Error,
      message: String = "Something went wrong while loading this order.",
  ) {
    show(error)
    compose.onNodeWithText(message).assertIsDisplayed()
    assertProtectedContentAbsent()
    compose.runOnIdle {
      assertEquals(0, retries)
      assertEquals(0, backs)
    }
    compose
        .onNodeWithText("Retry")
        .performScrollTo()
        .assertIsDisplayed()
        .assertIsEnabled()
        .performClick()
    compose.runOnIdle {
      assertEquals(1, retries)
      assertEquals(0, backs)
    }
    backWorks()
  }

  @Test
  fun switchingFromContentToNonContentStatesRemovesProtectedInformation() {
    show()
    for ((next, message) in
        listOf(
            OrderDetailsUiState.Loading to null,
            OrderDetailsUiState.NotFound to "This order could not be found.",
            OrderDetailsUiState.Error.OrderFailure(OrderError.PermissionDenied) to
                "You don't have permission to access or modify this order.",
        )) {
      compose.runOnIdle { state.value = OrderDetailsUiState.Content(order, trip) }
      compose.onNodeWithText("2 × Stored oat milk").performScrollTo().assertIsDisplayed()
      compose.onNodeWithText("Stored handoff").performScrollTo().assertIsDisplayed()
      compose.runOnIdle { state.value = next }
      assertProtectedContentAbsent()
      if (message != null) compose.onNodeWithText(message).performScrollTo().assertIsDisplayed()
      else
          compose
              .onNodeWithContentDescription("Loading order details")
              .performScrollTo()
              .assertIsDisplayed()
    }
    backWorks()
  }

  @Test
  @Config(qualifiers = "en-rUS-w320dp-h320dp-mdpi")
  fun longNamesAndLargeTextInShortViewportKeepContentAndBackReachable() {
    val name = "Stored wholegrain bread with sunflower seeds and a very long product description"
    val store = "Stored supermarket at the far end of the university shopping district"
    val handoff = "Stored handoff beside the main entrance of the university learning center"
    show(
        OrderDetailsUiState.Content(
            order.copy(items = listOf(OrderItem("long", name, 180, 12))),
            trip.copy(
                store = trip.store.copy(name = store),
                handoffLocation = trip.handoffLocation.copy(name = handoff),
            ),
        ),
        fontScale = 1.5f,
    )
    compose.onRoot().assertWidthIsEqualTo(320.dp).assertHeightIsEqualTo(320.dp)
    compose.onNodeWithContentDescription("Back").assertIsDisplayed()
    for (text in listOf(store, handoff)) {
      compose.onNodeWithText(text).performScrollTo().assertIsDisplayed()
    }
    compose.onNodeWithText("12 × $name").performScrollTo().assertIsDisplayed()
    compose.onNodeWithText("CHF 4.05").performScrollTo().assertIsDisplayed()
    backWorks()
  }

  @Test
  @Config(qualifiers = "en-rUS-w320dp-h320dp-mdpi")
  fun shortViewportKeepsErrorRetryAndBackReachable() {
    show(OrderDetailsUiState.Error.TripFailure(TripError.Unknown), fontScale = 1.5f)
    compose.onRoot().assertWidthIsEqualTo(320.dp).assertHeightIsEqualTo(320.dp)
    compose.onNodeWithContentDescription("Back").assertIsDisplayed()
    compose
        .onNodeWithText("Retry")
        .performScrollTo()
        .assertIsDisplayed()
        .assertIsEnabled()
        .performClick()
    compose.runOnIdle { assertEquals(1, retries) }
    backWorks()
  }
}
