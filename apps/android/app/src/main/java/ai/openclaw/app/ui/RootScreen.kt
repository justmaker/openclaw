package ai.openclaw.app.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import ai.openclaw.app.MainViewModel
import ai.openclaw.app.standalone.AppMode
import ai.openclaw.app.standalone.AppModeStore
import ai.openclaw.app.standalone.ui.ModeSelectionScreen
import ai.openclaw.app.standalone.ui.StandaloneRootScreen

@Composable
fun RootScreen(viewModel: MainViewModel) {
  val context = LocalContext.current
  val modeStore = remember { AppModeStore(context) }
  val appMode by modeStore.mode.collectAsState()

  when (appMode) {
    AppMode.UNSET -> {
      ModeSelectionScreen(
        onSelectGateway = { modeStore.setMode(AppMode.GATEWAY) },
        onSelectStandalone = { modeStore.setMode(AppMode.STANDALONE) },
      )
    }
    AppMode.GATEWAY -> {
      val onboardingCompleted by viewModel.onboardingCompleted.collectAsState()
      if (!onboardingCompleted) {
        OnboardingFlow(viewModel = viewModel, modifier = Modifier.fillMaxSize())
      } else {
        PostOnboardingTabs(viewModel = viewModel, modifier = Modifier.fillMaxSize())
      }
    }
    AppMode.STANDALONE -> {
      StandaloneRootScreen(
        onBackToModeSelection = { modeStore.reset() },
      )
    }
  }
}
