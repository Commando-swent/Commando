package com.android.sample.ui.trips

// AI assistance: Claude Code.
import androidx.annotation.StringRes
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.android.sample.R
import com.android.sample.model.DeparturePeriod
import com.android.sample.model.StoreFilter
import com.android.sample.model.TripSearchCriteria
import com.android.sample.model.TripSort
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

object TripSearchTestTags {
  const val TOGGLE = "tripSearchToggle"
  const val CLEAR = "tripSearchClear"
  const val PERIOD_ANY = "tripSearchPeriodAny"
  const val PERIOD_TODAY = "tripSearchPeriodToday"
  const val PERIOD_TOMORROW = "tripSearchPeriodTomorrow"
  const val PERIOD_CUSTOM = "tripSearchPeriodCustom"

  fun store(store: StoreFilter) = "tripSearchStore_${store.name}"

  fun sort(sort: TripSort) = "tripSearchSort_${sort.name}"
}

/**
 * Store, departure and sort controls for available trips, collapsed by default into one row that
 * names the active filters. [onClear] resets store and departure (not the sort or the delivery
 * point). Distance sorting is only offered when [distanceAvailable], i.e. a point is selected.
 */
@Composable
fun TripSearchControls(
    criteria: TripSearchCriteria,
    onCriteriaChange: (TripSearchCriteria) -> Unit,
    onClear: () -> Unit,
    distanceAvailable: Boolean,
    today: LocalDate,
    modifier: Modifier = Modifier,
    labelColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
  var expanded by remember { mutableStateOf(false) }
  var pickingStart by remember { mutableStateOf(false) }
  var pickedStart by remember { mutableStateOf<LocalDate?>(null) }
  val custom = criteria.period as? DeparturePeriod.Custom
  val customLabel = custom?.let {
    stringResource(R.string.trips_period_custom_range, formatDay(it.start), formatDay(it.end))
  }
  // The collapsed row names the active filters, so they stay visible without the chips.
  val active =
      listOfNotNull(
          criteria.store.takeIf { it != StoreFilter.ALL }?.let { stringResource(storeLabel(it)) },
          when (criteria.period) {
            DeparturePeriod.AnyTime -> null
            DeparturePeriod.Today -> stringResource(R.string.trips_period_today)
            DeparturePeriod.Tomorrow -> stringResource(R.string.trips_period_tomorrow)
            is DeparturePeriod.Custom -> customLabel
          },
      )

  Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      TextButton(
          onClick = { expanded = !expanded },
          modifier = Modifier.weight(1f).testTag(TripSearchTestTags.TOGGLE),
      ) {
        Text(
            if (active.isEmpty()) stringResource(R.string.trips_filters)
            else stringResource(R.string.trips_filters_active, active.joinToString(" · ")),
            modifier = Modifier.fillMaxWidth(),
        )
      }
      if (criteria.isActive) {
        TextButton(onClick = onClear, modifier = Modifier.testTag(TripSearchTestTags.CLEAR)) {
          Text(stringResource(R.string.trips_filters_clear))
        }
      }
    }
    if (expanded) {
      Filters(criteria, onCriteriaChange, distanceAvailable, labelColor, customLabel) {
        pickingStart = true
      }
    }
  }

  // Custom range: pick the first day, then the last day (never before the first).
  if (pickingStart) {
    // A past start (expired range) is not preselected: it could not be picked again.
    TripDatePickerDialog(
        custom?.start?.takeIf { it >= today },
        today,
        onDismiss = { pickingStart = false },
    ) {
      pickingStart = false
      pickedStart = it
    }
  }
  pickedStart?.let { start ->
    TripDatePickerDialog(custom?.end?.takeIf { it >= start }, start, { pickedStart = null }) { end
      ->
      pickedStart = null
      onCriteriaChange(criteria.copy(period = DeparturePeriod.Custom(start, end)))
    }
  }
}

@Composable
private fun Filters(
    criteria: TripSearchCriteria,
    onCriteriaChange: (TripSearchCriteria) -> Unit,
    distanceAvailable: Boolean,
    labelColor: Color,
    customLabel: String?,
    onPickDates: () -> Unit,
) {
  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    ChipRow(R.string.trips_filter_store_label, labelColor) {
      StoreFilter.entries.forEach { store ->
        Chip(
            selected = criteria.store == store,
            label = stringResource(storeLabel(store)),
            tag = TripSearchTestTags.store(store),
        ) {
          onCriteriaChange(criteria.copy(store = store))
        }
      }
    }
    ChipRow(R.string.trips_filter_period_label, labelColor) {
      PeriodChip(criteria, DeparturePeriod.AnyTime, R.string.trips_period_any, onCriteriaChange)
      PeriodChip(criteria, DeparturePeriod.Today, R.string.trips_period_today, onCriteriaChange)
      PeriodChip(
          criteria,
          DeparturePeriod.Tomorrow,
          R.string.trips_period_tomorrow,
          onCriteriaChange,
      )
      Chip(
          selected = customLabel != null,
          label = customLabel ?: stringResource(R.string.trips_period_custom),
          tag = TripSearchTestTags.PERIOD_CUSTOM,
          onClick = onPickDates,
      )
    }
    ChipRow(R.string.trips_sort_label, labelColor) {
      TripSort.entries.forEach { sort ->
        Chip(
            // Without a point the feed keeps departure order, so show that as the active sort.
            selected =
                sort == if (distanceAvailable) criteria.sort else TripSort.EARLIEST_DEPARTURE,
            label = stringResource(sortLabel(sort)),
            tag = TripSearchTestTags.sort(sort),
            enabled = sort != TripSort.DISTANCE || distanceAvailable,
        ) {
          onCriteriaChange(criteria.copy(sort = sort))
        }
      }
    }
  }
}

@Composable
private fun ChipRow(@StringRes label: Int, labelColor: Color, chips: @Composable () -> Unit) {
  Text(stringResource(label), color = labelColor, style = MaterialTheme.typography.labelMedium)
  Row(
      Modifier.horizontalScroll(rememberScrollState()),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    chips()
  }
}

@Composable
private fun Chip(
    selected: Boolean,
    label: String,
    tag: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
  FilterChip(
      selected = selected,
      onClick = onClick,
      label = { Text(label) },
      enabled = enabled,
      modifier = Modifier.testTag(tag),
  )
}

@Composable
private fun PeriodChip(
    criteria: TripSearchCriteria,
    period: DeparturePeriod,
    @StringRes label: Int,
    onCriteriaChange: (TripSearchCriteria) -> Unit,
) {
  val tag =
      when (period) {
        DeparturePeriod.Today -> TripSearchTestTags.PERIOD_TODAY
        DeparturePeriod.Tomorrow -> TripSearchTestTags.PERIOD_TOMORROW
        else -> TripSearchTestTags.PERIOD_ANY
      }
  Chip(criteria.period == period, stringResource(label), tag) {
    onCriteriaChange(criteria.copy(period = period))
  }
}

@Composable
private fun formatDay(day: LocalDate): String =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
        .withLocale(LocalConfiguration.current.locales[0])
        .format(day)

@StringRes
private fun storeLabel(store: StoreFilter) =
    when (store) {
      StoreFilter.ALL -> R.string.trips_store_all
      StoreFilter.MIGROS -> R.string.trips_store_migros
      StoreFilter.COOP -> R.string.trips_store_coop
    }

@StringRes
private fun sortLabel(sort: TripSort) =
    when (sort) {
      TripSort.EARLIEST_DEPARTURE -> R.string.trips_sort_earliest
      TripSort.DISTANCE -> R.string.trips_sort_distance
    }
