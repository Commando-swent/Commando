package com.android.sample.ui.navigation

// AI assistance: OpenAI Codex.
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.android.sample.R

/** Keeps the trip context in navigation until the requester flow is implemented in Sprint 2. */
@Composable
internal fun AddItemsPlaceholderScreen(onBack: () -> Unit) {
  Column(
      modifier =
          Modifier.fillMaxSize()
              .background(MaterialTheme.colorScheme.background)
              .safeDrawingPadding()
              .verticalScroll(rememberScrollState())
              .padding(24.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    Text(
        stringResource(CommandoScreens.AddItems.title),
        style = MaterialTheme.typography.headlineMedium,
    )
    Text(stringResource(R.string.add_items_sprint_two))
    Button(onClick = onBack) { Text(stringResource(R.string.nav_back)) }
  }
}
