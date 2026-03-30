package ai.openclaw.app.standalone.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ai.openclaw.app.standalone.agent.AgentConfig
import ai.openclaw.app.standalone.agent.AgentEngine
import ai.openclaw.app.standalone.agent.AgentEvent
import ai.openclaw.app.standalone.auth.AuthManager
import ai.openclaw.app.standalone.auth.Credential
import ai.openclaw.app.standalone.db.AppDatabase
import ai.openclaw.app.standalone.db.ConversationEntity
import ai.openclaw.app.standalone.db.MessageEntity
import ai.openclaw.app.standalone.provider.AnthropicProvider
import ai.openclaw.app.standalone.provider.GoogleProvider
import ai.openclaw.app.standalone.provider.LlmProvider
import ai.openclaw.app.standalone.provider.OpenAiProvider
import ai.openclaw.app.standalone.tools.ToolRegistry
import ai.openclaw.app.standalone.tools.WebSearchTool
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

/** UI message for display in the standalone chat. */
data class StandaloneMessage(
  val id: String,
  val role: String,
  val text: String,
  val isStreaming: Boolean = false,
  val toolName: String? = null,
  val isError: Boolean = false,
)

/** Minimal provider descriptor for the UI layer. */
data class ProviderOption(
  val id: String,
  val displayName: String,
  val isConfigured: Boolean,
)

class StandaloneViewModel(app: Application) : AndroidViewModel(app) {

  private val authManager = AuthManager(app)
  private val db = AppDatabase.getInstance(app)
  private val conversationDao = db.conversationDao()
  private val messageDao = db.messageDao()

  private val _messages = MutableStateFlow<List<StandaloneMessage>>(emptyList())
  val messages: StateFlow<List<StandaloneMessage>> = _messages.asStateFlow()

  private val _isProcessing = MutableStateFlow(false)
  val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

  private val _currentProviderId = MutableStateFlow<String?>(null)
  val currentProviderId: StateFlow<String?> = _currentProviderId.asStateFlow()

  private val _currentConversationId = MutableStateFlow<String?>(null)
  val currentConversationId: StateFlow<String?> = _currentConversationId.asStateFlow()

  private val _streamingText = MutableStateFlow<String?>(null)
  val streamingText: StateFlow<String?> = _streamingText.asStateFlow()

  private val _executingTool = MutableStateFlow<String?>(null)
  val executingTool: StateFlow<String?> = _executingTool.asStateFlow()

  private val _errorText = MutableStateFlow<String?>(null)
  val errorText: StateFlow<String?> = _errorText.asStateFlow()

  val conversations = conversationDao.getAll()
    .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

  private var engine: AgentEngine? = null
  private var activeJob: Job? = null

  init {
    // Auto-select first configured provider.
    val configured = authManager.listConfiguredProviders()
    if (configured.isNotEmpty()) {
      _currentProviderId.value = configured.first()
      rebuildEngine()
    }
  }

  fun sendMessage(text: String) {
    val trimmed = text.trim()
    if (trimmed.isEmpty() || _isProcessing.value) return

    val currentEngine = engine
    if (currentEngine == null) {
      _errorText.value = "No provider configured"
      return
    }

    val conversationId = ensureConversation()

    // Add user message to UI.
    val userMsg = StandaloneMessage(
      id = UUID.randomUUID().toString(),
      role = "user",
      text = trimmed,
    )
    _messages.value = _messages.value + userMsg
    _errorText.value = null

    // Persist user message.
    viewModelScope.launch {
      persistMessage(conversationId, userMsg)
    }

    // Run agent.
    _isProcessing.value = true
    _streamingText.value = null
    _executingTool.value = null

    activeJob = viewModelScope.launch {
      val textBuffer = StringBuilder()
      try {
        currentEngine.processMessage(trimmed).collect { event ->
          when (event) {
            is AgentEvent.TextDelta -> {
              textBuffer.append(event.text)
              _streamingText.value = textBuffer.toString()
            }
            is AgentEvent.ToolExecuting -> {
              _executingTool.value = event.name
            }
            is AgentEvent.ToolResult -> {
              _executingTool.value = null
            }
            is AgentEvent.FinalResponse -> {
              _streamingText.value = null
              _executingTool.value = null
              val assistantMsg = StandaloneMessage(
                id = UUID.randomUUID().toString(),
                role = "assistant",
                text = event.text,
              )
              _messages.value = _messages.value + assistantMsg
              persistMessage(conversationId, assistantMsg)
              updateConversationTimestamp(conversationId)
            }
            is AgentEvent.Error -> {
              _streamingText.value = null
              _executingTool.value = null
              _errorText.value = event.message
            }
          }
        }
      } catch (e: Throwable) {
        _errorText.value = e.message ?: "Unknown error"
        _streamingText.value = null
        _executingTool.value = null
      } finally {
        _isProcessing.value = false
      }
    }
  }

  fun newConversation() {
    activeJob?.cancel()
    _isProcessing.value = false
    _messages.value = emptyList()
    _streamingText.value = null
    _executingTool.value = null
    _errorText.value = null
    _currentConversationId.value = null
    engine?.reset()
  }

  fun selectConversation(id: String) {
    if (id == _currentConversationId.value) return
    activeJob?.cancel()
    _isProcessing.value = false
    _streamingText.value = null
    _executingTool.value = null
    _errorText.value = null
    _currentConversationId.value = id

    // Reset engine for new conversation context.
    engine?.reset()

    // Load messages from DB.
    viewModelScope.launch {
      messageDao.getByConversationId(id).collect { entities ->
        _messages.value = entities.map { entity ->
          StandaloneMessage(
            id = entity.id,
            role = entity.role,
            text = entity.content ?: "",
          )
        }
      }
    }
  }

  fun switchProvider(providerId: String) {
    if (providerId == _currentProviderId.value) return
    _currentProviderId.value = providerId
    rebuildEngine()
    // Start fresh on provider switch.
    newConversation()
  }

  fun availableProviders(): List<ProviderOption> {
    val configured = authManager.listConfiguredProviders().toSet()
    return KNOWN_PROVIDERS.map { (id, name) ->
      ProviderOption(id, name, configured.contains(id))
    }
  }

  fun hasAnyProvider(): Boolean {
    return authManager.listConfiguredProviders().isNotEmpty()
  }

  private fun rebuildEngine() {
    val providerId = _currentProviderId.value ?: return
    val provider = createProvider(providerId) ?: return
    val toolRegistry = ToolRegistry()
    toolRegistry.register(WebSearchTool())
    val config = AgentConfig(
      model = provider.supportedModels.first().id,
      systemPrompt = "You are a helpful AI assistant running on an Android device via OpenClaw.",
    )
    engine = AgentEngine(provider, toolRegistry, config)
  }

  private fun createProvider(providerId: String): LlmProvider? {
    val apiKey = authManager.getApiKey(providerId) ?: return null
    return when (providerId) {
      "google" -> GoogleProvider(apiKey)
      "anthropic" -> AnthropicProvider(apiKey)
      "openai" -> OpenAiProvider(apiKey)
      else -> null
    }
  }

  private fun ensureConversation(): String {
    _currentConversationId.value?.let { return it }
    val id = UUID.randomUUID().toString()
    val now = System.currentTimeMillis()
    val providerId = _currentProviderId.value ?: "unknown"
    val model = engine?.let { providerId } ?: "unknown"
    _currentConversationId.value = id

    viewModelScope.launch {
      conversationDao.insert(
        ConversationEntity(
          id = id,
          title = null,
          providerId = providerId,
          model = model,
          createdAt = now,
          updatedAt = now,
        ),
      )
    }
    return id
  }

  private suspend fun persistMessage(conversationId: String, msg: StandaloneMessage) {
    messageDao.insert(
      MessageEntity(
        id = msg.id,
        conversationId = conversationId,
        role = msg.role,
        content = msg.text,
        toolCalls = null,
        toolCallId = null,
        createdAt = System.currentTimeMillis(),
      ),
    )
  }

  private suspend fun updateConversationTimestamp(conversationId: String) {
    val existing = conversationDao.getById(conversationId) ?: return
    conversationDao.update(existing.copy(updatedAt = System.currentTimeMillis()))
  }

  companion object {
    private val KNOWN_PROVIDERS = listOf(
      "google" to "Google Gemini",
      "anthropic" to "Anthropic Claude",
      "openai" to "OpenAI",
    )
  }
}
