package com.android.sample.ui.navigation

// AI assistance: OpenAI Codex. Shared bars from the Command'o Figma design.
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
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
    onTrips: () -> Unit = {},
    onBack: (() -> Unit)? = null,
    content: @Composable (PaddingValues) -> Unit,
) {
  Scaffold(
      modifier = Modifier.testTag(AppTestTags.APP_SCAFFOLD),
      containerColor = MaterialTheme.colorScheme.background,
      topBar = {
        if (currentScreen.showModeSelector) {
          AppTopBar(
              mode,
              onSwitchMode,
              commandoEnabled = currentScreen != CommandoScreens.AvailableTrips,
          )
        } else if (currentScreen == CommandoScreens.TripDetails && onBack != null) {
          AppBackBar(onBack)
        }
      },
      bottomBar = {
        if (currentScreen.showBottomBar) {
          AppBottomBar(currentScreen, onHome, onProfile, onTrips)
        }
      },
      content = content,
  )
}

@Composable
private fun AppBackBar(onBack: () -> Unit) {
  Row(
      Modifier.fillMaxWidth().statusBarsPadding().height(72.dp).padding(horizontal = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    IconButton(
        onClick = onBack,
        modifier = Modifier.size(48.dp).testTag(AppTestTags.BACK_BUTTON),
    ) {
      Box(
          Modifier.size(40.dp).background(Color.Black.copy(alpha = 0.33f), CircleShape),
          contentAlignment = Alignment.Center,
      ) {
        Icon(
            painter = painterResource(R.drawable.nav_back),
            contentDescription = stringResource(R.string.nav_back),
            tint = Color.White,
            modifier = Modifier.size(24.dp),
        )
      }
    }
  }
}

@Composable
private fun AppTopBar(
    mode: AppMode,
    onSwitchMode: (AppMode) -> Unit,
    commandoEnabled: Boolean,
) {
  Row(
      Modifier.testTag(AppTestTags.TOP_BAR)
          .fillMaxWidth()
          .statusBarsPadding()
          .height(64.dp)
          .padding(start = 16.dp, end = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    AppImage(R.drawable.nav_logo, Modifier.size(30.dp))
    Row(
        Modifier.weight(1f)
            .padding(horizontal = 8.dp)
            .height(42.dp)
            .clip(RoundedCornerShape(20.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(20.dp))
            .selectableGroup(),
    ) {
      AppMode.entries.forEach { option ->
        val selected = mode == option
        val enabled = option != AppMode.Commando || commandoEnabled
        Row(
            Modifier.weight(1f)
                .alpha(if (enabled) 1f else 0.38f)
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
                    enabled = enabled,
                    role = Role.RadioButton,
                    onClick = { onSwitchMode(option) },
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
        ) {
          if (selected) AppImage(R.drawable.nav_selected, Modifier.size(16.dp))
          Text(
              stringResource(
                  if (option == AppMode.Requester) R.string.home_requester
                  else R.string.home_commando
              ),
              fontSize = 13.sp,
              lineHeight = 20.sp,
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
    onTrips: () -> Unit,
) {
  NavigationBar(
      modifier = Modifier.testTag(AppTestTags.BOTTOM_BAR),
      containerColor = MaterialTheme.colorScheme.surfaceContainer,
  ) {
    AppNavigationItem(
        R.drawable.nav_home,
        R.string.nav_home,
        modifier = Modifier.testTag(AppTestTags.HOME_BUTTON),
        selected = currentScreen == CommandoScreens.Home,
        onClick = onHome,
    )
    AppNavigationItem(
        R.drawable.nav_trips,
        R.string.home_trips,
        modifier = Modifier.testTag(AppTestTags.TRIPS_BUTTON),
        selected = currentScreen == CommandoScreens.AvailableTrips,
        onClick = onTrips,
    )
    AppNavigationItem(
        R.drawable.nav_orders,
        R.string.nav_orders,
        modifier = Modifier.testTag(AppTestTags.ORDERS_BUTTON),
        enabled = false,
        onClick = {},
    )
    AppNavigationItem(
        R.drawable.nav_profile,
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
      colors =
          NavigationBarItemDefaults.colors(
              selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
              selectedTextColor = MaterialTheme.colorScheme.onSurface,
              indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
              unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
              unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
              disabledIconColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
              disabledTextColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
          ),
      icon = {
        Box(Modifier.width(32.dp).height(24.dp), contentAlignment = Alignment.Center) {
          Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(24.dp))
        }
      },
      label = {
        Text(
            stringResource(label),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            lineHeight = 15.sp,
        )
      },
  )
}
