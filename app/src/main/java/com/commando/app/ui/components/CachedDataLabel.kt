package com.commando.app.ui.components

// AI assistance: OpenAI Codex.
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.commando.app.R
import java.text.DateFormat
import java.time.Instant
import java.util.Date

/** Displays the time the server last confirmed an offline copy. */
@Composable
fun CachedDataLabel(time: Instant, modifier: Modifier = Modifier) {
  Text(
      stringResource(
          R.string.offline_confirmed_time,
          DateFormat.getDateTimeInstance().format(Date.from(time)),
      ),
      modifier,
  )
}
