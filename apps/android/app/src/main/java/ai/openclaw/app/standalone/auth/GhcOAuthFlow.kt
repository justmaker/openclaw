package ai.openclaw.app.standalone.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

/** State updates emitted during the GitHub Device Flow. */
sealed class GhcOAuthState {
  /** Device code received — show user code and verification URL. */
  data class PendingUserAuth(
    val verificationUri: String,
    val userCode: String,
    val expiresInSeconds: Int,
  ) : GhcOAuthState()

  /** Polling for authorization. */
  data object Polling : GhcOAuthState()

  /** Authorization successful. */
  data class Authorized(val accessToken: String) : GhcOAuthState()

  /** Authorization failed or expired. */
  data class Failed(val message: String) : GhcOAuthState()
}

/** Intermediate state from the device code request. */
private data class DeviceCodeResponse(
  val deviceCode: String,
  val userCode: String,
  val verificationUri: String,
  val interval: Int,
  val expiresIn: Int,
)

/**
 * Implements the GitHub Device Flow for Copilot authentication.
 * Uses the same client_id as GitHub CLI for Copilot access.
 */
class GhcOAuthFlow(
  private val client: OkHttpClient = OkHttpClient(),
) {

  private val json = Json { ignoreUnknownKeys = true }

  /**
   * Start the device flow and emit state updates.
   * Caller should collect the flow and react to each state.
   */
  fun start(): Flow<GhcOAuthState> = flow {
    // Step 1: Request device code.
    val deviceCode = requestDeviceCode()
    if (deviceCode == null) {
      emit(GhcOAuthState.Failed("Failed to start GitHub authentication"))
      return@flow
    }

    emit(
      GhcOAuthState.PendingUserAuth(
        verificationUri = deviceCode.verificationUri,
        userCode = deviceCode.userCode,
        expiresInSeconds = deviceCode.expiresIn,
      ),
    )

    // Step 2: Poll for access token.
    val intervalMs = (deviceCode.interval * 1000).toLong().coerceAtLeast(5000L)
    val deadline = System.currentTimeMillis() + deviceCode.expiresIn * 1000L

    while (System.currentTimeMillis() < deadline) {
      delay(intervalMs)
      // Keep PendingUserAuth visible while polling — don't emit Polling state.

      val result = pollAccessToken(deviceCode.deviceCode)
      when {
        result == null -> {
          emit(GhcOAuthState.Failed("Failed to poll for authorization"))
          return@flow
        }
        result.token != null -> {
          emit(GhcOAuthState.Authorized(result.token))
          return@flow
        }
        result.error == "authorization_pending" -> continue
        result.error == "slow_down" -> {
          delay(5000) // extra back-off
          continue
        }
        else -> {
          emit(GhcOAuthState.Failed(result.errorDescription ?: result.error ?: "Unknown error"))
          return@flow
        }
      }
    }

    emit(GhcOAuthState.Failed("Authorization timed out"))
  }.flowOn(Dispatchers.IO)

  private fun requestDeviceCode(): DeviceCodeResponse? {
    val formBody = FormBody.Builder()
      .add("client_id", CLIENT_ID)
      .add("scope", SCOPE)
      .build()

    val request = Request.Builder()
      .url(DEVICE_CODE_URL)
      .addHeader("Accept", "application/json")
      .post(formBody)
      .build()

    return try {
      val response = client.newCall(request).execute()
      val body = response.body?.string()
      response.close()
      if (body == null) return null

      val obj = json.parseToJsonElement(body).jsonObject
      DeviceCodeResponse(
        deviceCode = obj["device_code"]?.jsonPrimitive?.contentOrNull ?: return null,
        userCode = obj["user_code"]?.jsonPrimitive?.contentOrNull ?: return null,
        verificationUri = obj["verification_uri"]?.jsonPrimitive?.contentOrNull ?: return null,
        interval = obj["interval"]?.jsonPrimitive?.intOrNull ?: 5,
        expiresIn = obj["expires_in"]?.jsonPrimitive?.intOrNull ?: 900,
      )
    } catch (_: Exception) {
      null
    }
  }

  private data class PollResult(
    val token: String?,
    val error: String?,
    val errorDescription: String?,
  )

  private fun pollAccessToken(deviceCode: String): PollResult? {
    val formBody = FormBody.Builder()
      .add("client_id", CLIENT_ID)
      .add("device_code", deviceCode)
      .add("grant_type", GRANT_TYPE)
      .build()

    val request = Request.Builder()
      .url(ACCESS_TOKEN_URL)
      .addHeader("Accept", "application/json")
      .post(formBody)
      .build()

    return try {
      val response = client.newCall(request).execute()
      val body = response.body?.string()
      response.close()
      if (body == null) return null

      val obj = json.parseToJsonElement(body).jsonObject
      PollResult(
        token = obj["access_token"]?.jsonPrimitive?.contentOrNull,
        error = obj["error"]?.jsonPrimitive?.contentOrNull,
        errorDescription = obj["error_description"]?.jsonPrimitive?.contentOrNull,
      )
    } catch (_: Exception) {
      null
    }
  }

  companion object {
    // Public client_id used by GitHub CLI for Copilot access.
    private const val CLIENT_ID = "Iv1.b507a08c87ecfe98"
    private const val SCOPE = "copilot"
    private const val DEVICE_CODE_URL = "https://github.com/login/device/code"
    private const val ACCESS_TOKEN_URL = "https://github.com/login/oauth/access_token"
    private const val GRANT_TYPE = "urn:ietf:params:oauth:grant-type:device_code"
  }
}
