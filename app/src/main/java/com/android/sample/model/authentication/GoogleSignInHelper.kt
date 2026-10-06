package com.android.sample.model.authentication

import android.os.Bundle
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.AuthCredential
import com.google.firebase.auth.GoogleAuthProvider

/**
 * Extracts Google ID token credentials and converts them to Firebase credentials.
 *
 * Isolates the static Google SDK calls so that repositories can be unit tested with a fake helper.
 * Implementations must never log ID tokens.
 */
interface GoogleSignInHelper {

  /**
   * Extracts a [GoogleIdTokenCredential] from the given credential data.
   *
   * @param bundle Credential data from the Google sign-in response.
   * @return A [GoogleIdTokenCredential] containing the user's ID token.
   * @throws com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException if the
   *   bundle does not contain a valid Google ID token.
   */
  fun extractIdTokenCredential(bundle: Bundle): GoogleIdTokenCredential

  /**
   * Creates a Firebase [AuthCredential] from a Google ID token.
   *
   * @param idToken The ID token returned by Google sign-in.
   * @return An [AuthCredential] used to sign in with FirebaseAuth.
   */
  fun toFirebaseCredential(idToken: String): AuthCredential
}

/** Implementation of [GoogleSignInHelper] that directly calls the Google SDK. */
class DefaultGoogleSignInHelper : GoogleSignInHelper {
  override fun extractIdTokenCredential(bundle: Bundle): GoogleIdTokenCredential =
      GoogleIdTokenCredential.createFrom(bundle)

  override fun toFirebaseCredential(idToken: String): AuthCredential =
      GoogleAuthProvider.getCredential(idToken, null)
}
