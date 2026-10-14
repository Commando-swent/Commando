package com.android.sample.ui.orders

// AI assistance: Claude (Anthropic).
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.sample.R
import com.android.sample.data.repository.FakeOrderRepository
import com.android.sample.data.repository.FakeTripRepository
import com.android.sample.model.Location
import com.android.sample.model.Order
import com.android.sample.model.OrderItem
import com.android.sample.model.OrderStatus
import com.android.sample.model.Trip
import com.android.sample.model.TripStatus
import com.android.sample.ui.theme.SampleAppTheme
import com.android.sample.ui.trips.formatTripDeparture
import java.time.Instant

object MyOrdersTestTags {
  const val LOADING = "my_orders_loading"
  const val EMPTY = "my_orders_empty"
  const val ERROR = "my_orders_error"
  const val RETRY = "my_orders_retry"
  const val BROWSE_TRIPS = "my_orders_browse_trips"
  const val ORDER_CARD_PREFIX = "my_orders_card_"
}

/** Binds the supplied signed-in requester; the ViewModel clears the list when it changes. */
@Composable
fun MyOrdersScreen(
    viewModel: MyOrdersViewModel,
    requesterId: String,
    onOrderSelected: (String) -> Unit,
    onBrowseTrips: () -> Unit,
    modifier: Modifier = Modifier,
) {
  LaunchedEffect(viewModel, requesterId) { viewModel.setRequester(requesterId) }
  val state by viewModel.uiState.collectAsState()
  MyOrdersScreen(state, viewModel::retry, onOrderSelected, onBrowseTrips, modifier)
}

@Composable
fun MyOrdersScreen(
    state: MyOrdersUiState,
    onRetry: () -> Unit,
    onOrderSelected: (String) -> Unit,
    onBrowseTrips: () -> Unit,
    modifier: Modifier = Modifier,
) {
  val colors = MaterialTheme.colorScheme
  Column(
      modifier.fillMaxSize().background(colors.background).padding(horizontal = 20.dp),
      verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    Column(Modifier.padding(start = 4.dp, top = 16.dp)) {
      Text(
          stringResource(R.string.my_orders_title),
          color = colors.onBackground,
          style = MaterialTheme.typography.headlineMedium,
      )
      Text(
          stringResource(R.string.my_orders_subtitle),
          color = colors.onSurfaceVariant,
          style = MaterialTheme.typography.bodySmall,
      )
    }
    when (state) {
      MyOrdersUiState.Loading -> {
        val loading = stringResource(R.string.my_orders_loading)
        Column(
            Modifier.testTag(MyOrdersTestTags.LOADING).semantics { contentDescription = loading },
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
          repeat(3) { SkeletonCard() }
        }
      }
      is MyOrdersUiState.Content ->
          LazyColumn(
              verticalArrangement = Arrangement.spacedBy(14.dp),
              contentPadding = PaddingValues(bottom = 20.dp),
          ) {
            items(state.rows, key = { it.order.id }) { row ->
              OrderCard(row) { onOrderSelected(row.order.id) }
            }
          }
      MyOrdersUiState.Empty ->
          StateCard(
              tag = MyOrdersTestTags.EMPTY,
              icon = R.drawable.ic_trip_shopping_bag,
              message = stringResource(R.string.my_orders_empty),
              help = stringResource(R.string.my_orders_empty_help),
              action = stringResource(R.string.my_orders_browse_trips),
              actionTag = MyOrdersTestTags.BROWSE_TRIPS,
              onAction = onBrowseTrips,
          )
      is MyOrdersUiState.Error ->
          StateCard(
              tag = MyOrdersTestTags.ERROR,
              icon = R.drawable.ic_trip_alert_circle,
              message = stringResource(R.string.my_orders_error),
              help = null,
              action = stringResource(R.string.my_orders_retry),
              actionTag = MyOrdersTestTags.RETRY,
              onAction = onRetry,
          )
    }
  }
}

@Composable
private fun OrderCard(row: MyOrderRow, onClick: () -> Unit) {
  val colors = MaterialTheme.colorScheme
  val locale = LocalConfiguration.current.locales[0]
  val trip = row.trip
  // Plural category only; the displayed count stays exact.
  val quantity = row.itemCount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
  Card(
      onClick = onClick,
      modifier = Modifier.fillMaxWidth().testTag(MyOrdersTestTags.ORDER_CARD_PREFIX + row.order.id),
      shape = RoundedCornerShape(16.dp),
      colors = CardDefaults.cardColors(containerColor = colors.surfaceContainer),
      border = BorderStroke(1.dp, colors.outlineVariant),
  ) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            trip?.store?.name ?: stringResource(R.string.my_orders_trip_unavailable),
            modifier = Modifier.weight(1f),
            color = colors.onSurface,
            style = MaterialTheme.typography.titleMedium,
        )
        Text("›", Modifier.clearAndSetSemantics {}, color = colors.onSurfaceVariant)
      }
      Row(verticalAlignment = Alignment.CenterVertically) {
        StatusBadge(row.order.status)
        Spacer(Modifier.weight(1f))
        Text(
            pluralStringResource(
                R.plurals.my_orders_items,
                quantity,
                row.itemCount,
                money(row.order.commissionCents),
            ),
            color = colors.onSurface,
            style = MaterialTheme.typography.bodyMedium,
        )
      }
      if (trip != null) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Icon(
              painterResource(R.drawable.ic_trip_calendar),
              contentDescription = null,
              modifier = Modifier.size(20.dp),
              tint = colors.onSurfaceVariant,
          )
          Text(
              formatTripDeparture(trip.scheduledAt, locale),
              color = colors.onSurface,
              style = MaterialTheme.typography.bodySmall,
          )
        }
        Text(
            stringResource(R.string.my_orders_handoff, trip.handoffLocation.name),
            color = colors.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
      }
    }
  }
}

/** Shows the stored status as is; no status is derived or invented here. */
@Composable
private fun StatusBadge(status: OrderStatus) {
  val colors = MaterialTheme.colorScheme
  // ponytail: one badge style while the contract only has PENDING; per-status colors with more.
  Text(
      status.name,
      Modifier.background(colors.secondaryContainer, CircleShape)
          .border(1.dp, colors.outline, CircleShape)
          .padding(horizontal = 10.dp, vertical = 2.dp),
      color = colors.onSecondaryContainer,
      style = MaterialTheme.typography.labelSmall,
  )
}

@Composable
private fun SkeletonCard() {
  val colors = MaterialTheme.colorScheme
  Card(
      modifier = Modifier.fillMaxWidth(),
      shape = RoundedCornerShape(16.dp),
      colors = CardDefaults.cardColors(containerColor = colors.surfaceContainer),
      border = BorderStroke(1.dp, colors.outlineVariant),
  ) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      listOf(0.6f, 0.25f, 0.5f, 0.65f).forEach { width ->
        Box(
            Modifier.fillMaxWidth(width)
                .height(16.dp)
                .background(colors.surfaceContainerHigh, RoundedCornerShape(8.dp))
        )
      }
    }
  }
}

@Composable
private fun StateCard(
    tag: String,
    icon: Int,
    message: String,
    help: String?,
    action: String,
    actionTag: String,
    onAction: () -> Unit,
) {
  val colors = MaterialTheme.colorScheme
  Card(
      modifier =
          Modifier.fillMaxWidth().testTag(tag).semantics { liveRegion = LiveRegionMode.Polite },
      shape = RoundedCornerShape(16.dp),
      colors = CardDefaults.cardColors(containerColor = colors.surfaceContainer),
      border = BorderStroke(1.dp, colors.outlineVariant),
  ) {
    Column(
        Modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      Box(
          Modifier.size(88.dp).background(colors.primary, CircleShape),
          contentAlignment = Alignment.Center,
      ) {
        Icon(
            painterResource(icon),
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = colors.onPrimary,
        )
      }
      Text(
          message,
          color = colors.onSurface,
          style = MaterialTheme.typography.titleLarge,
          textAlign = TextAlign.Center,
      )
      if (help != null) {
        Text(
            help,
            color = colors.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
        )
      }
      Button(
          onClick = onAction,
          modifier = Modifier.fillMaxWidth().height(56.dp).testTag(actionTag),
          colors =
              ButtonDefaults.buttonColors(
                  containerColor = colors.primary,
                  contentColor = colors.onPrimary,
              ),
      ) {
        Text(action)
      }
    }
  }
}

/** Interactive preview backed by the shared in-memory fakes; no Firestore involved. */
@Preview(name = "My Orders", widthDp = 412, heightDp = 915)
@Composable
private fun MyOrdersScreenPreview() {
  val requester = "preview-requester"
  val viewModel = remember {
    val createdAt = Instant.parse("2026-10-13T12:00:00Z")
    val trip =
        Trip(
            id = "preview-trip",
            ownerId = "preview-commando",
            store = Location("Migros EPFL", 46.52, 6.57),
            scheduledAt = Instant.parse("2026-10-13T16:30:00Z"),
            handoffLocation = Location("Rolex Learning Center", 46.52, 6.57),
            status = TripStatus.PUBLISHED,
            createdAt = createdAt,
            updatedAt = createdAt,
        )
    val order =
        Order(
            id = "preview-order",
            tripId = trip.id,
            requesterId = requester,
            items = listOf(OrderItem("milk", "Milk", 165, 2), OrderItem("bread", "Bread", 290, 1)),
            commissionCents = 450,
            status = OrderStatus.PENDING,
            createdAt = createdAt,
        )
    MyOrdersViewModel(
        FakeOrderRepository({ requester }, listOf(order)),
        FakeTripRepository(trip.ownerId, initialTrips = listOf(trip)),
        requester,
    )
  }
  SampleAppTheme { MyOrdersScreen(viewModel, requester, onOrderSelected = {}, onBrowseTrips = {}) }
}
