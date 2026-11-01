package com.android.sample.ui.orders

// AI assistance: Claude Code.
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.android.sample.data.repository.OrderError
import com.android.sample.model.ShoppingChecklistItem
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-rUS")
class ShoppingChecklistScreenTest {
  @get:Rule val compose = createComposeRule()
  private val milk = ShoppingChecklistItem("milk", "Milk", 3, purchased = false)
  private val bread = ShoppingChecklistItem("bread", "Bread", 1, purchased = true)
  private var refreshes = 0
  private val toggles = mutableListOf<Pair<String, Boolean>>()

  private fun show(state: ShoppingChecklistUiState) = compose.setContent {
    MaterialTheme {
      ShoppingChecklistScreen(
          state,
          { refreshes++ },
          { item, value -> toggles += item.productId to value },
      )
    }
  }

  private fun tag(tag: String) = compose.onNodeWithTag(tag)

  private fun row(item: ShoppingChecklistItem) =
      tag(ShoppingChecklistTestTags.ITEM_PREFIX + item.productId)

  @Test
  fun contentShowsProgressAndTogglesTheTappedItem() {
    show(ShoppingChecklistUiState.Content(listOf(bread, milk)))
    compose.onNodeWithText("Shopping list").assertIsDisplayed()
    tag(ShoppingChecklistTestTags.PROGRESS).assertTextEquals("1 of 2 purchased")
    row(milk).assertIsOff().assert(hasText("Milk")).assert(hasText("Qty 3")).performClick()
    row(bread).assertIsOn().performClick()
    tag(ShoppingChecklistTestTags.SAVE_ERROR).assertDoesNotExist()
    compose.runOnIdle { assertEquals(listOf("milk" to true, "bread" to false), toggles) }
  }

  @Test
  fun saveErrorIsShown() {
    show(ShoppingChecklistUiState.Content(listOf(milk), OrderError.Unknown))
    tag(ShoppingChecklistTestTags.SAVE_ERROR)
        .assertTextEquals("This item could not be saved. Please try again.")
  }

  @Test
  fun loadingStateIsAccessible() {
    show(ShoppingChecklistUiState.Loading)
    tag(ShoppingChecklistTestTags.LOADING)
        .assertContentDescriptionEquals("Loading the shopping list")
  }

  @Test
  fun emptyAndErrorStatesReload() {
    var state by mutableStateOf<ShoppingChecklistUiState>(ShoppingChecklistUiState.Empty)
    compose.setContent {
      MaterialTheme { ShoppingChecklistScreen(state, { refreshes++ }, { _, _ -> }) }
    }
    compose.onNodeWithText("No accepted orders on this trip yet.").assertIsDisplayed()
    tag(ShoppingChecklistTestTags.REFRESH).performClick()
    state = ShoppingChecklistUiState.Error(OrderError.Unknown)
    tag(ShoppingChecklistTestTags.ERROR).assertIsDisplayed()
    tag(ShoppingChecklistTestTags.RETRY).performClick()
    compose.runOnIdle { assertEquals(2, refreshes) }
  }
}
