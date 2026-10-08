package com.android.sample.screen

import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import com.android.sample.resources.C
import com.android.sample.ui.navigation.NavigationTestTags
import io.github.kakaocup.compose.node.element.ComposeScreen
import io.github.kakaocup.compose.node.element.KNode

class MainScreen(semanticsProvider: SemanticsNodeInteractionsProvider) :
    ComposeScreen<MainScreen>(
        semanticsProvider = semanticsProvider,
        viewBuilderAction = { hasTestTag(C.Tag.main_screen_container) },
    ) {

  val loginScreen: KNode = child { hasTestTag(NavigationTestTags.LOGIN_SCREEN) }
}
