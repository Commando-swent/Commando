package com.android.sample.ui.components

// AI assistance: OpenAI Codex (extract existing catalogue formatter for shared use).
import android.text.format.DateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

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
