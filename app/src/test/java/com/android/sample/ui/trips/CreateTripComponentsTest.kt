package com.android.sample.ui.trips

// AI assistance: Claude Code.
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.sample.R
import com.android.sample.model.Location
import com.android.sample.model.TripLocations
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CreateTripComponentsTest {

  @get:Rule val composeTestRule = createComposeRule()

  @Test
  fun pickerField_showsPlaceholderOrValueAndOpensOnlyWhenEnabled() {
    var value by mutableStateOf<String?>(null)
    var enabled by mutableStateOf(true)
    var clicks = 0
    composeTestRule.setContent { TimeField(value, error = null, enabled = enabled) { clicks++ } }
    composeTestRule.onNodeWithText("Select time").assertExists()
    composeTestRule.onNodeWithText("Must be in the future").assertExists()

    composeTestRule.onNodeWithTag(INPUT).performClick()
    value = "17:30"
    enabled = false
    composeTestRule.onNodeWithTag(INPUT).performClick()

    composeTestRule.onNodeWithText("17:30").assertExists()
    composeTestRule.runOnIdle { assertEquals(1, clicks) }
  }

  @Test
  fun pickerField_withError_showsTheMessageInsteadOfTheHelper() {
    composeTestRule.setContent { TimeField(null, CreateTripError.TIME_REQUIRED, enabled = true) {} }

    composeTestRule.onNodeWithTag(ERROR).assertExists()
    composeTestRule.onNodeWithText("Choose a time.").assertExists()
    composeTestRule.onNodeWithText("Must be in the future").assertDoesNotExist()
  }

  @Test
  fun everyErrorHasAMessage() {
    val messages =
        mapOf(
            CreateTripError.STORE_REQUIRED to "Choose a store.",
            CreateTripError.DATE_REQUIRED to "Choose a date.",
            CreateTripError.DATE_IN_PAST to "Choose today or a later date.",
            CreateTripError.TIME_REQUIRED to "Choose a time.",
            CreateTripError.TIME_IN_PAST to "That time has already passed.",
            CreateTripError.HANDOFF_LOCATION_REQUIRED to
                "Choose where you will hand over the groceries.",
        )
    assertEquals(CreateTripError.entries.toSet(), messages.keys)
    var error by mutableStateOf(CreateTripError.STORE_REQUIRED)
    composeTestRule.setContent { TimeField(null, error, enabled = true) {} }

    messages.forEach { (shown, message) ->
      error = shown
      composeTestRule.onNodeWithText(message).assertExists()
    }
  }

  @Test
  fun locationPickerField_showsThePlaceholderThenTheSelectedPlace() {
    var selected by mutableStateOf<Location?>(null)
    composeTestRule.setContent {
      LocationPickerField(
          label = R.string.create_trip_store_label,
          icon = R.drawable.ic_trip_shopping_bag,
          selected = selected,
          options = TripLocations.stores,
          placeholder = R.string.create_trip_store_placeholder,
          helper = R.string.create_trip_store_helper,
          error = null,
          enabled = true,
          onSelected = {},
          tag = INPUT,
          errorTag = ERROR,
      )
    }
    composeTestRule.onNodeWithTag(INPUT).assertTextContains("Select a store")

    selected = TripLocations.stores[1]

    composeTestRule.onNodeWithTag(INPUT).assertTextContains(TripLocations.stores[1].name)
  }

  @Composable
  private fun TimeField(
      value: String?,
      error: CreateTripError?,
      enabled: Boolean,
      onClick: () -> Unit,
  ) {
    PickerField(
        label = R.string.create_trip_time_label,
        icon = R.drawable.ic_trip_clock,
        value = value,
        placeholder = R.string.create_trip_time_placeholder,
        helper = R.string.create_trip_time_helper,
        error = error,
        enabled = enabled,
        onClick = onClick,
        tag = INPUT,
        errorTag = ERROR,
    )
  }

  private companion object {
    const val INPUT = "input"
    const val ERROR = "error"
  }
}
