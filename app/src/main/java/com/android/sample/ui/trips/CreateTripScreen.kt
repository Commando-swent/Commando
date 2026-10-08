package com.android.sample.ui.trips

// AI assistance: Claude Code. Layout from the Command'o Figma "Publish a trip" frames.
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.android.sample.R
import com.android.sample.data.repository.TripError
import com.android.sample.model.Location
import com.android.sample.model.Trip
import com.android.sample.model.TripLocations
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Lets a commando publish a delivery run. [onTripPublished] is called once per published trip,
 * after the confirmation has been shown, and the back action is unavailable while publishing.
 */
@Composable
fun CreateTripScreen(
    viewModel: CreateTripViewModel,
    onBack: () -> Unit,
    onTripPublished: (Trip) -> Unit,
    modifier: Modifier = Modifier,
) {
  val state by viewModel.uiState.collectAsState()
  val snackbarHostState = remember { SnackbarHostState() }
  val publishedMessage = stringResource(R.string.create_trip_published)
  val currentOnTripPublished by rememberUpdatedState(onTripPublished)
  var handledTripId by rememberSaveable { mutableStateOf<String?>(null) }
  val publishedTrip = state.publishedTrip

  LaunchedEffect(publishedTrip) {
    if (publishedTrip != null && publishedTrip.id != handledTripId) {
      // Confirm first: the callback may leave this screen, which would dismiss the snackbar.
      snackbarHostState.showSnackbar(publishedMessage)
      handledTripId = publishedTrip.id
      currentOnTripPublished(publishedTrip)
    }
  }
  // Leaving would cancel the publication, so system back is ignored while it runs.
  BackHandler(enabled = state.isPublishing) {}

  CreateTripContent(
      state = state,
      onBack = onBack,
      onStoreSelected = viewModel::setStore,
      onDateSelected = viewModel::setDate,
      onTimeSelected = viewModel::setTime,
      onHandoffLocationSelected = viewModel::setHandoffLocation,
      onPublish = viewModel::publish,
      modifier = modifier,
      snackbarHostState = snackbarHostState,
  )
}

@Composable
fun CreateTripContent(
    state: CreateTripUiState,
    onBack: () -> Unit,
    onStoreSelected: (Location) -> Unit,
    onDateSelected: (LocalDate) -> Unit,
    onTimeSelected: (LocalTime) -> Unit,
    onHandoffLocationSelected: (Location) -> Unit,
    onPublish: () -> Unit,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    today: LocalDate = LocalDate.now(),
) {
  val editable = !state.isPublishing && state.publishedTrip == null
  var showDatePicker by rememberSaveable { mutableStateOf(false) }
  var showTimePicker by rememberSaveable { mutableStateOf(false) }

  Scaffold(
      modifier = modifier,
      containerColor = MaterialTheme.colorScheme.background,
      contentWindowInsets = WindowInsets(0),
      topBar = { CreateTripTopBar(backEnabled = !state.isPublishing, onBack = onBack) },
      bottomBar = { PublishBar(state = state, onPublish = onPublish) },
      snackbarHost = { SnackbarHost(snackbarHostState) },
  ) { padding ->
    Column(
        modifier =
            Modifier.fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
      Text(
          text = stringResource(R.string.create_trip_intro),
          style = tripTextStyle(14, 20),
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      state.publishError?.let { PublishErrorBanner(it) }
      LocationPickerField(
          label = R.string.create_trip_store_label,
          icon = R.drawable.ic_trip_shopping_bag,
          selected = state.store,
          options = TripLocations.stores,
          placeholder = R.string.create_trip_store_placeholder,
          helper = R.string.create_trip_store_helper,
          error = state.storeError,
          enabled = editable,
          onSelected = onStoreSelected,
          tag = CreateTripScreenTestTags.STORE_FIELD,
          errorTag = CreateTripScreenTestTags.STORE_ERROR,
      )
      Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        PickerField(
            label = R.string.create_trip_date_label,
            icon = R.drawable.ic_trip_calendar,
            value = state.date?.let { formatDate(it, today) },
            placeholder = R.string.create_trip_date_placeholder,
            helper = R.string.create_trip_date_helper,
            error = state.dateError,
            enabled = editable,
            onClick = { showDatePicker = true },
            tag = CreateTripScreenTestTags.DATE_FIELD,
            errorTag = CreateTripScreenTestTags.DATE_ERROR,
            modifier = Modifier.weight(1f),
        )
        PickerField(
            label = R.string.create_trip_time_label,
            icon = R.drawable.ic_trip_clock,
            value = state.time?.format(TimeFormatter),
            placeholder = R.string.create_trip_time_placeholder,
            helper = R.string.create_trip_time_helper,
            error = state.timeError,
            enabled = editable,
            onClick = { showTimePicker = true },
            tag = CreateTripScreenTestTags.TIME_FIELD,
            errorTag = CreateTripScreenTestTags.TIME_ERROR,
            modifier = Modifier.weight(1f),
        )
      }
      LocationPickerField(
          label = R.string.create_trip_handoff_label,
          icon = R.drawable.ic_trip_map_pin,
          selected = state.handoffLocation,
          options = TripLocations.handoffPoints,
          placeholder = R.string.create_trip_handoff_placeholder,
          helper = R.string.create_trip_handoff_helper,
          error = state.handoffLocationError,
          enabled = editable,
          onSelected = onHandoffLocationSelected,
          tag = CreateTripScreenTestTags.HANDOFF_LOCATION_FIELD,
          errorTag = CreateTripScreenTestTags.HANDOFF_LOCATION_ERROR,
      )
    }
  }

  if (showDatePicker) {
    TripDatePickerDialog(state.date, today, onDismiss = { showDatePicker = false }) {
      showDatePicker = false
      onDateSelected(it)
    }
  }
  if (showTimePicker) {
    TripTimePickerDialog(state.time, onDismiss = { showTimePicker = false }) {
      showTimePicker = false
      onTimeSelected(it)
    }
  }
}

private val TimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val DayMonthFormatter = DateTimeFormatter.ofPattern("d MMM")
private val WeekdayDayMonthFormatter = DateTimeFormatter.ofPattern("EEE, d MMM")

@Composable
private fun formatDate(date: LocalDate, today: LocalDate): String =
    when (date) {
      today -> stringResource(R.string.create_trip_date_today, date.format(DayMonthFormatter))
      today.plusDays(1) ->
          stringResource(R.string.create_trip_date_tomorrow, date.format(DayMonthFormatter))
      else -> date.format(WeekdayDayMonthFormatter)
    }

@Composable
private fun CreateTripTopBar(backEnabled: Boolean, onBack: () -> Unit) {
  Row(
      modifier =
          Modifier.fillMaxWidth()
              .statusBarsPadding()
              .padding(start = 16.dp, end = 24.dp, top = 12.dp, bottom = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Surface(
        onClick = onBack,
        enabled = backEnabled,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier =
            Modifier.size(40.dp)
                .alpha(if (backEnabled) 1f else 0.5f)
                .testTag(CreateTripScreenTestTags.BACK_BUTTON),
    ) {
      Box(contentAlignment = Alignment.Center) {
        Icon(
            painter = painterResource(R.drawable.ic_trip_arrow_left),
            contentDescription = stringResource(R.string.create_trip_back),
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(24.dp),
        )
      }
    }
    Text(
        text = stringResource(R.string.create_trip_title),
        style = tripTextStyle(20, 28, FontWeight.Bold),
        color = MaterialTheme.colorScheme.onSurface,
    )
  }
}

/** Shown after a failed publication; the inputs are kept so the user can try again. */
@Composable
internal fun PublishErrorBanner(error: TripError) {
  val message =
      when (error) {
        TripError.NetworkError -> R.string.create_trip_publish_failed_network
        TripError.PermissionDenied -> R.string.create_trip_publish_failed_permission
        TripError.InvalidData -> R.string.create_trip_publish_failed_invalid
        TripError.NotFound,
        TripError.Unknown -> R.string.create_trip_publish_failed_unknown
      }
  Row(
      modifier =
          Modifier.fillMaxWidth()
              .background(MaterialTheme.colorScheme.errorContainer, FieldShape)
              .padding(16.dp)
              .testTag(CreateTripScreenTestTags.ERROR_BANNER),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Icon(
        painter = painterResource(R.drawable.ic_trip_alert_circle),
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier.size(24.dp),
    )
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
      Text(
          text = stringResource(R.string.create_trip_publish_failed_title),
          style = tripTextStyle(14, 20, FontWeight.Bold),
          color = MaterialTheme.colorScheme.onErrorContainer,
      )
      Text(
          text = stringResource(message),
          style = tripTextStyle(13, 18),
          color = MaterialTheme.colorScheme.onErrorContainer,
      )
    }
  }
}

/** The bottom bar: "Publish trip", a spinner while publishing, then "Try again" after a failure. */
@Composable
internal fun PublishBar(state: CreateTripUiState, onPublish: () -> Unit) {
  val colors = MaterialTheme.colorScheme
  val publishing = state.isPublishing
  Box(
      Modifier.fillMaxWidth()
          .background(colors.background)
          .navigationBarsPadding()
          .padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 28.dp)
  ) {
    Button(
        onClick = onPublish,
        enabled = state.canPublish,
        shape = CircleShape,
        elevation = null,
        contentPadding = PaddingValues(horizontal = 24.dp),
        colors =
            ButtonDefaults.buttonColors(
                containerColor = colors.primary,
                contentColor = colors.onPrimary,
                disabledContainerColor =
                    if (publishing) colors.primaryContainer else colors.surfaceContainerHigh,
                disabledContentColor =
                    if (publishing) colors.onPrimaryContainer else colors.onSurfaceVariant,
            ),
        modifier =
            Modifier.fillMaxWidth()
                .height(56.dp)
                .shadow(
                    elevation = if (state.canPublish) 12.dp else 0.dp,
                    shape = CircleShape,
                    ambientColor = colors.primary,
                    spotColor = colors.primary,
                )
                .testTag(CreateTripScreenTestTags.PUBLISH_BUTTON),
    ) {
      if (publishing) {
        CircularProgressIndicator(
            color = colors.onPrimaryContainer,
            strokeWidth = 2.dp,
            modifier = Modifier.size(24.dp).testTag(CreateTripScreenTestTags.PUBLISH_PROGRESS),
        )
        Spacer(Modifier.width(10.dp))
      }
      val label =
          when {
            publishing -> R.string.create_trip_publishing
            state.publishError != null -> R.string.create_trip_retry
            else -> R.string.create_trip_publish
          }
      Text(stringResource(label), style = tripTextStyle(16, 24, FontWeight.Bold))
    }
  }
}
