package ai.openclaw.app.standalone.provider

import okhttp3.OkHttpClient

/**
 * GitHub Copilot provider — reuses OpenAI-compatible protocol
 * with Copilot-specific endpoint and auth headers.
 */
class GhcProvider(
  token: String,
  client: OkHttpClient = OkHttpClient(),
) : OpenAiProvider(
  apiKey = token,
  baseUrl = "https://api.githubcopilot.com",
  client = client,
  extraHeaders = mapOf(
    "Editor-Version" to "openclaw/1.0.0",
    "Copilot-Integration-Id" to "openclaw-android",
  ),
  id = "ghc",
  displayName = "GitHub Copilot",
  supportedModels = listOf(
    ModelInfo("gpt-4o", "GPT-4o", 16384),
    ModelInfo("claude-sonnet-4-20250514", "Claude Sonnet 4", 8192),
  ),
)
