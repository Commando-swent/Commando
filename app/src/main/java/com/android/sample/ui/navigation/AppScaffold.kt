package com.android.sample.ui.navigation

// AI assistance: OpenAI Codex. Shared bars from the Command'o Figma design.
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.sample.R

/** Shared layout for authenticated destinations; content owns neither bars nor navigation. */
@Composable
fun AppScaffold(
    currentScreen: CommandoScreens,
    mode: AppMode,
    onSwitchMode: (AppMode) -> Unit,
    onHome: () -> Unit,
    onProfile: () -> Unit,
    content: @Composable (PaddingValues) -> Unit,
) {
  Scaffold(
      modifier = Modifier.testTag(AppTestTags.APP_SCAFFOLD),
      containerColor = MaterialTheme.colorScheme.background,
      topBar = { if (currentScreen.showModeSelector) AppTopBar(mode, onSwitchMode) },
      bottomBar = {
        if (currentScreen.showBottomBar) {
          AppBottomBar(currentScreen, onHome, onProfile)
        }
      },
      content = content,
  )
}

@Composable
private fun AppTopBar(mode: AppMode, onSwitchMode: (AppMode) -> Unit) {
  Row(
      Modifier.testTag(AppTestTags.TOP_BAR)
          .fillMaxWidth()
          .statusBarsPadding()
          .height(64.dp)
          .padding(horizontal = 16.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    AppImage(R.drawable.home_logo, Modifier.size(30.dp))
    Row(
        Modifier.weight(1f)
            .height(42.dp)
            .clip(RoundedCornerShape(20.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(20.dp))
            .selectableGroup(),
    ) {
      AppMode.entries.forEach { option ->
        val selected = mode == option
        Row(
            Modifier.weight(1f)
                .fillMaxHeight()
                .background(
                    if (selected) MaterialTheme.colorScheme.secondaryContainer
                    else MaterialTheme.colorScheme.background
                )
                .testTag(
                    if (option == AppMode.Requester) AppTestTags.REQUESTER_MODE
                    else AppTestTags.COMMANDO_MODE
                )
                .selectable(
                    selected,
                    role = Role.RadioButton,
                    onClick = { onSwitchMode(option) },
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
        ) {
          if (selected) AppImage(R.drawable.home_selected, Modifier.size(16.dp))
          Text(
              stringResource(
                  if (option == AppMode.Requester) R.string.home_requester
                  else R.string.home_commando
              ),
              fontSize = 13.sp,
              style = MaterialTheme.typography.labelMedium,
              color =
                  if (selected) MaterialTheme.colorScheme.onSecondaryContainer
                  else MaterialTheme.colorScheme.onSurface,
          )
        }
        if (option == AppMode.Requester) VerticalDivider(color = MaterialTheme.colorScheme.outline)
      }
    }
  }
}

@Composable
private fun AppBottomBar(
    currentScreen: CommandoScreens,
    onHome: () -> Unit,
    onProfile: () -> Unit,
) {
  NavigationBar(
      modifier = Modifier.testTag(AppTestTags.BOTTOM_BAR),
      containerColor = MaterialTheme.colorScheme.surfaceContainer,
  ) {
    AppNavigationItem(
        R.drawable.home_tab,
        R.string.nav_home,
        modifier = Modifier.testTag(AppTestTags.HOME_BUTTON),
        selected = currentScreen == CommandoScreens.Home,
        onClick = onHome,
    )
    AppNavigationItem(
        R.drawable.home_trips,
        R.string.home_trips,
        enabled = false,
        onClick = {},
    )
    AppNavigationItem(R.drawable.home_map, R.string.home_map, enabled = false, onClick = {})
    AppNavigationItem(
        R.drawable.home_profile,
        R.string.nav_profile,
        modifier = Modifier.testTag(AppTestTags.PROFILE_BUTTON),
        selected = currentScreen == CommandoScreens.Profile,
        onClick = onProfile,
    )
  }
}

@Composable
private fun AppImage(resource: Int, modifier: Modifier) {
  Image(painterResource(resource), contentDescription = null, modifier = modifier)
}

@Composable
private fun RowScope.AppNavigationItem(
    icon: Int,
    label: Int,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
  NavigationBarItem(
      selected = selected,
      onClick = onClick,
      enabled = enabled,
      modifier = modifier,
      icon = { AppImage(icon, Modifier.size(24.dp)) },
      label = {
        Text(
            stringResource(label),
            style = MaterialTheme.typography.labelSmall,
            color =
                if (selected) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant,
        )
      },
  )
}
