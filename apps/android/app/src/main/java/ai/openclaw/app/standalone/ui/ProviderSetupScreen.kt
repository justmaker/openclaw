package ai.openclaw.app.standalone.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.openclaw.app.standalone.auth.AuthManager
import ai.openclaw.app.standalone.auth.Credential
import ai.openclaw.app.standalone.auth.GhcOAuthFlow
import ai.openclaw.app.standalone.auth.GhcOAuthState
import ai.openclaw.app.standalone.provider.AnthropicProvider
import ai.openclaw.app.standalone.provider.GoogleProvider
import ai.openclaw.app.standalone.provider.OpenAiProvider
import ai.openclaw.app.standalone.provider.OpenRouterProvider
import ai.openclaw.app.ui.mobileAccent
import ai.openclaw.app.ui.mobileAccentSoft
import ai.openclaw.app.ui.mobileBorder
import ai.openclaw.app.ui.mobileBorderStrong
import ai.openclaw.app.ui.mobileCallout
import ai.openclaw.app.ui.mobileCaption1
import ai.openclaw.app.ui.mobileCardSurface
import ai.openclaw.app.ui.mobileHeadline
import ai.openclaw.app.ui.mobileSurface
import ai.openclaw.app.ui.mobileSuccess
import ai.openclaw.app.ui.mobileText
import ai.openclaw.app.ui.mobileTextSecondary
import ai.openclaw.app.ui.mobileTextTertiary
import ai.openclaw.app.ui.mobileTitle1
import ai.openclaw.app.ui.mobileDanger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Info about a provider entry in the setup list. */
private data class ProviderEntry(
  val id: String,
  val displayName: String,
  val type: ProviderAuthType,
  val isConfigured: Boolean,
)

private enum class ProviderAuthType { API_KEY, OAUTH }

@Composable
fun ProviderSetupScreen(
  authManager: AuthManager,
  onBack: () -> Unit,
  onProviderChanged: () -> Unit,
) {
  var refreshTrigger by remember { mutableStateOf(0) }

  val providers = remember(refreshTrigger) {
    val configured = authManager.listConfiguredProviders().toSet()
    listOf(
      ProviderEntry("google", "Google Gemini", ProviderAuthType.API_KEY, configured.contains("google")),
      ProviderEntry("anthropic", "Anthropic Claude", ProviderAuthType.API_KEY, configured.contains("anthropic")),
      ProviderEntry("openai", "OpenAI", ProviderAuthType.API_KEY, configured.contains("openai")),
      ProviderEntry("openrouter", "OpenRouter", ProviderAuthType.API_KEY, configured.contains("openrouter")),
      ProviderEntry("ghc", "GitHub Copilot", ProviderAuthType.OAUTH, configured.contains("ghc")),
    )
  }

  Column(
    modifier = Modifier
      .fillMaxSize()
      .padding(horizontal = 16.dp, vertical = 8.dp)
      .verticalScroll(rememberScrollState()),
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
    ) {
      IconButton(onClick = onBack) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = mobileText)
      }
      Text(
        text = "Provider Setup",
        style = mobileTitle1,
        color = mobileText,
      )
    }

    Spacer(modifier = Modifier.height(16.dp))

    for (entry in providers) {
      ProviderCard(
        entry = entry,
        authManager = authManager,
        onChanged = {
          refreshTrigger++
          onProviderChanged()
        },
      )
      Spacer(modifier = Modifier.height(12.dp))
    }
  }
}

@Composable
private fun ProviderCard(
  entry: ProviderEntry,
  authManager: AuthManager,
  onChanged: () -> Unit,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var apiKeyInput by remember { mutableStateOf("") }
  var isValidating by remember { mutableStateOf(false) }
  var expanded by remember { mutableStateOf(false) }

  Surface(
    shape = RoundedCornerShape(16.dp),
    color = mobileCardSurface,
    border = BorderStroke(1.dp, if (entry.isConfigured) mobileSuccess else mobileBorderStrong),
  ) {
    Column(modifier = Modifier.padding(16.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Icon(
          Icons.Default.Cloud,
          contentDescription = null,
          modifier = Modifier.size(24.dp),
          tint = if (entry.isConfigured) mobileSuccess else mobileTextSecondary,
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = entry.displayName,
            style = mobileHeadline.copy(fontWeight = FontWeight.Bold),
            color = mobileText,
          )
          Text(
            text = if (entry.isConfigured) "Configured" else "Not configured",
            style = mobileCaption1,
            color = if (entry.isConfigured) mobileSuccess else mobileTextTertiary,
          )
        }
        if (entry.isConfigured) {
          Icon(
            Icons.Default.Check,
            contentDescription = "Configured",
            modifier = Modifier.size(20.dp),
            tint = mobileSuccess,
          )
        }
      }

      Spacer(modifier = Modifier.height(12.dp))

      when (entry.type) {
        ProviderAuthType.API_KEY -> {
          if (!expanded && !entry.isConfigured) {
            Button(
              onClick = { expanded = true },
              shape = RoundedCornerShape(12.dp),
              colors = ButtonDefaults.buttonColors(
                containerColor = mobileAccent,
                contentColor = Color.White,
              ),
            ) {
              Text("Configure", style = mobileCallout.copy(fontWeight = FontWeight.SemiBold))
            }
          } else if (expanded || entry.isConfigured) {
            OutlinedTextField(
              value = apiKeyInput,
              onValueChange = { apiKeyInput = it },
              modifier = Modifier.fillMaxWidth(),
              placeholder = { Text("API Key", style = mobileCallout, color = mobileTextTertiary) },
              visualTransformation = PasswordVisualTransformation(),
              singleLine = true,
              textStyle = mobileCallout.copy(color = mobileText),
              shape = RoundedCornerShape(12.dp),
              colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = mobileSurface,
                unfocusedContainerColor = mobileSurface,
                focusedBorderColor = mobileAccent,
                unfocusedBorderColor = mobileBorder,
                cursorColor = mobileAccent,
              ),
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              Button(
                onClick = {
                  val key = apiKeyInput.trim()
                  if (key.isEmpty()) return@Button
                  isValidating = true
                  scope.launch {
                    val valid = validateApiKey(entry.id, key)
                    isValidating = false
                    if (valid) {
                      authManager.saveCredential(entry.id, Credential.ApiKey(key))
                      apiKeyInput = ""
                      expanded = false
                      onChanged()
                      Toast.makeText(context, "${entry.displayName} configured!", Toast.LENGTH_SHORT).show()
                    } else {
                      Toast.makeText(context, "Invalid API key", Toast.LENGTH_SHORT).show()
                    }
                  }
                },
                enabled = apiKeyInput.trim().isNotEmpty() && !isValidating,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                  containerColor = mobileAccent,
                  contentColor = Color.White,
                ),
              ) {
                if (isValidating) {
                  CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = Color.White)
                  Spacer(modifier = Modifier.width(6.dp))
                }
                Text("Save & Validate", style = mobileCallout.copy(fontWeight = FontWeight.SemiBold))
              }

              if (entry.isConfigured) {
                Button(
                  onClick = {
                    authManager.deleteCredential(entry.id)
                    apiKeyInput = ""
                    onChanged()
                    Toast.makeText(context, "${entry.displayName} removed", Toast.LENGTH_SHORT).show()
                  },
                  shape = RoundedCornerShape(12.dp),
                  colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Transparent,
                    contentColor = mobileDanger,
                  ),
                  border = BorderStroke(1.dp, mobileDanger),
                ) {
                  Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(14.dp))
                  Spacer(modifier = Modifier.width(4.dp))
                  Text("Remove", style = mobileCallout.copy(fontWeight = FontWeight.SemiBold))
                }
              }
            }
          }
        }

        ProviderAuthType.OAUTH -> {
          GhcOAuthSection(
            entry = entry,
            authManager = authManager,
            onChanged = onChanged,
          )
        }
      }
    }
  }
}

@Composable
private fun GhcOAuthSection(
  entry: ProviderEntry,
  authManager: AuthManager,
  onChanged: () -> Unit,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val oauthStateFlow = remember { MutableStateFlow<GhcOAuthState?>(null) }
  val oauthState by oauthStateFlow.collectAsState()

  if (entry.isConfigured) {
    Row(
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Text("Connected", style = mobileCallout, color = mobileSuccess)
      Spacer(modifier = Modifier.weight(1f))
      Button(
        onClick = {
          authManager.deleteCredential(entry.id)
          onChanged()
          Toast.makeText(context, "${entry.displayName} removed", Toast.LENGTH_SHORT).show()
        },
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
          containerColor = Color.Transparent,
          contentColor = mobileDanger,
        ),
        border = BorderStroke(1.dp, mobileDanger),
      ) {
        Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(14.dp))
        Spacer(modifier = Modifier.width(4.dp))
        Text("Disconnect", style = mobileCallout.copy(fontWeight = FontWeight.SemiBold))
      }
    }
    return
  }

  when (val state = oauthState) {
    null -> {
      Button(
        onClick = {
          scope.launch {
            GhcOAuthFlow().start().collect { newState ->
              oauthStateFlow.value = newState
              if (newState is GhcOAuthState.Authorized) {
                authManager.saveCredential(
                  "ghc",
                  Credential.OAuthToken(
                    accessToken = newState.accessToken,
                    refreshToken = null,
                    expiresAtMs = 0L,
                  ),
                )
                onChanged()
              }
            }
          }
        },
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
          containerColor = mobileAccent,
          contentColor = Color.White,
        ),
      ) {
        Text("Connect with GitHub", style = mobileCallout.copy(fontWeight = FontWeight.SemiBold))
      }
    }

    is GhcOAuthState.PendingUserAuth -> {
      Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        Text(
          text = "Enter this code on GitHub:",
          style = mobileCallout,
          color = mobileTextSecondary,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Surface(
          shape = RoundedCornerShape(8.dp),
          color = mobileSurface,
          border = BorderStroke(1.dp, mobileAccent),
        ) {
          Text(
            text = state.userCode,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
            style = mobileHeadline.copy(
              fontWeight = FontWeight.Bold,
              fontSize = 24.sp,
              letterSpacing = 4.sp,
            ),
            color = mobileAccent,
            textAlign = TextAlign.Center,
          )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Button(
            onClick = {
              val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
              clipboard.setPrimaryClip(ClipData.newPlainText("GitHub Code", state.userCode))
              Toast.makeText(context, "Code copied!", Toast.LENGTH_SHORT).show()
            },
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
              containerColor = mobileAccentSoft,
              contentColor = mobileAccent,
            ),
          ) {
            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text("Copy", style = mobileCallout.copy(fontWeight = FontWeight.SemiBold))
          }
          Button(
            onClick = {
              context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(state.verificationUri)))
            },
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
              containerColor = mobileAccent,
              contentColor = Color.White,
            ),
          ) {
            Text("Open GitHub", style = mobileCallout.copy(fontWeight = FontWeight.SemiBold))
          }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
          CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = mobileAccent)
          Spacer(modifier = Modifier.width(8.dp))
          Text("Waiting for authorization…", style = mobileCaption1, color = mobileTextTertiary)
        }
      }
    }

    is GhcOAuthState.Polling -> {
      Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = mobileAccent)
        Spacer(modifier = Modifier.width(8.dp))
        Text("Checking authorization…", style = mobileCallout, color = mobileTextSecondary)
      }
    }

    is GhcOAuthState.Authorized -> {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(20.dp), tint = mobileSuccess)
        Spacer(modifier = Modifier.width(8.dp))
        Text("Connected!", style = mobileCallout.copy(fontWeight = FontWeight.SemiBold), color = mobileSuccess)
      }
    }

    is GhcOAuthState.Failed -> {
      Column {
        Text(
          text = state.message,
          style = mobileCaption1,
          color = mobileDanger,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Button(
          onClick = { oauthStateFlow.value = null },
          shape = RoundedCornerShape(12.dp),
          colors = ButtonDefaults.buttonColors(
            containerColor = mobileAccent,
            contentColor = Color.White,
          ),
        ) {
          Text("Try Again", style = mobileCallout.copy(fontWeight = FontWeight.SemiBold))
        }
      }
    }
  }
}

private suspend fun validateApiKey(providerId: String, key: String): Boolean {
  return when (providerId) {
    "google" -> GoogleProvider(key).validateCredentials()
    "anthropic" -> AnthropicProvider(key).validateCredentials()
    "openai" -> OpenAiProvider(key).validateCredentials()
    "openrouter" -> OpenRouterProvider(key).validateCredentials()
    else -> false
  }
}
