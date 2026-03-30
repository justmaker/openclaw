package ai.openclaw.app.standalone.agent

/** Configuration for the agent engine. */
data class AgentConfig(
  val model: String,
  val systemPrompt: String? = null,
  val maxIterations: Int = 10,
  val maxTokens: Int = 8192,
)
