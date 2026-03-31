package ai.openclaw.app.standalone.provider

import okhttp3.OkHttpClient

/**
 * OpenRouter provider — uses OpenAI-compatible protocol with
 * OpenRouter-specific headers and endpoint.
 */
class OpenRouterProvider(
  apiKey: String,
  client: OkHttpClient = OkHttpClient(),
) : OpenAiProvider(
  apiKey = apiKey,
  baseUrl = "https://openrouter.ai/api/v1",
  client = client,
  extraHeaders = mapOf(
    "HTTP-Referer" to "https://openclaw.ai",
    "X-Title" to "OpenClaw Android",
  ),
  id = "openrouter",
  displayName = "OpenRouter",
  supportedModels = listOf(
    ModelInfo("anthropic/claude-sonnet-4", "Claude Sonnet 4", 8192),
    ModelInfo("openai/gpt-4o", "GPT-4o", 16384),
    ModelInfo("google/gemini-2.5-pro", "Gemini 2.5 Pro", 8192),
  ),
)
