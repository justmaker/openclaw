package ai.openclaw.app.standalone.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

/** Factory to provide application context to [StandaloneViewModel]. */
class StandaloneViewModelFactory(
  private val appContext: Context,
) : ViewModelProvider.Factory {

  @Suppress("UNCHECKED_CAST")
  override fun <T : ViewModel> create(modelClass: Class<T>): T {
    if (modelClass.isAssignableFrom(StandaloneViewModel::class.java)) {
      return StandaloneViewModel(
        appContext as android.app.Application,
      ) as T
    }
    throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
  }
}
