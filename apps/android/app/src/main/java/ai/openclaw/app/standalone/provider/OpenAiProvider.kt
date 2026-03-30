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

/**
 * OpenAI Chat Completions API provider.
 *
 * Accepts configurable [baseUrl] and [extraHeaders] so subclasses or
 * wrappers (e.g. GhcProvider) can reuse the same protocol logic.
 */
open class OpenAiProvider(
  private val apiKey: String,
  private val baseUrl: String = "https://api.openai.com/v1",
  private val client: OkHttpClient = OkHttpClient(),
  private val extraHeaders: Map<String, String> = emptyMap(),
  override val id: String = "openai",
  override val displayName: String = "OpenAI",
  override val supportedModels: List<ModelInfo> = DEFAULT_MODELS,
) : LlmProvider {

  private val json = Json { ignoreUnknownKeys = true }

  override fun chat(
    messages: List<Message>,
    tools: List<ToolDefinition>?,
    model: String,
    stream: Boolean,
  ): Flow<ChatEvent> = flow {
    val body = buildJsonObject {
      put("model", model)
      put("stream", stream)
      put("messages", buildApiMessages(messages))
      if (!tools.isNullOrEmpty()) {
        put("tools", buildToolsArray(tools))
      }
    }

    val requestBuilder = Request.Builder()
      .url("$baseUrl/chat/completions")
      .addHeader("Authorization", "Bearer $apiKey")
      .addHeader("Content-Type", JSON_TYPE)
      .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))

    for ((key, value) in extraHeaders) {
      requestBuilder.addHeader(key, value)
    }

    val response = client.newCall(requestBuilder.build()).execute()
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

    // Streaming: accumulate partial tool calls across SSE events.
    val toolCallIds = mutableMapOf<Int, String>()
    val toolCallNames = mutableMapOf<Int, StringBuilder>()
    val toolCallArgs = mutableMapOf<Int, StringBuilder>()

    response.parseSseEvents().collect { event ->
      if (event.data == "[DONE]") {
        flushToolCalls(toolCallIds, toolCallNames, toolCallArgs)
        emit(ChatEvent.Done(null))
        return@collect
      }

      val data = try {
        json.parseToJsonElement(event.data).jsonObject
      } catch (_: Exception) {
        return@collect
      }

      val choices = data["choices"]?.jsonArray ?: return@collect
      val choice = choices.firstOrNull()?.jsonObject ?: return@collect
      val delta = choice["delta"]?.jsonObject ?: return@collect

      // Text content
      val content = delta["content"]?.jsonPrimitive?.contentOrNull
      if (content != null) {
        emit(ChatEvent.TextDelta(content))
      }

      // Tool calls (streamed incrementally)
      val toolCalls = delta["tool_calls"]?.jsonArray
      if (toolCalls != null) {
        for (tc in toolCalls) {
          val tcObj = tc.jsonObject
          val idx = tcObj["index"]?.jsonPrimitive?.intOrNull ?: 0
          val tcId = tcObj["id"]?.jsonPrimitive?.contentOrNull
          if (tcId != null) toolCallIds[idx] = tcId

          val fn = tcObj["function"]?.jsonObject
          if (fn != null) {
            val fnName = fn["name"]?.jsonPrimitive?.contentOrNull
            if (fnName != null) {
              toolCallNames.getOrPut(idx) { StringBuilder() }.append(fnName)
            }
            val fnArgs = fn["arguments"]?.jsonPrimitive?.contentOrNull
            if (fnArgs != null) {
              toolCallArgs.getOrPut(idx) { StringBuilder() }.append(fnArgs)
            }
          }
        }
      }

      // Check finish_reason
      val finishReason = choice["finish_reason"]?.jsonPrimitive?.contentOrNull
      if (finishReason != null) {
        flushToolCalls(toolCallIds, toolCallNames, toolCallArgs)
        val usage = data["usage"]?.jsonObject
        val inTok = usage?.get("prompt_tokens")?.jsonPrimitive?.intOrNull ?: 0
        val outTok = usage?.get("completion_tokens")?.jsonPrimitive?.intOrNull ?: 0
        val usageObj = if (inTok > 0 || outTok > 0) Usage(inTok, outTok) else null
        emit(ChatEvent.Done(usageObj))
      }
    }
  }.flowOn(Dispatchers.IO)

  override suspend fun validateCredentials(): Boolean = withContext(Dispatchers.IO) {
    val model = supportedModels.firstOrNull()?.id ?: return@withContext false
    val body = buildJsonObject {
      put("model", model)
      put("max_tokens", 1)
      put("messages", buildJsonArray {
        add(buildJsonObject {
          put("role", "user")
          put("content", "hi")
        })
      })
    }

    val requestBuilder = Request.Builder()
      .url("$baseUrl/chat/completions")
      .addHeader("Authorization", "Bearer $apiKey")
      .addHeader("Content-Type", JSON_TYPE)
      .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))

    for ((key, value) in extraHeaders) {
      requestBuilder.addHeader(key, value)
    }

    try {
      val response = client.newCall(requestBuilder.build()).execute()
      val ok = response.isSuccessful
      response.close()
      ok
    } catch (_: Exception) {
      false
    }
  }

  private suspend fun kotlinx.coroutines.flow.FlowCollector<ChatEvent>.flushToolCalls(
    ids: MutableMap<Int, String>,
    names: MutableMap<Int, StringBuilder>,
    args: MutableMap<Int, StringBuilder>,
  ) {
    for ((idx, id) in ids) {
      val name = names[idx]?.toString() ?: continue
      val argsStr = args[idx]?.toString() ?: "{}"
      val argsObj = try {
        json.parseToJsonElement(argsStr).jsonObject
      } catch (_: Exception) {
        buildJsonObject {}
      }
      emit(ChatEvent.ToolCall(id, name, argsObj))
    }
    ids.clear()
    names.clear()
    args.clear()
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
      val choices = root["choices"]?.jsonArray
      val choice = choices?.firstOrNull()?.jsonObject
      val message = choice?.get("message")?.jsonObject

      val content = message?.get("content")?.jsonPrimitive?.contentOrNull
      if (content != null) {
        emit(ChatEvent.TextDelta(content))
      }

      val toolCalls = message?.get("tool_calls")?.jsonArray
      if (toolCalls != null) {
        for (tc in toolCalls) {
          val tcObj = tc.jsonObject
          val tcId = tcObj["id"]?.jsonPrimitive?.contentOrNull ?: continue
          val fn = tcObj["function"]?.jsonObject ?: continue
          val fnName = fn["name"]?.jsonPrimitive?.contentOrNull ?: continue
          val fnArgs = fn["arguments"]?.jsonPrimitive?.contentOrNull ?: "{}"
          val argsObj = try {
            json.parseToJsonElement(fnArgs).jsonObject
          } catch (_: Exception) {
            buildJsonObject {}
          }
          emit(ChatEvent.ToolCall(tcId, fnName, argsObj))
        }
      }

      val usage = root["usage"]?.jsonObject
      val inTok = usage?.get("prompt_tokens")?.jsonPrimitive?.intOrNull ?: 0
      val outTok = usage?.get("completion_tokens")?.jsonPrimitive?.intOrNull ?: 0
      emit(ChatEvent.Done(Usage(inTok, outTok)))
    } catch (e: Exception) {
      emit(ChatEvent.Error(e.message ?: "Parse error"))
    }
  }

  companion object {
    private const val JSON_TYPE = "application/json"
    private val JSON_MEDIA_TYPE = JSON_TYPE.toMediaType()

    internal val DEFAULT_MODELS = listOf(
      ModelInfo("gpt-4o", "GPT-4o", 16384),
      ModelInfo("gpt-4o-mini", "GPT-4o Mini", 16384),
      ModelInfo("o1-preview", "o1 Preview", 32768),
    )

    private fun buildToolsArray(tools: List<ToolDefinition>) = buildJsonArray {
      for (tool in tools) {
        add(buildJsonObject {
          put("type", "function")
          put("function", buildJsonObject {
            put("name", tool.name)
            put("description", tool.description)
            put("parameters", tool.parameters)
          })
        })
      }
    }

    private fun buildApiMessages(messages: List<Message>) = buildJsonArray {
      for (msg in messages) {
        add(buildJsonObject {
          put("role", msg.role.value)
          when (msg.role) {
            Role.TOOL -> {
              put("tool_call_id", msg.toolCallId ?: "")
              put("content", msg.content ?: "")
            }
            Role.ASSISTANT -> {
              put("content", msg.content ?: "")
              if (!msg.toolCalls.isNullOrEmpty()) {
                put("tool_calls", buildJsonArray {
                  for (tc in msg.toolCalls) {
                    add(buildJsonObject {
                      put("id", tc.id)
                      put("type", "function")
                      put("function", buildJsonObject {
                        put("name", tc.name)
                        put("arguments", tc.args.toString())
                      })
                    })
                  }
                })
              }
            }
            Role.SYSTEM, Role.USER -> {
              put("content", msg.content ?: "")
            }
          }
        })
      }
    }
  }
}
