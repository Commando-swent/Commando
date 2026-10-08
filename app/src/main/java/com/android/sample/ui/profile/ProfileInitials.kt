package com.android.sample.ui.profile

import java.util.Locale

/** Uses the first and last words of a real name; missing names have no invented initials. */
internal fun profileInitials(fullName: String?): String? {
  if (fullName.isNullOrBlank()) return null
  val words = fullName.trim().split(Regex("[\\s\\p{Z}]+"))
  val initials = words.first().take(1) + if (words.size > 1) words.last().take(1) else ""
  return initials.uppercase(Locale.ROOT)
}
