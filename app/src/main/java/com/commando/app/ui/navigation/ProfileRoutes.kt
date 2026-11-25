package com.commando.app.ui.navigation

// AI assistance: OpenAI Codex (#323).
import androidx.activity.compose.BackHandler
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.commando.app.data.repository.NotificationRepository
import com.commando.app.model.authentication.AuthException
import com.commando.app.ui.notifications.NotificationInboxScreen
import com.commando.app.ui.notifications.NotificationPreferencesScreen
import com.commando.app.ui.notifications.NotificationsViewModel
import com.commando.app.ui.orders.SessionOrderViewModel
import com.commando.app.ui.profile.AppLanguage
import com.commando.app.ui.profile.EditProfileScreen
import com.commando.app.ui.profile.ProfileEditTestTags
import com.commando.app.ui.profile.ProfileHelpScreen
import com.commando.app.ui.profile.ProfileLanguageScreen
import com.commando.app.ui.profile.ProfileScreen
import com.commando.app.ui.profile.ProfileUiState
import com.commando.app.ui.profile.ProfileViewModel
import com.commando.app.ui.profile.PublicNameEditor
import com.commando.app.ui.profile.PublicNameViewModel
import com.commando.app.ui.profile.PublicNameWritesViewModel
import com.commando.app.ui.profile.rememberProfilePhotoActions

/**
 * The app-level account actions Profile triggers: [signOut] reports a failure through
 * [signOutError], and [selectLanguage] applies the chosen app language.
 */
internal class ProfileRouteActions(
    val signOut: () -> Unit,
    val signOutError: AuthException?,
    val selectLanguage: (AppLanguage) -> Unit,
)

/** Registers Profile and its settings pages around the app-owned shared Profile ViewModel. */
internal fun NavGraphBuilder.profileRoutes(
    navController: NavHostController,
    profileViewModel: ProfileViewModel,
    publicNameWrites: PublicNameWritesViewModel,
    session: SessionDependencies,
    photoSnackbarHost: SnackbarHostState,
    actions: ProfileRouteActions,
) {
  composable(route = CommandoScreens.Profile.name) {
    val profileUiState by profileViewModel.uiState.collectAsState()
    val (photo, onPhotoClick) = rememberProfilePhotoActions(profileViewModel, photoSnackbarHost)
    ProfileScreen(
        uiState = profileUiState,
        onBack = { navController.popBackStack() },
        onSignOut = actions.signOut,
        signOutError = actions.signOutError,
        photo = photo,
        onPhotoClick = onPhotoClick,
        onOrderHistory = {
          // History is a child of Profile even when My Orders was opened earlier from a tab.
          navController.navigate(CommandoScreens.MyOrders.name) { launchSingleTop = true }
        },
        onHelp = { navController.openScreen(CommandoScreens.ProfileHelp) },
        onLanguage = { navController.openScreen(CommandoScreens.ProfileLanguage) },
        onNotifications = { navController.openScreen(CommandoScreens.Notifications) },
        onNotificationPreferences = {
          navController.openScreen(CommandoScreens.NotificationPreferences)
        },
        onEdit = {
          profileViewModel.beginEditing()
          navController.navigate(CommandoScreens.EditProfile.name) { launchSingleTop = true }
        },
    )
  }
  composable(route = CommandoScreens.Notifications.name) {
    val model = notificationsViewModel(session.notifications, session.orders)
    RefreshOnResume(model, model::refreshInbox)
    val state by model.inbox.collectAsState()
    NotificationInboxScreen(
        state,
        { navController.popBackStack() },
        { navController.openScreen(CommandoScreens.NotificationPreferences) },
        model::refreshInbox,
        model::markAsRead,
        model::retryReadUpdate,
        {
          navController.navigate(CommandoScreens.orderDetailsRoute(it)) { launchSingleTop = true }
        },
    )
  }
  composable(route = CommandoScreens.NotificationPreferences.name) {
    val model = notificationsViewModel(session.notifications, session.orders)
    RefreshOnResume(model, model::refreshPreferences)
    val state by model.preferences.collectAsState()
    NotificationPreferencesScreen(
        state,
        { navController.popBackStack() },
        model::refreshPreferences,
        model::updatePreferences,
        model::retryPreferenceUpdate,
    )
  }
  composable(route = CommandoScreens.ProfileHelp.name) {
    ProfileHelpScreen(onBack = { navController.popBackStack() })
  }
  composable(route = CommandoScreens.ProfileLanguage.name) {
    ProfileLanguageScreen(
        selected = AppLanguage.fromLanguage(LocalConfiguration.current.locales[0].language),
        onSelectLanguage = actions.selectLanguage,
        onBack = { navController.popBackStack() },
    )
  }
  composable(route = CommandoScreens.EditProfile.name) { entry ->
    val names: PublicNameViewModel =
        viewModel(viewModelStoreOwner = entry) {
          PublicNameViewModel(
              session.publicProfiles,
              publicNameWrites,
              session.orders::currentUserId,
              session.orders.sessionChanges,
          )
        }
    val draft by profileViewModel.editState.collectAsState()
    val publicName by names.publicName.collectAsState()
    val profile by profileViewModel.uiState.collectAsState()
    LaunchedEffect(profile, draft == null) {
      if (profile is ProfileUiState.Content) profileViewModel.beginEditing()
    }
    val onCancel = {
      if (profileViewModel.editState.value?.isSaving != true) {
        profileViewModel.cancelEditing()
        navController.popBackStack()
      }
      Unit
    }
    BackHandler { onCancel() }
    val (photo, onPhotoClick) = rememberProfilePhotoActions(profileViewModel, photoSnackbarHost)
    draft?.let {
      EditProfileScreen(
          it,
          profileViewModel::updateFullName,
          profileViewModel::updateEmail,
          profileViewModel::saveProfile,
          onCancel,
          (profile as? ProfileUiState.Content)?.fullName,
          photo = photo,
          onPhotoClick = onPhotoClick,
          publicNameEditor = {
            PublicNameEditor(
                publicName,
                names::loadPublicName,
                names::editPublicName,
                names::savePublicName,
                Modifier.testTag(ProfileEditTestTags.PUBLIC_SECTION),
            )
          },
      )
    } ?: CircularProgressIndicator()
  }
}

/** Scopes a notifications ViewModel to the current entry and the order session's identity. */
@Composable
private fun notificationsViewModel(
    notifications: Lazy<NotificationRepository>,
    orders: SessionOrderViewModel,
): NotificationsViewModel = viewModel {
  NotificationsViewModel(notifications.value, orders::currentUserId, orders.sessionChanges)
}
