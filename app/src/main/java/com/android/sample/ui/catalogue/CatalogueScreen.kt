package com.android.sample.ui.catalogue

// AI assistance: Claude Code. Layout from Figma "05 · Product catalogue (#65)".
import android.text.format.DateFormat
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.sample.R
import com.android.sample.data.repository.ProductError
import com.android.sample.data.repository.TripError
import com.android.sample.model.Location
import com.android.sample.model.MAX_ORDER_PRODUCTS
import com.android.sample.model.OrderItem
import com.android.sample.model.Product
import com.android.sample.model.Trip
import com.android.sample.model.TripStatus
import com.android.sample.ui.orders.money
import com.android.sample.ui.theme.SampleAppTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlinx.coroutines.flow.first

/**
 * Requester catalogue for one trip. [onContinue] receives the loaded trip, ready for Review Order;
 * it is only called once at least one product is selected.
 */
@Composable
fun CatalogueRoute(
    viewModel: CatalogueViewModel,
    onBack: () -> Unit,
    onContinue: (Trip) -> Unit,
    modifier: Modifier = Modifier,
) {
  val state by viewModel.uiState.collectAsState()
  CatalogueScreen(
      state = state,
      onBack = onBack,
      onQueryChange = viewModel::onQueryChange,
      onClearSearch = viewModel::clearSearch,
      onAdd = viewModel::add,
      onIncrease = viewModel::increment,
      onDecrease = viewModel::decrement,
      onRetry = viewModel::retry,
      onLoadMore = viewModel::loadMore,
      onContinue = { if (state.canContinue) state.trip?.let(onContinue) },
      modifier = modifier,
  )
}

@Composable
fun CatalogueScreen(
    state: CatalogueUiState,
    onBack: () -> Unit,
    onQueryChange: (String) -> Unit,
    onClearSearch: () -> Unit,
    onAdd: (String) -> Unit,
    onIncrease: (String) -> Unit,
    onDecrease: (String) -> Unit,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
  val c = MaterialTheme.colorScheme
  val listState = rememberLazyListState()
  val loaded = state.content as? CatalogueContent.Loaded
  // A failed page waits for its Retry button instead of being requested again on every scroll.
  val canAutoLoad = loaded?.canLoadMore == true && loaded.loadMoreError == null
  val currentOnLoadMore by rememberUpdatedState(onLoadMore)
  // Restarts after each page, as loading clears canAutoLoad, so one request is sent per page.
  LaunchedEffect(listState, canAutoLoad) {
    if (!canAutoLoad) return@LaunchedEffect
    snapshotFlow {
      val layout = listState.layoutInfo
      val lastVisible = layout.visibleItemsInfo.lastOrNull()?.index ?: -1
      lastVisible >= layout.totalItemsCount - AUTO_LOAD_DISTANCE
    }
        .first { it }
    currentOnLoadMore()
  }
  Column(
      modifier
          .fillMaxSize()
          .background(c.background)
          .statusBarsPadding()
          .navigationBarsPadding()
          .imePadding()
  ) {
    CatalogueTopBar(onBack)
    // Summary, search and products scroll together so short screens keep the list reachable.
    LazyColumn(
        Modifier.weight(1f).fillMaxWidth().testTag(CatalogueTestTags.PRODUCT_LIST),
        state = listState,
        contentPadding = PaddingValues(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      item(key = "header") {
        Column {
          state.trip?.let { TripSummary(it) }
          CatalogueSearchField(
              query = state.query,
              onQueryChange = onQueryChange,
              enabled = state.canSearch,
              modifier = Modifier.padding(horizontal = 24.dp),
          )
        }
      }
      catalogueBody(state, onClearSearch, onAdd, onIncrease, onDecrease, onRetry, onLoadMore)
    }
    CatalogueBottomBar(state, onContinue)
  }
}

@Composable
private fun CatalogueTopBar(onBack: () -> Unit) {
  val c = MaterialTheme.colorScheme
  Row(
      // The back button's 48 dp touch target adds a 4 dp inset, keeping Figma's 64 dp bar.
      Modifier.fillMaxWidth().padding(start = 12.dp, end = 24.dp, top = 8.dp, bottom = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    RoundButton(
        R.drawable.ic_trip_arrow_left,
        stringResource(R.string.nav_back),
        c.surfaceContainer,
        c.onSurface,
        onBack,
        Modifier.testTag(CatalogueTestTags.BACK),
        border = BorderStroke(1.dp, c.outlineVariant),
        size = 40.dp,
        iconSize = 24.dp,
    )
    Text(
        stringResource(R.string.add_items_title),
        modifier = Modifier.semantics { heading() },
        style = catalogueText(20, FontWeight.Bold).copy(lineHeight = 28.sp),
        color = c.onSurface,
    )
  }
}

@Composable
private fun TripSummary(trip: Trip) {
  val c = MaterialTheme.colorScheme
  val departure =
      formatTripTime(
          trip.scheduledAt,
          now = Instant.now(),
          zone = ZoneId.systemDefault(),
          locale = LocalConfiguration.current.locales[0],
          today = stringResource(R.string.catalogue_today),
          tomorrow = stringResource(R.string.catalogue_tomorrow),
      )
  Row(
      Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 16.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    CatalogueIcon(R.drawable.ic_catalogue_store, 18.dp, c.onSurfaceVariant)
    Text(
        stringResource(R.string.catalogue_trip_summary, trip.store.name, departure),
        style = catalogueText(13, FontWeight.Medium),
        color = c.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
  }
}

/**
 * Figma trip time in [zone]: "Today, 17:30", "Tomorrow, 17:30" or "16 Oct, 17:30". The time and the
 * day-month order follow [locale].
 */
internal fun formatTripTime(
    scheduledAt: Instant,
    now: Instant,
    zone: ZoneId,
    locale: Locale,
    today: String,
    tomorrow: String,
): String {
  val departure = scheduledAt.atZone(zone)
  val day =
      when (ChronoUnit.DAYS.between(now.atZone(zone).toLocalDate(), departure.toLocalDate())) {
        0L -> today
        1L -> tomorrow
        else ->
            DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "dMMM"), locale)
                .format(departure)
      }
  val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)
  return "$day, ${time.format(departure)}"
}

private val ListItemPadding = Modifier.padding(horizontal = 20.dp)

/** The next page is requested once one of the last [AUTO_LOAD_DISTANCE] list items is visible. */
private const val AUTO_LOAD_DISTANCE = 3

private fun LazyListScope.catalogueBody(
    state: CatalogueUiState,
    onClearSearch: () -> Unit,
    onAdd: (String) -> Unit,
    onIncrease: (String) -> Unit,
    onDecrease: (String) -> Unit,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
) {
  val content = state.content
  if (content is CatalogueContent.Loading) {
    item(key = "loading") { SectionLabel(stringResource(R.string.catalogue_loading)) }
    items(6) { SkeletonProductRow(ListItemPadding) }
    return
  }
  if (content !is CatalogueContent.Loaded || content.products.isEmpty()) {
    item(key = "message") { CatalogueStateMessage(content, onClearSearch, onRetry) }
    return
  }
  val loaded = content.products
  // Only loaded pages are counted; "+" marks that the store has more matching products.
  val more = content.nextCursor != null
  item(key = "label") {
    SectionLabel(
        if (content.query.isEmpty())
            stringResource(
                if (more) R.string.catalogue_all_products_more else R.string.catalogue_all_products,
                loaded.size,
            )
        else
            pluralStringResource(
                if (more) R.plurals.catalogue_results_more else R.plurals.catalogue_results,
                loaded.size,
                loaded.size,
                content.query.uppercase(),
            )
    )
  }
  items(loaded, key = { it.id }) { product ->
    CatalogueProductRow(
        product = product,
        quantity = state.quantityOf(product.id),
        onAdd = { onAdd(product.id) },
        onIncrease = { onIncrease(product.id) },
        onDecrease = { onDecrease(product.id) },
        modifier = ListItemPadding,
    )
  }
  // Scrolling near the end requests the next page; only a failed page needs a button.
  if (content.isLoadingMore) {
    // Screen readers hear one polite announcement; the rows themselves are only placeholders.
    item(key = "loading_more") {
      val description = stringResource(R.string.catalogue_loading_more)
      SkeletonProductRow(
          ListItemPadding.semantics {
            contentDescription = description
            liveRegion = LiveRegionMode.Polite
          }
      )
    }
    item { SkeletonProductRow(ListItemPadding) }
  }
  if (content.loadMoreError != null)
      item(key = "load_more_error") {
        Column(
            ListItemPadding.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Text(
              stringResource(R.string.catalogue_load_more_error),
              style = catalogueText(14),
              color = MaterialTheme.colorScheme.error,
          )
          SecondaryPillButton(
              stringResource(R.string.catalogue_retry),
              onLoadMore,
              Modifier.testTag(CatalogueTestTags.LOAD_MORE),
          )
        }
      }
}

/** Empty catalogue, empty search, unsupported store and failures; never a silent empty list. */
@Composable
private fun CatalogueStateMessage(
    content: CatalogueContent,
    onClearSearch: () -> Unit,
    onRetry: () -> Unit,
) {
  val modifier = ListItemPadding.padding(top = 4.dp)
  val failure = content.failureMessage()
  if (failure != null) {
    CatalogueMessage(
        R.drawable.ic_trip_alert_circle,
        stringResource(R.string.catalogue_error_title),
        stringResource(failure),
        accentIcon = true,
        // A trip that no longer exists cannot be retried.
        action =
            if (failure == R.string.catalogue_trip_not_found) null
            else stringResource(R.string.catalogue_retry),
        primaryAction = true,
        onAction = onRetry,
        modifier = modifier,
    )
    return
  }
  // Only reached for an empty result, an unsearchable query, or an unsupported store.
  val noMatch = content is CatalogueContent.Loaded && content.query.isNotEmpty()
  val searching = noMatch || content is CatalogueContent.UnsearchableQuery
  val (icon, title, body) =
      when {
        content is CatalogueContent.UnsupportedStore ->
            Triple(
                R.drawable.ic_catalogue_store,
                stringResource(R.string.catalogue_unsupported_title),
                stringResource(R.string.catalogue_unsupported_body, content.storeName),
            )
        content is CatalogueContent.UnsearchableQuery ->
            Triple(
                R.drawable.ic_catalogue_search,
                stringResource(R.string.catalogue_short_query_title),
                stringResource(R.string.catalogue_short_query_body),
            )
        noMatch ->
            Triple(
                R.drawable.ic_catalogue_search_x,
                stringResource(
                    R.string.catalogue_no_match_title,
                    (content as CatalogueContent.Loaded).query,
                ),
                stringResource(R.string.catalogue_no_match_body),
            )
        else ->
            Triple(
                R.drawable.ic_trip_shopping_bag,
                stringResource(R.string.catalogue_empty_title),
                stringResource(R.string.catalogue_empty_body),
            )
      }
  CatalogueMessage(
      icon,
      title,
      body,
      accentIcon = false,
      action = if (searching) stringResource(R.string.catalogue_clear_search) else null,
      onAction = onClearSearch,
      modifier = modifier,
  )
}

/** Permission and network failures are never shown as an empty catalogue. */
@StringRes
private fun CatalogueContent.failureMessage(): Int? =
    when (this) {
      is CatalogueContent.TripFailed ->
          when (error) {
            TripError.NotFound -> R.string.catalogue_trip_not_found
            TripError.PermissionDenied -> R.string.catalogue_error_permission
            TripError.NetworkError -> R.string.catalogue_error_network
            else -> R.string.catalogue_error_unknown
          }
      is CatalogueContent.ProductsFailed ->
          when (error) {
            ProductError.PermissionDenied -> R.string.catalogue_error_permission
            ProductError.NetworkError -> R.string.catalogue_error_network
            else -> R.string.catalogue_error_unknown
          }
      else -> null
    }

@Composable
private fun SectionLabel(text: String) {
  Text(
      text,
      modifier = ListItemPadding,
      style = catalogueText(11, FontWeight.SemiBold).copy(letterSpacing = 0.8.sp),
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
  )
}

@Composable
private fun CatalogueBottomBar(state: CatalogueUiState, onContinue: () -> Unit) {
  val c = MaterialTheme.colorScheme
  HorizontalDivider(color = c.outlineVariant)
  Column(
      Modifier.fillMaxWidth()
          .background(c.background)
          .padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 28.dp),
      verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    if (state.items.isNotEmpty()) {
      val count = state.selectedCount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
      Row(
          Modifier.fillMaxWidth().testTag(CatalogueTestTags.SUMMARY).semantics(true) {},
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
            pluralStringResource(R.plurals.catalogue_item_count, count, count),
            style = catalogueText(14, FontWeight.SemiBold),
            color = c.onSurface,
        )
        Text(
            stringResource(R.string.catalogue_estimate, money(state.estimatedCents)),
            style = catalogueText(14, FontWeight.Bold),
            color = c.primary,
        )
      }
    }
    if (state.hasTooManyProducts) {
      Text(stringResource(R.string.order_product_limit, MAX_ORDER_PRODUCTS), color = c.error)
    }
    PrimaryPillButton(
        stringResource(
            if (state.canContinue || state.hasTooManyProducts) R.string.catalogue_continue
            else R.string.catalogue_select_to_continue
        ),
        enabled = state.canContinue,
        onClick = onContinue,
        modifier = Modifier.testTag(CatalogueTestTags.CONTINUE),
    )
  }
}

private val previewTrip =
    Trip(
        id = "preview-trip",
        ownerId = "owner",
        store = Location("Migros EPFL", 46.5229, 6.5657),
        scheduledAt = Instant.parse("2026-10-14T15:30:00Z"),
        handoffLocation = Location("Rolex Learning Center", 46.5183, 6.5683),
        status = TripStatus.PUBLISHED,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
    )

private val previewProducts =
    listOf("Oat drink" to 220, "Bananas" to 295, "Wholemeal bread" to 340, "Eggs, 6 pcs" to 450)
        .mapIndexed { index, (name, cents) -> Product("migros:p$index", "migros", name, cents) }

private val previewItems =
    listOf(
        OrderItem("migros:p0", "Oat drink", 220, 2),
        OrderItem("migros:p2", "Wholemeal bread", 340, 1),
    )

@Composable
private fun CataloguePreview(state: CatalogueUiState) {
  SampleAppTheme { CatalogueScreen(state, {}, {}, {}, {}, {}, {}, {}, {}, {}) }
}

@Preview(name = "01 · Content", widthDp = 412, heightDp = 917)
@Composable
private fun CatalogueContentPreview() =
    CataloguePreview(CatalogueUiState(previewTrip, CatalogueContent.Loaded(previewProducts)))

@Preview(name = "02 · Items selected", widthDp = 412, heightDp = 917)
@Composable
private fun CatalogueSelectedPreview() =
    CataloguePreview(
        CatalogueUiState(
            previewTrip,
            CatalogueContent.Loaded(previewProducts),
            items = previewItems,
        )
    )

@Preview(name = "03 · Search results", widthDp = 412, heightDp = 917)
@Composable
private fun CatalogueSearchPreview() =
    CataloguePreview(
        CatalogueUiState(
            previewTrip,
            CatalogueContent.Loaded(previewProducts.take(1), "oat"),
            "oat",
            previewItems,
        )
    )

@Preview(name = "04 · Loading", widthDp = 412, heightDp = 917)
@Composable
private fun CatalogueLoadingPreview() = CataloguePreview(CatalogueUiState(previewTrip))

@Preview(name = "05 · Empty search", widthDp = 412, heightDp = 917)
@Composable
private fun CatalogueEmptySearchPreview() =
    CataloguePreview(
        CatalogueUiState(
            previewTrip,
            CatalogueContent.Loaded(emptyList(), "chocolat"),
            "chocolat",
            previewItems,
        )
    )

@Preview(name = "06 · Error", widthDp = 412, heightDp = 917)
@Composable
private fun CatalogueErrorPreview() =
    CataloguePreview(
        CatalogueUiState(previewTrip, CatalogueContent.ProductsFailed(ProductError.NetworkError))
    )
