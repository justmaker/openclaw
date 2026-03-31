package ai.openclaw.app.standalone.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request

/** Fetches a web page and returns readable text content. */
class WebFetchTool(
  private val client: OkHttpClient = OkHttpClient(),
) : Tool {

  override val name = "web_fetch"
  override val description = "Fetch a web page and return its text content (HTML tags stripped)."
  override val parameters: JsonObject = buildJsonObject {
    put("type", "object")
    put("properties", buildJsonObject {
      put("url", buildJsonObject {
        put("type", "string")
        put("description", "The URL to fetch")
      })
      put("max_chars", buildJsonObject {
        put("type", "integer")
        put("description", "Maximum characters to return (default 10000)")
      })
    })
    put("required", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("url")) })
  }

  override suspend fun execute(args: JsonObject): ToolResult {
    val url = args["url"]?.jsonPrimitive?.contentOrNull
      ?: return ToolResult.error("Missing required parameter: url")
    val maxChars = args["max_chars"]?.jsonPrimitive?.intOrNull ?: DEFAULT_MAX_CHARS

    return withContext(Dispatchers.IO) {
      try {
        val request = Request.Builder()
          .url(url)
          .addHeader("User-Agent", USER_AGENT)
          .get()
          .build()

        val response = client.newCall(request).execute()
        val body = response.body?.string()
        response.close()

        if (body == null) return@withContext ToolResult.error("Empty response from $url")

        val text = extractReadableText(body)
        val truncated = if (text.length > maxChars) text.take(maxChars) + "\n[truncated]" else text

        if (truncated.isBlank()) {
          ToolResult.success("No readable text content found at $url")
        } else {
          ToolResult.success(truncated)
        }
      } catch (e: Exception) {
        ToolResult.error("Fetch failed: ${e.message}")
      }
    }
  }

  companion object {
    private const val DEFAULT_MAX_CHARS = 10_000
    private const val USER_AGENT =
      "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    private val SCRIPT_STYLE_RE = Regex(
      "<(script|style)[^>]*>[\\s\\S]*?</\\1>",
      RegexOption.IGNORE_CASE,
    )
    private val HTML_TAG_RE = Regex("<[^>]+>")
    private val WHITESPACE_RE = Regex("[ \\t]+")
    private val BLANK_LINES_RE = Regex("\\n{3,}")

    /** Strip HTML to plain text — intentionally simple. */
    internal fun extractReadableText(html: String): String {
      var text = html
      text = text.replace(SCRIPT_STYLE_RE, "")
      text = text.replace(HTML_TAG_RE, " ")
      text = text.replace("&nbsp;", " ")
      text = text.replace("&amp;", "&")
      text = text.replace("&lt;", "<")
      text = text.replace("&gt;", ">")
      text = text.replace("&quot;", "\"")
      text = text.replace(WHITESPACE_RE, " ")
      text = text.replace(BLANK_LINES_RE, "\n\n")
      return text.trim()
    }
  }
}
