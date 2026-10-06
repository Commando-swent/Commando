package com.android.sample.ui.theme

// AI assistance: OpenAI Codex. Typography from the Command'o Figma design.
import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.android.sample.R

@OptIn(ExperimentalTextApi::class)
private val commandoFont =
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
        fontFamily = commandoFont,
        fontSize = size.sp,
        lineHeight = (size * 1.4).sp,
        fontWeight = weight,
        letterSpacing = 0.sp,
    )

val Typography =
    Typography(
        headlineLarge = style(32, FontWeight.Bold),
        headlineMedium =
            style(30, FontWeight.ExtraBold).copy(lineHeight = 36.sp, letterSpacing = (-0.6).sp),
        bodyMedium = style(16, FontWeight.Normal).copy(lineHeight = 24.sp),
        labelSmall = style(12, FontWeight.Medium),
        titleMedium = style(18, FontWeight.Bold),
        bodyLarge = style(16, FontWeight.Normal),
        bodySmall = style(12, FontWeight.Normal),
        labelLarge = style(16, FontWeight.Bold),
        labelMedium = style(14, FontWeight.SemiBold),
    )
