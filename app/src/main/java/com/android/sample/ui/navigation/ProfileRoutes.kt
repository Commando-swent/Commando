package com.android.sample.ui.navigation

// AI assistance: OpenAI Codex (#323).
import androidx.activity.compose.BackHandler
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.android.sample.data.repository.NotificationRepository
import com.android.sample.model.authentication.AuthException
import com.android.sample.ui.notifications.NotificationInboxScreen
import com.android.sample.ui.notifications.NotificationPreferencesScreen
import com.android.sample.ui.notifications.NotificationsViewModel
import com.android.sample.ui.orders.SessionOrderViewModel
import com.android.sample.ui.profile.AppLanguage
import com.android.sample.ui.profile.EditProfileScreen
import com.android.sample.ui.profile.ProfileHelpScreen
import com.android.sample.ui.profile.ProfileLanguageScreen
import com.android.sample.ui.profile.ProfileScreen
import com.android.sample.ui.profile.ProfileUiState
import com.android.sample.ui.profile.ProfileViewModel
import com.android.sample.ui.profile.rememberProfilePhotoActions

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
    photoSnackbarHost: SnackbarHostState,
    notifications: Lazy<NotificationRepository>,
    orders: SessionOrderViewModel,
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
    val model = notificationsViewModel(notifications, orders)
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
    val model = notificationsViewModel(notifications, orders)
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
  composable(route = CommandoScreens.EditProfile.name) {
    val draft by profileViewModel.editState.collectAsState()
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
