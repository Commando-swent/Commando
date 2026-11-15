package com.android.sample.ui.catalogue

// AI assistance: OpenAI Codex and Claude Code. Browse controls from Figma (#231).
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.android.sample.R
import java.text.Collator
import java.util.Locale
import kotlin.math.roundToInt

/** Category the importer gives products that the store has not put in a category. */
internal const val OTHER_CATEGORY = "Autres"

/**
 * Labels of the importer's 18 departments, by exact stored value, in Figma's chip order. Any other
 * stored value is shown as it is, so a new category appears without an app update.
 */
private val CATEGORY_LABELS: Map<String, Int> =
    linkedMapOf(
        "Fruits & légumes" to R.string.category_fruit_veg,
        "Produits laitiers, œufs & plats préparés frais" to R.string.category_dairy_eggs,
        "Boulangerie, pâtisserie & petit déjeuner" to R.string.category_bakery,
        "Viandes & poissons" to R.string.category_meat_fish,
        "Pâtes, condiments & conserves" to R.string.category_pantry,
        "Snacks & confiseries" to R.string.category_snacks_sweets,
        "Boissons, café & thé" to R.string.category_drinks,
        "Vins, bières & spiritueux" to R.string.category_alcohol,
        "Surgelés" to R.string.category_frozen,
        "Bébé et enfant" to R.string.category_baby_kids,
        "Animaux" to R.string.category_pets,
        "Ménage & nettoyage" to R.string.category_household,
        "Beauté & santé" to R.string.category_beauty_health,
        "Maison & cuisine" to R.string.category_home_kitchen,
        "Mode & accessoires" to R.string.category_fashion,
        "Papeterie, bureau & accessoires électroniques" to R.string.category_stationery_tech,
        "Jardin & extérieur" to R.string.category_garden,
        "Jeux, loisirs & cadeaux" to R.string.category_toys_gifts,
        OTHER_CATEGORY to R.string.category_other,
    )

private val CATEGORY_ORDER: Map<String, Int> =
    CATEGORY_LABELS.keys.withIndex().associate { (index, category) -> category to index }

@StringRes internal fun categoryLabelRes(category: String): Int? = CATEGORY_LABELS[category]

@Composable
internal fun categoryLabel(category: String): String =
    categoryLabelRes(category)?.let { stringResource(it) } ?: category

/** Known departments in Figma's order, then unknown values by label, and [OTHER_CATEGORY] last. */
internal fun orderedCategories(
    categories: List<String>,
    locale: Locale,
    label: (String) -> String,
): List<String> {
  val collator = Collator.getInstance(locale)
  val unknown = CATEGORY_ORDER.getValue(OTHER_CATEGORY)
  return categories.sortedWith(
      compareBy<String> { it == OTHER_CATEGORY }
          .thenBy { CATEGORY_ORDER[it] ?: unknown }
          .thenComparator { a, b -> collator.compare(label(a), label(b)) }
  )
}

internal fun chainName(storeId: String): String = storeId.replaceFirstChar {
  it.titlecase(Locale.ROOT)
}

internal fun otherChainName(storeId: String): String =
    chainName(if (storeId == "coop") "migros" else "coop")

/** Catalogue/Favourites segmented switch; the Favourites segment includes the loaded count. */
@Composable
internal fun CatalogueTabs(
    tab: CatalogueTab,
    favouritesCount: Int?,
    onSelect: (CatalogueTab) -> Unit,
    modifier: Modifier = Modifier,
) {
  val c = MaterialTheme.colorScheme
  Row(
      modifier
          .fillMaxWidth()
          .background(c.surfaceContainer, RoundedCornerShape(24.dp))
          .border(1.dp, c.outlineVariant, RoundedCornerShape(24.dp))
          .padding(4.dp),
      horizontalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    TabSegment(
        icon = R.drawable.ic_trip_shopping_bag,
        label = stringResource(R.string.catalogue_tab_catalogue),
        selected = tab == CatalogueTab.CATALOGUE,
        count = null,
        onClick = { onSelect(CatalogueTab.CATALOGUE) },
        modifier = Modifier.weight(1f).testTag(CatalogueTestTags.TAB_CATALOGUE),
    )
    TabSegment(
        icon =
            if (tab == CatalogueTab.FAVOURITES) R.drawable.ic_catalogue_heart_filled
            else R.drawable.ic_catalogue_heart,
        label = stringResource(R.string.catalogue_tab_favourites),
        selected = tab == CatalogueTab.FAVOURITES,
        count = favouritesCount,
        onClick = { onSelect(CatalogueTab.FAVOURITES) },
        modifier = Modifier.weight(1f).testTag(CatalogueTestTags.TAB_FAVOURITES),
    )
  }
}

@Composable
private fun TabSegment(
    @DrawableRes icon: Int,
    label: String,
    selected: Boolean,
    count: Int?,
    onClick: () -> Unit,
    modifier: Modifier,
) {
  val c = MaterialTheme.colorScheme
  val shape = RoundedCornerShape(20.dp)
  Row(
      modifier
          .heightIn(min = 48.dp)
          .clip(shape)
          .then(
              if (selected)
                  Modifier.background(c.secondaryContainer, shape).border(1.dp, c.outline, shape)
              else Modifier
          )
          .selectable(selected = selected, role = Role.Tab, onClick = onClick),
      horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    CatalogueIcon(icon, 18.dp, if (selected) c.onSurface else c.onSurfaceVariant)
    Text(
        label,
        style = catalogueText(15, if (selected) FontWeight.Bold else FontWeight.Medium),
        color = if (selected) c.onSurface else c.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    if (count != null) {
      Text(
          count.toString(),
          Modifier.background(c.surfaceContainerHigh, CircleShape)
              .padding(horizontal = 8.dp, vertical = 1.dp),
          style = catalogueText(12, FontWeight.SemiBold),
          color = c.onSurface,
      )
    }
  }
}

/**
 * "All" then the stored [categories]; tapping All or the selected chip clears the filter. The row
 * scrolls itself, never the product list, to keep the selected chip on screen.
 */
@Composable
internal fun CategoryChips(
    categories: List<String>,
    selected: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
  val locale = LocalConfiguration.current.locales[0]
  val resources = LocalResources.current
  // Sorting builds a Collator, so it runs only when the categories or the locale change.
  val ordered =
      remember(categories, locale, resources) {
        orderedCategories(categories, locale) { stored ->
          categoryLabelRes(stored)?.let(resources::getString) ?: stored
        }
      }
  val scroll = rememberScrollState()
  // Start of each chip in the scrolled row, "All" included under the null key.
  val starts = remember { mutableStateMapOf<String?, Int>() }
  val selectedStart = starts[selected]
  LaunchedEffect(selected, selectedStart) {
    if (selectedStart != null) scroll.animateScrollTo(selectedStart)
  }
  Row(
      modifier
          .fillMaxWidth()
          .horizontalScroll(scroll)
          .padding(horizontal = 24.dp)
          .testTag(CatalogueTestTags.CATEGORY_CHIPS),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    CategoryChip(
        stringResource(R.string.catalogue_category_all),
        selected == null,
        showClear = false,
        onClick = { onSelect(null) },
        modifier =
            Modifier.onPlaced { starts[null] = it.positionInParent().x.roundToInt() }
                .testTag(CatalogueTestTags.CATEGORY_ALL),
    )
    ordered.forEach { category ->
      val isSelected = category == selected
      CategoryChip(
          categoryLabel(category),
          isSelected,
          showClear = isSelected,
          onClick = { onSelect(if (isSelected) null else category) },
          modifier =
              Modifier.onPlaced { starts[category] = it.positionInParent().x.roundToInt() }
                  .testTag(CatalogueTestTags.category(category)),
      )
    }
  }
}

@Composable
private fun CategoryChip(
    label: String,
    selected: Boolean,
    showClear: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
  val c = MaterialTheme.colorScheme
  val shape = RoundedCornerShape(18.dp)
  Row(
      // The 36 dp Figma chip keeps a 48 dp touch target; like Material's FilterChip, it is a
      // selectable checkbox, so TalkBack announces whether its category filters the list.
      modifier
          .minimumInteractiveComponentSize()
          .heightIn(min = 36.dp)
          .clip(shape)
          .background(if (selected) c.secondaryContainer else c.background, shape)
          .border(1.dp, if (selected) c.outline else c.outlineVariant, shape)
          .selectable(selected = selected, role = Role.Checkbox, onClick = onClick)
          .padding(start = 16.dp, end = if (showClear) 10.dp else 16.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    Text(
        label,
        style = catalogueText(14, if (selected) FontWeight.SemiBold else FontWeight.Medium),
        color = c.onSurface,
        maxLines = 1,
    )
    if (showClear) CatalogueIcon(R.drawable.ic_catalogue_close, 16.dp, c.onSurface)
  }
}

private val HeartBadgeSize = 26.dp
private val HeartTouchTarget = 48.dp
/** Badge centre measured from the photo's top-end corner (Figma product row). */
private val HeartCentreFromPhotoCorner = DpOffset((-7).dp, 4.dp)

/** A compact heart badge on the photo's top-end corner, centred in a full 48 dp target. */
@Composable
internal fun BoxScope.FavouriteHeart(
    productId: String,
    productName: String,
    favourite: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
  val c = MaterialTheme.colorScheme
  val description =
      stringResource(
          if (favourite) R.string.catalogue_favourite_remove else R.string.catalogue_favourite_add,
          productName,
      )
  Box(
      modifier
          .align(Alignment.TopEnd)
          // Moves the target's centre onto the badge centre, so badge and target stay together.
          .offset(
              HeartCentreFromPhotoCorner.x + HeartTouchTarget / 2,
              HeartCentreFromPhotoCorner.y - HeartTouchTarget / 2,
          )
          .size(HeartTouchTarget)
          .clickable(enabled = enabled, role = Role.Button, onClick = onToggle)
          .semantics { contentDescription = description }
          .testTag(CatalogueTestTags.favourite(productId)),
      contentAlignment = Alignment.Center,
  ) {
    Box(
        Modifier.size(HeartBadgeSize)
            .alpha(if (enabled) 1f else 0.6f)
            .background(c.background, CircleShape)
            .border(1.dp, c.outlineVariant, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
      CatalogueIcon(
          if (favourite) R.drawable.ic_catalogue_heart_filled else R.drawable.ic_catalogue_heart,
          15.dp,
          if (favourite) c.primary else c.onSurface,
      )
    }
  }
}

private fun Modifier.dashedBorder(color: Color, shape: Shape) = drawBehind {
  drawOutline(
      shape.createOutline(size, layoutDirection, this),
      color,
      style =
          Stroke(
              width = 1.dp.toPx(),
              pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())),
          ),
  )
}

/** A saved product that is missing or no longer sold, but can still be removed from favourites. */
@Composable
internal fun UnavailableFavouriteRow(
    productId: String,
    storeId: String,
    pending: Boolean,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
  val c = MaterialTheme.colorScheme
  val name = stringResource(R.string.catalogue_favourite_unknown_product)
  val shape = RoundedCornerShape(14.dp)
  Row(
      modifier
          .fillMaxWidth()
          .background(c.surfaceContainer, shape)
          .dashedBorder(c.outlineVariant, shape)
          .padding(12.dp)
          .testTag(CatalogueTestTags.unavailable(productId)),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    Box(Modifier.size(56.dp)) {
      ProductImagePlaceholder(Modifier.alpha(0.5f))
      FavouriteHeart(productId, name, true, !pending, onRemove)
    }
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
      Text(
          name,
          style = catalogueText(16, FontWeight.Medium),
          color = c.onSurfaceVariant,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
      )
      Text(
          stringResource(R.string.catalogue_favourite_no_longer_available, chainName(storeId)),
          style = catalogueText(14),
          color = c.onSurfaceVariant,
      )
    }
  }
}
