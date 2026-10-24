package com.android.sample.ui.profile

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.android.sample.R

/** Groups related Profile actions using the existing theme and the Figma card layout. */
@Composable
internal fun ProfileMenuSection(
    @StringRes title: Int,
    content: @Composable ColumnScope.() -> Unit,
) {
  Text(
      stringResource(title),
      Modifier.fillMaxWidth().padding(start = 4.dp, top = 20.dp, bottom = 8.dp),
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      style = MaterialTheme.typography.bodySmall,
  )
  Surface(
      shape = RoundedCornerShape(18.dp),
      color = MaterialTheme.colorScheme.surfaceContainer,
      border = BorderStroke(0.75.dp, MaterialTheme.colorScheme.outlineVariant),
  ) {
    Column(Modifier.fillMaxWidth(), content = content)
  }
}

/** A named action with an optional icon; its caller owns navigation. */
@Composable
internal fun ProfileMenuRow(
    @StringRes title: Int,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
) {
  Row(
      modifier
          .fillMaxWidth()
          .clickable(enabled = onClick != null, role = Role.Button, onClick = onClick ?: {})
          .heightIn(min = 56.dp)
          .padding(14.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    icon?.let { ProfileMenuIcon(it) }
    Text(stringResource(title), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
    Text("›", Modifier.clearAndSetSemantics {}, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
}

/** Keeps the Figma glyph at its native size inside the shared settings icon container. */
@Composable
internal fun ProfileMenuIcon(@DrawableRes icon: Int) {
  Surface(
      shape = RoundedCornerShape(9.dp),
      color = MaterialTheme.colorScheme.surfaceContainerHigh,
  ) {
    Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
      Image(painterResource(icon), contentDescription = null, modifier = Modifier.size(16.dp))
    }
  }
}

/** Scrollable settings content; the app root supplies the destination and Back action. */
@Composable
internal fun ProfileSettingsPage(
    @StringRes title: Int,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
  Column(
      modifier
          .fillMaxSize()
          .verticalScroll(rememberScrollState())
          .padding(horizontal = 24.dp, vertical = 12.dp)
  ) {
    val backDescription = stringResource(R.string.nav_back)
    Row(verticalAlignment = Alignment.CenterVertically) {
      IconButton(onClick = onBack, modifier = Modifier.testTag(ProfileTestTags.SETTINGS_BACK)) {
        Text(
            "←",
            Modifier.clearAndSetSemantics { contentDescription = backDescription },
            style = MaterialTheme.typography.titleLarge,
        )
      }
      Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
    }
    content()
    Spacer(Modifier.height(24.dp))
  }
}
