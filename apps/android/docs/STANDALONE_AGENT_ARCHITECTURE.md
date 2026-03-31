# AndroidClaw — Standalone Agent Architecture (Kotlin Native)

> **Status:** Design  
> **Date:** 2026-03-31  
> **Decision:** 不用 nodejs-mobile，用純 Kotlin 實作精簡版 agent

## 決策摘要

| 方案 | 結論 |
|------|------|
| nodejs-mobile | ❌ 排除 — 沒有 `child_process`，Node 18 EOL，+40MB |
| QuickJS | ❌ 排除 — 無 Node.js API，重寫量太大 |
| Kotlin Native | ✅ 採用 — 輕量、原生、multi-provider HTTP call 直接做 |
| Termux | ❌ 排除 — UX 差，無法獨立分發 |

## 架構總覽

```
┌─────────────────────────────────────────────┐
│              AndroidClaw App                 │
│                                              │
│  ┌──────────────┐  ┌──────────────────────┐ │
│  │   Auth Layer  │  │     Agent Engine      │ │
│  │              │  │                      │ │
│  │ ● GHC OAuth  │  │ ● Conversation Mgr   │ │
│  │ ● API Key    │  │ ● Tool Dispatcher    │ │
│  │   (Anthropic │  │ ● Streaming Handler  │ │
│  │    OpenAI    │  │ ● System Prompt      │ │
│  │    Google)   │  │                      │ │
│  └──────┬───────┘  └──────────┬───────────┘ │
│         │                     │              │
│  ┌──────┴─────────────────────┴───────────┐ │
│  │          Provider Abstraction           │ │
│  │                                         │ │
│  │  ┌─────────┐ ┌────────┐ ┌───────────┐ │ │
│  │  │Anthropic│ │ OpenAI │ │  Google    │ │ │
│  │  │ Client  │ │ Client │ │  Client   │ │ │
│  │  └─────────┘ └────────┘ └───────────┘ │ │
│  │  ┌─────────┐ ┌────────┐               │ │
│  │  │  GHC    │ │OpenRtr │               │ │
│  │  │ Client  │ │ Client │               │ │
│  │  └─────────┘ └────────┘               │ │
│  └────────────────────────────────────────┘ │
│                                              │
│  ┌──────────────────────────────────────┐   │
│  │            Tool Registry              │   │
│  │                                       │   │
│  │  ● web_search    ● read_file         │   │
│  │  ● write_file    ● list_files        │   │
│  │  ● web_fetch     ● (future: camera)  │   │
│  └──────────────────────────────────────┘   │
│                                              │
│  ┌──────────────────────────────────────┐   │
│  │         UI Layer (Compose)            │   │
│  │                                       │   │
│  │  ● Chat (沿用現有)                    │   │
│  │  ● Settings (provider 設定)           │   │
│  │  ● Onboarding (OAuth / API key)      │   │
│  └──────────────────────────────────────┘   │
│                                              │
│  ┌──────────────────────────────────────┐   │
│  │       Persistence Layer               │   │
│  │                                       │   │
│  │  ● Conversation history (Room DB)     │   │
│  │  ● Provider credentials (EncryptedSP) │   │
│  │  ● Agent config (AGENTS.md local)     │   │
│  └──────────────────────────────────────┘   │
└─────────────────────────────────────────────┘
```

## Module 設計

### 1. Provider Abstraction (`provider/`)

統一介面，每個 provider 實作相同的 contract：

```kotlin
interface LlmProvider {
    val id: String
    val displayName: String
    val supportedModels: List<ModelInfo>
    
    suspend fun chat(
        messages: List<Message>,
        tools: List<ToolDefinition>?,
        model: String,
        stream: Boolean = true,
    ): Flow<ChatEvent>
    
    suspend fun validateCredentials(): Boolean
}

sealed class ChatEvent {
    data class TextDelta(val text: String) : ChatEvent()
    data class ToolCall(val id: String, val name: String, val args: JsonObject) : ChatEvent()
    data class Done(val usage: Usage?) : ChatEvent()
    data class Error(val message: String, val code: String?) : ChatEvent()
}

data class Message(
    val role: Role,           // system, user, assistant, tool
    val content: String?,
    val toolCalls: List<ToolCallResult>? = null,
    val toolCallId: String? = null,
)
```

### Provider 實作

| Provider | Auth | API Format | Streaming |
|----------|------|-----------|-----------|
| `AnthropicProvider` | `x-api-key` header | Messages API | SSE |
| `OpenAiProvider` | `Bearer` token | Chat Completions | SSE |
| `GhcProvider` | OAuth token | Copilot Chat API | SSE |
| `GoogleProvider` | API key / OAuth | Gemini generateContent | SSE |
| `OpenRouterProvider` | `Bearer` token | OpenAI-compatible | SSE |

### 2. Agent Engine (`agent/`)

精簡版 agent loop，對標 OpenClaw 的核心行為：

```kotlin
class AgentEngine(
    private val provider: LlmProvider,
    private val toolRegistry: ToolRegistry,
    private val config: AgentConfig,
) {
    private val conversationHistory = mutableListOf<Message>()
    
    /**
     * 處理使用者訊息，回傳 agent 回應的 Flow
     * 支援多輪 tool use（agent 可以連續呼叫多個 tools）
     */
    suspend fun processMessage(userMessage: String): Flow<AgentEvent> = flow {
        conversationHistory.add(Message(Role.USER, userMessage))
        
        var maxIterations = 10  // 防止無限迴圈
        while (maxIterations-- > 0) {
            val response = provider.chat(
                messages = buildMessages(),
                tools = toolRegistry.definitions(),
                model = config.model,
                stream = true,
            )
            
            val result = collectResponse(response)
            
            if (result.toolCalls.isEmpty()) {
                // 沒有 tool call → 純文字回應，結束
                emit(AgentEvent.FinalResponse(result.text))
                break
            }
            
            // 有 tool calls → 執行並繼續
            for (toolCall in result.toolCalls) {
                emit(AgentEvent.ToolExecuting(toolCall.name, toolCall.args))
                val toolResult = toolRegistry.execute(toolCall.name, toolCall.args)
                emit(AgentEvent.ToolResult(toolCall.name, toolResult))
                conversationHistory.add(Message(
                    role = Role.TOOL,
                    content = toolResult.toJson(),
                    toolCallId = toolCall.id,
                ))
            }
        }
    }
}
```

### 3. Tool Registry (`tools/`)

```kotlin
interface Tool {
    val name: String
    val description: String
    val parameters: JsonSchema
    
    suspend fun execute(args: JsonObject): ToolResult
}

class ToolRegistry {
    private val tools = mutableMapOf<String, Tool>()
    
    fun register(tool: Tool) { tools[tool.name] = tool }
    fun definitions(): List<ToolDefinition> = tools.values.map { it.toDefinition() }
    suspend fun execute(name: String, args: JsonObject): ToolResult {
        return tools[name]?.execute(args) 
            ?: ToolResult.error("Unknown tool: $name")
    }
}
```

**Phase 1 Tools:**

| Tool | 功能 | 實作 |
|------|------|------|
| `web_search` | 搜尋網頁 | Brave Search API / DuckDuckGo |
| `web_fetch` | 抓取網頁內容 | OkHttp + Readability |
| `read_file` | 讀取本地檔案 | Android File API (app sandbox) |
| `write_file` | 寫入本地檔案 | Android File API |
| `list_files` | 列出目錄 | Android File API |

**Phase 2 Tools:**

| Tool | 功能 |
|------|------|
| `camera_snap` | 拍照 |
| `location_get` | 取得位置 |
| `contacts_search` | 搜尋聯絡人 |
| `calendar_events` | 查看行事曆 |

### 4. Auth Layer (`auth/`)

```kotlin
sealed class ProviderCredential {
    data class ApiKey(val key: String) : ProviderCredential()
    data class OAuthToken(
        val accessToken: String,
        val refreshToken: String?,
        val expiresAt: Long?,
    ) : ProviderCredential()
}

class AuthManager(private val securePrefs: SecurePrefs) {
    fun saveCredential(providerId: String, credential: ProviderCredential)
    fun getCredential(providerId: String): ProviderCredential?
    fun deleteCredential(providerId: String)
    
    // GHC OAuth flow
    suspend fun startGhcOAuth(): Uri  // 回傳 OAuth authorize URL
    suspend fun handleGhcCallback(code: String): OAuthToken
}
```

### 5. Persistence (`db/`)

```kotlin
@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val id: String,
    val title: String?,
    val providerId: String,
    val model: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val role: String,
    val content: String?,
    val toolCalls: String?,  // JSON serialized
    val toolCallId: String?,
    val createdAt: Long,
)
```

## Gradle Module 結構

```
apps/android/
├── app/                          # 現有 app module
│   └── src/main/java/ai/openclaw/app/
│       ├── standalone/           # ← 新增：standalone agent mode
│       │   ├── agent/
│       │   │   ├── AgentEngine.kt
│       │   │   ├── AgentConfig.kt
│       │   │   └── AgentEvent.kt
│       │   ├── provider/
│       │   │   ├── LlmProvider.kt
│       │   │   ├── AnthropicProvider.kt
│       │   │   ├── OpenAiProvider.kt
│       │   │   ├── GhcProvider.kt
│       │   │   ├── GoogleProvider.kt
│       │   │   └── OpenRouterProvider.kt
│       │   ├── tools/
│       │   │   ├── Tool.kt
│       │   │   ├── ToolRegistry.kt
│       │   │   ├── WebSearchTool.kt
│       │   │   ├── WebFetchTool.kt
│       │   │   ├── ReadFileTool.kt
│       │   │   ├── WriteFileTool.kt
│       │   │   └── ListFilesTool.kt
│       │   ├── auth/
│       │   │   ├── AuthManager.kt
│       │   │   └── GhcOAuthFlow.kt
│       │   └── db/
│       │       ├── AppDatabase.kt
│       │       └── MessageDao.kt
│       ├── chat/                 # 現有 chat UI（沿用）
│       ├── gateway/              # 現有 gateway 連線（保留）
│       └── ui/                   # 現有 UI
│           └── standalone/       # ← 新增：standalone mode UI
│               ├── StandaloneSetupScreen.kt
│               ├── ProviderPickerScreen.kt
│               └── StandaloneChatScreen.kt
```

## 與現有 App 的共存策略

**不是取代，是新增模式：**

```
App 啟動
  ├── 選擇模式
  │   ├── 🔗 Connect Mode（現有）→ 連接外部 Gateway
  │   └── 🤖 Standalone Mode（新增）→ 本地 agent
  │
  └── Standalone Mode
      ├── 首次：Onboarding → 選 provider → 設定 credentials
      └── 之後：直接進 Chat UI
```

## MVP Scope（Phase 1）

**目標：在 Android 上跑通一個完整的 AI agent 對話**

- [x] `LlmProvider` interface + `AnthropicProvider` 實作
- [x] `OpenAiProvider` 實作
- [x] `GhcProvider` 實作（extends OpenAiProvider, OAuth flow 待 auth layer）
- [x] `GoogleProvider` 實作（Gemini API）
- [x] `AgentEngine` — 基本 agent loop（對話 + tool calling）
- [x] `WebSearchTool` — DuckDuckGo HTML scrape
- [x] Tool system（`Tool` interface + `ToolRegistry` + `ToolResult`）
- [x] Standalone Chat UI — 用現有 Compose Chat 組件
- [x] Provider 設定 UI — 輸入 API key 或 OAuth login
- [x] Room DB 儲存對話歷史
- [x] EncryptedSharedPreferences 儲存 credentials
- [x] `OpenRouterProvider` — OpenRouter (OpenAI-compatible)
- [x] `GhcOAuthFlow` — GitHub Device Flow for Copilot auth
- [x] `ProviderErrorHandler` — Error classification + exponential backoff retry
- [x] `WebFetchTool` — Fetch web pages, strip HTML
- [x] `ReadFileTool` / `WriteFileTool` / `ListFilesTool` — App sandbox file I/O

**不做的：**
- ❌ 完整 OpenClaw 相容
- ❌ MCP support
- ❌ Plugin system
- ❌ Channel routing
- ❌ Canvas / A2UI
- ❌ Voice

## 依賴新增

```kotlin
// build.gradle.kts 新增
dependencies {
    // 已有
    // implementation("com.squareup.okhttp3:okhttp:4.x")
    // implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.x")
    
    // 新增
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    
    // SSE streaming (如果 OkHttp 不夠用)
    implementation("com.squareup.okhttp3:okhttp-sse:4.12.0")
}
```

## 開發順序

```
Week 1: Provider layer
  ├── Day 1-2: LlmProvider interface + AnthropicProvider
  ├── Day 3-4: OpenAiProvider + GhcProvider (OAuth)  
  └── Day 5: GoogleProvider

Week 2: Agent engine + Tools
  ├── Day 1-2: AgentEngine (conversation + tool calling loop)
  ├── Day 3: WebSearchTool + WebFetchTool
  └── Day 4-5: ReadFile/WriteFile/ListFiles tools

Week 3: UI + Persistence
  ├── Day 1-2: Room DB + message persistence
  ├── Day 3: Standalone Chat UI (adapt existing Compose)
  ├── Day 4: Provider settings UI + credential storage
  └── Day 5: Mode selection (Connect vs Standalone)

Week 4: Polish + Testing
  ├── Day 1-2: Error handling, retry, streaming UX
  ├── Day 3: GHC OAuth end-to-end testing
  └── Day 4-5: Integration testing on real device
```
