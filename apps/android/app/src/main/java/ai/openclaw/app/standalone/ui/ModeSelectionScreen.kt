package ai.openclaw.app.standalone.ui

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ai.openclaw.app.ui.mobileAccent
import ai.openclaw.app.ui.mobileAccentBorderStrong
import ai.openclaw.app.ui.mobileAccentSoft
import ai.openclaw.app.ui.mobileBorderStrong
import ai.openclaw.app.ui.mobileCallout
import ai.openclaw.app.ui.mobileCardSurface
import ai.openclaw.app.ui.mobileDisplay
import ai.openclaw.app.ui.mobileHeadline
import ai.openclaw.app.ui.mobileText
import ai.openclaw.app.ui.mobileTextSecondary

@Composable
fun ModeSelectionScreen(
  onSelectGateway: () -> Unit,
  onSelectStandalone: () -> Unit,
) {
  Column(
    modifier = Modifier
      .fillMaxSize()
      .padding(horizontal = 24.dp, vertical = 48.dp),
    verticalArrangement = Arrangement.Center,
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Text(
      text = "OpenClaw",
      style = mobileDisplay,
      color = mobileText,
    )
    Spacer(modifier = Modifier.height(8.dp))
    Text(
      text = "Choose how to connect",
      style = mobileCallout,
      color = mobileTextSecondary,
    )
    Spacer(modifier = Modifier.height(40.dp))

    ModeCard(
      icon = Icons.Default.Cloud,
      title = "Connect to Gateway",
      description = "Use your OpenClaw gateway for full feature access including all channels and tools.",
      onClick = onSelectGateway,
    )

    Spacer(modifier = Modifier.height(16.dp))

    ModeCard(
      icon = Icons.Default.PhoneAndroid,
      title = "Standalone Agent",
      description = "Run an AI agent directly on your device with your own API keys. No gateway required.",
      accentBorder = true,
      onClick = onSelectStandalone,
    )
  }
}

@Composable
private fun ModeCard(
  icon: ImageVector,
  title: String,
  description: String,
  accentBorder: Boolean = false,
  onClick: () -> Unit,
) {
  Surface(
    onClick = onClick,
    shape = RoundedCornerShape(20.dp),
    color = if (accentBorder) mobileAccentSoft else mobileCardSurface,
    border = BorderStroke(
      width = if (accentBorder) 2.dp else 1.dp,
      color = if (accentBorder) mobileAccentBorderStrong else mobileBorderStrong,
    ),
    tonalElevation = 0.dp,
    shadowElevation = 0.dp,
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(20.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Icon(
        imageVector = icon,
        contentDescription = null,
        modifier = Modifier.size(36.dp),
        tint = if (accentBorder) mobileAccent else mobileTextSecondary,
      )
      Spacer(modifier = Modifier.width(16.dp))
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = title,
          style = mobileHeadline.copy(fontWeight = FontWeight.Bold),
          color = mobileText,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
          text = description,
          style = mobileCallout,
          color = mobileTextSecondary,
        )
      }
    }
  }
}
