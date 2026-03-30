package ai.openclaw.app.standalone.tools

import ai.openclaw.app.standalone.provider.ToolDefinition
import kotlinx.serialization.json.JsonObject

/** Central registry for all available tools. */
class ToolRegistry {
  private val tools = mutableMapOf<String, Tool>()

  fun register(tool: Tool) {
    tools[tool.name] = tool
  }

  fun definitions(): List<ToolDefinition> = tools.values.map { tool ->
    ToolDefinition(
      name = tool.name,
      description = tool.description,
      parameters = tool.parameters,
    )
  }

  suspend fun execute(name: String, args: JsonObject): ToolResult {
    val tool = tools[name] ?: return ToolResult.error("Unknown tool: $name")
    return tool.execute(args)
  }
}
