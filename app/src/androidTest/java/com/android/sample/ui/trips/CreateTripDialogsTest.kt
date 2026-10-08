package com.android.sample.ui.trips

// AI assistance: Claude Code.
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.sample.R
import com.android.sample.model.TripLocations
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Runs on a device: Robolectric cannot idle dialog and menu windows. */
@RunWith(AndroidJUnit4::class)
class CreateTripDialogsTest {

  @get:Rule val composeTestRule = createComposeRule()

  private val events = mutableListOf<Any>()

  @Test
  fun locationPicker_opensOnlyWhenEnabledAndReportsThePickedPlace() {
    var enabled by mutableStateOf(false)
    composeTestRule.setContent { StorePicker(enabled) { events += it } }
    val option = CreateTripScreenTestTags.locationOption(TripLocations.stores[1].name)

    composeTestRule.onNodeWithTag(FIELD).performClick()
    composeTestRule.onNodeWithTag(option).assertDoesNotExist()
    enabled = true
    composeTestRule.onNodeWithTag(FIELD).performClick()
    composeTestRule.onNodeWithTag(option).performClick()

    composeTestRule.onNodeWithTag(option).assertDoesNotExist()
    composeTestRule.runOnIdle { assertEquals(listOf<Any>(TripLocations.stores[1]), events) }
  }

  @Test
  fun datePicker_returnsTheSelectedDayWhateverTheTimeZone() {
    val day = LocalDate.of(2026, 12, 24)
    composeTestRule.setContent {
      TripDatePickerDialog(day, today = LocalDate.of(2026, 10, 5), { events += "dismiss" }) {
        events += it
      }
    }

    composeTestRule.onNodeWithText("OK").performClick()

    composeTestRule.runOnIdle { assertEquals(listOf<Any>(day), events) }
  }

  @Test
  fun timePicker_returnsTheSelectedTime() {
    composeTestRule.setContent {
      TripTimePickerDialog(LocalTime.of(17, 30), { events += "dismiss" }) { events += it }
    }

    composeTestRule.onNodeWithText("OK").performClick()

    composeTestRule.runOnIdle { assertEquals(listOf<Any>(LocalTime.of(17, 30)), events) }
  }

  @Composable
  private fun StorePicker(enabled: Boolean, onSelected: (Any) -> Unit) {
    LocationPickerField(
        label = R.string.create_trip_store_label,
        icon = R.drawable.ic_trip_shopping_bag,
        selected = null,
        options = TripLocations.stores,
        placeholder = R.string.create_trip_store_placeholder,
        helper = R.string.create_trip_store_helper,
        error = null,
        enabled = enabled,
        onSelected = onSelected,
        tag = FIELD,
        errorTag = "error",
    )
  }

  private companion object {
    const val FIELD = "field"
  }
}
