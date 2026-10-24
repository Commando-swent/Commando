package com.android.sample.ui.profile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.android.sample.R

/** Explains implemented functionality and displays the team's support email address. */
@Composable
fun ProfileHelpScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
  var expanded by rememberSaveable { mutableIntStateOf(-1) }
  val questions =
      listOf(
          R.string.profile_faq_trips to R.string.profile_faq_trips_answer,
          R.string.profile_faq_orders to R.string.profile_faq_orders_answer,
          R.string.profile_faq_payments to R.string.profile_faq_payments_answer,
          R.string.profile_faq_cancel to R.string.profile_faq_cancel_answer,
          R.string.profile_faq_contact to R.string.profile_faq_contact_answer,
      )
  ProfileSettingsPage(
      R.string.profile_help,
      onBack,
      modifier.testTag(ProfileTestTags.HELP_SCREEN),
  ) {
    ProfileMenuSection(R.string.profile_popular_questions) {
      questions.forEachIndexed { index, (title, answer) ->
        val isExpanded = expanded == index
        val stateLabel =
            stringResource(
                if (isExpanded) R.string.profile_faq_expanded else R.string.profile_faq_collapsed
            )
        if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        ProfileMenuRow(
            title,
            onClick = { expanded = if (isExpanded) -1 else index },
            modifier =
                Modifier.testTag("${ProfileTestTags.FAQ_QUESTION}_$index").semantics {
                  stateDescription = stateLabel
                },
        )
        if (isExpanded) {
          Text(
              stringResource(answer),
              Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              style = MaterialTheme.typography.bodyMedium,
          )
        }
      }
    }
    ProfileMenuSection(R.string.profile_still_need_help) {
      Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
          ProfileMenuIcon(R.drawable.profile_support)
          Text(
              stringResource(R.string.profile_contact_support),
              style = MaterialTheme.typography.titleSmall,
          )
        }
        SelectionContainer {
          Text(
              stringResource(R.string.profile_support_email),
              color = MaterialTheme.colorScheme.primary,
          )
        }
      }
    }
  }
}
