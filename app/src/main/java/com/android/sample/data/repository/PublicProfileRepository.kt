package com.android.sample.data.repository

private val publicNameLength = Regex("""[^\r\n\p{Cs}]{1,80}""")

/**
 * Accepts 1–80 Unicode code points (a supplementary character counts once), excluding CR/LF and
 * unpaired surrogates. Unicode whitespace and the JVM whitespace controls alone are blank.
 */
internal fun isValidPublicName(name: String): Boolean =
    publicNameLength.matches(name) && name.any { !it.isWhitespace() && it != '\u0085' }

/** Access to names explicitly published for other authenticated users to see. */
interface PublicProfileRepository {
  /**
   * Reads the public display name of [ownerId] from the server. Returns null for a missing profile.
   * An invalid owner ID, a failed read, or a changed viewer session raises an exception.
   */
  suspend fun getDisplayName(ownerId: String): String?

  /**
   * Publishes or replaces the current owner's public name with [displayName] as entered. The
   * original spelling and spacing are preserved. Invalid input raises [IllegalArgumentException].
   */
  suspend fun publishDisplayName(displayName: String)

  /**
   * Removes the current owner's public name. A failed or unconfirmed removal raises an exception.
   */
  suspend fun removeDisplayName()
}
