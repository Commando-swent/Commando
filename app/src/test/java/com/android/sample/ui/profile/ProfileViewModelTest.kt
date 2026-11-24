package com.android.sample.ui.profile

import androidx.lifecycle.ViewModelStore
import com.android.sample.data.repository.ProfilePhotoRepository
import com.android.sample.model.authentication.AuthException
import com.android.sample.model.authentication.AuthRepository
import com.android.sample.model.authentication.AuthUser
import com.android.sample.model.authentication.FakeAuthRepository
import com.android.sample.model.authentication.ProfileUpdateResult
import com.commando.app.data.repository.PublicProfileRepository
import com.commando.app.model.ProfilePhotoSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileViewModelTest {
  private val alice = AuthUser("alice", displayName = "Alice Martin", email = "alice@epfl.ch")
  private val store = ViewModelStore()

  @Before
  fun setUp() {
    Dispatchers.setMain(StandardTestDispatcher())
  }

  @After
  fun tearDown() {
    store.clear()
    Dispatchers.resetMain()
  }

  private fun createViewModel(repository: AuthRepository): ProfileViewModel =
      ProfileViewModel(repository).also { store.put("profile", it) }

  @Test
  fun startsLoadingBeforeReceivingExistingUser() = runTest {
    val viewModel = createViewModel(FakeAuthRepository(alice))

    assertEquals(ProfileUiState.Loading, viewModel.uiState.value)
    runCurrent()

    assertEquals(
        ProfileUiState.Content("Alice Martin", "alice@epfl.ch"),
        viewModel.uiState.value,
    )
  }

  @Test
  fun remainsLoadingUntilAuthFlowEmitsInsteadOfUsingSnapshot() = runTest {
    val repository = ControlledAuthRepository()
    val viewModel = createViewModel(repository)
    runCurrent()

    assertEquals(ProfileUiState.Loading, viewModel.uiState.value)
    repository.emit(alice)
    runCurrent()

    assertEquals(
        ProfileUiState.Content("Alice Martin", "alice@epfl.ch"),
        viewModel.uiState.value,
    )
  }

  @Test
  fun absentUserBecomesMissingUserWithoutProfileInformation() = runTest {
    val viewModel = createViewModel(FakeAuthRepository())
    runCurrent()

    assertEquals(ProfileUiState.MissingUser, viewModel.uiState.value)
  }

  @Test
  fun delayedNullEmissionEndsLoadingWithMissingUser() = runTest {
    val repository = ControlledAuthRepository()
    val viewModel = createViewModel(repository)
    runCurrent()

    repository.emit(null)
    runCurrent()

    assertEquals(ProfileUiState.MissingUser, viewModel.uiState.value)
  }

  @Test
  fun nullEmptyAndWhitespaceNamesRemainAbsentWithoutPlaceholderData() = runTest {
    val repository = FakeAuthRepository(alice)
    val viewModel = createViewModel(repository)
    runCurrent()

    for (name in listOf(null, "", " ", "\t\n ")) {
      repository.signInWithEmailResult = Result.success(alice.copy(displayName = name))
      repository.signInWithEmail("", "")
      runCurrent()

      assertEquals(ProfileUiState.Content(null, alice.email), viewModel.uiState.value)
    }
  }

  @Test
  fun preservesActualNameSpellingAndEmail() = runTest {
    val user = alice.copy(displayName = "  Élodie van der Meer  ", email = "Elodie+work@epfl.ch")
    val viewModel = createViewModel(FakeAuthRepository(user))
    runCurrent()

    assertEquals(ProfileUiState.Content(user.displayName, user.email), viewModel.uiState.value)
  }

  @Test
  fun missingEmailIsNotReplacedWithFabricatedInformation() = runTest {
    val viewModel = createViewModel(FakeAuthRepository(alice.copy(email = null)))
    runCurrent()

    assertEquals(ProfileUiState.Content("Alice Martin", null), viewModel.uiState.value)
  }

  @Test
  fun receivesProfileAfterSignUp() = runTest {
    val repository = FakeAuthRepository()
    val viewModel = createViewModel(repository)
    runCurrent()
    repository.signUpWithEmailResult = Result.success(alice)

    repository.signUpWithEmail(alice.email!!, "password")
    runCurrent()

    assertEquals(
        ProfileUiState.Content("Alice Martin", "alice@epfl.ch"),
        viewModel.uiState.value,
    )
  }

  @Test
  fun accountChangeReplacesAllPreviousInformationEvenWhenNewFieldsAreAbsent() = runTest {
    val repository = FakeAuthRepository(alice)
    val viewModel = createViewModel(repository)
    runCurrent()
    repository.signInWithEmailResult = Result.success(AuthUser("bob"))

    repository.signInWithEmail("", "")
    runCurrent()

    assertEquals(ProfileUiState.Content(null, null), viewModel.uiState.value)
  }

  @Test
  fun changedDetailsForSameAccountAreObserved() = runTest {
    val repository = FakeAuthRepository(alice)
    val viewModel = createViewModel(repository)
    runCurrent()
    val updated = alice.copy(displayName = "Alice Dupont", email = "alice.dupont@epfl.ch")
    repository.signInWithEmailResult = Result.success(updated)

    repository.signInWithEmail("", "")
    runCurrent()

    assertEquals(
        ProfileUiState.Content(updated.displayName, updated.email),
        viewModel.uiState.value,
    )
  }

  @Test
  fun signOutClearsProfileAndLaterSignInLoadsTheNewAccount() = runTest {
    val repository = FakeAuthRepository(alice)
    val viewModel = createViewModel(repository)
    runCurrent()

    repository.signOut()
    runCurrent()
    assertEquals(ProfileUiState.MissingUser, viewModel.uiState.value)

    val bob = AuthUser("bob", displayName = "Bob Lee", email = "bob@epfl.ch")
    repository.signInWithEmailResult = Result.success(bob)
    repository.signInWithEmail("", "")
    runCurrent()
    assertEquals(ProfileUiState.Content("Bob Lee", "bob@epfl.ch"), viewModel.uiState.value)
  }

  @Test
  fun clearingViewModelCancelsAuthObservation() = runTest {
    val repository = ControlledAuthRepository()
    val viewModel = createViewModel(repository)
    runCurrent()
    repository.emit(alice)
    runCurrent()
    assertTrue(repository.isCollecting)

    store.clear()
    runCurrent()
    assertFalse(repository.isCollecting)

    repository.emit(null)
    runCurrent()
    assertEquals(
        ProfileUiState.Content("Alice Martin", "alice@epfl.ch"),
        viewModel.uiState.value,
    )
  }

  @Test
  fun editValidationCancellationAndReopeningPreserveConfirmedValues() = runTest {
    val fake = FakeAuthRepository(alice)
    val model = createViewModel(fake)
    runCurrent()
    model.beginEditing()
    assertEquals(alice.displayName, model.editState.value!!.fullName)
    assertEquals(alice.email, model.editState.value!!.email)
    model.updateFullName(" ")
    model.updateEmail("invalid")
    model.saveProfile()
    runCurrent()
    assertFalse(model.editState.value!!.canSave)
    assertEquals(alice, fake.currentUser)
    model.cancelEditing()
    assertNull(model.editState.value)
    model.beginEditing()
    model.updateFullName(" Alice Dupont ")
    model.updateEmail(" new@epfl.ch ")
    model.saveProfile()
    runCurrent()
    assertTrue(model.editState.value!!.saved)
    assertEquals("new@epfl.ch", model.editState.value!!.pendingEmail)
    assertEquals(ProfileUiState.Content("Alice Dupont", alice.email), model.uiState.value)
  }

  @Test
  fun saveFailuresKeepDraftAndCorrectionAllowsRetry() = runTest {
    val fake = FakeAuthRepository(alice)
    val model = createViewModel(fake)
    runCurrent()
    model.beginEditing()
    for (error in
        listOf(
            AuthException.Network(),
            AuthException.RequiresRecentLogin(),
            AuthException.EmailAlreadyInUse(),
            AuthException.InvalidEmail(),
            AuthException.Unknown(),
        )) {
      fake.updateProfileResult = Result.failure(error)
      model.updateEmail(alice.email!!)
      model.updateFullName("Draft name")
      model.saveProfile()
      runCurrent()
      assertSame(error, model.editState.value!!.error)
      assertEquals("Draft name", model.editState.value!!.fullName)
      assertEquals(alice, fake.currentUser)
    }
    fake.updateProfileResult = null
    model.updateEmail(alice.email!!)
    model.saveProfile()
    runCurrent()
    assertTrue(model.editState.value!!.saved)
    assertNull(model.editState.value!!.pendingEmail)
  }

  @Test
  fun savingGuardsDuplicateClicksAndAccountChangeCancelsTheOldDraft() = runTest {
    val fake = FakeAuthRepository(alice)
    val pending = CompletableDeferred<Result<ProfileUpdateResult>>()
    var calls = 0
    val repository =
        object : AuthRepository by fake {
          override suspend fun updateProfile(
              fullName: String,
              email: String,
          ): Result<ProfileUpdateResult> {
            calls++
            return pending.await()
          }
        }
    val model = createViewModel(repository)
    runCurrent()
    model.beginEditing()
    model.saveProfile()
    model.saveProfile()
    model.cancelEditing()
    model.updateFullName("Ignored")
    runCurrent()
    assertEquals(1, calls)
    assertEquals(alice.displayName, model.editState.value!!.fullName)
    fake.signInWithEmailResult = Result.success(AuthUser("bob", "Bob", "bob@epfl.ch"))
    fake.signInWithEmail("", "")
    runCurrent()
    pending.complete(Result.success(ProfileUpdateResult(alice)))
    runCurrent()
    assertNull(model.editState.value)
    assertEquals(ProfileUiState.Content("Bob", "bob@epfl.ch"), model.uiState.value)
  }

  @Test
  fun photoFailuresAndStaleSelectionsPreserveTheAccount() = runTest {
    val fake = FakeAuthRepository(alice)
    val bytes = byteArrayOf(1, 2, 3)
    val image = java.util.Base64.getEncoder().encodeToString(bytes)
    var fail = false
    var pending = CompletableDeferred<ByteArray?>(bytes)
    val photos =
        object : ProfilePhotoRepository {
          override suspend fun load(userId: String): ByteArray? = null

          override suspend fun save(userId: String, source: ProfilePhotoSource?): ByteArray? {
            if (fail) error("Offline")
            return pending.await()
          }
        }
    val model = ProfileViewModel(fake, photos).also { store.put("photo", it) }
    runCurrent()
    assertNull(model.photoState.value.image)
    val source = ProfilePhotoSource.Gallery(io.mockk.mockk())
    model.savePhoto(alice.uid, source)
    runCurrent()
    assertEquals(image, model.photoState.value.image)
    fail = true
    for (selection in listOf(null, source)) {
      model.savePhoto(alice.uid, selection)
      runCurrent()
      assertEquals(PhotoUiState(image, failed = true), model.photoState.value)
    }
    fail = false
    fake.signInWithEmailResult = Result.success(AuthUser("bob"))
    fake.signInWithEmail("", "")
    runCurrent()
    model.savePhoto(alice.uid, source)
    assertEquals(PhotoUiState(), model.photoState.value)
    pending = CompletableDeferred()
    model.savePhoto("bob", source)
    model.savePhoto("bob", source)
    runCurrent()
    assertTrue(model.photoState.value.busy)
    fake.signOut()
    runCurrent()
    model.savePhoto(alice.uid, source)
    pending.complete(bytes)
    runCurrent()
    assertEquals(PhotoUiState(), model.photoState.value)
    assertNull(model.photoOwner())
    model.refreshPhoto()
    assertEquals(PhotoUiState(), model.photoState.value)
  }

  @Test
  fun previousAccountsLatePhotoCannotReplaceTheCurrentAvatar() = verifyLatePhotoLoad(fails = false)

  @Test
  fun previousAccountsLateFailureCannotShowAnErrorForTheCurrentAccount() =
      verifyLatePhotoLoad(fails = true)

  private fun verifyLatePhotoLoad(fails: Boolean) = runTest {
    val auth = FakeAuthRepository(alice)
    val previousLoad = CompletableDeferred<ByteArray?>()
    val currentPhoto = byteArrayOf(4, 5, 6)
    val photos =
        object : ProfilePhotoRepository {
          override suspend fun load(userId: String): ByteArray? =
              if (userId == alice.uid) withContext(NonCancellable) { previousLoad.await() }
              else currentPhoto

          override suspend fun save(userId: String, source: ProfilePhotoSource?): ByteArray? = null
        }
    val model = ProfileViewModel(auth, photos).also { store.put("photo", it) }
    runCurrent()
    auth.signInWithEmailResult = Result.success(AuthUser("bob"))
    auth.signInWithEmail("", "")
    runCurrent()
    val confirmed = PhotoUiState(java.util.Base64.getEncoder().encodeToString(currentPhoto))
    assertEquals(confirmed, model.photoState.value)

    if (fails) previousLoad.completeExceptionally(IllegalStateException("Offline"))
    else previousLoad.complete(byteArrayOf(1, 2, 3))
    runCurrent()

    assertEquals(confirmed, model.photoState.value)
    assertEquals("bob", model.photoOwner())
  }

  private val publicAuth = FakeAuthRepository(alice)
  private var published: String? = null
  private var readName: suspend (String) -> String? = { published }
  private var writeFails = false
  private var publishName: suspend (String) -> Unit = { name ->
    check(!writeFails)
    published = name
  }
  private var reads = 0
  private val publicRepository =
      object : PublicProfileRepository {
        override suspend fun getDisplayName(ownerId: String): String? {
          reads++
          return readName(ownerId)
        }

        override suspend fun publishDisplayName(displayName: String) = publishName(displayName)

        override suspend fun removeDisplayName() {
          check(!writeFails)
          published = null
        }
      }

  private fun TestScope.openPublicEditor(): ProfileViewModel =
      ProfileViewModel(publicAuth, publicProfiles = lazy { publicRepository }).also {
        store.put("public", it)
        runCurrent()
        it.beginEditing()
        it.loadPublicName()
        runCurrent()
      }

  @Test
  fun missingPublicProfileOpensEmptyWithoutCopyingAuthName() = runTest {
    val model = openPublicEditor()
    model.loadPublicName()
    runCurrent()
    assertEquals(PublicNameEditState(loaded = true), model.editState.value?.publicName)
    assertEquals(1, reads)
  }

  @Test
  fun savingPrivateProfileDoesNotPublishPublicDraft() = runTest {
    val model = openPublicEditor()
    model.editPublicName("Public alias")
    model.saveProfile()
    runCurrent()
    assertEquals(true, model.editState.value?.saved)
    assertNull(published)
    assertEquals(alice.displayName, publicAuth.currentUser?.displayName)
  }

  @Test
  fun explicitPublicationConfirmsTypedAlias() = runTest {
    val model = openPublicEditor()
    model.editPublicName("Public alias")
    model.savePublicName("Public alias")
    runCurrent()
    assertEquals("Public alias", published)
    assertEquals(published, model.editState.value?.publicName?.confirmed)
  }

  @Test
  fun explicitUpdateReplacesPublishedAlias() = runTest {
    published = "First"
    val model = openPublicEditor()
    model.editPublicName("Second")
    model.savePublicName("Second")
    runCurrent()
    assertEquals("Second", published)
    assertEquals(alice.displayName, publicAuth.currentUser?.displayName)
  }

  @Test
  fun removalReturnsEditorToUnpublishedState() = runTest {
    published = "Old alias"
    val model = openPublicEditor()
    model.savePublicName(null)
    runCurrent()
    assertNull(published)
    assertEquals(PublicNameEditState(loaded = true), model.editState.value?.publicName)
  }

  @Test
  fun failedReadRemainsUnconfirmedUntilRetrySucceeds() = runTest {
    readName = { error("offline") }
    val model = openPublicEditor()
    assertEquals(PublicNameEditState(failed = true), model.editState.value?.publicName)
    readName = { "Recovered" }
    model.loadPublicName()
    runCurrent()
    assertEquals("Recovered", model.editState.value?.publicName?.confirmed)
  }

  @Test
  fun failedUpdatePreservesTheDraftForSuccessfulRetry() = runTest {
    published = "Old"
    val model = openPublicEditor()
    model.editPublicName("New")
    writeFails = true
    model.savePublicName("New")
    runCurrent()
    assertEquals(
        PublicNameEditState(loaded = true, confirmed = "Old", draft = "New", failed = true),
        model.editState.value?.publicName,
    )

    writeFails = false
    model.savePublicName(checkNotNull(model.editState.value?.publicName).draft)
    runCurrent()
    assertEquals("New", published)
    assertEquals(
        PublicNameEditState(loaded = true, confirmed = "New", draft = "New"),
        model.editState.value?.publicName,
    )
  }

  @Test
  fun latePublicationSuccessPreservesReopenedEditorsDraft() =
      verifyLatePublicWrite(initialName = null, rejectsWrite = false)

  @Test
  fun lateUpdateFailurePreservesReopenedEditorsDraft() =
      verifyLatePublicWrite(initialName = "Original", rejectsWrite = true)

  private fun verifyLatePublicWrite(initialName: String?, rejectsWrite: Boolean) = runTest {
    published = initialName
    val outcome = CompletableDeferred<Unit>()
    var started = false
    var settled = false
    publishName = { name ->
      withContext(NonCancellable) {
        started = true
        try {
          outcome.await()
          published = name
        } finally {
          settled = true
        }
      }
    }
    val model = openPublicEditor()
    model.editPublicName("Submitted")
    model.savePublicName("Submitted")
    runCurrent()
    assertTrue(started)

    model.cancelEditing()
    model.beginEditing()
    model.loadPublicName()
    runCurrent()
    assertEquals(2, reads)
    model.editPublicName("New draft")
    val reopened = PublicNameEditState(loaded = true, confirmed = initialName, draft = "New draft")
    assertEquals(reopened, model.editState.value?.publicName)
    assertFalse(settled)

    if (rejectsWrite) outcome.completeExceptionally(IllegalStateException("Rejected"))
    else outcome.complete(Unit)
    runCurrent()
    assertTrue(settled)
    assertEquals(if (rejectsWrite) initialName else "Submitted", published)
    assertEquals(reopened, model.editState.value?.publicName)
  }

  @Test
  fun previousAccountsLateReadFailureLeavesTheNewAccountsPublicEditorIntact() = runTest {
    val oldRead = CompletableDeferred<String?>()
    readName = { uid ->
      when (uid) {
        alice.uid -> withContext(NonCancellable) { oldRead.await() }
        "bob" -> "Bob's alias"
        else -> error("Unexpected owner: $uid")
      }
    }
    val model = openPublicEditor()
    publicAuth.signInWithEmailResult = Result.success(AuthUser("bob"))
    publicAuth.signInWithEmail("", "")
    runCurrent()
    assertNull(model.editState.value)
    model.beginEditing()
    model.loadPublicName()
    runCurrent()
    val bobEditor =
        PublicNameEditState(loaded = true, confirmed = "Bob's alias", draft = "Bob's alias")
    assertEquals(bobEditor, model.editState.value?.publicName)

    oldRead.completeExceptionally(IllegalStateException("offline"))
    runCurrent()
    assertEquals(bobEditor, model.editState.value?.publicName)
  }

  @Test
  fun publicActionsBeforeOpeningEditorDoNotStartRequests() = runTest {
    val model = ProfileViewModel(publicAuth, publicProfiles = lazyOf(publicRepository))
    store.put("guards", model)
    model.loadPublicName()
    model.editPublicName("Ignored")
    model.savePublicName("Ignored")
    runCurrent()
    model.loadPublicName()
    model.closePublicNameEditor()
    model.cancelEditing()
    assertEquals(0, reads)
    assertNull(model.editState.value)
  }

  @Test
  fun unopenedPublicSectionRejectsDraftChangesAndWrites() = runTest {
    val model = openPublicEditor()
    model.closePublicNameEditor()
    model.editPublicName("Ignored")
    model.savePublicName("Ignored")
    assertNull(model.editState.value?.publicName)
    assertNull(published)
  }

  @Test
  fun busyPublicReadRejectsDuplicateLoadsEditsAndWrites() = runTest {
    val pending = CompletableDeferred<String?>()
    readName = { pending.await() }
    val model = openPublicEditor()
    model.loadPublicName()
    model.editPublicName("Ignored")
    model.savePublicName("Ignored")
    assertEquals(1, reads)
    assertTrue(checkNotNull(model.editState.value?.publicName).busy)
    pending.complete(null)
    runCurrent()
    assertEquals("", model.editState.value?.publicName?.draft)
  }

  @Test
  fun publicEditorWithoutRepositoryDoesNotStartLoading() = runTest {
    val model = createViewModel(publicAuth)
    runCurrent()
    model.beginEditing()
    model.loadPublicName()
    assertNull(model.editState.value?.publicName)
  }

  @Test
  fun signedOutPublicActionsCannotRestoreClearedEditor() = runTest {
    val model = openPublicEditor()
    publicAuth.signOut()
    runCurrent()
    model.loadPublicName()
    model.editPublicName("Ignored")
    model.savePublicName(null)
    assertNull(model.editState.value)
  }

  @Test
  fun providerCancelledReadRemainsRetryable() = runTest {
    readName = { throw kotlinx.coroutines.CancellationException("provider cancelled") }
    val model = openPublicEditor()
    assertEquals(PublicNameEditState(), model.editState.value?.publicName)
    readName = { "Recovered" }
    model.loadPublicName()
    runCurrent()
    assertEquals("Recovered", model.editState.value?.publicName?.confirmed)
  }

  @Test
  fun pendingUpdateRejectsEditsAndDuplicateWrites() = runTest {
    val pending = CompletableDeferred<Unit>()
    publishName = { pending.await() }
    val model = openPublicEditor()
    model.savePublicName("Submitted")
    runCurrent()
    model.editPublicName("Ignored")
    model.savePublicName("Ignored")
    assertTrue(checkNotNull(model.editState.value?.publicName).busy)
    pending.complete(Unit)
    runCurrent()
    assertEquals("Submitted", model.editState.value?.publicName?.confirmed)
  }

  @Test
  fun authSnapshotChangeBeforeObserverEmissionRejectsPublicLoad() = runTest {
    var snapshot: AuthUser? = alice
    val auth =
        object : AuthRepository by publicAuth {
          override val currentUser
            get() = snapshot

          override fun observeAuthState() = kotlinx.coroutines.flow.flowOf(alice)
        }
    val model = ProfileViewModel(auth, publicProfiles = lazyOf(publicRepository))
    store.put("snapshot", model)
    runCurrent()
    model.beginEditing()
    snapshot = AuthUser("bob")
    model.loadPublicName()
    snapshot = null
    model.loadPublicName()
    assertEquals(0, reads)
  }

  @Test
  fun completedReadCannotPublishAfterAuthSnapshotSignsOut() = runTest {
    var snapshot: AuthUser? = alice
    val auth =
        object : AuthRepository by publicAuth {
          override val currentUser
            get() = snapshot

          override fun observeAuthState() = kotlinx.coroutines.flow.flowOf(alice)
        }
    val response = CompletableDeferred<String?>()
    readName = { response.await() }
    val model = ProfileViewModel(auth, publicProfiles = lazyOf(publicRepository))
    store.put("snapshot", model)
    runCurrent()
    model.beginEditing()
    model.loadPublicName()
    runCurrent()
    snapshot = null
    response.complete("Old account")
    runCurrent()
    assertNull(model.editState.value?.publicName?.confirmed)
  }

  @Test
  fun closingEditorInsideProviderCancellationDoesNotRestoreIt() = runTest {
    val model = openPublicEditor()
    model.closePublicNameEditor()
    readName = {
      model.cancelEditing()
      throw kotlinx.coroutines.CancellationException("provider")
    }
    model.loadPublicName()
    runCurrent()
    assertNull(model.editState.value)
  }

  private class ControlledAuthRepository : AuthRepository by FakeAuthRepository() {
    private val users = MutableSharedFlow<AuthUser?>(replay = 1)
    var isCollecting = false
      private set

    override val currentUser: AuthUser?
      get() = error("Profile must obtain users from observeAuthState, not a snapshot")

    override fun observeAuthState(): Flow<AuthUser?> = flow {
      isCollecting = true
      try {
        users.collect { emit(it) }
      } finally {
        isCollecting = false
      }
    }

    suspend fun emit(user: AuthUser?) {
      users.emit(user)
    }
  }
}
