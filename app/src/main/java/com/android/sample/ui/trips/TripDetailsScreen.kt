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
import com.android.sample.model.TripStatus

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
  Column(
      modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding()
  ) {
    // Keep Back outside the scrolling body and independent of the loading result.
    TextButton(
        onClick = onBack,
        modifier = Modifier.padding(horizontal = 12.dp),
        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary),
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
              enabled =
                  when (state.trip.status) {
                    TripStatus.PUBLISHED,
                    TripStatus.IN_PROGRESS -> true
                    TripStatus.COMPLETED,
                    TripStatus.CANCELLED -> false
                  },
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
        color = MaterialTheme.colorScheme.onSurface,
        style = MaterialTheme.typography.headlineSmall,
    )
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    TripDetailsField(
        label = stringResource(R.string.trip_details_shopping_time),
        value = formatTripDeparture(trip.scheduledAt, locale),
    )
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    TripDetailsField(
        label = stringResource(R.string.trip_details_handoff),
        value = trip.handoffLocation.name,
    )
  }
}

@Composable
private fun TripDetailsField(label: String, value: String) {
  Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Text(
        label,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall,
    )
    Text(
        value,
        color = MaterialTheme.colorScheme.onSurface,
        style = MaterialTheme.typography.titleMedium,
    )
  }
}

@Composable
private fun TripDetailsLoading() {
  TripDetailsCard {
    CircularProgressIndicator(
        modifier = Modifier.align(Alignment.CenterHorizontally),
        color = MaterialTheme.colorScheme.primary,
    )
    Text(
        stringResource(R.string.trip_details_loading),
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        color = MaterialTheme.colorScheme.onSurface,
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
        color = MaterialTheme.colorScheme.onSurface,
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
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
      border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
  ) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        content = content,
    )
  }
}

@Composable
private fun TripDetailsButton(text: String, onClick: () -> Unit, enabled: Boolean = true) {
  Button(
      onClick = onClick,
      enabled = enabled,
      modifier = Modifier.fillMaxWidth(),
      colors =
          ButtonDefaults.buttonColors(
              containerColor = MaterialTheme.colorScheme.primary,
              contentColor = MaterialTheme.colorScheme.onPrimary,
          ),
  ) {
    Text(text, textAlign = TextAlign.Center)
  }
}
