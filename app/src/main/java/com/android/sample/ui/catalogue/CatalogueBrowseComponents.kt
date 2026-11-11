package com.android.sample.ui.catalogue

// AI assistance: OpenAI Codex and Claude Code. Category chips from Figma (#231).
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
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
  val resources = LocalContext.current.resources
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
