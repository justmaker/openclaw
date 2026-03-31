package ai.openclaw.app.standalone

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Tracks which mode the user chose: gateway, standalone, or unset.
 * Uses plain SharedPreferences (no encryption needed — just a mode flag).
 */
class AppModeStore(context: Context) {

  private val prefs =
    context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

  private val _mode = MutableStateFlow(
    AppMode.fromString(prefs.getString(KEY_MODE, null)),
  )
  val mode: StateFlow<AppMode> = _mode

  fun setMode(value: AppMode) {
    prefs.edit { putString(KEY_MODE, value.key) }
    _mode.value = value
  }

  fun reset() {
    prefs.edit { remove(KEY_MODE) }
    _mode.value = AppMode.UNSET
  }

  companion object {
    private const val PREFS_NAME = "openclaw.app_mode"
    private const val KEY_MODE = "selected_mode"
  }
}

enum class AppMode(val key: String) {
  UNSET("unset"),
  GATEWAY("gateway"),
  STANDALONE("standalone"),
  ;

  companion object {
    fun fromString(value: String?): AppMode =
      entries.firstOrNull { it.key == value } ?: UNSET
  }
}
