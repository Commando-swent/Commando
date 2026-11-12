package com.android.sample.ui.auth

// AI assistance: OpenAI Codex.
import android.content.Context
import android.os.Bundle
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialUnknownException
import androidx.credentials.exceptions.NoCredentialException
import com.android.sample.R
import com.android.sample.model.authentication.AuthException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GoogleCredentialClientTest {
  private val context = mockk<Context>()
  private val manager = mockk<CredentialManager>()
  private val credential = CustomCredential("google", Bundle())
  private val client = AndroidGoogleCredentialClient(context, manager)

  private fun configure(clientId: String = "web.apps.googleusercontent.com") {
    every { context.getString(R.string.default_web_client_id) } returns clientId
  }

  @Test
  fun buttonRequestsGoogleCredentialWithWebClientAndForegroundContext() = runTest {
    configure()
    val foreground = mockk<Context>()
    val request = slot<GetCredentialRequest>()
    coEvery { manager.getCredential(foreground, capture(request)) } returns
        GetCredentialResponse(credential)
    assertSame(credential, client.request(foreground))
    val option = request.captured.credentialOptions.single() as GetSignInWithGoogleOption
    assertEquals("web.apps.googleusercontent.com", option.serverClientId)
  }

  @Test
  fun blankConfigurationDoesNotOpenPicker() = runTest {
    configure("")
    try {
      client.request(context)
      fail("Blank configuration must fail")
    } catch (_: GoogleSignInNotConfiguredException) {
      coVerify(exactly = 0) { manager.getCredential(any<Context>(), any<GetCredentialRequest>()) }
    }
  }

  @Test
  fun dismissingSystemPickerReturnsNull() = runTest {
    configure()
    coEvery { manager.getCredential(context, any<GetCredentialRequest>()) } throws
        GetCredentialCancellationException()
    assertNull(client.request(context))
  }

  @Test
  fun noAccountIsReportedAsSafeGoogleAuthenticationError() = runTest {
    configure()
    val error = NoCredentialException()
    coEvery { manager.getCredential(context, any<GetCredentialRequest>()) } throws error
    try {
      client.request(context)
      fail("Provider error must be reported")
    } catch (failure: AuthException.InvalidGoogleCredential) {
      assertSame(error, failure.cause)
    }
  }

  @Test
  fun otherProviderErrorsRetainTheirCause() = runTest {
    configure()
    val error = GetCredentialUnknownException()
    coEvery { manager.getCredential(context, any<GetCredentialRequest>()) } throws error
    try {
      client.request(context)
      fail("Provider error must be reported")
    } catch (failure: AuthException.InvalidGoogleCredential) {
      assertSame(error, failure.cause)
    }
  }

  @Test
  fun coroutineCancellationIsPreserved() = runTest {
    configure()
    val cancelled = CancellationException()
    coEvery { manager.getCredential(context, any<GetCredentialRequest>()) } throws cancelled
    try {
      client.request(context)
      fail("Coroutine cancellation must propagate")
    } catch (error: CancellationException) {
      assertSame(cancelled, error)
    }
  }

  @Test
  fun signOutNotifiesTheCredentialProvider() = runTest {
    coEvery { manager.clearCredentialState(any<ClearCredentialStateRequest>()) } returns Unit
    client.clearSession()
    coVerify(exactly = 1) { manager.clearCredentialState(any<ClearCredentialStateRequest>()) }
  }
}
