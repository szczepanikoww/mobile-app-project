package com.trainingapp.android.auth

import android.app.Activity
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.GetCredentialInterruptedException
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException
import androidx.credentials.exceptions.GetCredentialUnsupportedException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.trainingapp.data.auth.GoogleIdTokenSource
import com.trainingapp.data.auth.GoogleSignInResult
import java.lang.ref.WeakReference

/**
 * Remembers the visible activity: Credential Manager shows its account picker over an activity, and the sign-in starts
 * from a view model that has none. [MainActivity][pl.trainerapp.MainActivity] registers itself.
 */
object CurrentActivity {
    private var reference: WeakReference<Activity>? = null

    fun set(activity: Activity) {
        reference = WeakReference(activity)
    }

    fun clear(activity: Activity) {
        if (reference?.get() === activity) reference = null
    }

    val value: Activity? get() = reference?.get()
}

/**
 * Google sign-in with Credential Manager's "Sign in with Google" button flow (docs/plans/firebase-auth.md §9). The
 * Google ID token is exchanged for a Firebase session by the auth repository; it is never stored or logged.
 */
class CredentialManagerGoogleIdTokens : GoogleIdTokenSource {
    override suspend fun requestIdToken(webClientId: String): GoogleSignInResult {
        val activity = CurrentActivity.value ?: return GoogleSignInResult.Unavailable
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(GetSignInWithGoogleOption.Builder(webClientId).build())
            .build()
        return try {
            val credential = CredentialManager.create(activity).getCredential(activity, request).credential
            if (credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                GoogleSignInResult.Token(GoogleIdTokenCredential.createFrom(credential.data).idToken)
            } else {
                GoogleSignInResult.Unavailable
            }
        } catch (e: GetCredentialCancellationException) {
            GoogleSignInResult.Cancelled
        } catch (e: GetCredentialInterruptedException) {
            GoogleSignInResult.Cancelled
        } catch (e: NoCredentialException) {
            GoogleSignInResult.Unavailable
        } catch (e: GetCredentialProviderConfigurationException) {
            GoogleSignInResult.Unavailable
        } catch (e: GetCredentialUnsupportedException) {
            GoogleSignInResult.Unavailable
        } catch (e: GetCredentialException) {
            // Network problems reach here as a generic error type; treat them as not sent.
            if (e.type.contains("NETWORK", ignoreCase = true)) GoogleSignInResult.Offline else GoogleSignInResult.Unavailable
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            GoogleSignInResult.Unavailable
        }
    }
}
