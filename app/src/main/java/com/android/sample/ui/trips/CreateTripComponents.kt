package com.android.sample.ui.trips

// AI assistance: Claude Code. Layout from the Command'o Figma "Publish a trip" frames.
import android.text.format.DateFormat
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.sample.R
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

// Building blocks of the trip creation form: fields and pickers.

internal val FieldShape = RoundedCornerShape(16.dp)

/** A Figma text style on top of the theme typography, so the app font still applies. */
@Composable
internal fun tripTextStyle(size: Int, lineHeight: Int, weight: FontWeight = FontWeight.Normal) =
    MaterialTheme.typography.bodyLarge.copy(
        fontSize = size.sp,
        lineHeight = lineHeight.sp,
        fontWeight = weight,
        letterSpacing = 0.sp,
    )

/** A label, the [input], then the helper text or the [error] message. */
@Composable
internal fun FormField(
    @StringRes label: Int,
    @StringRes helper: Int,
    error: CreateTripError?,
    errorTag: String,
    modifier: Modifier = Modifier,
    input: @Composable () -> Unit,
) {
  Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text(
        text = stringResource(label),
        style = tripTextStyle(13, 18, FontWeight.SemiBold),
        color = MaterialTheme.colorScheme.onSurface,
    )
    input()
    if (error == null) {
      Text(
          text = stringResource(helper),
          style = tripTextStyle(12, 16, FontWeight.Medium),
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    } else {
      Row(Modifier.testTag(errorTag), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(
            painter = painterResource(R.drawable.ic_trip_alert_circle_small),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = stringResource(error.message),
            style = tripTextStyle(12, 16, FontWeight.Medium),
            color = MaterialTheme.colorScheme.error,
        )
      }
    }
  }
}

private val CreateTripError.message: Int
  @StringRes
  get() =
      when (this) {
        CreateTripError.STORE_REQUIRED -> R.string.create_trip_error_store_required
        CreateTripError.DATE_REQUIRED -> R.string.create_trip_error_date_required
        CreateTripError.DATE_IN_PAST -> R.string.create_trip_error_date_in_past
        CreateTripError.TIME_REQUIRED -> R.string.create_trip_error_time_required
        CreateTripError.TIME_IN_PAST -> R.string.create_trip_error_time_in_past
        CreateTripError.HANDOFF_LOCATION_REQUIRED -> R.string.create_trip_error_handoff_required
        CreateTripError.MAX_ORDERS_REQUIRED,
        CreateTripError.MAX_ORDERS_NOT_POSITIVE_INTEGER -> R.string.create_trip_error_max_orders
      }

@Composable
internal fun TextInputField(
    @StringRes label: Int,
    @DrawableRes icon: Int,
    value: String,
    onValueChange: (String) -> Unit,
    @StringRes placeholder: Int,
    @StringRes helper: Int,
    error: CreateTripError?,
    enabled: Boolean,
    keyboardOptions: KeyboardOptions,
    tag: String,
    errorTag: String,
) {
  FormField(label, helper, error, errorTag) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        singleLine = true,
        textStyle = inputStyle(isValue = true, enabled = enabled),
        keyboardOptions = keyboardOptions,
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        modifier = Modifier.fillMaxWidth().testTag(tag),
        decorationBox = { innerTextField ->
          InputBox(icon = icon, isError = error != null, enabled = enabled) {
            Box(Modifier.weight(1f)) {
              if (value.isEmpty()) {
                Text(stringResource(placeholder), style = inputStyle(isValue = false, enabled))
              }
              innerTextField()
            }
          }
        },
    )
  }
}

/** A read-only field that shows [value] (or the placeholder) and opens a picker on click. */
@Composable
internal fun PickerField(
    @StringRes label: Int,
    @DrawableRes icon: Int,
    value: String?,
    @StringRes placeholder: Int,
    @StringRes helper: Int,
    error: CreateTripError?,
    enabled: Boolean,
    onClick: () -> Unit,
    tag: String,
    errorTag: String,
    modifier: Modifier = Modifier,
) {
  FormField(label, helper, error, errorTag, modifier) {
    InputBox(
        icon = icon,
        isError = error != null,
        enabled = enabled,
        modifier =
            Modifier.clip(FieldShape)
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                .testTag(tag),
    ) {
      Text(
          text = value ?: stringResource(placeholder),
          style = inputStyle(isValue = value != null, enabled = enabled),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

@Composable
private fun inputStyle(isValue: Boolean, enabled: Boolean): TextStyle =
    tripTextStyle(16, 22, if (isValue) FontWeight.Medium else FontWeight.Normal)
        .copy(
            color =
                if (isValue && enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant
        )

/** The 56dp rounded input of the Figma frames, dimmed while the form is disabled. */
@Composable
private fun InputBox(
    @DrawableRes icon: Int,
    isError: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
  val colors = MaterialTheme.colorScheme
  val border =
      when {
        isError -> BorderStroke(1.5.dp, colors.error)
        enabled -> BorderStroke(1.dp, colors.outline)
        else -> BorderStroke(1.dp, colors.outlineVariant)
      }
  Row(
      modifier =
          modifier
              .fillMaxWidth()
              .height(56.dp)
              .background(
                  if (enabled) colors.surfaceContainer else colors.surfaceContainerLow,
                  FieldShape,
              )
              .border(border, FieldShape)
              .padding(horizontal = 16.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Icon(
        painter = painterResource(icon),
        contentDescription = null,
        tint = colors.onSurfaceVariant,
        modifier = Modifier.size(24.dp),
    )
    content()
  }
}

/** Lets the user pick today or a later day. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TripDatePickerDialog(
    initialDate: LocalDate?,
    today: LocalDate,
    onDismiss: () -> Unit,
    onDatePicked: (LocalDate) -> Unit,
) {
  // DatePicker works with UTC midnights, whatever the device time zone.
  val todayMillis = today.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
  val state =
      rememberDatePickerState(
          initialSelectedDateMillis =
              initialDate?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli(),
          selectableDates =
              object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis >= todayMillis

                override fun isSelectableYear(year: Int) = year >= today.year
              },
      )
  DatePickerDialog(
      onDismissRequest = onDismiss,
      confirmButton = {
        OkButton {
          state.selectedDateMillis?.let {
            onDatePicked(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate())
          } ?: onDismiss()
        }
      },
      dismissButton = { CancelButton(onDismiss) },
  ) {
    DatePicker(state = state)
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TripTimePickerDialog(
    initialTime: LocalTime?,
    onDismiss: () -> Unit,
    onTimePicked: (LocalTime) -> Unit,
) {
  val state =
      rememberTimePickerState(
          initialHour = initialTime?.hour ?: 12,
          initialMinute = initialTime?.minute ?: 0,
          is24Hour = DateFormat.is24HourFormat(LocalContext.current),
      )
  AlertDialog(
      onDismissRequest = onDismiss,
      text = { TimePicker(state = state) },
      confirmButton = { OkButton { onTimePicked(LocalTime.of(state.hour, state.minute)) } },
      dismissButton = { CancelButton(onDismiss) },
  )
}

@Composable
private fun OkButton(onClick: () -> Unit) {
  TextButton(onClick = onClick) { Text(stringResource(R.string.create_trip_dialog_ok)) }
}

@Composable
private fun CancelButton(onClick: () -> Unit) {
  TextButton(onClick = onClick) { Text(stringResource(R.string.create_trip_dialog_cancel)) }
}
