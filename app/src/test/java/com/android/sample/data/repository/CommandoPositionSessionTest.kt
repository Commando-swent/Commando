package com.android.sample.data.repository

// AI assistance: OpenAI Codex.
import com.android.sample.model.CommandoPosition
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FirebaseFirestore
import io.mockk.*
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test

/** Reject invalid identities and inputs before any position write or listener registration. */
class CommandoPositionSessionTest {
  private val firestore = mockk<FirebaseFirestore>()
  private val auth = mockk<FirebaseAuth>()
  private var identity: String? = "owner"
  private var authenticated: FirebaseUser? = mockk { every { uid } returns "owner" }
  private val now = Instant.parse("2026-11-09T12:00:00Z")
  private val fix = CommandoPosition(46.52, 6.56, 30.0, now)
  private val repo =
      CommandoPositionRepositoryFirestore(
          { identity },
          firestore,
          Clock.fixed(now, ZoneOffset.UTC),
      )

  @Before
  fun setUp() {
    mockkStatic(FirebaseAuth::class)
    every { firestore.app } returns mockk()
    every { FirebaseAuth.getInstance(any()) } returns auth
    every { auth.currentUser } answers { authenticated }
  }

  @After fun tearDown() = unmockkAll()

  @Test
  fun invalidOrMismatchedIdentityCannotReadWriteOrDelete() = runTest {
    for (uid in listOf(null, "", "a/b", "different")) {
      identity = uid
      expect<SecurityException> { repo.publish("trip", fix) }
      expect<SecurityException> { repo.stopSharing() }
      expect<SecurityException> { repo.observe("owner").first() }
    }
    identity = "owner"
    authenticated = null
    expect<SecurityException> { repo.publish("trip", fix) }
    verify(exactly = 0) { firestore.collection(any()) }
  }

  @Test
  fun invalidReferencesAndExpiredFixesDoNotReachFirestore() = runTest {
    expect<IllegalArgumentException> { repo.publish("a/b", fix) }
    expect<IllegalArgumentException> {
      repo.publish("trip", fix.copy(capturedAt = now.minusSeconds(120)))
    }
    expect<IllegalArgumentException> { repo.observe("a/b").first() }
    expect<IllegalArgumentException> { repo.observe("owner", "a/b").first() }
    verify(exactly = 0) { firestore.collection(any()) }
  }

  private suspend inline fun <reified T : Throwable> expect(action: () -> Unit) {
    try {
      action()
      org.junit.Assert.fail("Expected ${T::class.simpleName}")
    } catch (error: Throwable) {
      if (error !is T) throw error
    }
  }
}
