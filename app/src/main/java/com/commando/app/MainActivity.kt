package com.commando.app

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.core.os.LocaleListCompat
import androidx.core.view.WindowCompat
import com.android.sample.resources.C
import com.commando.app.data.repository.PaymentRepositoryFirestore
import com.commando.app.data.repository.ProfilePhotoRepositoryFirestore
import com.commando.app.model.authentication.AuthRepositoryProvider
import com.commando.app.ui.navigation.CommandoApp
import com.commando.app.ui.theme.SampleAppTheme

/** Hosts Compose and Android's persisted, installation-wide language preference. */
class MainActivity : AppCompatActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    // AI assistance: OpenAI Codex. Compose draws behind transparent system bars.
    WindowCompat.enableEdgeToEdge(window)
    setContent {
      SampleAppTheme {
        // A surface container using the 'background' color from the theme
        Surface(
            modifier = Modifier.fillMaxSize().semantics { testTag = C.Tag.main_screen_container },
            color = MaterialTheme.colorScheme.background,
        ) {
          val auth = remember { AuthRepositoryProvider.repository }
          val photos = remember { ProfilePhotoRepositoryFirestore(contentResolver, auth) }
          CommandoApp(
              repository = auth,
              photoRepository = photos,
              paymentRepositoryFactory = { identity -> PaymentRepositoryFirestore(identity) },
              onLanguageSelected = { language ->
                AppCompatDelegate.setApplicationLocales(
                    LocaleListCompat.forLanguageTags(language.tag)
                )
              },
          )
        }
      }
    }
  }
}
