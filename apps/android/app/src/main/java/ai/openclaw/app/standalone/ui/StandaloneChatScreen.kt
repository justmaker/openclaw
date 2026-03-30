package ai.openclaw.app.standalone.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.openclaw.app.ui.mobileAccent
import ai.openclaw.app.ui.mobileAccentBorderStrong
import ai.openclaw.app.ui.mobileAccentSoft
import ai.openclaw.app.ui.mobileBorder
import ai.openclaw.app.ui.mobileBorderStrong
import ai.openclaw.app.ui.mobileCallout
import ai.openclaw.app.ui.mobileCaption1
import ai.openclaw.app.ui.mobileCaption2
import ai.openclaw.app.ui.mobileCardSurface
import ai.openclaw.app.ui.mobileHeadline
import ai.openclaw.app.ui.mobileSurface
import ai.openclaw.app.ui.mobileDanger
import ai.openclaw.app.ui.mobileDangerSoft
import ai.openclaw.app.ui.mobileText
import ai.openclaw.app.ui.mobileTextSecondary
import ai.openclaw.app.ui.mobileTextTertiary
import ai.openclaw.app.ui.mobileTitle2
import kotlinx.coroutines.launch

@Composable
fun StandaloneChatScreen(
  viewModel: StandaloneViewModel,
  onNavigateToProviderSetup: () -> Unit,
) {
  val messages by viewModel.messages.collectAsState()
  val isProcessing by viewModel.isProcessing.collectAsState()
  val streamingText by viewModel.streamingText.collectAsState()
  val executingTool by viewModel.executingTool.collectAsState()
  val errorText by viewModel.errorText.collectAsState()
  val currentProviderId by viewModel.currentProviderId.collectAsState()
  val conversations by viewModel.conversations.collectAsState()
  val currentConversationId by viewModel.currentConversationId.collectAsState()

  val drawerState = rememberDrawerState(DrawerValue.Closed)
  val scope = rememberCoroutineScope()

  ModalNavigationDrawer(
    drawerState = drawerState,
    drawerContent = {
      ConversationDrawer(
        conversations = conversations.map { conv ->
          DrawerConversation(
            id = conv.id,
            title = conv.title ?: "Untitled",
            isActive = conv.id == currentConversationId,
          )
        },
        onSelectConversation = { id ->
          viewModel.selectConversation(id)
          scope.launch { drawerState.close() }
        },
        onNewConversation = {
          viewModel.newConversation()
          scope.launch { drawerState.close() }
        },
        onOpenSettings = {
          scope.launch { drawerState.close() }
          onNavigateToProviderSetup()
        },
      )
    },
  ) {
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
      // Top bar
      ChatTopBar(
        providerId = currentProviderId,
        onOpenDrawer = { scope.launch { drawerState.open() } },
        onOpenSettings = onNavigateToProviderSetup,
      )

      Spacer(modifier = Modifier.height(8.dp))

      // Error rail
      if (!errorText.isNullOrBlank()) {
        StandaloneErrorRail(errorText = errorText!!)
        Spacer(modifier = Modifier.height(8.dp))
      }

      // Message list
      StandaloneMessageList(
        messages = messages,
        streamingText = streamingText,
        executingTool = executingTool,
        isProcessing = isProcessing,
        modifier = Modifier.weight(1f),
      )

      Spacer(modifier = Modifier.height(8.dp))

      // Input area
      StandaloneComposer(
        isProcessing = isProcessing,
        hasProvider = currentProviderId != null,
        modifier = Modifier.imePadding(),
        onSend = { text -> viewModel.sendMessage(text) },
      )
    }
  }
}

@Composable
private fun ChatTopBar(
  providerId: String?,
  onOpenDrawer: () -> Unit,
  onOpenSettings: () -> Unit,
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    IconButton(onClick = onOpenDrawer) {
      Icon(Icons.Default.Menu, contentDescription = "Conversations", tint = mobileText)
    }
    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = "Standalone Agent",
        style = mobileHeadline.copy(fontWeight = FontWeight.Bold),
        color = mobileText,
      )
      if (providerId != null) {
        Text(
          text = providerLabel(providerId),
          style = mobileCaption1,
          color = mobileTextSecondary,
        )
      }
    }
    IconButton(onClick = onOpenSettings) {
      Icon(Icons.Default.Settings, contentDescription = "Provider settings", tint = mobileTextSecondary)
    }
  }
}

@Composable
private fun StandaloneMessageList(
  messages: List<StandaloneMessage>,
  streamingText: String?,
  executingTool: String?,
  isProcessing: Boolean,
  modifier: Modifier = Modifier,
) {
  val listState = rememberLazyListState()

  // Auto-scroll to bottom on new messages or streaming updates.
  LaunchedEffect(messages.size, streamingText) {
    val total = messages.size + (if (streamingText != null || executingTool != null) 1 else 0)
    if (total > 0) {
      listState.animateScrollToItem(total - 1)
    }
  }

  Surface(
    modifier = modifier.fillMaxWidth(),
    shape = RoundedCornerShape(16.dp),
    color = mobileSurface,
    border = BorderStroke(1.dp, mobileBorder),
  ) {
    if (messages.isEmpty() && streamingText == null && !isProcessing) {
      Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
          text = "Start a conversation",
          style = mobileCallout,
          color = mobileTextTertiary,
        )
      }
    } else {
      LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        items(messages, key = { it.id }) { msg ->
          StandaloneMessageBubble(msg)
        }

        // Show tool execution status.
        if (executingTool != null) {
          item(key = "tool_executing") {
            ToolExecutingBubble(toolName = executingTool!!)
          }
        }

        // Show streaming text.
        if (streamingText != null) {
          item(key = "streaming") {
            StreamingBubble(text = streamingText!!)
          }
        } else if (isProcessing && executingTool == null) {
          item(key = "thinking") {
            ThinkingBubble()
          }
        }
      }
    }
  }
}

@Composable
private fun StandaloneMessageBubble(msg: StandaloneMessage) {
  val isUser = msg.role == "user"
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
  ) {
    Surface(
      shape = RoundedCornerShape(12.dp),
      color = if (isUser) mobileAccentSoft else mobileCardSurface,
      border = BorderStroke(1.dp, if (isUser) mobileAccent else mobileBorderStrong),
      modifier = Modifier.fillMaxWidth(0.88f),
    ) {
      Column(
        modifier = Modifier.padding(horizontal = 11.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
      ) {
        Text(
          text = if (isUser) "You" else "Assistant",
          style = mobileCaption2.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp),
          color = if (isUser) mobileAccent else mobileTextSecondary,
        )
        Text(
          text = msg.text,
          style = mobileCallout,
          color = mobileText,
        )
      }
    }
  }
}

@Composable
private fun StreamingBubble(text: String) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.Start,
  ) {
    Surface(
      shape = RoundedCornerShape(12.dp),
      color = mobileCardSurface,
      border = BorderStroke(1.dp, mobileAccent),
      modifier = Modifier.fillMaxWidth(0.88f),
    ) {
      Column(
        modifier = Modifier.padding(horizontal = 11.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
      ) {
        Text(
          text = "Assistant · Live",
          style = mobileCaption2.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp),
          color = mobileAccent,
        )
        Text(text = text, style = mobileCallout, color = mobileText)
      }
    }
  }
}

@Composable
private fun ToolExecutingBubble(toolName: String) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.Start,
  ) {
    Surface(
      shape = RoundedCornerShape(12.dp),
      color = mobileCardSurface,
      border = BorderStroke(1.dp, mobileBorderStrong),
      modifier = Modifier.fillMaxWidth(0.88f),
    ) {
      Row(
        modifier = Modifier.padding(horizontal = 11.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = mobileAccent)
        Text(
          text = "Running: $toolName",
          style = mobileCaption1.copy(fontWeight = FontWeight.SemiBold),
          color = mobileTextSecondary,
        )
      }
    }
  }
}

@Composable
private fun ThinkingBubble() {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.Start,
  ) {
    Surface(
      shape = RoundedCornerShape(12.dp),
      color = mobileCardSurface,
      border = BorderStroke(1.dp, mobileBorderStrong),
      modifier = Modifier.fillMaxWidth(0.88f),
    ) {
      Row(
        modifier = Modifier.padding(horizontal = 11.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = mobileTextSecondary)
        Text("Thinking...", style = mobileCallout, color = mobileTextSecondary)
      }
    }
  }
}

@Composable
private fun StandaloneErrorRail(errorText: String) {
  Surface(
    modifier = Modifier.fillMaxWidth(),
    color = mobileDangerSoft,
    shape = RoundedCornerShape(12.dp),
    border = BorderStroke(1.dp, mobileDanger),
  ) {
    Column(
      modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
      Text(
        text = "ERROR",
        style = mobileCaption2.copy(letterSpacing = 0.6.sp),
        color = mobileDanger,
      )
      Text(text = errorText, style = mobileCallout, color = mobileText)
    }
  }
}

@Composable
private fun StandaloneComposer(
  isProcessing: Boolean,
  hasProvider: Boolean,
  modifier: Modifier = Modifier,
  onSend: (String) -> Unit,
) {
  var input by rememberSaveable { mutableStateOf("") }
  val canSend = !isProcessing && input.trim().isNotEmpty() && hasProvider

  Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    OutlinedTextField(
      value = input,
      onValueChange = { input = it },
      modifier = Modifier.fillMaxWidth(),
      placeholder = {
        Text(
          if (hasProvider) "Type a message..." else "Configure a provider first",
          style = mobileCallout,
          color = mobileTextTertiary,
        )
      },
      minLines = 2,
      maxLines = 5,
      textStyle = mobileCallout.copy(color = mobileText),
      shape = RoundedCornerShape(14.dp),
      colors = OutlinedTextFieldDefaults.colors(
        focusedContainerColor = mobileSurface,
        unfocusedContainerColor = mobileSurface,
        focusedBorderColor = mobileAccent,
        unfocusedBorderColor = mobileBorder,
        focusedTextColor = mobileText,
        unfocusedTextColor = mobileText,
        cursorColor = mobileAccent,
      ),
    )

    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Spacer(modifier = Modifier.weight(1f))
      Button(
        onClick = {
          val text = input
          input = ""
          onSend(text)
        },
        enabled = canSend,
        modifier = Modifier.height(44.dp),
        shape = RoundedCornerShape(14.dp),
        contentPadding = PaddingValues(horizontal = 20.dp),
        colors = ButtonDefaults.buttonColors(
          containerColor = mobileAccent,
          contentColor = Color.White,
          disabledContainerColor = mobileBorderStrong,
          disabledContentColor = mobileTextTertiary,
        ),
        border = BorderStroke(1.dp, if (canSend) mobileAccentBorderStrong else mobileBorderStrong),
      ) {
        if (isProcessing) {
          CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
        } else {
          Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(16.dp))
        }
        Spacer(modifier = Modifier.width(6.dp))
        Text(
          text = "Send",
          style = mobileHeadline.copy(fontWeight = FontWeight.Bold),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
  }
}

// -- Conversation drawer --

private data class DrawerConversation(
  val id: String,
  val title: String,
  val isActive: Boolean,
)

@Composable
private fun ConversationDrawer(
  conversations: List<DrawerConversation>,
  onSelectConversation: (String) -> Unit,
  onNewConversation: () -> Unit,
  onOpenSettings: () -> Unit,
) {
  ModalDrawerSheet {
    Column(modifier = Modifier.padding(16.dp)) {
      Text(
        text = "Conversations",
        style = mobileTitle2,
        color = mobileText,
      )
      Spacer(modifier = Modifier.height(12.dp))

      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
          onClick = onNewConversation,
          shape = RoundedCornerShape(12.dp),
          colors = ButtonDefaults.buttonColors(
            containerColor = mobileAccent,
            contentColor = Color.White,
          ),
          contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        ) {
          Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
          Spacer(modifier = Modifier.width(4.dp))
          Text("New", style = mobileCallout.copy(fontWeight = FontWeight.SemiBold))
        }

        Button(
          onClick = onOpenSettings,
          shape = RoundedCornerShape(12.dp),
          colors = ButtonDefaults.buttonColors(
            containerColor = mobileCardSurface,
            contentColor = mobileTextSecondary,
          ),
          border = BorderStroke(1.dp, mobileBorderStrong),
          contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        ) {
          Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(16.dp))
          Spacer(modifier = Modifier.width(4.dp))
          Text("Providers", style = mobileCallout.copy(fontWeight = FontWeight.SemiBold))
        }
      }

      Spacer(modifier = Modifier.height(12.dp))
      HorizontalDivider(color = mobileBorder)
      Spacer(modifier = Modifier.height(8.dp))

      if (conversations.isEmpty()) {
        Text("No conversations yet", style = mobileCallout, color = mobileTextTertiary)
      } else {
        for (conv in conversations) {
          NavigationDrawerItem(
            label = {
              Text(
                text = conv.title,
                style = mobileCallout,
                color = if (conv.isActive) mobileAccent else mobileText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
            },
            selected = conv.isActive,
            onClick = { onSelectConversation(conv.id) },
            shape = RoundedCornerShape(12.dp),
          )
        }
      }
    }
  }
}

private fun providerLabel(providerId: String): String {
  return when (providerId) {
    "google" -> "Google Gemini"
    "anthropic" -> "Anthropic Claude"
    "openai" -> "OpenAI"
    else -> providerId
  }
}
