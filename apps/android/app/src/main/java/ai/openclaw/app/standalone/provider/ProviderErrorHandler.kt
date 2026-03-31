package ai.openclaw.app.standalone.provider

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.math.min

/**
 * Classifies provider errors into actionable categories.
 */
enum class ProviderErrorKind {
  RATE_LIMIT,
  AUTH_ERROR,
  SERVER_ERROR,
  NETWORK,
  UNKNOWN,
}

/** Parse an HTTP status code (or error string) into a [ProviderErrorKind]. */
fun classifyError(code: String?, message: String): ProviderErrorKind {
  val statusCode = code?.toIntOrNull()
  return when {
    statusCode == 401 || statusCode == 403 -> ProviderErrorKind.AUTH_ERROR
    statusCode == 429 -> ProviderErrorKind.RATE_LIMIT
    statusCode != null && statusCode in 500..599 -> ProviderErrorKind.SERVER_ERROR
    message.contains("timeout", ignoreCase = true) -> ProviderErrorKind.NETWORK
    message.contains("connect", ignoreCase = true) -> ProviderErrorKind.NETWORK
    message.contains("network", ignoreCase = true) -> ProviderErrorKind.NETWORK
    message.contains("reset", ignoreCase = true) -> ProviderErrorKind.NETWORK
    else -> ProviderErrorKind.UNKNOWN
  }
}

/** Whether the error kind is worth retrying. */
fun ProviderErrorKind.isRetryable(): Boolean = when (this) {
  ProviderErrorKind.RATE_LIMIT,
  ProviderErrorKind.SERVER_ERROR,
  ProviderErrorKind.NETWORK,
  -> true
  ProviderErrorKind.AUTH_ERROR,
  ProviderErrorKind.UNKNOWN,
  -> false
}

/** User-friendly message for an error kind. */
fun ProviderErrorKind.userMessage(detail: String? = null): String = when (this) {
  ProviderErrorKind.RATE_LIMIT -> "Rate limited by the provider. Please wait a moment."
  ProviderErrorKind.AUTH_ERROR -> "Authentication failed. Please check your API key."
  ProviderErrorKind.SERVER_ERROR -> "The provider is experiencing issues. Retrying…"
  ProviderErrorKind.NETWORK -> "Network error. Check your connection."
  ProviderErrorKind.UNKNOWN -> detail ?: "An unexpected error occurred."
}

/**
 * Wraps a [Flow]<[ChatEvent]> with exponential-backoff retry for transient errors.
 *
 * Non-retryable errors (auth, unknown) are emitted immediately.
 * After [maxRetries] retries, the last error is emitted with a user-friendly message.
 */
fun Flow<ChatEvent>.withRetry(
  maxRetries: Int = 3,
): Flow<ChatEvent> = flow {
  var attempt = 0
  var lastError: ChatEvent.Error? = null

  while (attempt <= maxRetries) {
    var sawError = false

    this@withRetry.collect { event ->
      when {
        event is ChatEvent.Error -> {
          val kind = classifyError(event.code, event.message)
          if (!kind.isRetryable() || attempt >= maxRetries) {
            // Emit final user-friendly error.
            emit(ChatEvent.Error(kind.userMessage(event.message), event.code))
            sawError = true
            return@collect
          }
          lastError = event
          sawError = true
        }
        else -> emit(event)
      }
    }

    if (!sawError) return@flow // success path

    val error = lastError ?: return@flow
    val kind = classifyError(error.code, error.message)
    if (!kind.isRetryable()) return@flow

    attempt++
    if (attempt > maxRetries) {
      emit(ChatEvent.Error(kind.userMessage(error.message), error.code))
      return@flow
    }

    // Exponential backoff: 1s, 2s, 4s (capped at 10s).
    val backoffMs = min(1000L * (1L shl (attempt - 1)), 10_000L)
    delay(backoffMs)
  }
}
