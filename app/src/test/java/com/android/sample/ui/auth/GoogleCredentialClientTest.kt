package com.android.sample.ui.auth

// AI assistance: OpenAI Codex.
import android.content.Context
import android.os.Bundle
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.exceptions.ClearCredentialUnknownException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialUnknownException
import com.android.sample.model.authentication.AuthException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import io.mockk.*
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
  private val client =
      GoogleCredentialClient(context, manager) { "client.apps.googleusercontent.com" }

  @Test
  fun buttonRequestsGoogleCredentialWithConfiguredClient() = runTest {
    val credential = CustomCredential("test-google", Bundle())
    coEvery { manager.getCredential(context, any<GetCredentialRequest>()) } returns
        GetCredentialResponse(credential)
    assertSame(credential, client.requestCredential())
    coVerify {
      manager.getCredential(
          context,
          match<GetCredentialRequest> {
            val option = it.credentialOptions.single() as GetSignInWithGoogleOption
            option.serverClientId == "client.apps.googleusercontent.com"
          },
      )
    }
  }

  @Test
  fun userCancellationReturnsNoCredential() = runTest {
    coEvery { manager.getCredential(context, any<GetCredentialRequest>()) } throws
        GetCredentialCancellationException()
    assertNull(client.requestCredential())
  }

  @Test
  fun providerFailureIsReportedAsGoogleAuthError() = runTest {
    coEvery { manager.getCredential(context, any<GetCredentialRequest>()) } throws
        GetCredentialUnknownException()
    try {
      client.requestCredential()
      fail("Expected a Google authentication error")
    } catch (error: AuthException.InvalidGoogleCredential) {
      assertTrue(error.cause is GetCredentialUnknownException)
    }
  }

  @Test
  fun missingConfigurationFailsWithoutOpeningPicker() = runTest {
    val unconfigured = GoogleCredentialClient(context, manager) { "" }
    try {
      unconfigured.requestCredential()
      fail("Expected a configuration error")
    } catch (_: AuthException.InvalidGoogleCredential) {
      coVerify(exactly = 0) { manager.getCredential(any<Context>(), any<GetCredentialRequest>()) }
    }
  }

  @Test
  fun coroutineCancellationIsNotConvertedToAnAuthError() = runTest {
    coEvery { manager.getCredential(context, any<GetCredentialRequest>()) } throws
        CancellationException()
    try {
      client.requestCredential()
      fail("Expected cancellation")
    } catch (_: CancellationException) {}
  }

  @Test
  fun signOutClearsCredentialStateAndToleratesProviderFailure() = runTest {
    coEvery { manager.clearCredentialState(any()) } throws ClearCredentialUnknownException()
    client.clearSession()
    coVerify(exactly = 1) { manager.clearCredentialState(any()) }
  }
}
