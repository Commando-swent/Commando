package com.android.sample.ui.home

// AI assistance: OpenAI Codex. Assets and layout: Figma Home / Requester & Commando.
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.sample.R
import com.android.sample.ui.navigation.NavigationTestTags
import com.android.sample.ui.theme.SampleAppTheme

/** Trip actions become enabled when their destinations are connected by the navigation root. */
@Composable
fun HomeScreen(
    uiState: HomeUiState,
    onSwitchMode: (HomeMode) -> Unit,
    onProfile: () -> Unit,
    onFindTrip: (() -> Unit)? = null,
    onPublishTrip: (() -> Unit)? = null,
) {
  val requester = uiState.mode == HomeMode.Requester
  Scaffold(
      modifier = Modifier.testTag(NavigationTestTags.HOME_SCREEN),
      containerColor = MaterialTheme.colorScheme.background,
      topBar = {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().height(64.dp).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
          HomeImage(R.drawable.home_logo, Modifier.size(30.dp))
          Row(
              Modifier.weight(1f)
                  .height(42.dp)
                  .clip(RoundedCornerShape(20.dp))
                  .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(20.dp))
                  .selectableGroup(),
          ) {
            HomeMode.entries.forEach { mode ->
              val selected = uiState.mode == mode
              Row(
                  Modifier.weight(1f)
                      .fillMaxHeight()
                      .background(
                          if (selected) MaterialTheme.colorScheme.secondaryContainer
                          else MaterialTheme.colorScheme.background
                      )
                      .testTag(
                          if (mode == HomeMode.Requester) HomeTestTags.REQUESTER_MODE
                          else HomeTestTags.COMMANDO_MODE
                      )
                      .selectable(
                          selected,
                          role = Role.RadioButton,
                          onClick = { onSwitchMode(mode) },
                      ),
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
              ) {
                if (selected) HomeImage(R.drawable.home_selected, Modifier.size(16.dp))
                Text(
                    stringResource(
                        if (mode == HomeMode.Requester) R.string.home_requester
                        else R.string.home_commando
                    ),
                    fontSize = 13.sp,
                    style = MaterialTheme.typography.labelMedium,
                    color =
                        if (selected) MaterialTheme.colorScheme.onSecondaryContainer
                        else MaterialTheme.colorScheme.onSurface,
                )
              }
              if (mode == HomeMode.Requester)
                  VerticalDivider(color = MaterialTheme.colorScheme.outline)
            }
          }
        }
      },
      bottomBar = {
        NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
          HomeNavigationItem(
              R.drawable.home_tab,
              R.string.nav_home,
              selected = true,
              onClick = {},
          )
          HomeNavigationItem(
              R.drawable.home_trips,
              R.string.home_trips,
              enabled = onFindTrip != null,
              onClick = { onFindTrip?.invoke() },
          )
          HomeNavigationItem(
              R.drawable.home_map,
              R.string.home_map,
              enabled = false,
              onClick = {},
          )
          HomeNavigationItem(
              R.drawable.home_profile,
              R.string.nav_profile,
              modifier = Modifier.testTag(NavigationTestTags.PROFILE_BUTTON),
              onClick = onProfile,
          )
        }
      },
  ) { padding ->
    Column(
        Modifier.fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
      HomeImage(
          if (requester) R.drawable.home_groceries else R.drawable.home_scooter,
          if (requester) Modifier.size(200.dp) else Modifier.size(width = 220.dp, height = 184.dp),
      )
      Text(
          stringResource(
              if (requester) R.string.home_requester_title else R.string.home_commando_title
          ),
          modifier = Modifier.testTag(HomeTestTags.EMPTY_STATE_TITLE),
          style = MaterialTheme.typography.headlineMedium,
          textAlign = TextAlign.Center,
      )
      Text(
          stringResource(
              if (requester) R.string.home_requester_description
              else R.string.home_commando_description
          ),
          modifier = Modifier.widthIn(max = 300.dp),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          textAlign = TextAlign.Center,
      )
      val action = if (requester) onFindTrip else onPublishTrip
      Button(
          onClick = { action?.invoke() },
          enabled = action != null,
          modifier =
              Modifier.padding(top = 8.dp)
                  .width(220.dp)
                  .height(56.dp)
                  .testTag(HomeTestTags.TRIP_ACTION),
      ) {
        if (!requester) HomeImage(R.drawable.home_add, Modifier.size(20.dp))
        Spacer(Modifier.width(if (requester) 0.dp else 8.dp))
        Text(stringResource(if (requester) R.string.home_find_trip else R.string.home_publish_trip))
        if (requester) {
          Spacer(Modifier.width(8.dp))
          HomeImage(R.drawable.home_arrow, Modifier.size(20.dp))
        }
      }
    }
  }
}

@Composable
private fun HomeImage(resource: Int, modifier: Modifier) {
  Image(painterResource(resource), contentDescription = null, modifier = modifier)
}

@Composable
private fun RowScope.HomeNavigationItem(
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
      icon = { HomeImage(icon, Modifier.size(24.dp)) },
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

@Preview(name = "Requester", widthDp = 412, heightDp = 915)
@Composable
private fun RequesterHomePreview() {
  SampleAppTheme { HomeScreen(HomeUiState(), onSwitchMode = {}, onProfile = {}, onFindTrip = {}) }
}

@Preview(name = "Commando", widthDp = 412, heightDp = 915)
@Composable
private fun CommandoHomePreview() {
  SampleAppTheme {
    HomeScreen(
        HomeUiState(HomeMode.Commando),
        onSwitchMode = {},
        onProfile = {},
        onPublishTrip = {},
    )
  }
}
