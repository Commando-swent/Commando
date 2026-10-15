package com.android.sample.ui.catalogue

// AI assistance: Claude Code. Components from the Figma "Components — Product catalogue" frame.
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.sample.R
import com.android.sample.model.Product
import com.android.sample.ui.orders.money
import com.android.sample.ui.theme.IllustrationCream

@Composable
internal fun catalogueText(size: Int, weight: FontWeight = FontWeight.Normal): TextStyle =
    MaterialTheme.typography.bodySmall.copy(
        fontSize = size.sp,
        lineHeight = (size * 1.3).sp,
        fontWeight = weight,
    )

@Composable
internal fun CatalogueSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
  val c = MaterialTheme.colorScheme
  val interaction = remember { MutableInteractionSource() }
  val focused by interaction.collectIsFocusedAsState()
  val focusManager = LocalFocusManager.current
  val searchLabel = stringResource(R.string.catalogue_search_hint)
  val border =
      when {
        !enabled -> c.outlineVariant
        focused || query.isNotEmpty() -> c.primary
        else -> c.outline
      }
  val contentAlpha = if (enabled) 1f else 0.5f
  BasicTextField(
      value = query,
      onValueChange = onQueryChange,
      enabled = enabled,
      singleLine = true,
      textStyle = catalogueText(16).copy(color = c.onSurface),
      cursorBrush = SolidColor(c.primary),
      interactionSource = interaction,
      keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
      keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
      modifier =
          modifier.fillMaxWidth().testTag(CatalogueTestTags.SEARCH).semantics {
            contentDescription = searchLabel
          },
      decorationBox = { field ->
        Row(
            Modifier.height(52.dp)
                .background(c.surfaceContainer, RoundedCornerShape(16.dp))
                .border(1.dp, border, RoundedCornerShape(16.dp))
                // The clear button's 48 dp touch target supplies the remaining 10 dp inset.
                .padding(start = 16.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          CatalogueIcon(
              R.drawable.ic_catalogue_search,
              22.dp,
              c.onSurfaceVariant.copy(alpha = contentAlpha),
          )
          Box(Modifier.weight(1f)) {
            if (query.isEmpty()) {
              Text(
                  searchLabel,
                  style = catalogueText(16),
                  color = c.onSurfaceVariant.copy(alpha = contentAlpha),
                  maxLines = 1,
              )
            }
            field()
          }
          if (query.isNotEmpty() && enabled) {
            RoundButton(
                R.drawable.ic_catalogue_close,
                stringResource(R.string.catalogue_clear_query),
                c.surfaceContainerHigh,
                c.onSurfaceVariant,
                { onQueryChange("") },
                Modifier.testTag(CatalogueTestTags.CLEAR_QUERY),
                size = 28.dp,
                iconSize = 16.dp,
            )
          }
        }
      },
  )
}

/** Tinted tile from Figma's "Product image" component, shown when no photo is displayed. */
@Composable
internal fun ProductImagePlaceholder(modifier: Modifier = Modifier) {
  Box(
      modifier
          .size(56.dp)
          .clip(RoundedCornerShape(12.dp))
          .background(MaterialTheme.colorScheme.background),
      contentAlignment = Alignment.Center,
  ) {
    Box(Modifier.matchParentSize().background(IllustrationCream.copy(alpha = 0.18f)))
    CatalogueIcon(R.drawable.ic_trip_shopping_bag, 26.dp, IllustrationCream)
  }
}

@Composable
internal fun CatalogueProductRow(
    product: Product,
    quantity: Int,
    onAdd: () -> Unit,
    onIncrease: () -> Unit,
    onDecrease: () -> Unit,
    modifier: Modifier = Modifier,
) {
  val c = MaterialTheme.colorScheme
  Row(
      modifier
          .fillMaxWidth()
          .background(c.background, RoundedCornerShape(14.dp))
          .border(1.dp, c.outlineVariant, RoundedCornerShape(14.dp))
          // 12 dp plus the last button's 2 dp touch-target inset gives Figma's 14 dp end padding.
          .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 12.dp)
          .testTag(CatalogueTestTags.row(product.id)),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    ProductImagePlaceholder()
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
      Text(
          product.name,
          style = catalogueText(16, FontWeight.Medium),
          color = c.onSurface,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
      )
      Text(money(product.priceCents), style = catalogueText(14), color = c.onSurfaceVariant)
    }
    QuantityControl(product, quantity, onAdd, onIncrease, onDecrease)
  }
}

/** Not added: cart button. Quantity 1: red remove button. Quantity 2+: minus / qty / plus. */
@Composable
private fun QuantityControl(
    product: Product,
    quantity: Int,
    onAdd: () -> Unit,
    onIncrease: () -> Unit,
    onDecrease: () -> Unit,
) {
  val c = MaterialTheme.colorScheme
  Row(
      Modifier.animateContentSize(tween(300)),
      verticalAlignment = Alignment.CenterVertically,
      // With the 2 dp touch-target inset on each button, this keeps Figma's 12 dp visual gaps.
      horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    if (quantity == 0) {
      RoundButton(
          R.drawable.ic_catalogue_cart,
          stringResource(R.string.catalogue_add, product.name),
          c.primary,
          c.onPrimary,
          onAdd,
          Modifier.testTag(CatalogueTestTags.add(product.id)),
      )
      return@Row
    }
    val last = quantity == 1
    RoundButton(
        if (last) R.drawable.ic_catalogue_trash else R.drawable.ic_catalogue_minus,
        stringResource(
            if (last) R.string.catalogue_remove else R.string.catalogue_decrease,
            product.name,
        ),
        if (last) c.errorContainer else c.surfaceContainerHigh,
        if (last) c.onErrorContainer else c.onSurface,
        onDecrease,
        border = if (last) null else BorderStroke(1.dp, c.outlineVariant),
    )
    AnimatedContent(
        targetState = quantity,
        transitionSpec = {
          val direction = if (targetState > initialState) 1 else -1
          (slideInVertically { it * direction } + fadeIn()) togetherWith
              (slideOutVertically { -it * direction } + fadeOut())
        },
        label = "quantity",
    ) { value ->
      Text(
          value.toString(),
          modifier = Modifier.widthIn(min = 24.dp).testTag(CatalogueTestTags.quantity(product.id)),
          style = catalogueText(16, FontWeight.Medium),
          color = c.onSurface,
          textAlign = TextAlign.Center,
          maxLines = 1,
      )
    }
    RoundButton(
        R.drawable.ic_catalogue_plus,
        stringResource(R.string.catalogue_increase, product.name),
        c.primary,
        c.onPrimary,
        onIncrease,
        Modifier.testTag(CatalogueTestTags.increase(product.id)),
        enabled = quantity < Int.MAX_VALUE,
    )
  }
}

/**
 * Circular icon button drawn at its Figma [size] and centred in a touch target of at least 48 dp.
 * Material's IconButton would instead force a 40 dp visual container.
 */
@Composable
internal fun RoundButton(
    @DrawableRes icon: Int,
    description: String,
    container: Color,
    content: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    border: BorderStroke? = null,
    enabled: Boolean = true,
    size: Dp = 44.dp,
    iconSize: Dp = 20.dp,
) {
  val alpha = if (enabled) 1f else 0.4f
  Box(
      modifier
          .sizeIn(minWidth = MinTouchTarget, minHeight = MinTouchTarget)
          .clickable(
              interactionSource = null,
              indication = ripple(bounded = false, radius = size / 2),
              enabled = enabled,
              role = Role.Button,
              onClick = onClick,
          ),
      contentAlignment = Alignment.Center,
  ) {
    Box(
        Modifier.size(size)
            .background(container.copy(alpha = container.alpha * alpha), CircleShape)
            .then(if (border != null) Modifier.border(border, CircleShape) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
      Icon(painterResource(icon), description, Modifier.size(iconSize), content.copy(alpha = alpha))
    }
  }
}

private val MinTouchTarget = 48.dp

@Composable
internal fun SkeletonProductRow(modifier: Modifier = Modifier) {
  val c = MaterialTheme.colorScheme
  val block = c.surfaceContainerHigh
  Row(
      modifier
          .fillMaxWidth()
          .border(1.dp, c.outlineVariant, RoundedCornerShape(14.dp))
          .padding(start = 12.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    Box(Modifier.size(56.dp).background(block, RoundedCornerShape(12.dp)))
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Box(Modifier.width(120.dp).height(14.dp).background(block, RoundedCornerShape(7.dp)))
      Box(Modifier.width(64.dp).height(10.dp).background(block, RoundedCornerShape(5.dp)))
    }
    Box(Modifier.size(44.dp).background(block, CircleShape))
  }
}

/** Shared card for the empty-search, empty, unsupported and error states. */
@Composable
internal fun CatalogueMessage(
    @DrawableRes icon: Int,
    title: String,
    body: String,
    accentIcon: Boolean,
    modifier: Modifier = Modifier,
    action: String? = null,
    primaryAction: Boolean = false,
    onAction: () -> Unit = {},
) {
  val c = MaterialTheme.colorScheme
  Column(
      modifier
          .fillMaxWidth()
          .background(c.secondaryContainer, RoundedCornerShape(20.dp))
          .border(1.dp, c.outline, RoundedCornerShape(20.dp))
          .padding(start = 24.dp, end = 24.dp, top = 40.dp, bottom = 28.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    Box(
        Modifier.size(88.dp)
            .background(if (accentIcon) c.primary else c.surfaceContainerHigh, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
      CatalogueIcon(icon, 40.dp, if (accentIcon) c.onPrimary else c.onSurfaceVariant)
    }
    Text(
        title,
        modifier = Modifier.semantics { heading() },
        style = catalogueText(22, FontWeight.Bold),
        color = c.onSurface,
        textAlign = TextAlign.Center,
    )
    Text(body, style = catalogueText(14), color = c.onSurfaceVariant, textAlign = TextAlign.Center)
    if (action == null) return@Column
    if (primaryAction) {
      PrimaryPillButton(action, true, onAction, Modifier.testTag(CatalogueTestTags.MESSAGE_ACTION))
    } else {
      OutlinedButton(
          onClick = onAction,
          modifier =
              Modifier.width(200.dp)
                  .heightIn(min = 48.dp)
                  .testTag(CatalogueTestTags.MESSAGE_ACTION),
          shape = RoundedCornerShape(24.dp),
          border = BorderStroke(1.dp, c.outline),
      ) {
        Text(action, style = catalogueText(15, FontWeight.Bold), color = c.onSurface)
      }
    }
  }
}

/** Figma "Button / Primary": 56 dp pill with a soft green glow while enabled. */
@Composable
internal fun PrimaryPillButton(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
  val c = MaterialTheme.colorScheme
  val shape = RoundedCornerShape(28.dp)
  val glow = c.primary.copy(alpha = 0.33f)
  Button(
      onClick = onClick,
      enabled = enabled,
      shape = shape,
      colors =
          ButtonDefaults.buttonColors(
              containerColor = c.primary,
              contentColor = c.onPrimary,
              disabledContainerColor = c.surfaceContainerHigh,
              disabledContentColor = c.onSurfaceVariant,
          ),
      modifier =
          modifier
              .fillMaxWidth()
              .heightIn(min = 56.dp)
              .then(
                  if (enabled) Modifier.shadow(12.dp, shape, ambientColor = glow, spotColor = glow)
                  else Modifier
              ),
  ) {
    Text(text, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
  }
}

@Composable
internal fun CatalogueIcon(
    @DrawableRes id: Int,
    size: Dp,
    tint: Color,
    description: String? = null,
) {
  Icon(painterResource(id), description, Modifier.size(size), tint = tint)
}
