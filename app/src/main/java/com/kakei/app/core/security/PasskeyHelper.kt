package com.kakei.app.core.security

import android.content.Context
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PublicKeyCredential
import androidx.credentials.exceptions.CreateCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** The user dismissed the system passkey sheet; not an error worth reporting. */
class PasskeyCancelledException(cause: Throwable? = null) : Exception("Passkey ceremony cancelled", cause)

/** No passkey for this app's relying party is available on the device. */
class PasskeyNotFoundException(cause: Throwable? = null) : Exception("No passkey available", cause)

/**
 * Bridges the Kakei server's WebAuthn ceremonies (webauthn-rs) and the Android Credential Manager.
 *
 * The server returns `{"publicKey": {...}, "mediation": ...}`; Credential Manager expects only the
 * inner `publicKey` options as JSON. The returned credential JSON follows the WebAuthn Level 3
 * `toJSON()` format, which the server deserializes directly.
 *
 * [context] must be an Activity context: Credential Manager shows its sheet on top of it.
 */
object PasskeyHelper {

    private val json = Json { ignoreUnknownKeys = true }

    /** Runs the registration ceremony and returns the attestation JSON for `register-finish`. */
    suspend fun createPasskey(context: Context, challenge: JsonElement): JsonElement {
        val request = CreatePublicKeyCredentialRequest(requestJson = extractPublicKeyOptions(challenge))
        val response = try {
            CredentialManager.create(context).createCredential(context, request)
        } catch (e: CreateCredentialCancellationException) {
            throw PasskeyCancelledException(e)
        }
        val registrationJson = (response as? CreatePublicKeyCredentialResponse)?.registrationResponseJson
            ?: throw IllegalStateException("Unexpected credential response: ${response.type}")
        return json.parseToJsonElement(registrationJson)
    }

    /** Runs the authentication ceremony and returns the assertion JSON for `passkey-login-finish`. */
    suspend fun getAssertion(context: Context, challenge: JsonElement): JsonElement {
        val request = GetCredentialRequest(
            listOf(GetPublicKeyCredentialOption(requestJson = extractPublicKeyOptions(challenge)))
        )
        val result = try {
            CredentialManager.create(context).getCredential(context, request)
        } catch (e: GetCredentialCancellationException) {
            throw PasskeyCancelledException(e)
        } catch (e: NoCredentialException) {
            throw PasskeyNotFoundException(e)
        }
        val credential = result.credential as? PublicKeyCredential
            ?: throw IllegalStateException("Unexpected credential type: ${result.credential.type}")
        return json.parseToJsonElement(credential.authenticationResponseJson)
    }

    /** Unwraps the `publicKey` options from a webauthn-rs challenge (or passes bare options through). */
    fun extractPublicKeyOptions(challenge: JsonElement): String {
        val obj = challenge.jsonObject
        val options = obj["publicKey"] as? JsonObject ?: obj
        return options.toString()
    }
}
