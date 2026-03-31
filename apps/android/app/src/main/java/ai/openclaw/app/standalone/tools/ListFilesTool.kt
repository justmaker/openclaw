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

/** Lists files and directories in app-internal storage. */
class ListFilesTool(
  private val baseDir: File,
) : Tool {

  override val name = "list_files"
  override val description = "List files and directories in the app's internal storage."
  override val parameters: JsonObject = buildJsonObject {
    put("type", "object")
    put("properties", buildJsonObject {
      put("path", buildJsonObject {
        put("type", "string")
        put("description", "Directory path relative to app storage (default: root)")
      })
    })
  }

  override suspend fun execute(args: JsonObject): ToolResult {
    val path = args["path"]?.jsonPrimitive?.contentOrNull ?: "."

    if (containsTraversal(path)) {
      return ToolResult.error("Path traversal not allowed")
    }

    return withContext(Dispatchers.IO) {
      try {
        val dir = File(baseDir, path)
        if (!dir.canonicalPath.startsWith(baseDir.canonicalPath)) {
          return@withContext ToolResult.error("Path traversal not allowed")
        }
        if (!dir.exists()) {
          return@withContext ToolResult.error("Directory not found: $path")
        }
        if (!dir.isDirectory) {
          return@withContext ToolResult.error("Not a directory: $path")
        }

        val entries = dir.listFiles()
        if (entries.isNullOrEmpty()) {
          return@withContext ToolResult.success("(empty directory)")
        }

        val sb = StringBuilder()
        for (file in entries.sortedBy { it.name }) {
          val type = if (file.isDirectory) "dir" else "file"
          val size = if (file.isFile) " (${formatSize(file.length())})" else ""
          sb.appendLine("$type  ${file.name}$size")
        }
        ToolResult.success(sb.toString().trimEnd())
      } catch (e: Exception) {
        ToolResult.error("List failed: ${e.message}")
      }
    }
  }

  companion object {
    private fun formatSize(bytes: Long): String = when {
      bytes < 1024 -> "${bytes}B"
      bytes < 1024 * 1024 -> "${bytes / 1024}KB"
      else -> "${"%.1f".format(bytes.toDouble() / (1024 * 1024))}MB"
    }
  }
}
