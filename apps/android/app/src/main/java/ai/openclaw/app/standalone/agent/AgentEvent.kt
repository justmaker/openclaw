package ai.openclaw.app.standalone.agent

import kotlinx.serialization.json.JsonObject

/** Events emitted by the AgentEngine during message processing. */
sealed class AgentEvent {
  /** Streaming text chunk from the LLM. */
  data class TextDelta(val text: String) : AgentEvent()

  /** A tool is about to be executed. */
  data class ToolExecuting(val name: String, val args: JsonObject) : AgentEvent()

  /** A tool has finished executing. */
  data class ToolResult(
    val name: String,
    val output: String,
    val isError: Boolean,
  ) : AgentEvent()

  /** The agent has finished and produced a final response. */
  data class FinalResponse(val text: String) : AgentEvent()

  /** An error occurred during processing. */
  data class Error(val message: String) : AgentEvent()
}
