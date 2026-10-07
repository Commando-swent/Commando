qpackage com.android.sample.ui.theme

// AI assistance: OpenAI Codex.
import android.app.Activity
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val AppColorScheme =
    darkColorScheme(
        primary = Primary,
        onPrimary = OnPrimary,
        primaryContainer = PrimaryContainer,
        onPrimaryContainer = OnPrimaryContainer,
        surfaceContainerHighest = SurfaceContainerHighest,
        background = Background,
        onBackground = OnBackground,
        surface = Surface,
        onSurface = OnSurface,
        onSurfaceVariant = OnSurfaceVariant,
        outline = Outline,
        outlineVariant = OutlineVariant,
        secondaryContainer = SecondaryContainer,
        onSecondaryContainer = OnSecondaryContainer,
        surfaceContainer = SurfaceContainer,
        error = Error,
        primaryContainer = PrimaryContainer,
        onPrimaryContainer = OnPrimaryContainer,
        surfaceContainerLow = SurfaceContainerLow,
        surfaceContainerHigh = SurfaceContainerHigh,
        errorContainer = ErrorContainer,
        onErrorContainer = OnErrorContainer,
    )

/** The Figma design uses a fixed dark palette, independent of Android's wallpaper colors. */
@Composable
fun SampleAppTheme(content: @Composable () -> Unit) {
  val view = LocalView.current
  if (!view.isInEditMode) {
    SideEffect {
      val window = (view.context as? Activity)?.window
      if (window != null) {
        window.statusBarColor = AppColorScheme.background.toArgb()
        window.navigationBarColor = AppColorScheme.surfaceContainer.toArgb()
        WindowCompat.getInsetsController(window, view).apply {
          isAppearanceLightStatusBars = false
          isAppearanceLightNavigationBars = false
        }
      }
    }
  }
  MaterialTheme(colorScheme = AppColorScheme, typography = Typography) {
    CompositionLocalProvider(LocalContentColor provides AppColorScheme.onSurface, content = content)
  }
}
