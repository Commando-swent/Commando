package com.android.sample.ui.trips

// AI assistance: Claude Code.
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.android.sample.R
import com.android.sample.model.Trip
import com.android.sample.model.TripStatus

object MyTripsTestTags {
  const val SCREEN = "my_trips_screen"
  const val LOADING = "my_trips_loading"
  const val EMPTY = "my_trips_empty"
  const val ERROR = "my_trips_error"
  const val RETRY = "my_trips_retry"
  const val PUBLISH_TRIP = "my_trips_publish_trip"
  const val TRIP_CARD_PREFIX = "my_trips_card_"
  const val STATUS_PREFIX = "my_trips_status_"
}

@Composable
fun MyTripsScreen(
    viewModel: MyTripsViewModel,
    onTripSelected: (String) -> Unit,
    onPublishTrip: () -> Unit,
    modifier: Modifier = Modifier,
) {
  val state by viewModel.uiState.collectAsState()
  MyTripsScreen(state, viewModel::retry, onTripSelected, onPublishTrip, modifier)
}

@Composable
fun MyTripsScreen(
    state: MyTripsUiState,
    onRetry: () -> Unit,
    onTripSelected: (String) -> Unit,
    onPublishTrip: () -> Unit,
    modifier: Modifier = Modifier,
) {
  val colors = MaterialTheme.colorScheme
  Box(modifier.fillMaxSize().background(colors.background).testTag(MyTripsTestTags.SCREEN)) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      Column(Modifier.padding(start = 4.dp, top = 16.dp)) {
        Text(
            stringResource(R.string.my_trips_title),
            color = colors.onBackground,
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            stringResource(R.string.my_trips_subtitle),
            color = colors.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
      }
      when (state) {
        MyTripsUiState.Loading -> {
          val loading = stringResource(R.string.my_trips_loading)
          Column(
              Modifier.testTag(MyTripsTestTags.LOADING).semantics { contentDescription = loading },
              verticalArrangement = Arrangement.spacedBy(14.dp),
          ) {
            repeat(3) { SkeletonCard() }
          }
        }
        is MyTripsUiState.Content ->
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(14.dp),
                // Leaves room so the last card is not hidden behind the publish button.
                contentPadding = PaddingValues(bottom = 96.dp),
            ) {
              items(state.trips, key = Trip::id) { trip ->
                TripCard(trip) { onTripSelected(trip.id) }
              }
            }
        MyTripsUiState.Empty ->
            StateCard(
                tag = MyTripsTestTags.EMPTY,
                icon = R.drawable.ic_trip_shopping_bag,
                message = stringResource(R.string.my_trips_empty),
                action = stringResource(R.string.my_trips_publish),
                actionTag = MyTripsTestTags.PUBLISH_TRIP,
                onAction = onPublishTrip,
            )
        is MyTripsUiState.Error ->
            StateCard(
                tag = MyTripsTestTags.ERROR,
                icon = R.drawable.ic_trip_alert_circle,
                message = stringResource(R.string.my_trips_error),
                action = stringResource(R.string.my_trips_retry),
                actionTag = MyTripsTestTags.RETRY,
                onAction = onRetry,
            )
      }
    }
    if (state is MyTripsUiState.Content) {
      ExtendedFloatingActionButton(
          onClick = onPublishTrip,
          modifier =
              Modifier.align(Alignment.BottomEnd)
                  .padding(20.dp)
                  .testTag(MyTripsTestTags.PUBLISH_TRIP),
          containerColor = colors.primary,
          contentColor = colors.onPrimary,
      ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Text("+", Modifier.clearAndSetSemantics {})
          Text(stringResource(R.string.my_trips_publish))
        }
      }
    }
  }
}

@Composable
private fun TripCard(trip: Trip, onClick: () -> Unit) {
  val colors = MaterialTheme.colorScheme
  val locale = LocalConfiguration.current.locales[0]
  Card(
      onClick = onClick,
      modifier = Modifier.fillMaxWidth().testTag(MyTripsTestTags.TRIP_CARD_PREFIX + trip.id),
      shape = RoundedCornerShape(16.dp),
      colors = CardDefaults.cardColors(containerColor = colors.surfaceContainer),
      border = BorderStroke(1.dp, colors.outlineVariant),
  ) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Icon(
            painterResource(R.drawable.ic_trip_shopping_bag),
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = colors.onSurfaceVariant,
        )
        Text(
            trip.store.name,
            modifier = Modifier.weight(1f),
            color = colors.onSurface,
            style = MaterialTheme.typography.titleMedium,
        )
        Text("›", Modifier.clearAndSetSemantics {}, color = colors.onSurfaceVariant)
      }
      StatusBadge(trip)
      Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Icon(
            painterResource(R.drawable.ic_trip_calendar),
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = colors.onSurfaceVariant,
        )
        Text(
            formatTripDeparture(trip.scheduledAt, locale),
            color = colors.onSurface,
            style = MaterialTheme.typography.bodySmall,
        )
      }
      Text(
          stringResource(R.string.my_trips_handoff, trip.handoffLocation.name),
          color = colors.onSurfaceVariant,
          style = MaterialTheme.typography.bodySmall,
      )
    }
  }
}

/** Shows the stored status; closed trips use a muted style, as in the Figma. */
@Composable
private fun StatusBadge(trip: Trip) {
  val colors = MaterialTheme.colorScheme
  val (label, active) =
      when (trip.status) {
        TripStatus.PUBLISHED -> R.string.my_trips_status_published to true
        TripStatus.IN_PROGRESS -> R.string.my_trips_status_in_progress to true
        TripStatus.COMPLETED -> R.string.my_trips_status_completed to false
        TripStatus.CANCELLED -> R.string.my_trips_status_cancelled to false
      }
  Text(
      stringResource(label),
      Modifier.testTag(MyTripsTestTags.STATUS_PREFIX + trip.id)
          .background(
              if (active) colors.secondaryContainer else colors.surfaceContainerHigh,
              CircleShape,
          )
          .border(1.dp, colors.outline, CircleShape)
          .padding(horizontal = 10.dp, vertical = 2.dp),
      color = if (active) colors.onSecondaryContainer else colors.onSurfaceVariant,
      style = MaterialTheme.typography.labelSmall,
  )
}

@Composable
private fun SkeletonCard() {
  val colors = MaterialTheme.colorScheme
  Card(
      modifier = Modifier.fillMaxWidth(),
      shape = RoundedCornerShape(16.dp),
      colors = CardDefaults.cardColors(containerColor = colors.surfaceContainer),
      border = BorderStroke(1.dp, colors.outlineVariant),
  ) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      listOf(0.6f, 0.25f, 0.5f, 0.65f).forEach { width ->
        Box(
            Modifier.fillMaxWidth(width)
                .height(16.dp)
                .background(colors.surfaceContainerHigh, RoundedCornerShape(8.dp))
        )
      }
    }
  }
}

@Composable
private fun StateCard(
    tag: String,
    icon: Int,
    message: String,
    action: String,
    actionTag: String,
    onAction: () -> Unit,
) {
  val colors = MaterialTheme.colorScheme
  Card(
      modifier =
          Modifier.fillMaxWidth().testTag(tag).semantics { liveRegion = LiveRegionMode.Polite },
      shape = RoundedCornerShape(16.dp),
      colors = CardDefaults.cardColors(containerColor = colors.surfaceContainer),
      border = BorderStroke(1.dp, colors.outlineVariant),
  ) {
    // Scrolls so the action stays reachable on short screens or with large fonts.
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      Box(
          Modifier.size(88.dp).background(colors.primary, CircleShape),
          contentAlignment = Alignment.Center,
      ) {
        Icon(
            painterResource(icon),
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = colors.onPrimary,
        )
      }
      Text(
          message,
          color = colors.onSurface,
          style = MaterialTheme.typography.titleLarge,
          textAlign = TextAlign.Center,
      )
      Button(
          onClick = onAction,
          modifier = Modifier.fillMaxWidth().height(56.dp).testTag(actionTag),
          colors =
              ButtonDefaults.buttonColors(
                  containerColor = colors.primary,
                  contentColor = colors.onPrimary,
              ),
      ) {
        Text(action)
      }
    }
  }
}
