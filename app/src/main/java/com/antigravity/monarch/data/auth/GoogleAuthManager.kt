package com.antigravity.monarch.data.auth

import android.content.Context
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import com.antigravity.monarch.BuildConfig
import com.antigravity.monarch.domain.repository.SettingsRepository
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import javax.inject.Inject
import javax.inject.Singleton

sealed interface SignInResult {
    data class Success(val email: String, val displayName: String?) : SignInResult
    data object Cancelled : SignInResult
    data object NotConfigured : SignInResult
    data class Error(val message: String) : SignInResult
}

/**
 * Google Sign-In through Credential Manager. Requires `GOOGLE_WEB_CLIENT_ID` (an OAuth "Web
 * application" client from Google Cloud, with the app's SHA-1 registered on an Android client)
 * in local.properties. Without it the app runs in offline guest mode, which is fully playable.
 */
@Singleton
class GoogleAuthManager @Inject constructor(
    private val settings: SettingsRepository,
) {
    val isConfigured: Boolean get() = BuildConfig.GOOGLE_WEB_CLIENT_ID.isNotBlank()

    /** [activityContext] must be an Activity so the account picker can be shown. */
    suspend fun signIn(activityContext: Context): SignInResult {
        if (!isConfigured) return SignInResult.NotConfigured
        val option = GetGoogleIdOption.Builder()
            .setServerClientId(BuildConfig.GOOGLE_WEB_CLIENT_ID)
            .setFilterByAuthorizedAccounts(false)
            .setAutoSelectEnabled(true)
            .build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        return try {
            val credential = CredentialManager.create(activityContext).getCredential(activityContext, request).credential
            if (credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                val google = GoogleIdTokenCredential.createFrom(credential.data)
                settings.update { it.copy(signedInEmail = google.id, signedInName = google.displayName) }
                SignInResult.Success(google.id, google.displayName)
            } else {
                SignInResult.Error("Unexpected credential type")
            }
        } catch (e: GetCredentialCancellationException) {
            SignInResult.Cancelled
        } catch (e: GetCredentialException) {
            SignInResult.Error(e.message ?: "Sign-in failed")
        }
    }

    suspend fun signOut(context: Context) {
        runCatching { CredentialManager.create(context).clearCredentialState(ClearCredentialStateRequest()) }
        settings.update { it.copy(signedInEmail = null, signedInName = null) }
    }
}
