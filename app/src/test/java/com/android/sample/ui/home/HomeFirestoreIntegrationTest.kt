package com.android.sample.ui.home

// AI assistance: OpenAI Codex.
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.android.sample.data.repository.TripFirestoreDataSource
import com.android.sample.data.repository.TripFirestoreDocument
import com.android.sample.data.repository.TripRepositoryFirestore
import com.android.sample.model.authentication.AuthUser
import com.android.sample.model.authentication.FakeAuthRepository
import com.android.sample.ui.auth.GoogleCredentialClient
import com.android.sample.ui.navigation.AppTestTags
import com.android.sample.ui.navigation.CommandoApp
import com.android.sample.ui.theme.SampleAppTheme
import com.google.android.gms.tasks.Tasks
import com.google.firebase.Timestamp
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.QuerySnapshot
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercises the Firestore repository and Home together without making network requests. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-mdpi")
class HomeFirestoreIntegrationTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
  private val googleCredentials = object : GoogleCredentialClient {
    override suspend fun request(context: Context) = null
    override suspend fun clearSession() {}
  }
  private val now = Instant.now()
  private val auth = FakeAuthRepository(AuthUser("alice"))
  private val source = HomeDataSource()

  private fun document(owner: String, status: String = "PUBLISHED") =
      TripFirestoreDocument(
          id = "$owner-trip",
          data =
              mapOf(
                  "ownerId" to owner,
                  "store" to
                      mapOf("name" to "$owner shop", "latitude" to 46.52, "longitude" to 6.63),
                  "handoffLocation" to
                      mapOf("name" to "EPFL", "latitude" to 46.52, "longitude" to 6.57),
                  "scheduledAt" to Timestamp(now.epochSecond + 3600, 0),
                  "status" to status,
                  "createdAt" to Timestamp(now.epochSecond, 0),
                  "updatedAt" to Timestamp(now.epochSecond, 0),
              ),
      )

  private fun show() {
    val trips = TripRepositoryFirestore(source, auth, { now })
    compose.setContent { SampleAppTheme { CommandoApp(auth, trips, googleCredentials) } }
    compose.onNodeWithTag(AppTestTags.COMMANDO_MODE).performClick()
  }

  @Test
  fun defaultAppRepositoryQueriesFirestoreForTheSignedInAccount() {
    val firestore = mockk<FirebaseFirestore>()
    val collection = mockk<CollectionReference>()
    val query = mockk<Query>()
    val snapshot = mockk<QuerySnapshot>()
    val storedTrip = mockk<DocumentSnapshot>()
    val data = document("alice")
    every { firestore.collection("trips") } returns collection
    every { collection.whereEqualTo("ownerId", "alice") } returns query
    every { query.get() } returns Tasks.forResult(snapshot)
    every { snapshot.documents } returns listOf(storedTrip)
    every { storedTrip.id } returns data.id
    every { storedTrip.data } returns data.data
    mockkStatic(FirebaseFirestore::class)
    try {
      every { FirebaseFirestore.getInstance() } returns firestore
      // Leave tripRepository unset to exercise the production wiring, not an injected adapter.
      compose.setContent { SampleAppTheme { CommandoApp(repository = auth) } }
      compose.onNodeWithTag(AppTestTags.COMMANDO_MODE).performClick()
      compose.onNodeWithText("alice shop").assertIsDisplayed()
      compose.onNodeWithText("EPFL").assertIsDisplayed()
      compose.runOnIdle { verify(exactly = 1) { collection.whereEqualTo("ownerId", "alice") } }
    } finally {
      unmockkStatic(FirebaseFirestore::class)
    }
  }

  @Test
  fun firestoreDocumentsDisplayTheSignedInUsersCurrentTrip() {
    source.documents = listOf(document("alice"), document("bob"))
    show()
    compose.onNodeWithText("alice shop").assertIsDisplayed()
    compose.onNodeWithText("bob shop").assertDoesNotExist()
    compose.onNodeWithText("Open").assertIsDisplayed()
    compose.runOnIdle { assertEquals(listOf("alice"), source.owners) }
  }

  @Test
  fun firestoreFailureCanRetryAndThenShowAnOngoingTrip() {
    source.fail = true
    source.documents = listOf(document("alice", "IN_PROGRESS"))
    show()
    compose.onNodeWithTag(HomeTestTags.RETRY).assertIsDisplayed()
    compose.runOnIdle { source.fail = false }
    compose.onNodeWithTag(HomeTestTags.RETRY).performClick()
    compose.onNodeWithText("alice shop").assertIsDisplayed()
    compose.onNodeWithText("Shopping now").assertIsDisplayed()
  }

  @Test
  fun switchingAccountWhileLoadingDoesNotExposeThePreviousUsersTrip() = runTest {
    val aliceResponse = CompletableDeferred<List<TripFirestoreDocument>>()
    source.pendingAlice = aliceResponse
    show()
    compose.onNodeWithTag(HomeTestTags.LOADING).assertIsDisplayed()
    auth.signInWithEmailResult = Result.success(AuthUser("bob"))
    auth.signInWithEmail("", "")
    compose.onNodeWithTag(AppTestTags.COMMANDO_MODE).performClick()
    compose.onNodeWithTag(HomeTestTags.EMPTY_STATE_TITLE).assertIsDisplayed()
    compose.runOnIdle { aliceResponse.complete(listOf(document("alice"))) }
    compose.onNodeWithTag(HomeTestTags.CURRENT_TRIP).assertDoesNotExist()
    compose.onNodeWithText("alice shop").assertDoesNotExist()
    compose.runOnIdle { assertEquals(listOf("alice", "bob"), source.owners) }
  }

  private class HomeDataSource : TripFirestoreDataSource {
    var documents = emptyList<TripFirestoreDocument>()
    var fail = false
    var pendingAlice: CompletableDeferred<List<TripFirestoreDocument>>? = null
    val owners = mutableListOf<String>()

    override suspend fun getTripsByOwner(ownerId: String): List<TripFirestoreDocument> {
      owners += ownerId
      if (fail) throw IOException("Offline")
      if (ownerId == "alice")
          pendingAlice?.let {
            return it.await()
          }
      return documents.filter { it.data["ownerId"] == ownerId }
    }

    override suspend fun createTrip(data: Map<String, Any?>): String = error("Not used by Home")

    override suspend fun getTrip(tripId: String): TripFirestoreDocument? = error("Not used by Home")

    override suspend fun getUpcomingTrips(
        status: String,
        scheduledAfter: Instant,
    ): List<TripFirestoreDocument> = error("Not used by Home")
  }
}
