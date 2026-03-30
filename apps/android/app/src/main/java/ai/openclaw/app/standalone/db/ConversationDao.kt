package ai.openclaw.app.standalone.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {
  @Query("SELECT * FROM conversations ORDER BY updatedAt DESC")
  fun getAll(): Flow<List<ConversationEntity>>

  @Query("SELECT * FROM conversations WHERE id = :id")
  suspend fun getById(id: String): ConversationEntity?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insert(conversation: ConversationEntity)

  @Update
  suspend fun update(conversation: ConversationEntity)

  @Query("DELETE FROM conversations WHERE id = :id")
  suspend fun delete(id: String)
}
