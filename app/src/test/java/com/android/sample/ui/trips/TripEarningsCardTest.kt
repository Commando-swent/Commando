package com.android.sample.ui.trips

// AI assistance: Claude Code.
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.android.sample.data.repository.OrderError
import com.commando.app.model.Location
import com.commando.app.model.Trip
import com.commando.app.model.TripEarnings
import com.commando.app.model.TripStatus
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-rUS")
class TripEarningsCardTest {
  @get:Rule val compose = createComposeRule()
  private val time = Instant.parse("2026-11-13T12:00:00Z")
  private val trip =
      Trip(
          id = "trip-1",
          ownerId = "owner",
          store = Location("Migros", 46.52, 6.63),
          scheduledAt = time,
          handoffLocation = Location("EPFL", 46.52, 6.57),
          status = TripStatus.COMPLETED,
          createdAt = time,
          updatedAt = time,
      )

  private fun content(totalCents: Long, count: Int) =
      TripEarningsUiState.Content(TripEarnings(totalCents, count))

  private fun showOwnerDetails(earnings: TripEarningsUiState) {
    compose.setContent {
      MaterialTheme {
        TripDetailsScreen(
            state = TripDetailsUiState.Content(trip),
            onRetry = {},
            onBack = {},
            onAddItems = null,
            ownerControls = TripOwnerControls({}, {}, {}, earnings = earnings),
        )
      }
    }
  }

  @Test
  fun cardShowsLoadingThenAmountAndDeliveredCount() {
    val state = mutableStateOf<TripEarningsUiState>(TripEarningsUiState.Loading)
    compose.setContent { MaterialTheme { TripEarningsCard(state.value, onRetry = {}) } }
    compose.onNodeWithText("Your earnings on this trip").assertIsDisplayed()
    compose.onNodeWithText("Loading earnings…").assertIsDisplayed()
    compose.runOnIdle { state.value = content(1_250L, 1) }
    compose.onNodeWithTag(TripEarningsTestTags.AMOUNT).assertTextEquals("CHF 12.50")
    compose
        .onNodeWithTag(TripEarningsTestTags.DELIVERED_COUNT)
        .assertTextEquals("Commission from 1 delivered order")
    compose.runOnIdle { state.value = content(0L, 0) }
    compose.onNodeWithTag(TripEarningsTestTags.AMOUNT).assertTextEquals("CHF 0.00")
    compose
        .onNodeWithTag(TripEarningsTestTags.DELIVERED_COUNT)
        .assertTextEquals("Commission from 0 delivered orders")
    compose.onNodeWithText("Loading earnings…").assertDoesNotExist()
  }

  @Test
  fun errorRetriesOnlyTheEarnings() {
    var retries = 0
    compose.setContent {
      MaterialTheme {
        TripEarningsCard(TripEarningsUiState.Error(OrderError.Unknown), onRetry = { retries++ })
      }
    }
    compose.onNodeWithText("Earnings unavailable. Try again.").assertIsDisplayed()
    compose.onNodeWithTag(TripEarningsTestTags.AMOUNT).assertDoesNotExist()
    compose.onNodeWithTag(TripEarningsTestTags.RETRY).performClick()
    compose.runOnIdle { assertEquals(1, retries) }
  }

  @Test
  @Config(qualifiers = "fr-rFR")
  fun cardIsTranslatedInFrench() {
    val state = mutableStateOf<TripEarningsUiState>(TripEarningsUiState.Loading)
    compose.setContent { MaterialTheme { TripEarningsCard(state.value, onRetry = {}) } }
    compose.onNodeWithText("Vos gains sur ce trajet").assertIsDisplayed()
    compose.onNodeWithText("Chargement des gains…").assertIsDisplayed()
    compose.runOnIdle { state.value = content(650L, 2) }
    compose
        .onNodeWithTag(TripEarningsTestTags.DELIVERED_COUNT)
        .assertTextEquals("Commission de 2 commandes livrées")
    compose.runOnIdle { state.value = TripEarningsUiState.Error(OrderError.Unknown) }
    compose.onNodeWithText("Gains indisponibles. Réessayez.").assertIsDisplayed()
  }

  @Test
  fun ownerSeesTheEarningsEvenOnceTheTripHasEnded() {
    showOwnerDetails(content(650L, 2))
    compose.onNodeWithTag(TripEarningsTestTags.CARD).assertIsDisplayed()
    compose.onNodeWithTag(TripEarningsTestTags.AMOUNT).assertTextEquals("CHF 6.50")
    // A completed trip has no transition left, but its earnings stay visible.
    compose.onNodeWithTag(TripOwnerTestTags.TRANSITION).assertDoesNotExist()
  }
}
