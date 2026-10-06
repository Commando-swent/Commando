package com.android.sample.ui.home

// AI assistance: OpenAI Codex. Content from the Command'o Figma Home states.
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.sample.R
import com.android.sample.ui.navigation.AppMode
import com.android.sample.ui.navigation.AppScaffold
import com.android.sample.ui.navigation.CommandoScreens
import com.android.sample.ui.navigation.NavigationTestTags
import com.android.sample.ui.theme.SampleAppTheme

/** Trip actions become enabled when their destinations are connected by the navigation root. */
@Composable
fun HomeScreen(
    mode: AppMode,
    modifier: Modifier = Modifier,
    onFindTrip: (() -> Unit)? = null,
    onPublishTrip: (() -> Unit)? = null,
) {
  val requester = mode == AppMode.Requester
  Column(
      modifier
          .fillMaxSize()
          .testTag(NavigationTestTags.HOME_SCREEN)
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

@Composable
private fun HomeImage(resource: Int, modifier: Modifier) {
  Image(painterResource(resource), contentDescription = null, modifier = modifier)
}

@Preview(name = "Requester", widthDp = 412, heightDp = 915)
@Composable
private fun RequesterHomePreview() {
  HomePreview(AppMode.Requester)
}

@Preview(name = "Commando", widthDp = 412, heightDp = 915)
@Composable
private fun CommandoHomePreview() {
  HomePreview(AppMode.Commando)
}

@Composable
private fun HomePreview(mode: AppMode) {
  SampleAppTheme {
    AppScaffold(
        CommandoScreens.Home,
        mode,
        onSwitchMode = {},
        onHome = {},
        onProfile = {},
        onFindTrip = {},
    ) { padding ->
      HomeScreen(mode, Modifier.padding(padding), onFindTrip = {}, onPublishTrip = {})
    }
  }
}
