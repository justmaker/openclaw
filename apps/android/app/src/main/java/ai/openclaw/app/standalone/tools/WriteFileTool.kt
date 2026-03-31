package ai.openclaw.app.standalone.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File

/**
 * Writes text to a file in app-internal storage.
 * Creates parent directories if needed.
 */
class WriteFileTool(
  private val baseDir: File,
) : Tool {

  override val name = "write_file"
  override val description = "Write text content to a file in the app's internal storage."
  override val parameters: JsonObject = buildJsonObject {
    put("type", "object")
    put("properties", buildJsonObject {
      put("path", buildJsonObject {
        put("type", "string")
        put("description", "File path relative to app storage")
      })
      put("content", buildJsonObject {
        put("type", "string")
        put("description", "Text content to write")
      })
    })
    put("required", buildJsonArray {
      add(kotlinx.serialization.json.JsonPrimitive("path"))
      add(kotlinx.serialization.json.JsonPrimitive("content"))
    })
  }

  override suspend fun execute(args: JsonObject): ToolResult {
    val path = args["path"]?.jsonPrimitive?.contentOrNull
      ?: return ToolResult.error("Missing required parameter: path")
    val content = args["content"]?.jsonPrimitive?.contentOrNull
      ?: return ToolResult.error("Missing required parameter: content")

    if (containsTraversal(path)) {
      return ToolResult.error("Path traversal not allowed")
    }

    return withContext(Dispatchers.IO) {
      try {
        val file = File(baseDir, path)
        if (!file.canonicalPath.startsWith(baseDir.canonicalPath)) {
          return@withContext ToolResult.error("Path traversal not allowed")
        }
        file.parentFile?.mkdirs()
        file.writeText(content)
        ToolResult.success("Written ${content.length} chars to $path")
      } catch (e: Exception) {
        ToolResult.error("Write failed: ${e.message}")
      }
    }
  }
}
