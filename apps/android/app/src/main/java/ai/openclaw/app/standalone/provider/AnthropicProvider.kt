package ai.openclaw.app.standalone.provider

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Anthropic Messages API provider (Claude models). */
class AnthropicProvider(
  private val apiKey: String,
  private val client: OkHttpClient = OkHttpClient(),
) : LlmProvider {

  override val id = "anthropic"
  override val displayName = "Anthropic"
  override val supportedModels = listOf(
    ModelInfo("claude-sonnet-4-20250514", "Claude Sonnet 4", 8192),
    ModelInfo("claude-opus-4-20250514", "Claude Opus 4", 8192),
    ModelInfo("claude-haiku-3.5", "Claude 3.5 Haiku", 8192),
  )

  private val json = Json { ignoreUnknownKeys = true }

  override fun chat(
    messages: List<Message>,
    tools: List<ToolDefinition>?,
    model: String,
    stream: Boolean,
  ): Flow<ChatEvent> = flow {
    val systemPrompt = messages.firstOrNull { it.role == Role.SYSTEM }?.content
    val apiMessages = buildApiMessages(messages.filter { it.role != Role.SYSTEM })

    val body = buildJsonObject {
      put("model", model)
      put("max_tokens", MAX_TOKENS)
      put("stream", stream)
      if (systemPrompt != null) {
        put("system", systemPrompt)
      }
      put("messages", apiMessages)
      if (!tools.isNullOrEmpty()) {
        put("tools", buildToolsArray(tools))
      }
    }

    val request = Request.Builder()
      .url(API_URL)
      .addHeader("x-api-key", apiKey)
      .addHeader("anthropic-version", API_VERSION)
      .addHeader("content-type", JSON_TYPE)
      .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
      .build()

    val response = client.newCall(request).execute()
    if (!response.isSuccessful) {
      val errorBody = response.body?.string() ?: "Unknown error"
      response.close()
      emit(ChatEvent.Error(errorBody, response.code.toString()))
      return@flow
    }

    if (!stream) {
      handleNonStreaming(response)
      return@flow
    }

    var currentToolId: String? = null
    var currentToolName: String? = null
    val toolJsonBuffer = StringBuilder()
    var inputTokens = 0
    var outputTokens = 0

    response.parseSseEvents().collect { event ->
      if (event.data == "[DONE]") return@collect
      val data = try {
        json.parseToJsonElement(event.data).jsonObject
      } catch (_: Exception) {
        return@collect
      }

      when (data["type"]?.jsonPrimitive?.contentOrNull) {
        "message_start" -> {
          val usage = data["message"]?.jsonObject?.get("usage")?.jsonObject
          inputTokens = usage?.get("input_tokens")?.jsonPrimitive?.intOrNull ?: 0
        }
        "content_block_start" -> {
          val block = data["content_block"]?.jsonObject ?: return@collect
          if (block["type"]?.jsonPrimitive?.contentOrNull == "tool_use") {
            currentToolId = block["id"]?.jsonPrimitive?.contentOrNull
            currentToolName = block["name"]?.jsonPrimitive?.contentOrNull
            toolJsonBuffer.clear()
          }
        }
        "content_block_delta" -> {
          val delta = data["delta"]?.jsonObject ?: return@collect
          when (delta["type"]?.jsonPrimitive?.contentOrNull) {
            "text_delta" -> {
              val text = delta["text"]?.jsonPrimitive?.contentOrNull ?: return@collect
              emit(ChatEvent.TextDelta(text))
            }
            "input_json_delta" -> {
              val partial = delta["partial_json"]?.jsonPrimitive?.contentOrNull
              if (partial != null) toolJsonBuffer.append(partial)
            }
          }
        }
        "content_block_stop" -> {
          val toolId = currentToolId
          val toolName = currentToolName
          if (toolId != null && toolName != null) {
            val args = try {
              json.parseToJsonElement(toolJsonBuffer.toString()).jsonObject
            } catch (_: Exception) {
              buildJsonObject {}
            }
            emit(ChatEvent.ToolCall(toolId, toolName, args))
            currentToolId = null
            currentToolName = null
            toolJsonBuffer.clear()
          }
        }
        "message_delta" -> {
          val usage = data["usage"]?.jsonObject
          outputTokens = usage?.get("output_tokens")?.jsonPrimitive?.intOrNull ?: outputTokens
        }
        "message_stop" -> {
          emit(ChatEvent.Done(Usage(inputTokens, outputTokens)))
        }
      }
    }
  }.flowOn(Dispatchers.IO)

  override suspend fun validateCredentials(): Boolean = withContext(Dispatchers.IO) {
    val body = buildJsonObject {
      put("model", "claude-haiku-3.5")
      put("max_tokens", 1)
      put("messages", buildJsonArray {
        add(buildJsonObject {
          put("role", "user")
          put("content", "hi")
        })
      })
    }

    val request = Request.Builder()
      .url(API_URL)
      .addHeader("x-api-key", apiKey)
      .addHeader("anthropic-version", API_VERSION)
      .addHeader("content-type", JSON_TYPE)
      .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
      .build()

    try {
      val response = client.newCall(request).execute()
      val ok = response.isSuccessful
      response.close()
      ok
    } catch (_: Exception) {
      false
    }
  }

  private suspend fun kotlinx.coroutines.flow.FlowCollector<ChatEvent>.handleNonStreaming(
    response: okhttp3.Response,
  ) {
    val bodyStr = response.body?.string()
    response.close()
    if (bodyStr == null) {
      emit(ChatEvent.Error("Empty response"))
      return
    }
    try {
      val root = json.parseToJsonElement(bodyStr).jsonObject
      val content = root["content"]?.jsonArray ?: run {
        emit(ChatEvent.Error("No content in response"))
        return
      }
      for (block in content) {
        val obj = block.jsonObject
        when (obj["type"]?.jsonPrimitive?.contentOrNull) {
          "text" -> {
            val text = obj["text"]?.jsonPrimitive?.contentOrNull ?: continue
            emit(ChatEvent.TextDelta(text))
          }
          "tool_use" -> {
            val toolId = obj["id"]?.jsonPrimitive?.contentOrNull ?: continue
            val toolName = obj["name"]?.jsonPrimitive?.contentOrNull ?: continue
            val args = obj["input"]?.jsonObject ?: buildJsonObject {}
            emit(ChatEvent.ToolCall(toolId, toolName, args))
          }
        }
      }
      val usage = root["usage"]?.jsonObject
      val inTok = usage?.get("input_tokens")?.jsonPrimitive?.intOrNull ?: 0
      val outTok = usage?.get("output_tokens")?.jsonPrimitive?.intOrNull ?: 0
      emit(ChatEvent.Done(Usage(inTok, outTok)))
    } catch (e: Exception) {
      emit(ChatEvent.Error(e.message ?: "Parse error"))
    }
  }

  companion object {
    private const val API_URL = "https://api.anthropic.com/v1/messages"
    private const val API_VERSION = "2023-06-01"
    private const val JSON_TYPE = "application/json"
    private const val MAX_TOKENS = 8192
    private val JSON_MEDIA_TYPE = JSON_TYPE.toMediaType()

    private fun buildToolsArray(tools: List<ToolDefinition>) = buildJsonArray {
      for (tool in tools) {
        add(buildJsonObject {
          put("name", tool.name)
          put("description", tool.description)
          put("input_schema", tool.parameters)
        })
      }
    }

    private fun buildApiMessages(messages: List<Message>) = buildJsonArray {
      for (msg in messages) {
        add(buildJsonObject {
          when (msg.role) {
            Role.TOOL -> {
              put("role", "user")
              put("content", buildJsonArray {
                add(buildJsonObject {
                  put("type", "tool_result")
                  put("tool_use_id", msg.toolCallId ?: "")
                  put("content", msg.content ?: "")
                })
              })
            }
            Role.ASSISTANT -> {
              put("role", "assistant")
              if (msg.toolCalls.isNullOrEmpty()) {
                put("content", msg.content ?: "")
              } else {
                put("content", buildJsonArray {
                  val text = msg.content
                  if (!text.isNullOrEmpty()) {
                    add(buildJsonObject {
                      put("type", "text")
                      put("text", text)
                    })
                  }
                  for (tc in msg.toolCalls) {
                    add(buildJsonObject {
                      put("type", "tool_use")
                      put("id", tc.id)
                      put("name", tc.name)
                      put("input", tc.args)
                    })
                  }
                })
              }
            }
            Role.SYSTEM, Role.USER -> {
              put("role", msg.role.value)
              put("content", msg.content ?: "")
            }
          }
        })
      }
    }
  }
}
