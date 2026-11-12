package com.android.sample.ui.auth

// AI assistance: OpenAI Codex. Assets and layout from Figma Auth · 01 · Welcome.
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.android.sample.R
import com.android.sample.ui.theme.SampleAppTheme

/** Stable selectors for the welcome destination and its authentication entry points. */
object WelcomeTestTags {
  const val SCREEN = "welcome_screen"
  const val LOGIN = "welcome_login"
  const val CREATE_ACCOUNT = "welcome_create_account"
}

/**
 * Public entry screen from Figma. The navigation owner opens the chosen authentication form.
 * Decorative artwork is excluded from accessibility; text and actions scroll with large fonts.
 *
 * @param onLogIn Opens the existing login form.
 * @param onCreateAccount Opens the existing sign-up form.
 * @param modifier Modifier applied to the screen root.
 */
@Composable
fun WelcomeScreen(onLogIn: () -> Unit, onCreateAccount: () -> Unit, modifier: Modifier = Modifier) {
  val colors = MaterialTheme.colorScheme
  BoxWithConstraints(
      modifier.fillMaxSize().background(colors.background).testTag(WelcomeTestTags.SCREEN),
      contentAlignment = Alignment.TopCenter,
  ) {
    // Match the 915dp Figma canvas while keeping the actions visible on shorter displays.
    val topSpacing = (maxHeight - 855.dp).coerceIn(24.dp, 60.dp)
    Box(Modifier.widthIn(max = 460.dp).fillMaxWidth().clipToBounds()) {
      Image(
          painterResource(R.drawable.welcome_ellipse),
          null,
          // Export contains the 250 × 270 portion visible inside the Figma screen.
          Modifier.offset(x = 162.dp).size(width = 250.dp, height = 270.dp),
      )
      Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        Row(
            Modifier.padding(start = 24.dp, end = 24.dp, top = topSpacing),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          Image(painterResource(R.drawable.welcome_logo), null, Modifier.size(38.dp))
          Text(
              stringResource(R.string.auth_brand),
              style =
                  MaterialTheme.typography.titleMedium.copy(
                      fontSize = 25.sp,
                      lineHeight = 32.sp,
                      letterSpacing = (-0.4).sp,
                  ),
          )
        }
        Spacer(Modifier.height(56.dp))
        Column(
            Modifier.padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
          Text(
              buildAnnotatedString {
                append(stringResource(R.string.welcome_headline))
                append('\n')
                withStyle(SpanStyle(color = colors.primary)) {
                  append(stringResource(R.string.welcome_headline_accent))
                }
              },
              style =
                  MaterialTheme.typography.headlineLarge.copy(
                      fontSize = 48.sp,
                      lineHeight = 52.sp,
                      letterSpacing = (-1.4).sp,
                      fontWeight = FontWeight.ExtraBold,
                  ),
          )
          Text(
              stringResource(R.string.welcome_subtitle),
              color = colors.onSurfaceVariant,
              style = MaterialTheme.typography.bodyLarge.copy(fontSize = 18.sp, lineHeight = 26.sp),
          )
        }
        // The artwork's transparent top overlaps the subtitle's layout in the Figma composition.
        BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(412f / 341f).zIndex(-1f)) {
          Image(
              painterResource(R.drawable.welcome_groceries),
              null,
              Modifier.fillMaxWidth()
                  .wrapContentSize(Alignment.TopStart, unbounded = true)
                  .requiredSize(width = maxWidth, height = maxWidth * (380f / 412f))
                  .offset(y = -maxWidth * (39f / 412f)),
          )
        }
        Column(
            Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          Button(
              onClick = onLogIn,
              shape = CircleShape,
              modifier =
                  Modifier.fillMaxWidth().heightIn(min = 60.dp).testTag(WelcomeTestTags.LOGIN),
          ) {
            Text(stringResource(R.string.auth_login_action))
          }
          OutlinedButton(
              onClick = onCreateAccount,
              shape = CircleShape,
              border = BorderStroke(1.dp, colors.onSurface),
              colors =
                  ButtonDefaults.outlinedButtonColors(
                      containerColor = colors.surface,
                      contentColor = colors.onSurface,
                  ),
              modifier =
                  Modifier.fillMaxWidth()
                      .heightIn(min = 60.dp)
                      .testTag(WelcomeTestTags.CREATE_ACCOUNT),
          ) {
            Text(stringResource(R.string.welcome_create_account))
          }
        }
      }
    }
  }
}

/** Preview with the original Figma viewport. */
@Preview(name = "Auth · Welcome", widthDp = 412, heightDp = 915)
@Composable
private fun WelcomePreview() {
  SampleAppTheme { WelcomeScreen({}, {}) }
}
