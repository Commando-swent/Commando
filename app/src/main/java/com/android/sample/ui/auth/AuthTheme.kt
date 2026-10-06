package com.android.sample.ui.auth

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.android.sample.R

@OptIn(ExperimentalTextApi::class)
private val authFont =
    FontFamily(
        Font(
            R.font.plus_jakarta_sans,
            FontWeight.Normal,
            variationSettings = FontVariation.Settings(FontVariation.weight(400)),
        ),
        Font(
            R.font.plus_jakarta_sans,
            FontWeight.SemiBold,
            variationSettings = FontVariation.Settings(FontVariation.weight(600)),
        ),
        Font(
            R.font.plus_jakarta_sans,
            FontWeight.Bold,
            variationSettings = FontVariation.Settings(FontVariation.weight(700)),
        ),
    )

private fun style(size: Int, weight: FontWeight) =
    TextStyle(
        fontFamily = authFont,
        fontSize = size.sp,
        lineHeight = (size * 1.4).sp,
        fontWeight = weight,
        letterSpacing = 0.sp,
    )

/** Scoped to authentication so the rest of the application's theme remains independent. */
@Composable
internal fun AuthTheme(content: @Composable () -> Unit) {
  MaterialTheme(
      colorScheme =
          darkColorScheme(
              primary = Color(0xFFB9E48A),
              onPrimary = Color(0xFF17240A),
              background = Color(0xFF161B12),
              surface = Color(0xFF1D2417),
              onSurface = Color(0xFFF4F2E6),
              onSurfaceVariant = Color(0xFFC3C9B2),
              outline = Color(0xFF8B947A),
              outlineVariant = Color(0xFF3F4A33),
              secondaryContainer = Color(0xFF34402A),
              error = Color(0xFFFFB4AB),
          ),
      typography =
          Typography(
              headlineLarge = style(32, FontWeight.Bold),
              titleMedium = style(18, FontWeight.Bold),
              bodyLarge = style(16, FontWeight.Normal),
              bodySmall = style(12, FontWeight.Normal),
              labelLarge = style(16, FontWeight.Bold),
              labelMedium = style(14, FontWeight.SemiBold),
          ),
      content = {
        CompositionLocalProvider(
            LocalContentColor provides MaterialTheme.colorScheme.onSurface,
            content = content,
        )
      },
  )
}
