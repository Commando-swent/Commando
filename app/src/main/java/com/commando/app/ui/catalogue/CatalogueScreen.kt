package com.commando.app.ui.catalogue

// AI assistance: Claude Code; OpenAI Codex (shared time formatter). Layout from Figma "05 · Product
// catalogue (#65)".
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import com.commando.app.data.repository.MAX_SEARCH_QUERY_LENGTH
import com.commando.app.data.repository.MIN_SEARCH_TERM_LENGTH
import com.commando.app.data.repository.ProductError
import com.commando.app.data.repository.TripError
import com.commando.app.model.Location
import com.commando.app.model.MAX_ORDER_PRODUCTS
import com.commando.app.model.OrderItem
import com.commando.app.model.Product
import com.commando.app.model.Trip
import com.commando.app.model.TripStatus
import com.commando.app.ui.components.formatTripTime
import com.commando.app.ui.orders.money
import com.commando.app.ui.theme.SampleAppTheme
import java.time.Instant
import java.time.ZoneId
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
      browseActions =
          CatalogueBrowseActions(
              onSelectCategory = viewModel::selectCategory,
              onSelectTab = viewModel::selectTab,
              onToggleFavourite = viewModel::toggleFavourite,
              onRetryFavourites = viewModel::retryFavourites,
              onUndoFavourite = viewModel::undoFavourite,
              onDismissFavouriteNotice = viewModel::dismissFavouriteNotice,
          ),
  )
}

/** Category and favourite callbacks, grouped to keep the screen's public API readable. */
data class CatalogueBrowseActions(
    val onSelectCategory: (String?) -> Unit = {},
    val onSelectTab: (CatalogueTab) -> Unit = {},
    val onToggleFavourite: (String) -> Unit = {},
    val onRetryFavourites: () -> Unit = {},
    val onUndoFavourite: (FavouriteNotice) -> Unit = {},
    val onDismissFavouriteNotice: (FavouriteNotice) -> Unit = {},
)

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
    browseActions: CatalogueBrowseActions = CatalogueBrowseActions(),
) {
  val c = MaterialTheme.colorScheme
  val listState = rememberLazyListState()
  val loaded = state.content as? CatalogueContent.Loaded
  // A failed page waits for its Retry button instead of being requested again on every scroll.
  val canAutoLoad =
      state.tab == CatalogueTab.CATALOGUE &&
          loaded?.canLoadMore == true &&
          loaded.loadMoreError == null
  val snackbar = remember { SnackbarHostState() }
  FavouriteNoticeEffect(state.favouriteNotice, snackbar, browseActions)
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
    Box(Modifier.weight(1f).fillMaxWidth()) {
      // Summary, search and products scroll together so short screens keep the list reachable.
      LazyColumn(
          Modifier.fillMaxSize().testTag(CatalogueTestTags.PRODUCT_LIST),
          state = listState,
          contentPadding = PaddingValues(bottom = 16.dp),
          verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        item(key = "header") { CatalogueHeader(state, onQueryChange, browseActions) }
        val rowActions =
            ProductRowActions(onAdd, onIncrease, onDecrease, browseActions.onToggleFavourite)
        if (state.tab == CatalogueTab.FAVOURITES) {
          favouritesBody(state, rowActions, browseActions)
        } else {
          val content = state.content
          if (content is CatalogueContent.Loaded && content.products.isNotEmpty()) {
            catalogueProducts(state, content, rowActions, onLoadMore)
          } else {
            catalogueStatus(content, onClearSearch, onRetry) {
              browseActions.onSelectCategory(null)
            }
          }
        }
      }
      SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(horizontal = 16.dp)) {
        Snackbar(it, Modifier.testTag(CatalogueTestTags.SNACKBAR))
      }
    }
    CatalogueBottomBar(state, onContinue)
  }
}

/** Shows feedback once; only successful removals expose Undo. */
@Composable
private fun FavouriteNoticeEffect(
    notice: FavouriteNotice?,
    snackbar: SnackbarHostState,
    actions: CatalogueBrowseActions,
) {
  val added = stringResource(R.string.catalogue_favourite_added)
  val removed = stringResource(R.string.catalogue_favourite_removed)
  val failed = stringResource(R.string.catalogue_favourite_failed)
  val undo = stringResource(R.string.catalogue_undo)
  val currentActions by rememberUpdatedState(actions)
  LaunchedEffect(notice) {
    if (notice == null) return@LaunchedEffect
    val message =
        when (notice) {
          is FavouriteNotice.Added -> added
          is FavouriteNotice.Removed -> removed
          is FavouriteNotice.Failed -> failed
        }
    val action = if (notice is FavouriteNotice.Removed) undo else null
    val result = snackbar.showSnackbar(message, action, duration = SnackbarDuration.Short)
    if (result == SnackbarResult.ActionPerformed) currentActions.onUndoFavourite(notice)
    else currentActions.onDismissFavouriteNotice(notice)
  }
}

@Composable
private fun CatalogueHeader(
    state: CatalogueUiState,
    onQueryChange: (String) -> Unit,
    actions: CatalogueBrowseActions,
) {
  Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    state.trip?.let { TripSummary(it) }
    if (state.showsFavourites) {
      CatalogueTabs(
          state.tab,
          (state.favourites as? FavouritesContent.Loaded)?.count,
          actions.onSelectTab,
          Modifier.padding(horizontal = 24.dp),
      )
    }
    if (state.tab == CatalogueTab.FAVOURITES) return@Column
    CatalogueSearchField(
        query = state.query,
        onQueryChange = onQueryChange,
        enabled = state.canSearch,
        modifier = Modifier.padding(horizontal = 24.dp),
    )
    // A store imported before category metadata simply has no chips.
    if (state.canSearch && state.categories.isNotEmpty()) {
      CategoryChips(state.categories, state.category, actions.onSelectCategory)
    }
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
          today = stringResource(R.string.trip_today),
          tomorrow = stringResource(R.string.trip_tomorrow),
      )
  Row(
      // With the header's spacing, this keeps a 16 dp gap above the next control.
      Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 4.dp),
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

private val ListItemPadding = Modifier.padding(horizontal = 20.dp)

/** The next page is requested once one of the last [AUTO_LOAD_DISTANCE] list items is visible. */
private const val AUTO_LOAD_DISTANCE = 3

/** Basket and favourite callbacks shared by catalogue and favourite product rows. */
private class ProductRowActions(
    val onAdd: (String) -> Unit,
    val onIncrease: (String) -> Unit,
    val onDecrease: (String) -> Unit,
    val onToggleFavourite: (String) -> Unit,
)

/** Loading skeleton, or the message for every state without products to list. */
private fun LazyListScope.catalogueStatus(
    content: CatalogueContent,
    onClearSearch: () -> Unit,
    onRetry: () -> Unit,
    onClearCategory: () -> Unit,
) {
  if (content is CatalogueContent.Loading) {
    item(key = "loading") { SectionLabel(stringResource(R.string.catalogue_loading)) }
    items(6) { SkeletonProductRow(ListItemPadding) }
  } else {
    item(key = "message") {
      CatalogueStateMessage(content, onClearSearch, onRetry, onClearCategory)
    }
  }
}

/** A non-empty page of products, with the next page's loading row or retry button. */
private fun LazyListScope.catalogueProducts(
    state: CatalogueUiState,
    content: CatalogueContent.Loaded,
    rows: ProductRowActions,
    onLoadMore: () -> Unit,
) {
  val loaded = content.products
  item(key = "label") { ResultsLabel(content) }
  if (content.category == OTHER_CATEGORY) {
    item(key = "other_hint") {
      Text(
          stringResource(R.string.catalogue_category_other_hint),
          ListItemPadding,
          style = catalogueText(13),
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
  productRows(loaded, state, rows)
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
              // A page loads without a tap, so its failure is announced too.
              Modifier.semantics { liveRegion = LiveRegionMode.Polite },
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

private fun LazyListScope.productRows(
    products: List<Product>,
    state: CatalogueUiState,
    rows: ProductRowActions,
) {
  items(products, key = { it.id }) { product ->
    CatalogueProductRow(
        product = product,
        quantity = state.quantityOf(product.id),
        onAdd = { rows.onAdd(product.id) },
        onIncrease = { rows.onIncrease(product.id) },
        onDecrease = { rows.onDecrease(product.id) },
        modifier = ListItemPadding,
        favourite =
            if (state.favourites is FavouritesContent.Loaded) state.isFavourite(product.id)
            else null,
        favouriteEnabled = product.id !in state.pendingFavourites,
        onToggleFavourite = { rows.onToggleFavourite(product.id) },
    )
  }
}

private fun LazyListScope.favouritesBody(
    state: CatalogueUiState,
    rows: ProductRowActions,
    actions: CatalogueBrowseActions,
) {
  when (val favourites = state.favourites) {
    FavouritesContent.Loading,
    FavouritesContent.Unsupported -> {
      item(key = "favourites_loading") { SectionLabel(stringResource(R.string.catalogue_loading)) }
      items(3) { SkeletonProductRow(ListItemPadding) }
    }
    is FavouritesContent.Failed -> favouritesFailure(favourites.error, actions.onRetryFavourites)
    is FavouritesContent.Loaded -> favouritesLoaded(state, favourites, rows, actions)
  }
}

private fun LazyListScope.favouritesFailure(error: ProductError, onRetry: () -> Unit) {
  item(key = "favourites_error") {
    CatalogueMessage(
        R.drawable.ic_trip_alert_circle,
        stringResource(R.string.catalogue_favourites_error_title),
        stringResource(
            when (error) {
              ProductError.PermissionDenied -> R.string.catalogue_error_permission
              ProductError.NetworkError -> R.string.catalogue_error_network
              else -> R.string.catalogue_error_unknown
            }
        ),
        accentIcon = true,
        action = stringResource(R.string.catalogue_retry),
        primaryAction = true,
        onAction = onRetry,
        modifier = ListItemPadding.padding(top = 4.dp),
    )
  }
}

private fun LazyListScope.favouritesLoaded(
    state: CatalogueUiState,
    favourites: FavouritesContent.Loaded,
    rows: ProductRowActions,
    actions: CatalogueBrowseActions,
) {
  val storeId = state.storeId ?: return
  if (favourites.count == 0) {
    item(key = "favourites_empty") {
      CatalogueMessage(
          R.drawable.ic_catalogue_heart,
          stringResource(R.string.catalogue_favourites_empty_title, chainName(storeId)),
          stringResource(R.string.catalogue_favourites_empty_body),
          accentIcon = false,
          action = stringResource(R.string.catalogue_favourites_browse),
          onAction = { actions.onSelectTab(CatalogueTab.CATALOGUE) },
          modifier = ListItemPadding.padding(top = 4.dp),
      )
    }
    return
  }
  item(key = "favourites_hint") {
    Text(
        stringResource(
            R.string.catalogue_favourites_hint,
            chainName(storeId),
            otherChainName(storeId),
        ),
        ListItemPadding,
        style = catalogueText(12),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
  if (favourites.products.isNotEmpty()) {
    item(key = "favourites_label") {
      SectionLabel(stringResource(R.string.catalogue_favourites_count, favourites.products.size))
    }
    productRows(favourites.products, state, rows)
  }
  if (favourites.unavailableProductIds.isEmpty()) return
  item(key = "unavailable_label") {
    SectionLabel(
        stringResource(
            R.string.catalogue_favourites_unavailable_count,
            favourites.unavailableProductIds.size,
        )
    )
  }
  items(favourites.unavailableProductIds, key = { "unavailable_$it" }) { productId ->
    UnavailableFavouriteRow(
        productId,
        storeId,
        pending = productId in state.pendingFavourites,
        onRemove = { actions.onToggleFavourite(productId) },
        modifier = ListItemPadding,
    )
  }
}

/** Only loaded pages are counted; "+" marks that the store has more matching products. */
@Composable
private fun ResultsLabel(content: CatalogueContent.Loaded) {
  val count = content.products.size
  val more = content.nextCursor != null
  SectionLabel(
      when {
        content.query.isNotEmpty() ->
            pluralStringResource(
                if (more) R.plurals.catalogue_results_more else R.plurals.catalogue_results,
                count,
                count,
                content.query.uppercase(),
            )
        content.category != null ->
            stringResource(
                if (more) R.string.catalogue_category_products_more
                else R.string.catalogue_category_products,
                categoryLabel(content.category).uppercase(),
                count,
            )
        else ->
            stringResource(
                if (more) R.string.catalogue_all_products_more else R.string.catalogue_all_products,
                count,
            )
      }
  )
}

/** Empty catalogue, empty search, unsupported store and failures; never a silent empty list. */
@Composable
private fun CatalogueStateMessage(
    content: CatalogueContent,
    onClearSearch: () -> Unit,
    onRetry: () -> Unit,
    onClearCategory: () -> Unit,
) {
  val modifier = ListItemPadding.padding(top = 4.dp)
  val category = (content as? CatalogueContent.Loaded)?.category
  // Widening to every category keeps the current query and selected basket items.
  if (category != null) {
    val label = categoryLabel(category)
    val query = content.query
    CatalogueMessage(
        R.drawable.ic_catalogue_search_x,
        if (query.isEmpty()) stringResource(R.string.catalogue_category_empty_title, label)
        else stringResource(R.string.catalogue_category_no_match_title, label, query),
        stringResource(
            if (query.isEmpty()) R.string.catalogue_category_empty_body
            else R.string.catalogue_category_no_match_body
        ),
        accentIcon = false,
        action = stringResource(R.string.catalogue_search_all_categories),
        onAction = onClearCategory,
        modifier = modifier,
    )
    return
  }
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
        content is CatalogueContent.UnsearchableQuery && content.isTooLong ->
            Triple(
                R.drawable.ic_catalogue_search,
                stringResource(R.string.catalogue_long_query_title),
                pluralStringResource(
                    R.plurals.catalogue_long_query_body,
                    MAX_SEARCH_QUERY_LENGTH,
                    MAX_SEARCH_QUERY_LENGTH,
                ),
            )
        content is CatalogueContent.UnsearchableQuery ->
            Triple(
                R.drawable.ic_catalogue_search,
                stringResource(R.string.catalogue_short_query_title),
                pluralStringResource(
                    R.plurals.catalogue_short_query_body,
                    MIN_SEARCH_TERM_LENGTH,
                    MIN_SEARCH_TERM_LENGTH,
                ),
            )
        noMatch ->
            Triple(
                R.drawable.ic_catalogue_search_x,
                stringResource(R.string.catalogue_no_match_title, content.query),
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
