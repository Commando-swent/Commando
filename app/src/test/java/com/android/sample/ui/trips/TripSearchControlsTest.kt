package com.android.sample.ui.trips

// AI assistance: Claude Code.

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.android.sample.R
import com.android.sample.model.DeparturePeriod
import com.android.sample.model.StoreFilter
import com.android.sample.model.TripSearchCriteria
import com.android.sample.model.TripSort
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-rUS")
class TripSearchControlsTest {
  @get:Rule val compose = createComposeRule()
  private val today = LocalDate.of(2026, 10, 5)
  private var criteria by mutableStateOf(TripSearchCriteria())
  private val emitted = mutableListOf<TripSearchCriteria>()
  private var clears = 0

  private fun show(initial: TripSearchCriteria = TripSearchCriteria(), distance: Boolean = false) {
    criteria = initial
    compose.setContent {
      MaterialTheme {
        TripSearchControls(
            criteria = criteria,
            onCriteriaChange = {
              emitted += it
              criteria = it
            },
            onClear = { clears++ },
            distanceAvailable = distance,
            today = today,
        )
      }
    }
  }

  private fun tag(tag: String) = compose.onNodeWithTag(tag)

  private fun expand() = tag(TripSearchTestTags.TOGGLE).performClick()

  private val allChipTags =
      StoreFilter.entries.map(TripSearchTestTags::store) +
          listOf(
              TripSearchTestTags.PERIOD_ANY,
              TripSearchTestTags.PERIOD_TODAY,
              TripSearchTestTags.PERIOD_TOMORROW,
              TripSearchTestTags.PERIOD_CUSTOM,
          ) +
          TripSort.entries.map(TripSearchTestTags::sort)

  private fun medium(day: LocalDate) =
      DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.US).format(day)

  private fun string(id: Int, vararg args: Any): String =
      ApplicationProvider.getApplicationContext<Context>().getString(id, *args)

  private val ok
    get() = string(R.string.create_trip_dialog_ok)

  private val cancel
    get() = string(R.string.create_trip_dialog_cancel)

  /** The collapsed toggle label for the given active filter labels. */
  private fun summary(vararg parts: String) =
      if (parts.isEmpty()) string(R.string.trips_filters)
      else string(R.string.trips_filters_active, parts.joinToString(" · "))

  private fun toggleState(id: Int) =
      SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, string(id))

  private fun range(start: LocalDate, end: LocalDate) =
      string(R.string.trips_period_custom_range, medium(start), medium(end))

  /**
   * Drives one Material date dialog in text-input mode. The mode switch has no test tag and its
   * label is Material's own resource, so it is the only lookup by Material copy.
   */
  private fun pickDate(day: LocalDate) {
    compose.onNodeWithContentDescription("Switch to text input mode").performClick()
    compose
        .onNode(hasSetTextAction())
        .performTextReplacement(day.format(DateTimeFormatter.ofPattern("MMddyyyy")))
    compose.onNodeWithText(ok).performClick()
  }

  @Test
  fun collapsedByDefaultShowsOnlyTheFiltersToggle() {
    show()
    tag(TripSearchTestTags.TOGGLE).assertIsDisplayed().assert(hasText(summary()))
    allChipTags.forEach { tag(it).assertDoesNotExist() }
    tag(TripSearchTestTags.CLEAR).assertDoesNotExist()
  }

  @Test
  fun expandingShowsEveryChipAndCollapsingHidesThem() {
    show()
    tag(TripSearchTestTags.TOGGLE).assert(toggleState(R.string.trips_filters_collapsed))
    expand()
    tag(TripSearchTestTags.TOGGLE).assert(toggleState(R.string.trips_filters_expanded))
    allChipTags.forEach { tag(it).assertExists() }
    tag(TripSearchTestTags.store(StoreFilter.ALL)).assertIsSelected()
    tag(TripSearchTestTags.PERIOD_ANY).assertIsSelected()
    tag(TripSearchTestTags.sort(TripSort.EARLIEST_DEPARTURE)).assertIsSelected()
    expand()
    tag(TripSearchTestTags.TOGGLE).assert(toggleState(R.string.trips_filters_collapsed))
    allChipTags.forEach { tag(it).assertDoesNotExist() }
  }

  @Test
  fun chipsEmitCopiesThatKeepTheOtherFields() {
    show(TripSearchCriteria(sort = TripSort.EARLIEST_DEPARTURE), distance = true)
    expand()
    tag(TripSearchTestTags.store(StoreFilter.MIGROS)).performClick()
    tag(TripSearchTestTags.PERIOD_TODAY).performClick()
    tag(TripSearchTestTags.PERIOD_TOMORROW).performClick()
    tag(TripSearchTestTags.sort(TripSort.DISTANCE)).performScrollTo().performClick()
    tag(TripSearchTestTags.PERIOD_ANY).performClick()
    tag(TripSearchTestTags.store(StoreFilter.COOP)).performClick()
    val migros = TripSearchCriteria(store = StoreFilter.MIGROS)
    assertEquals(
        listOf(
            migros,
            migros.copy(period = DeparturePeriod.Today),
            migros.copy(period = DeparturePeriod.Tomorrow),
            migros.copy(period = DeparturePeriod.Tomorrow, sort = TripSort.DISTANCE),
            migros.copy(sort = TripSort.DISTANCE),
            TripSearchCriteria(store = StoreFilter.COOP, sort = TripSort.DISTANCE),
        ),
        emitted,
    )
    tag(TripSearchTestTags.store(StoreFilter.COOP)).assertIsSelected()
    tag(TripSearchTestTags.store(StoreFilter.MIGROS)).assertIsNotSelected()
  }

  @Test
  fun activeSummaryNamesStoreAndPeriodAndClearCallsBack() {
    show(TripSearchCriteria(StoreFilter.MIGROS, DeparturePeriod.Today))
    tag(TripSearchTestTags.TOGGLE)
        .assert(
            hasText(
                summary(string(R.string.trips_store_migros), string(R.string.trips_period_today))
            )
        )
    tag(TripSearchTestTags.CLEAR)
        .assertIsDisplayed()
        .assert(hasText(string(R.string.trips_filters_clear)))
        .performClick()
    assertEquals(1, clears)
    assertEquals(emptyList<TripSearchCriteria>(), emitted)

    criteria = TripSearchCriteria(period = DeparturePeriod.Tomorrow)
    tag(TripSearchTestTags.TOGGLE).assert(hasText(summary(string(R.string.trips_period_tomorrow))))
    criteria = TripSearchCriteria(store = StoreFilter.COOP)
    tag(TripSearchTestTags.TOGGLE).assert(hasText(summary(string(R.string.trips_store_coop))))
  }

  @Test
  fun customRangeSummaryUsesMediumLocalizedDates() {
    val start = LocalDate.of(2026, 10, 10)
    val end = LocalDate.of(2026, 10, 12)
    show(TripSearchCriteria(StoreFilter.COOP, DeparturePeriod.Custom(start, end)))
    assertEquals("Oct 10, 2026", medium(start))
    tag(TripSearchTestTags.TOGGLE)
        .assert(hasText(summary(string(R.string.trips_store_coop), range(start, end))))
    tag(TripSearchTestTags.CLEAR).assertIsDisplayed()
    expand()
    tag(TripSearchTestTags.PERIOD_CUSTOM).assertIsSelected().assert(hasText(range(start, end)))
  }

  @Test
  fun sortAloneIsNotActive() {
    show(TripSearchCriteria(sort = TripSort.DISTANCE), distance = true)
    tag(TripSearchTestTags.TOGGLE).assert(hasText(summary()))
    tag(TripSearchTestTags.CLEAR).assertDoesNotExist()
  }

  @Test
  fun distanceChipIsDisabledWithoutAPoint() {
    show()
    expand()
    tag(TripSearchTestTags.sort(TripSort.DISTANCE)).assertIsNotEnabled().performClick()
    tag(TripSearchTestTags.sort(TripSort.EARLIEST_DEPARTURE)).assertIsEnabled()
    assertEquals(emptyList<TripSearchCriteria>(), emitted)
  }

  @Test
  fun distanceSortWithoutAPointShowsTheDepartureOrderItFallsBackTo() {
    // DISTANCE was chosen, then the delivery point was cleared: the feed keeps departure order.
    show(TripSearchCriteria(sort = TripSort.DISTANCE))
    expand()
    tag(TripSearchTestTags.sort(TripSort.EARLIEST_DEPARTURE)).assertIsSelected()
    tag(TripSearchTestTags.sort(TripSort.DISTANCE)).assertIsNotSelected().assertIsNotEnabled()
    assertEquals(emptyList<TripSearchCriteria>(), emitted)
  }

  @Test
  fun distanceChipIsEnabledAndEmitsWithAPoint() {
    show(TripSearchCriteria(store = StoreFilter.MIGROS), distance = true)
    expand()
    tag(TripSearchTestTags.sort(TripSort.DISTANCE)).assertIsEnabled().performClick()
    assertEquals(
        listOf(TripSearchCriteria(store = StoreFilter.MIGROS, sort = TripSort.DISTANCE)),
        emitted,
    )
  }

  @Test
  fun customChipPicksStartThenEndAndEmitsTheRange() {
    show(TripSearchCriteria(store = StoreFilter.MIGROS))
    expand()
    tag(TripSearchTestTags.PERIOD_CUSTOM)
        .assert(hasText(string(R.string.trips_period_custom)))
        .performClick()
    pickDate(LocalDate.of(2026, 10, 10))
    pickDate(LocalDate.of(2026, 10, 12))
    val range = DeparturePeriod.Custom(LocalDate.of(2026, 10, 10), LocalDate.of(2026, 10, 12))
    assertEquals(listOf(TripSearchCriteria(StoreFilter.MIGROS, range)), emitted)
    compose.onNodeWithText(ok).assertDoesNotExist()
    tag(TripSearchTestTags.PERIOD_CUSTOM).assertIsSelected()
  }

  @Test
  fun customChipConfirmingPreselectedDatesKeepsTheRange() {
    val range = DeparturePeriod.Custom(LocalDate.of(2026, 10, 10), LocalDate.of(2026, 10, 12))
    show(TripSearchCriteria(period = range))
    expand()
    tag(TripSearchTestTags.PERIOD_CUSTOM).performClick()
    compose.onNodeWithText(ok).performClick()
    compose.onNodeWithText(ok).performClick()
    assertEquals(listOf(TripSearchCriteria(period = range)), emitted)
  }

  @Test
  fun cancellingTheStartOrEndDialogEmitsNothing() {
    show()
    expand()
    tag(TripSearchTestTags.PERIOD_CUSTOM).performClick()
    compose.onNodeWithText(cancel).performClick()
    compose.onNodeWithText(ok).assertDoesNotExist()
    tag(TripSearchTestTags.PERIOD_CUSTOM).performClick()
    pickDate(LocalDate.of(2026, 10, 10))
    compose.onNodeWithText(cancel).performClick()
    compose.onNodeWithText(ok).assertDoesNotExist()
    assertEquals(emptyList<TripSearchCriteria>(), emitted)
    tag(TripSearchTestTags.PERIOD_CUSTOM).assertIsNotSelected()
  }
}
