package com.android.sample.ui.trips

// AI assistance: Claude Code.
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Runs on a device: Robolectric cannot idle a dialog window. */
@RunWith(AndroidJUnit4::class)
class CreateTripDialogsTest {

  @get:Rule val composeTestRule = createComposeRule()

  private val events = mutableListOf<Any>()

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
}
