package ai.openclaw.app.standalone.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder

/** Web search tool using DuckDuckGo HTML. */
class WebSearchTool(
  private val client: OkHttpClient = OkHttpClient(),
) : Tool {

  override val name = "web_search"
  override val description = "Search the web for information using a text query."
  override val parameters: JsonObject = buildJsonObject {
    put("type", "object")
    put("properties", buildJsonObject {
      put("query", buildJsonObject {
        put("type", "string")
        put("description", "The search query")
      })
    })
    put("required", buildJsonArray { add("query") })
  }

  override suspend fun execute(args: JsonObject): ToolResult {
    val query = args["query"]?.jsonPrimitive?.contentOrNull
      ?: return ToolResult.error("Missing required parameter: query")

    return withContext(Dispatchers.IO) {
      try {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "$DDG_HTML_URL?q=$encoded"
        val request = Request.Builder()
          .url(url)
          .addHeader("User-Agent", USER_AGENT)
          .get()
          .build()

        val response = client.newCall(request).execute()
        val body = response.body?.string()
        response.close()

        if (body == null) return@withContext ToolResult.error("Empty response from search")
        val results = parseSearchResults(body)
        if (results.isBlank()) {
          ToolResult.success("No results found for: $query")
        } else {
          ToolResult.success(results)
        }
      } catch (e: Exception) {
        ToolResult.error("Search failed: ${e.message}")
      }
    }
  }

  companion object {
    private const val DDG_HTML_URL = "https://html.duckduckgo.com/html/"
    private const val USER_AGENT =
      "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    private const val MAX_RESULTS = 5

    private val LINK_RE = Regex(
      """<a[^>]+class="result__a"[^>]*href="([^"]*)"[^>]*>(.*?)</a>""",
      RegexOption.DOT_MATCHES_ALL,
    )
    private val SNIPPET_RE = Regex(
      """<a[^>]+class="result__snippet"[^>]*>(.*?)</a>""",
      RegexOption.DOT_MATCHES_ALL,
    )
    private val HTML_TAG_RE = Regex("<[^>]+>")

    private fun stripHtml(text: String): String =
      text.replace(HTML_TAG_RE, "").trim()

    private fun parseSearchResults(html: String): String {
      val links = LINK_RE.findAll(html).toList()
      val snippets = SNIPPET_RE.findAll(html).toList()

      if (links.isEmpty()) return ""

      val sb = StringBuilder()
      for (i in links.indices.take(MAX_RESULTS)) {
        val link = links[i]
        val rawUrl = link.groupValues[1]
        val title = stripHtml(link.groupValues[2])
        val snippet = snippets.getOrNull(i)?.let { stripHtml(it.groupValues[1]) } ?: ""

        sb.appendLine("${i + 1}. $title")
        sb.appendLine("   URL: $rawUrl")
        if (snippet.isNotEmpty()) {
          sb.appendLine("   $snippet")
        }
        sb.appendLine()
      }
      return sb.toString().trimEnd()
    }
  }
}
