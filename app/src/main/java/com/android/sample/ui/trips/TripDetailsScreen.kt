package com.android.sample.ui.trips

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.android.sample.R
import com.android.sample.data.repository.TripError
import com.android.sample.model.Trip

private val detailsBackground = Color(0xFF161B12)
private val detailsSurface = Color(0xFF252E1D)
private val detailsOutline = Color(0xFF3F4A33)
private val detailsText = Color(0xFFF4F2E6)
private val detailsMuted = Color(0xFFC3C9B2)
private val detailsAccent = Color(0xFFB9E48A)
private val detailsOnAccent = Color(0xFF17240A)

@Composable
fun TripDetailsScreen(
    viewModel: TripDetailsViewModel,
    onBack: () -> Unit,
    onAddItems: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
  val state by viewModel.uiState.collectAsState()
  TripDetailsScreen(state, viewModel::retry, onBack, onAddItems, modifier)
}

@Composable
fun TripDetailsScreen(
    state: TripDetailsUiState,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    onAddItems: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
  Column(modifier.fillMaxSize().background(detailsBackground).safeDrawingPadding()) {
    // Keep Back outside the scrolling body and independent of the loading result.
    TextButton(
        onClick = onBack,
        modifier = Modifier.padding(horizontal = 12.dp),
        colors = ButtonDefaults.textButtonColors(contentColor = detailsAccent),
    ) {
      Text(stringResource(R.string.trip_details_back))
    }
    Column(
        modifier =
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
      when (state) {
        TripDetailsUiState.Loading -> TripDetailsLoading()
        is TripDetailsUiState.Content -> {
          TripDetailsContent(state.trip)
          TripDetailsButton(
              text = stringResource(R.string.trip_details_add_items),
              onClick = { onAddItems(state.trip.id) },
          )
        }
        is TripDetailsUiState.Error -> {
          val notFound = state.error == TripError.NotFound
          TripDetailsError(
              message =
                  stringResource(
                      if (notFound) R.string.trip_details_not_found else R.string.trip_details_error
                  ),
              onRetry = if (notFound) null else onRetry,
          )
        }
      }
    }
  }
}

@Composable
private fun TripDetailsContent(trip: Trip) {
  val locale = LocalConfiguration.current.locales[0]
  TripDetailsCard {
    Text(
        trip.store.name,
        modifier = Modifier.semantics { heading() },
        color = detailsText,
        style = MaterialTheme.typography.headlineSmall,
    )
    HorizontalDivider(color = detailsOutline)
    TripDetailsField(
        label = stringResource(R.string.trip_details_shopping_time),
        value = formatTripDeparture(trip.scheduledAt, locale),
    )
    HorizontalDivider(color = detailsOutline)
    TripDetailsField(
        label = stringResource(R.string.trip_details_handoff),
        value = trip.handoffLocation.name,
    )
  }
}

@Composable
private fun TripDetailsField(label: String, value: String) {
  Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Text(label, color = detailsMuted, style = MaterialTheme.typography.bodySmall)
    Text(value, color = detailsText, style = MaterialTheme.typography.titleMedium)
  }
}

@Composable
private fun TripDetailsLoading() {
  TripDetailsCard {
    CircularProgressIndicator(
        modifier = Modifier.align(Alignment.CenterHorizontally),
        color = detailsAccent,
    )
    Text(
        stringResource(R.string.trip_details_loading),
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        color = detailsText,
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.titleMedium,
    )
  }
}

@Composable
private fun TripDetailsError(message: String, onRetry: (() -> Unit)?) {
  TripDetailsCard {
    Text(
        message,
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        color = detailsText,
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.titleMedium,
    )
    if (onRetry != null) {
      TripDetailsButton(stringResource(R.string.trips_retry), onRetry)
    }
  }
}

@Composable
private fun TripDetailsCard(content: @Composable ColumnScope.() -> Unit) {
  Card(
      modifier = Modifier.fillMaxWidth(),
      shape = RoundedCornerShape(16.dp),
      colors = CardDefaults.cardColors(containerColor = detailsSurface),
      border = BorderStroke(1.dp, detailsOutline),
  ) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        content = content,
    )
  }
}

@Composable
private fun TripDetailsButton(text: String, onClick: () -> Unit) {
  Button(
      onClick = onClick,
      modifier = Modifier.fillMaxWidth(),
      colors =
          ButtonDefaults.buttonColors(
              containerColor = detailsAccent,
              contentColor = detailsOnAccent,
          ),
  ) {
    Text(text, textAlign = TextAlign.Center)
  }
}
