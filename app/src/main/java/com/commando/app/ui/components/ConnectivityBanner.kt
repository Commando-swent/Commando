package com.commando.app.ui.components

// AI assistance: Claude Code (#393). Layout from the Command'o Figma connection banner.
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.android.sample.R
import kotlinx.coroutines.delay

object ConnectivityBannerTestTags {
  const val OFFLINE = "connectivityBannerOffline"
  const val BACK_ONLINE = "connectivityBannerBackOnline"
}

/** How long "Back online" stays visible after the connection returns. */
internal const val BACK_ONLINE_VISIBLE_MS = 3_000L

/**
 * Shows "You're offline" while the device has no connection, then "Back online" for a few seconds
 * once it returns. It only reflects the device's connection, never whether the server answered.
 */
@Composable
fun ConnectivityBanner(isOnline: Boolean, modifier: Modifier = Modifier) {
  var wasOffline by remember { mutableStateOf(!isOnline) }
  var backOnline by remember { mutableStateOf(false) }
  LaunchedEffect(isOnline) {
    if (!isOnline) {
      wasOffline = true
      backOnline = false
    } else if (wasOffline) {
      wasOffline = false
      backOnline = true
      delay(BACK_ONLINE_VISIBLE_MS)
      backOnline = false
    }
  }
  when {
    !isOnline ->
        BannerContent(
            R.drawable.ic_connectivity_offline,
            R.string.connectivity_offline_title,
            R.string.connectivity_offline_body,
            modifier.testTag(ConnectivityBannerTestTags.OFFLINE),
        )
    // wasOffline covers the first frame back online, before the effect runs, so the bar never
    // blinks.
    backOnline || wasOffline ->
        BannerContent(
            R.drawable.ic_connectivity_online,
            R.string.connectivity_back_online_title,
            R.string.connectivity_back_online_body,
            modifier.testTag(ConnectivityBannerTestTags.BACK_ONLINE),
        )
  }
}

@Composable
private fun BannerContent(
    @DrawableRes icon: Int,
    @StringRes title: Int,
    @StringRes body: Int,
    modifier: Modifier,
) {
  val shape = RoundedCornerShape(16.dp)
  Row(
      modifier =
          modifier
              .fillMaxWidth()
              .padding(horizontal = 16.dp, vertical = 6.dp)
              .background(MaterialTheme.colorScheme.secondaryContainer, shape)
              .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
              .padding(horizontal = 16.dp, vertical = 8.dp)
              // Read once by screen readers when it appears, without interrupting.
              .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Icon(
        painterResource(icon),
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier.size(20.dp),
    )
    Column {
      Text(
          stringResource(title),
          style = MaterialTheme.typography.titleSmall,
          fontWeight = FontWeight.SemiBold,
          color = MaterialTheme.colorScheme.onSecondaryContainer,
      )
      Text(
          stringResource(body),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}
