package com.android.sample.ui.home

// AI assistance: OpenAI Codex. Figma: 105:296, Commando — Trip published.
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.sample.R
import com.android.sample.model.Trip
import com.android.sample.model.TripStatus
import com.android.sample.ui.navigation.NavigationTestTags
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
internal fun HomeCurrentTrip(
    state: HomeTripUiState,
    onRetry: () -> Unit,
    onManageTrip: ((String) -> Unit)?,
    onPublishTrip: (() -> Unit)?,
    modifier: Modifier,
) {
  if (state is HomeTripUiState.Loading || state is HomeTripUiState.Error) {
    Box(
        modifier.fillMaxSize().testTag(NavigationTestTags.HOME_SCREEN),
        contentAlignment = Alignment.Center,
    ) {
      Column(
          Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.spacedBy(16.dp),
      ) {
        if (state is HomeTripUiState.Loading) {
          CircularProgressIndicator(Modifier.size(48.dp).testTag(HomeTestTags.LOADING))
          Text(
              stringResource(R.string.home_trip_loading),
              textAlign = TextAlign.Center,
              style = MaterialTheme.typography.bodyMedium,
          )
        } else {
          Text(
              stringResource(R.string.home_trip_error),
              textAlign = TextAlign.Center,
              style = MaterialTheme.typography.bodyMedium,
          )
          Button(onClick = onRetry, modifier = Modifier.height(56.dp).testTag(HomeTestTags.RETRY)) {
            Text(stringResource(R.string.trips_retry))
          }
        }
      }
    }
    return
  }
  BoxWithConstraints(modifier.fillMaxSize().testTag(NavigationTestTags.HOME_SCREEN)) {
    val minimumHeight = maxHeight
    Column(
        Modifier.fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .heightIn(min = minimumHeight)
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      when (state) {
        is HomeTripUiState.Content -> {
          val trip = state.trip
          Text(
              stringResource(
                  if (trip.status == TripStatus.IN_PROGRESS) R.string.home_your_trip
                  else R.string.home_next_trip
              ),
              Modifier.padding(horizontal = 4.dp).semantics { heading() },
              style = MaterialTheme.typography.headlineMedium.copy(fontSize = 28.sp),
          )
          HomeTripCard(trip)
          Spacer(Modifier.weight(1f))
          Button(
              onClick = { onManageTrip?.invoke(trip.id) },
              enabled = onManageTrip != null,
              modifier = Modifier.fillMaxWidth().height(56.dp).testTag(HomeTestTags.MANAGE_TRIP),
          ) {
            Text(stringResource(R.string.home_manage_trip))
          }
          OutlinedButton(
              onClick = { onPublishTrip?.invoke() },
              enabled = onPublishTrip != null,
              modifier = Modifier.fillMaxWidth().height(56.dp).testTag(HomeTestTags.TRIP_ACTION),
          ) {
            Text(stringResource(R.string.home_publish_another_trip))
          }
        }
        HomeTripUiState.Empty -> Unit // HomeScreen renders the existing empty state.
        HomeTripUiState.Loading,
        is HomeTripUiState.Error -> Unit // Centered states render above.
      }
    }
  }
}

@Composable
private fun HomeTripCard(trip: Trip) {
  val colors = MaterialTheme.colorScheme
  val locale = LocalConfiguration.current.locales[0]
  val departure = trip.scheduledAt.atZone(ZoneId.systemDefault())
  val date =
      if (departure.toLocalDate() == LocalDate.now()) stringResource(R.string.home_trip_today)
      else
          departure.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))
  Card(
      Modifier.fillMaxWidth().testTag(HomeTestTags.CURRENT_TRIP),
      shape = RoundedCornerShape(24.dp),
      colors = CardDefaults.cardColors(containerColor = colors.surfaceContainer),
  ) {
    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
      Row(
          Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
      ) {
        Column {
          Text(date, color = colors.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
          Text(
              departure.format(DateTimeFormatter.ofPattern("HH:mm", locale)),
              color = colors.primary,
              style =
                  MaterialTheme.typography.headlineLarge.copy(fontSize = 40.sp, lineHeight = 44.sp),
          )
        }
        Surface(shape = RoundedCornerShape(16.dp), color = colors.primaryContainer) {
          Row(
              Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(6.dp),
          ) {
            if (trip.status == TripStatus.PUBLISHED)
                Image(painterResource(R.drawable.home_trip_open), null, Modifier.size(16.dp))
            Text(
                stringResource(
                    if (trip.status == TripStatus.IN_PROGRESS) R.string.home_trip_shopping
                    else R.string.home_trip_open
                ),
                style = MaterialTheme.typography.labelSmall,
                color = colors.onPrimaryContainer,
            )
          }
        }
      }
      Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Surface(
            Modifier.size(32.dp),
            shape = RoundedCornerShape(10.dp),
            color = colors.surfaceContainerHighest,
        ) {
          Box(contentAlignment = Alignment.Center) {
            Text(trip.store.name.take(1), color = colors.primary, fontWeight = FontWeight.ExtraBold)
          }
        }
        Text(trip.store.name, style = MaterialTheme.typography.labelMedium.copy(fontSize = 16.sp))
      }
      Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Image(painterResource(R.drawable.home_handoff), null, Modifier.size(20.dp))
        Column {
          Text(trip.handoffLocation.name, style = MaterialTheme.typography.labelMedium)
          Text(
              stringResource(R.string.home_trip_handoff),
              color = colors.onSurfaceVariant,
              style = MaterialTheme.typography.bodySmall,
          )
        }
      }
    }
  }
}
