package ai.openclaw.app.standalone.tools

import kotlinx.serialization.json.JsonObject

/** A tool that the agent can invoke during a conversation. */
interface Tool {
  val name: String
  val description: String
  val parameters: JsonObject

  suspend fun execute(args: JsonObject): ToolResult
}
