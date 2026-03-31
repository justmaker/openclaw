package ai.openclaw.app.standalone.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import ai.openclaw.app.standalone.auth.AuthManager

private enum class StandaloneNav {
  SETUP,
  CHAT,
}

/**
 * Root navigation for standalone mode.
 * Shows ProviderSetupScreen if no provider is configured,
 * otherwise goes straight to StandaloneChatScreen.
 */
@Composable
fun StandaloneRootScreen(
  onBackToModeSelection: () -> Unit,
) {
  val context = LocalContext.current
  val authManager = remember { AuthManager(context) }
  val hasProviders = remember { authManager.listConfiguredProviders().isNotEmpty() }

  var nav by remember {
    mutableStateOf(if (hasProviders) StandaloneNav.CHAT else StandaloneNav.SETUP)
  }

  val standaloneViewModel: StandaloneViewModel = viewModel(
    factory = StandaloneViewModelFactory(context.applicationContext),
  )

  when (nav) {
    StandaloneNav.SETUP -> {
      ProviderSetupScreen(
        authManager = authManager,
        onBack = {
          // If we have providers, go to chat; otherwise back to mode selection.
          if (authManager.listConfiguredProviders().isNotEmpty()) {
            nav = StandaloneNav.CHAT
          } else {
            onBackToModeSelection()
          }
        },
        onProviderChanged = {
          // Provider was added/removed; if we now have at least one, allow going to chat.
        },
      )
    }
    StandaloneNav.CHAT -> {
      StandaloneChatScreen(
        viewModel = standaloneViewModel,
        onNavigateToProviderSetup = { nav = StandaloneNav.SETUP },
      )
    }
  }
}
