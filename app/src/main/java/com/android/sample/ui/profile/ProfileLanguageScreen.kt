package com.android.sample.ui.profile

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.android.sample.R
import java.util.Locale

/**
 * Supported languages with labels in both interface languages; Android stores the app preference.
 */
enum class AppLanguage(
    val tag: String,
    @param:StringRes val label: Int,
    @param:StringRes val alternateLabel: Int,
) {
  English("en", R.string.profile_language_english, R.string.profile_language_english_alternate),
  French("fr", R.string.profile_language_french, R.string.profile_language_french_alternate);

  companion object {
    fun fromLanguage(language: String): AppLanguage =
        entries.firstOrNull { it.tag == language } ?: English
  }
}

/** Shows the effective resource language and delegates changes to the activity's locale API. */
@Composable
fun ProfileLanguageScreen(
    selected: AppLanguage,
    onSelectLanguage: (AppLanguage) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
  ProfileSettingsPage(
      R.string.profile_language,
      onBack,
      modifier.testTag(ProfileTestTags.LANGUAGE_SCREEN),
  ) {
    ProfileMenuSection(R.string.profile_app_language) {
      Column(Modifier.selectableGroup()) {
        AppLanguage.entries.forEachIndexed { index, language ->
          if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
          Row(
              Modifier.fillMaxWidth()
                  .testTag("${ProfileTestTags.LANGUAGE_OPTION}_${language.tag}")
                  .selectable(
                      selected == language,
                      role = Role.RadioButton,
                      onClick = { onSelectLanguage(language) },
                  )
                  .heightIn(min = 64.dp)
                  .padding(horizontal = 14.dp, vertical = 10.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            Surface(
                shape = RoundedCornerShape(9.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
              Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                Text(
                    language.tag.uppercase(Locale.ROOT),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelSmall,
                )
              }
            }
            Column(Modifier.weight(1f)) {
              Text(stringResource(language.label), style = MaterialTheme.typography.bodyMedium)
              Text(
                  stringResource(language.alternateLabel),
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  style = MaterialTheme.typography.bodySmall,
              )
            }
            RadioButton(selected = selected == language, onClick = null)
          }
        }
      }
    }
    Text(
        stringResource(R.string.profile_language_scope),
        Modifier.padding(start = 4.dp, top = 12.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall,
    )
  }
}
