package com.android.sample.ui.trips

// AI assistance: Claude Code.
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.sample.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CreateTripComponentsTest {

  @get:Rule val composeTestRule = createComposeRule()

  @Test
  fun textInputField_showsHelperAndPlaceholderThenTheTypedText() {
    var value by mutableStateOf("")
    composeTestRule.setContent { HandoffField(value, error = null, enabled = true) { value = it } }
    composeTestRule.onNodeWithText("Handoff location").assertExists()
    composeTestRule.onNodeWithText("e.g. Rolex Center").assertExists()
    composeTestRule
        .onNodeWithText("Where requesters meet you to collect their groceries.")
        .assertExists()

    composeTestRule.onNodeWithTag(INPUT).performTextInput("Rolex Center")

    composeTestRule.runOnIdle { assertEquals("Rolex Center", value) }
    composeTestRule.onNodeWithTag(INPUT).assertTextContains("Rolex Center")
    composeTestRule.onNodeWithTag(ERROR).assertDoesNotExist()
  }

  @Test
  fun textInputField_withErrorAndDisabled_showsTheMessageAndRejectsInput() {
    composeTestRule.setContent {
      HandoffField("", CreateTripError.HANDOFF_LOCATION_REQUIRED, enabled = false) {}
    }

    composeTestRule.onNodeWithTag(ERROR).assertExists()
    composeTestRule.onNodeWithText("Enter where you will hand over the groceries.").assertExists()
    composeTestRule
        .onNodeWithText("Where requesters meet you to collect their groceries.")
        .assertDoesNotExist()
    composeTestRule.onNodeWithTag(INPUT).assertIsNotEnabled()
  }

  @Test
  fun everyErrorHasAMessage() {
    val messages =
        mapOf(
            CreateTripError.STORES_REQUIRED to "Select at least one store.",
            CreateTripError.DATE_REQUIRED to "Choose a date.",
            CreateTripError.DATE_IN_PAST to "Choose today or a later date.",
            CreateTripError.TIME_REQUIRED to "Choose a time.",
            CreateTripError.TIME_IN_PAST to "That time has already passed.",
            CreateTripError.HANDOFF_LOCATION_REQUIRED to
                "Enter where you will hand over the groceries.",
            CreateTripError.MAX_ORDERS_REQUIRED to "Enter a whole number of 1 or more.",
            CreateTripError.MAX_ORDERS_NOT_POSITIVE_INTEGER to "Enter a whole number of 1 or more.",
        )
    assertEquals(CreateTripError.entries.toSet(), messages.keys)
    var error by mutableStateOf(CreateTripError.STORES_REQUIRED)
    composeTestRule.setContent { HandoffField("", error, enabled = true) {} }

    messages.forEach { (shown, message) ->
      error = shown
      composeTestRule.onNodeWithText(message).assertExists()
    }
  }

  @Test
  fun pickerField_showsPlaceholderOrValueAndOpensOnlyWhenEnabled() {
    var value by mutableStateOf<String?>(null)
    var enabled by mutableStateOf(true)
    var clicks = 0
    composeTestRule.setContent {
      PickerField(
          label = R.string.create_trip_time_label,
          icon = R.drawable.ic_trip_clock,
          value = value,
          placeholder = R.string.create_trip_time_placeholder,
          helper = R.string.create_trip_time_helper,
          error = null,
          enabled = enabled,
          onClick = { clicks++ },
          tag = INPUT,
          errorTag = ERROR,
      )
    }
    composeTestRule.onNodeWithText("Select time").assertExists()

    composeTestRule.onNodeWithTag(INPUT).performClick()
    value = "17:30"
    enabled = false
    composeTestRule.onNodeWithTag(INPUT).performClick()

    composeTestRule.onNodeWithText("17:30").assertExists()
    composeTestRule.runOnIdle { assertEquals(1, clicks) }
  }

  @Test
  fun storeChips_forwardRemoveAndAddOnlyWhenEnabled() {
    var enabled by mutableStateOf(true)
    val events = mutableListOf<String>()
    composeTestRule.setContent {
      Column {
        StoreChip("Coop", enabled) { events += "remove" }
        AddStoreChip(enabled) { events += "add" }
      }
    }
    composeTestRule.onNodeWithTag(CreateTripScreenTestTags.storeChip("Coop")).assertExists()

    composeTestRule.onNodeWithTag(CreateTripScreenTestTags.removeStoreButton("Coop")).performClick()
    composeTestRule.onNodeWithTag(CreateTripScreenTestTags.ADD_STORE_BUTTON).performClick()
    enabled = false
    composeTestRule.onNodeWithTag(CreateTripScreenTestTags.removeStoreButton("Coop")).performClick()
    composeTestRule.onNodeWithTag(CreateTripScreenTestTags.ADD_STORE_BUTTON).performClick()

    composeTestRule.runOnIdle { assertEquals(listOf("remove", "add"), events) }
  }

  @Composable
  private fun HandoffField(
      value: String,
      error: CreateTripError?,
      enabled: Boolean,
      onValueChange: (String) -> Unit,
  ) {
    TextInputField(
        label = R.string.create_trip_handoff_label,
        icon = R.drawable.ic_trip_map_pin,
        value = value,
        onValueChange = onValueChange,
        placeholder = R.string.create_trip_handoff_placeholder,
        helper = R.string.create_trip_handoff_helper,
        error = error,
        enabled = enabled,
        keyboardOptions = KeyboardOptions.Default,
        tag = INPUT,
        errorTag = ERROR,
    )
  }

  private companion object {
    const val INPUT = "input"
    const val ERROR = "error"
  }
}
