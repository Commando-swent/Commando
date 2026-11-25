package com.commando.app.ui.navigation

// AI assistance: Claude Code moved the session dependency wiring out of CommandoApp (#322) and
// added the session cache (#428).
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.commando.app.data.cache.SessionCache
import com.commando.app.data.repository.CommandoPositionRepository
import com.commando.app.data.repository.CommandoPositionRepositoryFirestore
import com.commando.app.data.repository.NotificationRepository
import com.commando.app.data.repository.NotificationRepositoryFirestore
import com.commando.app.data.repository.OrderRepository
import com.commando.app.data.repository.OrderRepositoryFirestore
import com.commando.app.data.repository.SavedTripSearchRepository
import com.commando.app.data.repository.SavedTripSearchRepositoryLocal
import com.commando.app.data.repository.ShoppingChecklistRepository
import com.commando.app.data.repository.ShoppingChecklistRepositoryFirestore
import com.commando.app.data.repository.TripRepository
import com.commando.app.data.repository.TripRepositoryFirestore
import com.commando.app.model.authentication.AuthRepository
import com.commando.app.ui.orders.SessionOrderViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** The repositories of one signed-in account, all bound to the same session identity. */
internal class SessionDependencies(
    val trips: TripRepository,
    val orders: SessionOrderViewModel,
    val checklists: ShoppingChecklistRepository,
    val savedSearches: SavedTripSearchRepository,
    val notifications: Lazy<NotificationRepository>,
    val positions: Lazy<CommandoPositionRepository>,
    val cache: SessionCache,
)

/**
 * Creates the signed-in account's repositories in the calling App entry, which is keyed by account,
 * so they are discarded on sign-out or account change. Trips, orders and positions are held by
 * ViewModels and survive rotation; the remembered repositories are rebuilt for the same account.
 * Each factory replaces its Firebase implementation in tests.
 */
@Composable
internal fun rememberSessionDependencies(
    userId: String,
    authRepository: AuthRepository,
    tripRepository: TripRepository?,
    orderRepositoryFactory: ((() -> String?) -> OrderRepository)?,
    checklistRepositoryFactory: ((() -> String?) -> ShoppingChecklistRepository)?,
    savedSearchRepositoryFactory: ((() -> String?) -> SavedTripSearchRepository)?,
    notificationRepositoryFactory: ((() -> String?) -> NotificationRepository)?,
    positionRepositoryFactory: ((() -> String?) -> CommandoPositionRepository)?,
): SessionDependencies {
  val context = LocalContext.current
  val trips: SessionTripRepositoryViewModel = viewModel {
    SessionTripRepositoryViewModel(
        tripRepository ?: TripRepositoryFirestore(authRepository = authRepository)
    )
  }
  val orders: SessionOrderViewModel = viewModel {
    SessionOrderViewModel(userId, authRepository) { currentUserId ->
      orderRepositoryFactory?.invoke(currentUserId) ?: OrderRepositoryFirestore(currentUserId)
    }
  }
  // Bound to the same session identity as the orders, so an account change ends it too.
  val checklists =
      remember(orders) {
        checklistRepositoryFactory?.invoke(orders::currentUserId)
            ?: ShoppingChecklistRepositoryFirestore(orders::currentUserId)
      }
  // Presets stay on this phone, one list per account (#191).
  val savedSearches =
      remember(orders) {
        savedSearchRepositoryFactory?.invoke(orders::currentUserId)
            ?: SavedTripSearchRepositoryLocal(context, orders::currentUserId)
      }
  // Lazily constructed so isolated previews and unrelated routes need no Firebase instance.
  val notifications =
      remember(orders) {
        lazy {
          notificationRepositoryFactory?.invoke(orders::currentUserId)
              ?: NotificationRepositoryFirestore(orders::currentUserId)
        }
      }
  val positions: SessionPositionRepository = viewModel {
    SessionPositionRepository {
      positionRepositoryFactory?.invoke(orders::currentUserId)
          ?: CommandoPositionRepositoryFirestore(orders::currentUserId)
    }
  }
  // Same identity as the orders; a new account gets a new, empty cache (#428).
  val cache: SessionCacheHolder = viewModel {
    SessionCacheHolder(SessionCache(orders::currentUserId), orders.sessionChanges)
  }
  return SessionDependencies(
      trips.repository,
      orders,
      checklists,
      savedSearches,
      notifications,
      positions.repository,
      cache.cache,
  )
}

/** Retains one trip repository for the authenticated App entry, including activity recreation. */
private class SessionTripRepositoryViewModel(val repository: TripRepository) : ViewModel()

/** Retains a lazy position transport for the authenticated session, including rotation. */
private class SessionPositionRepository(create: () -> CommandoPositionRepository) : ViewModel() {
  val repository = lazy(create)
}

/**
 * Keeps the session cache across rotation. It is emptied as soon as [sessionChanges] reports that
 * the session ended, without waiting for the next access, and again when the App entry is cleared.
 *
 * [SessionOrderViewModel.sessionChanges] emits null whenever the signed-in user is not this
 * account: sign-out, another account, or a cleared session. It follows the latest auth state, so a
 * switch away and back faster than the collector runs may be seen as no change. The App entry is
 * keyed by that same state, so no screen of the other account ran in that case, and every cache
 * access still checks the identity itself.
 */
internal class SessionCacheHolder(val cache: SessionCache, sessionChanges: Flow<String?>) :
    ViewModel() {
  init {
    viewModelScope.launch {
      sessionChanges.first { it == null }
      cache.close()
    }
  }

  override fun onCleared() = cache.close()
}
