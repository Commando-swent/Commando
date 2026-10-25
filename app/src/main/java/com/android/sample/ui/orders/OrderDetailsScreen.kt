package com.android.sample.ui.orders

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.sample.R
import com.android.sample.data.repository.OrderError
import com.android.sample.model.*
import com.android.sample.ui.theme.SampleAppTheme
import com.android.sample.ui.trips.formatTripDeparture
import java.time.Instant

/** Shared read-only presentation; the caller owns loading, authorization and navigation. */
@Composable
fun OrderDetailsScreen(
    state: OrderDetailsUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
  val colors = MaterialTheme.colorScheme
  Column(
      modifier
          .fillMaxSize()
          .background(
              Brush.verticalGradient(
                  0f to Color(0xFF0D1A08),
                  0.4f to Color(0xFF1A2E0F),
                  1f to colors.surfaceContainer,
              )
          )
          .safeDrawingPadding()
  ) {
    // Back stays outside the viewport, including while loading or displaying an error.
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier.size(48.dp).background(colors.secondaryContainer, CircleShape),
        ) {
          Icon(
              painterResource(R.drawable.ic_trip_arrow_left),
              stringResource(R.string.nav_back),
              Modifier.size(24.dp),
              tint = colors.onSurfaceVariant,
          )
        }
        Text(
            stringResource(R.string.order_details_title),
            Modifier.weight(1f).semantics { heading() },
            color = colors.onSurface,
            style = MaterialTheme.typography.titleMedium.copy(fontSize = 26.sp, lineHeight = 35.sp),
        )
      }
      when (state) {
        is OrderDetailsUiState.Content -> OrderDetailsStatus(state.order.status)
        OrderDetailsUiState.Loading -> OrderDetailsPlaceholder(Modifier.width(92.dp).height(30.dp))
        else -> Unit
      }
    }
    Column(
        Modifier.weight(1f)
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      when (state) {
        is OrderDetailsUiState.Content -> OrderDetailsContent(state.order, state.trip)
        OrderDetailsUiState.Loading -> OrderDetailsSkeleton()
        OrderDetailsUiState.NotFound -> OrderDetailsMessage(false, onRetry)
        is OrderDetailsUiState.Error -> OrderDetailsMessage(true, onRetry)
      }
    }
  }
}

@Composable
private fun OrderDetailsContent(order: Order, trip: Trip) {
  val locale = LocalConfiguration.current.locales[0]
  OrderDetailsCard {
    OrderDetailsSection(stringResource(R.string.order_details_trip))
    val fields =
        listOf(
            Triple(R.drawable.ic_trip_shopping_bag, R.string.order_details_store, trip.store.name),
            Triple(
                R.drawable.ic_trip_calendar,
                R.string.trip_details_shopping_time,
                formatTripDeparture(trip.scheduledAt, locale),
            ),
            Triple(
                R.drawable.ic_trip_map_pin,
                R.string.order_details_handoff,
                trip.handoffLocation.name,
            ),
        )
    fields.forEachIndexed { index, (icon, label, value) ->
      if (index > 0) OrderDetailsDivider()
      OrderDetailsField(icon, stringResource(label), value)
    }
  }
  OrderDetailsCard {
    OrderDetailsSection(stringResource(R.string.order_details_items))
    order.items.forEachIndexed { index, item ->
      if (index > 0) OrderDetailsDivider()
      Box(
          Modifier.fillMaxWidth().heightIn(min = 32.dp),
          contentAlignment = Alignment.CenterStart,
      ) {
        Text(
            stringResource(R.string.order_details_item, item.quantity, item.name),
            Modifier.fillMaxWidth(),
            style =
                MaterialTheme.typography.bodyLarge.copy(
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
        )
      }
    }
  }
  OrderDetailsCard {
    OrderDetailsSection(stringResource(R.string.order_details_commission))
    Text(
        money(order.commissionCents),
        style = MaterialTheme.typography.titleMedium.copy(fontSize = 26.sp, lineHeight = 35.sp),
    )
  }
}

@Composable
private fun OrderDetailsCard(content: @Composable ColumnScope.() -> Unit) {
  Card(
      modifier = Modifier.fillMaxWidth(),
      shape = RoundedCornerShape(20.dp),
      colors =
          CardDefaults.cardColors(
              containerColor = MaterialTheme.colorScheme.secondaryContainer,
              contentColor = MaterialTheme.colorScheme.onSurface,
          ),
      border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.33f)),
  ) {
    Column(
        Modifier.fillMaxWidth().padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        content = content,
    )
  }
}

@Composable
private fun OrderDetailsSection(label: String) {
  Text(
      label,
      Modifier.semantics { heading() },
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
  )
}

@Composable
private fun OrderDetailsStatus(status: OrderStatus) {
  val label =
      when (status) {
        OrderStatus.PENDING -> R.string.order_details_pending
        OrderStatus.ACCEPTED -> R.string.order_details_accepted
        OrderStatus.REJECTED -> R.string.order_details_rejected
      }
  Surface(
      shape = CircleShape,
      color = MaterialTheme.colorScheme.primaryContainer,
      contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
      border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
  ) {
    Text(
        stringResource(label),
        Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
        style = MaterialTheme.typography.labelSmall,
    )
  }
}

@Composable
private fun OrderDetailsField(icon: Int, label: String, value: String) {
  Row(
      Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    Box(
        Modifier.size(40.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
      Icon(
          painterResource(icon),
          null,
          Modifier.size(24.dp),
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
      Text(
          label,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
      )
      Text(value, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold))
    }
  }
}

@Composable
private fun OrderDetailsDivider() =
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

@Composable
private fun OrderDetailsMessage(isError: Boolean, onRetry: () -> Unit) {
  OrderDetailsCard {
    Column(
        Modifier.fillMaxWidth().heightIn(min = 324.dp).padding(horizontal = 6.dp, vertical = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
    ) {
      Box(
          Modifier.size(88.dp).background(MaterialTheme.colorScheme.primary, CircleShape),
          contentAlignment = Alignment.Center,
      ) {
        Icon(
            painterResource(
                if (isError) R.drawable.ic_trip_alert_circle else R.drawable.ic_trip_shopping_bag
            ),
            null,
            Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.onPrimary,
        )
      }
      Text(
          stringResource(
              if (isError) R.string.order_details_error else R.string.order_details_not_found
          ),
          Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
          textAlign = TextAlign.Center,
          style = MaterialTheme.typography.titleMedium.copy(fontSize = 24.sp, lineHeight = 32.sp),
      )
      if (isError) {
        Button(
            onClick = onRetry,
            modifier =
                Modifier.fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .shadow(
                        elevation = 12.dp,
                        shape = RoundedCornerShape(28.dp),
                        ambientColor = MaterialTheme.colorScheme.primary,
                        spotColor = MaterialTheme.colorScheme.primary,
                    ),
            shape = RoundedCornerShape(28.dp),
        ) {
          Text(stringResource(R.string.trips_retry), textAlign = TextAlign.Center)
        }
      }
    }
  }
}

@Composable
private fun OrderDetailsPlaceholder(modifier: Modifier) {
  Box(
      modifier
          .background(
              MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.16f),
              RoundedCornerShape(8.dp),
          )
          .clearAndSetSemantics {}
  )
}

@Composable
private fun OrderDetailsSkeleton() {
  val loading = stringResource(R.string.order_details_loading)
  Column(
      Modifier.fillMaxWidth().clearAndSetSemantics {
        contentDescription = loading
        liveRegion = LiveRegionMode.Polite
        progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
      },
      verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    OrderDetailsCard {
      OrderDetailsPlaceholder(Modifier.width(42.dp).height(17.dp))
      repeat(3) { index ->
        if (index > 0) OrderDetailsDivider()
        Row(
            modifier = Modifier.heightIn(min = 47.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
          OrderDetailsPlaceholder(Modifier.size(40.dp))
          Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            OrderDetailsPlaceholder(Modifier.fillMaxWidth(0.35f).height(14.dp))
            OrderDetailsPlaceholder(Modifier.fillMaxWidth(0.8f).height(22.dp))
          }
        }
      }
    }
    OrderDetailsCard {
      OrderDetailsPlaceholder(Modifier.width(50.dp).height(17.dp))
      repeat(3) { index ->
        if (index > 0) OrderDetailsDivider()
        Box(Modifier.height(32.dp), contentAlignment = Alignment.CenterStart) {
          OrderDetailsPlaceholder(
              Modifier.fillMaxWidth(if (index == 2) 0.47f else 0.36f).height(22.dp)
          )
        }
      }
    }
    OrderDetailsCard {
      OrderDetailsPlaceholder(Modifier.width(104.dp).height(17.dp))
      OrderDetailsPlaceholder(Modifier.width(140.dp).height(35.dp))
    }
  }
}

@Preview(name = "Order details · Content", widthDp = 412, heightDp = 917)
@Composable
private fun OrderDetailsContentPreview() {
  val time = Instant.parse("2026-10-13T16:30:00Z")
  val trip =
      Trip(
          id = "preview-trip",
          ownerId = "commando",
          store = Location("Migros EPFL", 46.52, 6.57),
          scheduledAt = time,
          handoffLocation = Location("Rolex Learning Center", 46.52, 6.57),
          status = TripStatus.PUBLISHED,
          createdAt = time,
          updatedAt = time,
      )
  val items =
      listOf(
          OrderItem("milk", "Milk", 220, 2),
          OrderItem("bread", "Bread", 180, 1),
          OrderItem("bananas", "Bananas", 60, 3),
      )
  val order = Order("preview-order", trip.id, "requester", items, 450, OrderStatus.PENDING, time)
  OrderDetailsPreview(OrderDetailsUiState.Content(order, trip))
}

@Preview(name = "Order details · Loading", widthDp = 412, heightDp = 917)
@Composable
private fun OrderDetailsLoadingPreview() = OrderDetailsPreview(OrderDetailsUiState.Loading)

@Preview(name = "Order details · Not found", widthDp = 412, heightDp = 917)
@Composable
private fun OrderDetailsNotFoundPreview() = OrderDetailsPreview(OrderDetailsUiState.NotFound)

@Preview(name = "Order details · Error", widthDp = 412, heightDp = 917)
@Composable
private fun OrderDetailsErrorPreview() =
    OrderDetailsPreview(OrderDetailsUiState.Error.OrderFailure(OrderError.Unknown))

@Composable
private fun OrderDetailsPreview(state: OrderDetailsUiState) {
  SampleAppTheme { OrderDetailsScreen(state = state, onBack = {}, onRetry = {}) }
}
