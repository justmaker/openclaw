package ai.openclaw.app.standalone.agent

import ai.openclaw.app.standalone.provider.ChatEvent
import ai.openclaw.app.standalone.provider.LlmProvider
import ai.openclaw.app.standalone.provider.Message
import ai.openclaw.app.standalone.provider.Role
import ai.openclaw.app.standalone.provider.ToolCallResult
import ai.openclaw.app.standalone.tools.ToolRegistry
import ai.openclaw.app.standalone.tools.ToolResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Core agent loop: sends messages to the LLM, executes tool calls,
 * and feeds results back until the LLM produces a final text response
 * or the iteration limit is reached.
 */
class AgentEngine(
  private val provider: LlmProvider,
  private val toolRegistry: ToolRegistry,
  private val config: AgentConfig,
) {
  private val conversationHistory = mutableListOf<Message>()

  /** Process a user message and return a stream of agent events. */
  fun processMessage(userMessage: String): Flow<AgentEvent> = flow {
    conversationHistory.add(Message(Role.USER, userMessage))

    var remaining = config.maxIterations
    while (remaining-- > 0) {
      val messages = buildMessages()
      val toolDefs = toolRegistry.definitions().ifEmpty { null }

      val textBuffer = StringBuilder()
      val pendingToolCalls = mutableListOf<ToolCallResult>()

      provider.chat(
        messages = messages,
        tools = toolDefs,
        model = config.model,
        stream = true,
      ).collect { event ->
        when (event) {
          is ChatEvent.TextDelta -> {
            textBuffer.append(event.text)
            emit(AgentEvent.TextDelta(event.text))
          }
          is ChatEvent.ToolCall -> {
            pendingToolCalls.add(ToolCallResult(event.id, event.name, event.args))
          }
          is ChatEvent.Done -> {
            // Usage tracked externally if needed; nothing to do here.
          }
          is ChatEvent.Error -> {
            emit(AgentEvent.Error(event.message))
          }
        }
      }

      // No tool calls → final text response; done.
      if (pendingToolCalls.isEmpty()) {
        conversationHistory.add(Message(Role.ASSISTANT, textBuffer.toString()))
        emit(AgentEvent.FinalResponse(textBuffer.toString()))
        return@flow
      }

      // Record assistant message with tool calls.
      conversationHistory.add(
        Message(
          role = Role.ASSISTANT,
          content = textBuffer.toString().ifEmpty { null },
          toolCalls = pendingToolCalls.toList(),
        ),
      )

      // Execute each tool and feed results back.
      for (toolCall in pendingToolCalls) {
        emit(AgentEvent.ToolExecuting(toolCall.name, toolCall.args))

        val result = toolRegistry.execute(toolCall.name, toolCall.args)
        emit(AgentEvent.ToolResult(toolCall.name, result.toJson(), result.isError))

        conversationHistory.add(
          Message(
            role = Role.TOOL,
            content = result.toJson(),
            toolCallId = toolCall.id,
          ),
        )
      }
    }

    emit(AgentEvent.Error("Reached maximum iteration limit (${config.maxIterations})"))
  }

  /** Reset the conversation, optionally keeping the system prompt. */
  fun reset() {
    conversationHistory.clear()
  }

  private fun buildMessages(): List<Message> {
    val messages = mutableListOf<Message>()
    val systemPrompt = config.systemPrompt
    if (systemPrompt != null) {
      messages.add(Message(Role.SYSTEM, systemPrompt))
    }
    messages.addAll(conversationHistory)
    return messages
  }
}
