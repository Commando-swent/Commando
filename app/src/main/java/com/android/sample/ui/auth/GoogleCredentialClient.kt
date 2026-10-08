package com.android.sample.ui.auth

// AI assistance: OpenAI Codex.
import android.annotation.SuppressLint
import android.content.Context
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.Credential
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.ClearCredentialException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import com.android.sample.model.authentication.AuthException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption

/** Activity-owned Google account picker; Firebase token exchange stays in AuthRepository. */
class GoogleCredentialClient(
    private val context: Context,
    private val manager: CredentialManager = CredentialManager.create(context),
    private val serverClientId: () -> String = { readWebClientId(context) },
) {
  suspend fun requestCredential(): Credential? {
    val clientId = serverClientId()
    if (clientId.isBlank()) throw AuthException.InvalidGoogleCredential()
    val request =
        GetCredentialRequest.Builder()
            .addCredentialOption(GetSignInWithGoogleOption.Builder(clientId).build())
            .build()
    return try {
      manager.getCredential(context, request).credential
    } catch (_: GetCredentialCancellationException) {
      null // Dismissing the account picker leaves the email form usable.
    } catch (error: GetCredentialException) {
      throw AuthException.InvalidGoogleCredential(error)
    }
  }

  suspend fun clearSession() {
    try {
      manager.clearCredentialState(ClearCredentialStateRequest())
    } catch (_: ClearCredentialException) {
      // Firebase has already signed out. Provider cleanup must not restore/block that session.
    }
  }

  private companion object {
    // Google Services generates this resource only when the Firebase config includes OAuth.
    @SuppressLint("DiscouragedApi")
    fun readWebClientId(context: Context): String {
      val id =
          context.resources.getIdentifier("default_web_client_id", "string", context.packageName)
      return if (id == 0) "" else context.getString(id)
    }
  }
}
