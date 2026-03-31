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
 * Reads a text file from app-internal storage.
 * Paths are relative to the provided [baseDir] (app files directory).
 */
class ReadFileTool(
  private val baseDir: File,
) : Tool {

  override val name = "read_file"
  override val description = "Read the contents of a text file from the app's internal storage."
  override val parameters: JsonObject = buildJsonObject {
    put("type", "object")
    put("properties", buildJsonObject {
      put("path", buildJsonObject {
        put("type", "string")
        put("description", "File path relative to app storage")
      })
    })
    put("required", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("path")) })
  }

  override suspend fun execute(args: JsonObject): ToolResult {
    val path = args["path"]?.jsonPrimitive?.contentOrNull
      ?: return ToolResult.error("Missing required parameter: path")

    if (containsTraversal(path)) {
      return ToolResult.error("Path traversal not allowed")
    }

    return withContext(Dispatchers.IO) {
      try {
        val file = File(baseDir, path)
        if (!file.canonicalPath.startsWith(baseDir.canonicalPath)) {
          return@withContext ToolResult.error("Path traversal not allowed")
        }
        if (!file.exists()) {
          return@withContext ToolResult.error("File not found: $path")
        }
        if (!file.isFile) {
          return@withContext ToolResult.error("Not a file: $path")
        }
        ToolResult.success(file.readText())
      } catch (e: Exception) {
        ToolResult.error("Read failed: ${e.message}")
      }
    }
  }
}

/** Reject obvious path traversal patterns. */
internal fun containsTraversal(path: String): Boolean {
  val normalized = path.replace('\\', '/')
  return normalized.contains("..") || normalized.startsWith("/")
}
