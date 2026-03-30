package ai.openclaw.app.standalone.tools

/** Result of a tool execution — either success with output or an error. */
sealed class ToolResult {
  data class Success(val output: String) : ToolResult()
  data class Error(val message: String) : ToolResult()

  fun toJson(): String = when (this) {
    is Success -> output
    is Error -> "Error: $message"
  }

  val isError: Boolean get() = this is Error

  companion object {
    fun success(output: String): ToolResult = Success(output)
    fun error(message: String): ToolResult = Error(message)
  }
}
