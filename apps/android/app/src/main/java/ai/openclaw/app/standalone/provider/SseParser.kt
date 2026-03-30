package ai.openclaw.app.standalone.provider

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.Response

data class SseEvent(
  val event: String?,
  val data: String,
)

/** Parse an OkHttp streaming response as Server-Sent Events. */
fun Response.parseSseEvents(): Flow<SseEvent> = flow {
  val stream = body?.byteStream() ?: return@flow
  val reader = stream.bufferedReader()
  try {
    var currentEvent: String? = null
    val dataLines = mutableListOf<String>()
    while (true) {
      val line = reader.readLine() ?: break
      when {
        line.startsWith("event:") -> {
          currentEvent = line.removePrefix("event:").trim()
        }
        line.startsWith("data:") -> {
          dataLines.add(line.removePrefix("data:").trimStart())
        }
        line.isBlank() && dataLines.isNotEmpty() -> {
          emit(SseEvent(currentEvent, dataLines.joinToString("\n")))
          currentEvent = null
          dataLines.clear()
        }
      }
    }
    if (dataLines.isNotEmpty()) {
      emit(SseEvent(currentEvent, dataLines.joinToString("\n")))
    }
  } finally {
    reader.close()
  }
}.flowOn(Dispatchers.IO)
