@file:Suppress("DEPRECATION")

package ai.openclaw.app.standalone.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Credential types for LLM providers. */
@Serializable
sealed class Credential {
  @Serializable
  data class ApiKey(val key: String) : Credential()

  @Serializable
  data class OAuthToken(
    val accessToken: String,
    val refreshToken: String?,
    val expiresAtMs: Long,
  ) : Credential()
}

/**
 * Manages provider credentials using EncryptedSharedPreferences,
 * following the same pattern as SecurePrefs.
 */
class AuthManager(context: Context) {

  private val appContext = context.applicationContext
  private val json = Json { ignoreUnknownKeys = true }

  private val masterKey by lazy {
    MasterKey.Builder(appContext)
      .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
      .build()
  }

  private val securePrefs: SharedPreferences by lazy {
    EncryptedSharedPreferences.create(
      appContext,
      PREFS_NAME,
      masterKey,
      EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
      EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )
  }

  fun saveCredential(providerId: String, credential: Credential) {
    val serialized = json.encodeToString(Credential.serializer(), credential)
    securePrefs.edit { putString(credentialKey(providerId), serialized) }
    // Track configured providers.
    val providers = loadProviderSet().toMutableSet()
    providers.add(providerId)
    securePrefs.edit { putString(PROVIDERS_KEY, providers.joinToString(",")) }
  }

  fun getCredential(providerId: String): Credential? {
    val raw = securePrefs.getString(credentialKey(providerId), null) ?: return null
    return try {
      json.decodeFromString(Credential.serializer(), raw)
    } catch (_: Throwable) {
      null
    }
  }

  fun deleteCredential(providerId: String) {
    securePrefs.edit { remove(credentialKey(providerId)) }
    val providers = loadProviderSet().toMutableSet()
    providers.remove(providerId)
    securePrefs.edit { putString(PROVIDERS_KEY, providers.joinToString(",")) }
  }

  fun listConfiguredProviders(): List<String> = loadProviderSet()

  /** Quick helper: get an API key string or null. */
  fun getApiKey(providerId: String): String? {
    return (getCredential(providerId) as? Credential.ApiKey)?.key
  }

  private fun loadProviderSet(): List<String> {
    val raw = securePrefs.getString(PROVIDERS_KEY, null) ?: return emptyList()
    return raw.split(",").filter { it.isNotBlank() }
  }

  private fun credentialKey(providerId: String) = "standalone.credential.$providerId"

  companion object {
    private const val PREFS_NAME = "openclaw.standalone.auth"
    private const val PROVIDERS_KEY = "standalone.configured_providers"
  }
}
