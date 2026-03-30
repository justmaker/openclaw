package ai.openclaw.app.standalone.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "conversations")
data class ConversationEntity(
  @PrimaryKey val id: String,
  val title: String?,
  val providerId: String,
  val model: String,
  val createdAt: Long,
  val updatedAt: Long,
)
