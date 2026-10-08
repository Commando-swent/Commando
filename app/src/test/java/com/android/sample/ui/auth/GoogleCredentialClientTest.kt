package com.android.sample.ui.auth

// AI assistance: OpenAI Codex.
import android.content.Context
import android.content.res.Resources
import android.os.Bundle
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
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
  private val resources = mockk<Resources>()
  private val manager = mockk<CredentialManager>()
  private val credential = CustomCredential("google", Bundle())
  private val client = AndroidGoogleCredentialClient(context, manager)

  private fun configure(
      resourceId: Int = 123,
      clientId: String = "web.apps.googleusercontent.com",
  ) {
    every { context.resources } returns resources
    every { context.packageName } returns "com.android.sample"
    every {
      resources.getIdentifier("default_web_client_id", "string", "com.android.sample")
    } returns resourceId
    every { context.getString(resourceId) } returns clientId
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
  fun missingOrBlankConfigurationDoesNotOpenPicker() = runTest {
    for (id in listOf(0, 123)) {
      configure(id, "")
      try {
        client.request(context)
        fail("Missing configuration must fail")
      } catch (_: GoogleSignInNotConfiguredException) {
        coVerify(exactly = 0) { manager.getCredential(any<Context>(), any<GetCredentialRequest>()) }
      }
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
