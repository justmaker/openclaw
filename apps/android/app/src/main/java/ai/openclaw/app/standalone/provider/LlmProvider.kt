package ai.openclaw.app.standalone.provider

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject

/** Unified LLM provider interface for multi-provider support. */
interface LlmProvider {
  val id: String
  val displayName: String
  val supportedModels: List<ModelInfo>

  fun chat(
    messages: List<Message>,
    tools: List<ToolDefinition>?,
    model: String,
    stream: Boolean = true,
  ): Flow<ChatEvent>

  suspend fun validateCredentials(): Boolean
}

sealed class ChatEvent {
  data class TextDelta(val text: String) : ChatEvent()

  data class ToolCall(
    val id: String,
    val name: String,
    val args: JsonObject,
  ) : ChatEvent()

  data class Done(val usage: Usage?) : ChatEvent()

  data class Error(
    val message: String,
    val code: String? = null,
  ) : ChatEvent()
}

enum class Role(val value: String) {
  SYSTEM("system"),
  USER("user"),
  ASSISTANT("assistant"),
  TOOL("tool"),
}

data class Message(
  val role: Role,
  val content: String?,
  val toolCalls: List<ToolCallResult>? = null,
  val toolCallId: String? = null,
)

data class ModelInfo(
  val id: String,
  val displayName: String,
  val maxTokens: Int,
)

data class Usage(
  val inputTokens: Int,
  val outputTokens: Int,
)

data class ToolDefinition(
  val name: String,
  val description: String,
  val parameters: JsonObject,
)

data class ToolCallResult(
  val id: String,
  val name: String,
  val args: JsonObject,
)
