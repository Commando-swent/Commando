package com.android.sample.ui.trips

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
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
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

private val runsBackground = Color(0xFF161B12)
private val runsSurfaceContainer = Color(0xFF252E1D)
private val runsOutlineVariant = Color(0xFF3F4A33)
private val runsText = Color(0xFFF4F2E6)
private val runsMuted = Color(0xFFC3C9B2)
private val runsAccent = Color(0xFFB9E48A)
private val runsOnAccent = Color(0xFF17240A)

@Composable
fun AvailableTripsScreen(
    viewModel: AvailableTripsViewModel,
    onTripSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
  val state by viewModel.uiState.collectAsState()
  AvailableTripsScreen(state, viewModel::retry, onTripSelected, modifier)
}

@Composable
fun AvailableTripsScreen(
    state: AvailableTripsUiState,
    onRetry: () -> Unit,
    onTripSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
  Column(
      modifier.fillMaxSize().background(runsBackground).padding(20.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Text(
        stringResource(R.string.trips_title),
        color = runsText,
        style = MaterialTheme.typography.headlineSmall,
    )
    Text(
        stringResource(R.string.trips_subtitle),
        color = runsMuted,
        style = MaterialTheme.typography.bodySmall,
    )
    when (state) {
      AvailableTripsUiState.Loading -> {
        val loading = stringResource(R.string.trips_loading)
        LazyColumn(
            modifier = Modifier.semantics { contentDescription = loading },
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          items(4) {
            Card(
                modifier = Modifier.fillMaxWidth().height(104.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = runsSurfaceContainer),
                border = BorderStroke(1.dp, runsOutlineVariant),
            ) {}
          }
        }
      }
      is AvailableTripsUiState.Content ->
          LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(state.trips) { trip -> TripCard(trip) { onTripSelected(trip.id) } }
          }
      AvailableTripsUiState.Empty -> TripStatusCard(stringResource(R.string.trips_empty))
      is AvailableTripsUiState.Error ->
          TripStatusCard(stringResource(R.string.trips_error), onRetry)
    }
  }
}

@Composable
private fun TripCard(trip: Trip, onClick: () -> Unit) {
  val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
  val departure = formatTripDeparture(trip.scheduledAt, locale)
  Card(
      onClick = onClick,
      modifier = Modifier.fillMaxWidth(),
      shape = RoundedCornerShape(16.dp),
      colors = CardDefaults.cardColors(containerColor = runsSurfaceContainer),
      border = BorderStroke(1.dp, runsOutlineVariant),
  ) {
    Column(
        Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      Text(trip.store.name, color = runsText, style = MaterialTheme.typography.titleMedium)
      Text(
          stringResource(R.string.trips_departure, departure),
          color = runsAccent,
          style = MaterialTheme.typography.bodySmall,
      )
      Text(
          stringResource(R.string.trips_handoff, trip.handoffLocation.name),
          color = runsMuted,
          style = MaterialTheme.typography.bodySmall,
      )
    }
  }
}

@Composable
private fun TripStatusCard(message: String, onRetry: (() -> Unit)? = null) {
  Card(
      modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
      shape = RoundedCornerShape(16.dp),
      colors = CardDefaults.cardColors(containerColor = runsSurfaceContainer),
      border = BorderStroke(1.dp, runsOutlineVariant),
  ) {
    Column(
        modifier =
            Modifier.fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = 280.dp)
                .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
    ) {
      Box(
          Modifier.size(64.dp).background(runsAccent, CircleShape),
          contentAlignment = Alignment.Center,
      ) {
        if (onRetry != null) {
          Text(
              "!",
              modifier = Modifier.clearAndSetSemantics {},
              color = runsOnAccent,
              style = MaterialTheme.typography.headlineMedium,
          )
        } else {
          Canvas(Modifier.size(28.dp)) {
            val stroke = Stroke(width = 1.5.dp.toPx())
            drawRoundRect(
                runsOnAccent,
                topLeft = Offset(size.width * 0.15f, size.height * 0.3f),
                size = Size(size.width * 0.7f, size.height * 0.6f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()),
                style = stroke,
            )
            drawArc(
                runsOnAccent,
                180f,
                180f,
                false,
                topLeft = Offset(size.width * 0.3f, size.height * 0.1f),
                size = Size(size.width * 0.4f, size.height * 0.4f),
                style = stroke,
            )
          }
        }
      }
      Text(
          message,
          color = runsText,
          style = MaterialTheme.typography.titleMedium,
          textAlign = TextAlign.Center,
      )
      if (onRetry != null) {
        Button(
            onClick = onRetry,
            modifier = Modifier.fillMaxWidth(),
            colors =
                ButtonDefaults.buttonColors(
                    containerColor = runsAccent,
                    contentColor = runsOnAccent,
                ),
        ) {
          Text(stringResource(R.string.trips_retry))
        }
      }
    }
  }
}

/** Presents the same instant in the device zone; explicit inputs allow deterministic tests. */
internal fun formatTripDeparture(
    instant: Instant,
    locale: Locale = Locale.getDefault(),
    zoneId: ZoneId = ZoneId.systemDefault(),
): String =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        .withLocale(locale)
        .withZone(zoneId)
        .format(instant)
