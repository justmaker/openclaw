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

/** Google Gemini API provider. */
class GoogleProvider(
  private val apiKey: String,
  private val client: OkHttpClient = OkHttpClient(),
) : LlmProvider {

  override val id = "google"
  override val displayName = "Google Gemini"
  override val supportedModels = listOf(
    ModelInfo("gemini-2.5-pro", "Gemini 2.5 Pro", 8192),
    ModelInfo("gemini-2.5-flash", "Gemini 2.5 Flash", 8192),
  )

  private val json = Json { ignoreUnknownKeys = true }

  override fun chat(
    messages: List<Message>,
    tools: List<ToolDefinition>?,
    model: String,
    stream: Boolean,
  ): Flow<ChatEvent> = flow {
    val systemPrompt = messages.firstOrNull { it.role == Role.SYSTEM }?.content
    val contents = buildContents(messages.filter { it.role != Role.SYSTEM })

    val body = buildJsonObject {
      put("contents", contents)
      if (systemPrompt != null) {
        put("systemInstruction", buildJsonObject {
          put("parts", buildJsonArray {
            add(buildJsonObject { put("text", systemPrompt) })
          })
        })
      }
      if (!tools.isNullOrEmpty()) {
        put("tools", buildJsonArray {
          add(buildJsonObject {
            put("functionDeclarations", buildJsonArray {
              for (tool in tools) {
                add(buildJsonObject {
                  put("name", tool.name)
                  put("description", tool.description)
                  put("parameters", tool.parameters)
                })
              }
            })
          })
        })
      }
      put("generationConfig", buildJsonObject {
        put("maxOutputTokens", MAX_OUTPUT_TOKENS)
      })
    }

    val action = if (stream) "streamGenerateContent" else "generateContent"
    val url = "$BASE_URL/models/$model:$action?key=$apiKey" +
      if (stream) "&alt=sse" else ""

    val request = Request.Builder()
      .url(url)
      .addHeader("Content-Type", JSON_TYPE)
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

    var lastUsage: Usage? = null

    response.parseSseEvents().collect { event ->
      val data = try {
        json.parseToJsonElement(event.data).jsonObject
      } catch (_: Exception) {
        return@collect
      }

      val candidates = data["candidates"]?.jsonArray ?: return@collect
      val candidate = candidates.firstOrNull()?.jsonObject ?: return@collect
      val content = candidate["content"]?.jsonObject
      val parts = content?.get("parts")?.jsonArray

      if (parts != null) {
        for (part in parts) {
          val partObj = part.jsonObject

          // Text part
          val text = partObj["text"]?.jsonPrimitive?.contentOrNull
          if (text != null) {
            emit(ChatEvent.TextDelta(text))
          }

          // Function call part
          val functionCall = partObj["functionCall"]?.jsonObject
          if (functionCall != null) {
            val name = functionCall["name"]?.jsonPrimitive?.contentOrNull ?: continue
            val args = functionCall["args"]?.jsonObject ?: buildJsonObject {}
            // Gemini doesn't provide tool call IDs; generate one.
            // Gemini has no tool call ID concept; use the function name.
            emit(ChatEvent.ToolCall(name, name, args))
          }
        }
      }

      // Usage metadata
      val usageMeta = data["usageMetadata"]?.jsonObject
      if (usageMeta != null) {
        val inTok = usageMeta["promptTokenCount"]?.jsonPrimitive?.intOrNull ?: 0
        val outTok = usageMeta["candidatesTokenCount"]?.jsonPrimitive?.intOrNull ?: 0
        lastUsage = Usage(inTok, outTok)
      }

      // Check if this candidate is finished
      val finishReason = candidate["finishReason"]?.jsonPrimitive?.contentOrNull
      if (finishReason != null && finishReason != "FINISH_REASON_UNSPECIFIED") {
        emit(ChatEvent.Done(lastUsage))
      }
    }
  }.flowOn(Dispatchers.IO)

  override suspend fun validateCredentials(): Boolean = withContext(Dispatchers.IO) {
    val model = supportedModels.first().id
    val body = buildJsonObject {
      put("contents", buildJsonArray {
        add(buildJsonObject {
          put("role", "user")
          put("parts", buildJsonArray {
            add(buildJsonObject { put("text", "hi") })
          })
        })
      })
      put("generationConfig", buildJsonObject {
        put("maxOutputTokens", 1)
      })
    }

    val url = "$BASE_URL/models/$model:generateContent?key=$apiKey"
    val request = Request.Builder()
      .url(url)
      .addHeader("Content-Type", JSON_TYPE)
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
      val candidates = root["candidates"]?.jsonArray
      val candidate = candidates?.firstOrNull()?.jsonObject
      val content = candidate?.get("content")?.jsonObject
      val parts = content?.get("parts")?.jsonArray

      if (parts != null) {
        for (part in parts) {
          val partObj = part.jsonObject
          val text = partObj["text"]?.jsonPrimitive?.contentOrNull
          if (text != null) emit(ChatEvent.TextDelta(text))

          val functionCall = partObj["functionCall"]?.jsonObject
          if (functionCall != null) {
            val name = functionCall["name"]?.jsonPrimitive?.contentOrNull ?: continue
            val args = functionCall["args"]?.jsonObject ?: buildJsonObject {}
            // Gemini has no tool call ID concept; use the function name.
            emit(ChatEvent.ToolCall(name, name, args))
          }
        }
      }

      val usageMeta = root["usageMetadata"]?.jsonObject
      val inTok = usageMeta?.get("promptTokenCount")?.jsonPrimitive?.intOrNull ?: 0
      val outTok = usageMeta?.get("candidatesTokenCount")?.jsonPrimitive?.intOrNull ?: 0
      emit(ChatEvent.Done(Usage(inTok, outTok)))
    } catch (e: Exception) {
      emit(ChatEvent.Error(e.message ?: "Parse error"))
    }
  }

  companion object {
    private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta"
    private const val JSON_TYPE = "application/json"
    private const val MAX_OUTPUT_TOKENS = 8192
    private val JSON_MEDIA_TYPE = JSON_TYPE.toMediaType()

    private fun buildContents(messages: List<Message>) = buildJsonArray {
      for (msg in messages) {
        when (msg.role) {
          Role.USER -> {
            add(buildJsonObject {
              put("role", "user")
              put("parts", buildJsonArray {
                add(buildJsonObject { put("text", msg.content ?: "") })
              })
            })
          }
          Role.ASSISTANT -> {
            add(buildJsonObject {
              put("role", "model")
              put("parts", buildJsonArray {
                val text = msg.content
                if (!text.isNullOrEmpty()) {
                  add(buildJsonObject { put("text", text) })
                }
                if (!msg.toolCalls.isNullOrEmpty()) {
                  for (tc in msg.toolCalls) {
                    add(buildJsonObject {
                      put("functionCall", buildJsonObject {
                        put("name", tc.name)
                        put("args", tc.args)
                      })
                    })
                  }
                }
              })
            })
          }
          Role.TOOL -> {
            add(buildJsonObject {
              put("role", "user")
              put("parts", buildJsonArray {
                add(buildJsonObject {
                  put("functionResponse", buildJsonObject {
                    // Best-effort: use tool call ID as function name if available.
                    put("name", msg.toolCallId ?: "unknown")
                    put("response", buildJsonObject {
                      put("result", msg.content ?: "")
                    })
                  })
                })
              })
            })
          }
          Role.SYSTEM -> {
            // System messages are handled via systemInstruction; skip here.
          }
        }
      }
    }
  }
}
