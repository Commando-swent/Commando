package com.commando.app.ui.orders

// AI assistance: Claude Code (checkout UI following Figma #283, #419).
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.commando.app.R
import com.commando.app.model.Order
import com.commando.app.model.PaymentMethod
import com.commando.app.model.itemCount
import com.commando.app.model.totalCents

/** Building blocks of the checkout states, styled after the Figma payment prototype (#283). */
@Composable
internal fun PaymentNotice(title: String, message: String, isError: Boolean) {
  val colors = MaterialTheme.colorScheme
  val accent = if (isError) colors.error else colors.tertiary
  Card(
      Modifier.fillMaxWidth().testTag(CheckoutTestTags.NOTICE).semantics(mergeDescendants = true) {
        liveRegion = LiveRegionMode.Polite
      },
      shape = RoundedCornerShape(20.dp),
      colors = CardDefaults.cardColors(containerColor = accent.copy(alpha = 0.08f)),
      border = BorderStroke(1.dp, accent),
  ) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Text(
          title,
          color = accent,
          style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp),
      )
      Text(message, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp))
    }
  }
}

@Composable
internal fun PaymentBreakdown(order: Order, paid: Boolean) {
  val products = order.totalCents - order.commissionCents
  PaymentCard {
    SectionLabel(
        stringResource(if (paid) R.string.payment_receipt else R.string.payment_order_summary)
    )
    AmountRow(
        stringResource(
            R.string.payment_products,
            pluralStringResource(
                R.plurals.payment_items,
                order.itemCount.toInt(),
                order.itemCount.toInt(),
            ),
        ),
        money(products),
    )
    AmountRow(stringResource(R.string.payment_commission), money(order.commissionCents.toLong()))
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Row(
        Modifier.fillMaxWidth().testTag(CheckoutTestTags.TOTAL).semantics(
            mergeDescendants = true
        ) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
          stringResource(if (paid) R.string.payment_total_paid else R.string.payment_total_to_pay),
          Modifier.weight(1f),
          style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
      )
      Text(
          money(order.totalCents),
          style = MaterialTheme.typography.titleMedium.copy(fontSize = 20.sp),
      )
    }
  }
}

@Composable
internal fun PaymentMethodRow(
    method: PaymentMethod,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
  val colors = MaterialTheme.colorScheme
  Row(
      Modifier.fillMaxWidth()
          .heightIn(min = 80.dp)
          .background(
              if (selected) colors.primaryContainer else colors.surfaceContainerLow,
              RoundedCornerShape(18.dp),
          )
          .border(
              if (selected) 1.5.dp else 1.dp,
              if (selected) colors.primary else colors.outlineVariant,
              RoundedCornerShape(18.dp),
          )
          .selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
          .padding(horizontal = 16.dp, vertical = 12.dp)
          .testTag(CheckoutTestTags.method(method)),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Box(
        Modifier.size(width = 52.dp, height = 32.dp)
            .background(
                if (method == PaymentMethod.TWINT) Color.Black else colors.surfaceContainer,
                RoundedCornerShape(8.dp),
            ),
        contentAlignment = Alignment.Center,
    ) {
      Text(
          if (method == PaymentMethod.TWINT) "TWINT" else "▭",
          color = if (method == PaymentMethod.TWINT) Color.White else colors.primary,
          fontSize = if (method == PaymentMethod.TWINT) 10.sp else 20.sp,
          fontWeight = FontWeight.Bold,
          modifier = Modifier.clearAndSetSemantics {},
      )
    }
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
      Text(
          methodName(method),
          style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
      )
      Text(
          stringResource(
              if (method == PaymentMethod.TWINT) R.string.payment_twint_hint
              else R.string.payment_card_hint
          ),
          color = colors.onSurfaceVariant,
          style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
      )
    }
    RadioButton(selected = selected, onClick = null, enabled = enabled)
  }
}

@Composable
internal fun PaymentBottomBar(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean,
    processing: Boolean,
    footer: String,
) {
  val colors = MaterialTheme.colorScheme
  Column(
      Modifier.fillMaxWidth()
          .background(colors.surfaceContainerLowest)
          .padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 24.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    if (processing) {
      val text = stringResource(R.string.payment_processing)
      Row(
          Modifier.testTag(CheckoutTestTags.PROCESSING).semantics(mergeDescendants = true) {
            liveRegion = LiveRegionMode.Polite
          },
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        Text(text, style = MaterialTheme.typography.bodySmall)
      }
    }
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(CheckoutTestTags.PRIMARY),
        shape = RoundedCornerShape(28.dp),
    ) {
      Text(label, fontWeight = FontWeight.Bold, fontSize = 16.sp)
    }
    Text(
        footer,
        color = colors.onSurfaceVariant,
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
    )
  }
}

@Composable
internal fun PaymentCard(content: @Composable ColumnScope.() -> Unit) {
  Card(
      Modifier.fillMaxWidth(),
      shape = RoundedCornerShape(20.dp),
      colors =
          CardDefaults.cardColors(
              containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
              contentColor = MaterialTheme.colorScheme.onSurface,
          ),
      border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
  ) {
    Column(
        Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
  }
}

@Composable
internal fun AmountRow(label: String, amount: String) {
  Row(
      Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Text(
        label,
        Modifier.weight(1f),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
    )
    Text(amount, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
  }
}

@Composable
internal fun SectionLabel(text: String) =
    Text(
        text,
        Modifier.semantics { heading() },
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
    )

@Composable
internal fun SecondaryText(text: String) =
    Text(
        text,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
    )
