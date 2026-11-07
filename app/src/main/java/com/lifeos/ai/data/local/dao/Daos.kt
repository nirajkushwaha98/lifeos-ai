package com.lifeos.ai.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.lifeos.ai.data.local.entity.AIActivityEntity
import com.lifeos.ai.data.local.entity.AIConversationEntity
import com.lifeos.ai.data.local.entity.AIMessageEntity
import com.lifeos.ai.data.local.entity.BudgetEntity
import com.lifeos.ai.data.local.entity.CategoryEntity
import com.lifeos.ai.data.local.entity.DocumentEntity
import com.lifeos.ai.data.local.entity.ExpenseEntity
import com.lifeos.ai.data.local.entity.NoteEntity
import com.lifeos.ai.data.local.entity.ReminderEntity
import com.lifeos.ai.data.local.entity.TaskEntity
import com.lifeos.ai.data.local.entity.TombstoneEntity
import com.lifeos.ai.data.local.entity.UserEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface UserDao {
    @Query("SELECT * FROM users WHERE userId = :userId")
    fun observe(userId: String): Flow<UserEntity?>

    @Query("SELECT * FROM users WHERE userId = :userId")
    suspend fun get(userId: String): UserEntity?

    @Upsert
    suspend fun upsert(user: UserEntity)

    @Query("DELETE FROM users WHERE userId = :userId")
    suspend fun delete(userId: String)
}

@Dao
interface TaskDao {
    @Query("SELECT * FROM tasks WHERE userId = :userId ORDER BY completed ASC, CASE WHEN dueAt IS NULL THEN 1 ELSE 0 END, dueAt ASC, createdAt DESC")
    fun observeAll(userId: String): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE taskId = :id")
    suspend fun get(id: String): TaskEntity?

    @Upsert
    suspend fun upsert(task: TaskEntity)

    @Query("UPDATE tasks SET completed = :completed, completedAt = :completedAt, updatedAt = :now, isSynced = 0 WHERE taskId = :id")
    suspend fun setCompleted(id: String, completed: Boolean, completedAt: Long?, now: Long)

    @Query("DELETE FROM tasks WHERE taskId = :id")
    suspend fun delete(id: String): Int

    @Query("SELECT * FROM tasks WHERE userId = :userId AND isSynced = 0")
    suspend fun unsynced(userId: String): List<TaskEntity>

    @Query("UPDATE tasks SET isSynced = 1 WHERE taskId = :id AND updatedAt = :updatedAt")
    suspend fun markSynced(id: String, updatedAt: Long)

    @Query("SELECT * FROM tasks WHERE userId = :userId")
    suspend fun all(userId: String): List<TaskEntity>
}

@Dao
interface ReminderDao {
    @Query("SELECT * FROM reminders WHERE userId = :userId ORDER BY completed ASC, scheduledTime ASC")
    fun observeAll(userId: String): Flow<List<ReminderEntity>>

    @Query("SELECT * FROM reminders WHERE reminderId = :id")
    suspend fun get(id: String): ReminderEntity?

    @Query("SELECT * FROM reminders WHERE enabled = 1 AND completed = 0")
    suspend fun allActive(): List<ReminderEntity>

    @Upsert
    suspend fun upsert(reminder: ReminderEntity)

    @Query("DELETE FROM reminders WHERE reminderId = :id")
    suspend fun delete(id: String): Int

    @Query("SELECT * FROM reminders WHERE userId = :userId AND isSynced = 0")
    suspend fun unsynced(userId: String): List<ReminderEntity>

    @Query("UPDATE reminders SET isSynced = 1 WHERE reminderId = :id AND updatedAt = :updatedAt")
    suspend fun markSynced(id: String, updatedAt: Long)

    @Query("SELECT * FROM reminders WHERE userId = :userId")
    suspend fun all(userId: String): List<ReminderEntity>
}

@Dao
interface ExpenseDao {
    @Query("SELECT * FROM expenses WHERE userId = :userId ORDER BY date DESC, createdAt DESC")
    fun observeAll(userId: String): Flow<List<ExpenseEntity>>

    @Query("SELECT * FROM expenses WHERE userId = :userId AND date BETWEEN :start AND :end ORDER BY date DESC")
    fun observeBetween(userId: String, start: Long, end: Long): Flow<List<ExpenseEntity>>

    @Query("SELECT * FROM expenses WHERE userId = :userId AND date BETWEEN :start AND :end")
    suspend fun between(userId: String, start: Long, end: Long): List<ExpenseEntity>

    @Query("SELECT COALESCE(SUM(amount), 0) FROM expenses WHERE userId = :userId AND type = :type AND date BETWEEN :start AND :end")
    suspend fun sumBetween(userId: String, type: String, start: Long, end: Long): Long

    @Query("SELECT * FROM expenses WHERE expenseId = :id")
    suspend fun get(id: String): ExpenseEntity?

    @Upsert
    suspend fun upsert(expense: ExpenseEntity)

    @Query("DELETE FROM expenses WHERE expenseId = :id")
    suspend fun delete(id: String): Int

    @Query("SELECT * FROM expenses WHERE userId = :userId AND isSynced = 0")
    suspend fun unsynced(userId: String): List<ExpenseEntity>

    @Query("UPDATE expenses SET isSynced = 1 WHERE expenseId = :id AND updatedAt = :updatedAt")
    suspend fun markSynced(id: String, updatedAt: Long)

    @Query("SELECT * FROM expenses WHERE userId = :userId")
    suspend fun all(userId: String): List<ExpenseEntity>
}

@Dao
interface CategoryDao {
    @Query("SELECT name FROM categories WHERE userId = :userId ORDER BY name COLLATE NOCASE")
    fun observe(userId: String): Flow<List<String>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(category: CategoryEntity)

    @Query("DELETE FROM categories WHERE userId = :userId AND name = :name")
    suspend fun delete(userId: String, name: String)

    @Query("SELECT * FROM budgets WHERE userId = :userId AND yearMonth = :yearMonth")
    fun observeBudget(userId: String, yearMonth: String): Flow<BudgetEntity?>

    @Upsert
    suspend fun upsertBudget(budget: BudgetEntity)

    @Query("DELETE FROM budgets WHERE userId = :userId AND yearMonth = :yearMonth")
    suspend fun deleteBudget(userId: String, yearMonth: String)
}

@Dao
interface NoteDao {
    @Query("SELECT * FROM notes WHERE userId = :userId ORDER BY isPinned DESC, updatedAt DESC")
    fun observeAll(userId: String): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE noteId = :id")
    suspend fun get(id: String): NoteEntity?

    @Query("SELECT * FROM notes WHERE userId = :userId AND title = :title COLLATE NOCASE AND isArchived = 0 LIMIT 1")
    suspend fun findByTitle(userId: String, title: String): NoteEntity?

    @Query("SELECT * FROM notes WHERE userId = :userId AND (title LIKE '%' || :q || '%' OR content LIKE '%' || :q || '%' OR checklistJson LIKE '%' || :q || '%') ORDER BY isPinned DESC, updatedAt DESC")
    suspend fun search(userId: String, q: String): List<NoteEntity>

    @Upsert
    suspend fun upsert(note: NoteEntity)

    @Query("DELETE FROM notes WHERE noteId = :id")
    suspend fun delete(id: String): Int

    @Query("SELECT * FROM notes WHERE userId = :userId AND isSynced = 0")
    suspend fun unsynced(userId: String): List<NoteEntity>

    @Query("UPDATE notes SET isSynced = 1 WHERE noteId = :id AND updatedAt = :updatedAt")
    suspend fun markSynced(id: String, updatedAt: Long)

    @Query("SELECT * FROM notes WHERE userId = :userId")
    suspend fun all(userId: String): List<NoteEntity>
}

@Dao
interface DocumentDao {
    @Query("SELECT * FROM documents WHERE userId = :userId ORDER BY updatedAt DESC")
    fun observeAll(userId: String): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM documents WHERE documentId = :id")
    suspend fun get(id: String): DocumentEntity?

    @Upsert
    suspend fun upsert(document: DocumentEntity)

    @Query("DELETE FROM documents WHERE documentId = :id")
    suspend fun delete(id: String): Int

    @Query("SELECT * FROM documents WHERE userId = :userId")
    suspend fun all(userId: String): List<DocumentEntity>
}

@Dao
interface ConversationDao {
    @Query("SELECT * FROM ai_conversations WHERE userId = :userId ORDER BY updatedAt DESC")
    fun observeAll(userId: String): Flow<List<AIConversationEntity>>

    @Query("SELECT * FROM ai_conversations WHERE conversationId = :id")
    suspend fun get(id: String): AIConversationEntity?

    @Insert
    suspend fun insert(conversation: AIConversationEntity)

    @Query("UPDATE ai_conversations SET updatedAt = :now WHERE conversationId = :id")
    suspend fun touch(id: String, now: Long)

    @Query("UPDATE ai_conversations SET title = :title, updatedAt = :now WHERE conversationId = :id")
    suspend fun rename(id: String, title: String, now: Long)

    @Query("DELETE FROM ai_conversations WHERE conversationId = :id")
    suspend fun delete(id: String)

    @Query("SELECT * FROM ai_messages WHERE conversationId = :conversationId ORDER BY timestamp ASC")
    fun observeMessages(conversationId: String): Flow<List<AIMessageEntity>>

    @Query("SELECT * FROM (SELECT * FROM ai_messages WHERE conversationId = :conversationId AND isError = 0 ORDER BY timestamp DESC LIMIT :limit) ORDER BY timestamp ASC")
    suspend fun recentMessages(conversationId: String, limit: Int): List<AIMessageEntity>

    @Insert
    suspend fun insertMessage(message: AIMessageEntity)

    @Query("DELETE FROM ai_messages WHERE messageId = :id")
    suspend fun deleteMessage(id: String)

    @Query("DELETE FROM ai_messages WHERE conversationId = :conversationId")
    suspend fun clearMessages(conversationId: String)

    @Insert
    suspend fun insertActivity(activity: AIActivityEntity)

    @Query("SELECT * FROM ai_activity WHERE userId = :userId ORDER BY timestamp DESC LIMIT 200")
    fun observeActivity(userId: String): Flow<List<AIActivityEntity>>

    @Query("SELECT * FROM ai_conversations WHERE userId = :userId")
    suspend fun allConversations(userId: String): List<AIConversationEntity>

    @Query("SELECT m.* FROM ai_messages m JOIN ai_conversations c ON m.conversationId = c.conversationId WHERE c.userId = :userId ORDER BY m.timestamp")
    suspend fun allMessages(userId: String): List<AIMessageEntity>
}

@Dao
interface SyncDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addTombstone(tombstone: TombstoneEntity)

    @Query("SELECT * FROM sync_tombstones WHERE userId = :userId")
    suspend fun tombstones(userId: String): List<TombstoneEntity>

    @Query("DELETE FROM sync_tombstones WHERE collection = :collection AND recordId = :recordId")
    suspend fun removeTombstone(collection: String, recordId: String)

    /** Moves records created while in guest/offline mode to the signed-in account. */
    @Query("UPDATE tasks SET userId = :newUserId, isSynced = 0 WHERE userId = :oldUserId")
    suspend fun reassignTasks(oldUserId: String, newUserId: String)

    @Query("UPDATE reminders SET userId = :newUserId, isSynced = 0 WHERE userId = :oldUserId")
    suspend fun reassignReminders(oldUserId: String, newUserId: String)

    @Query("UPDATE expenses SET userId = :newUserId, isSynced = 0 WHERE userId = :oldUserId")
    suspend fun reassignExpenses(oldUserId: String, newUserId: String)

    @Query("UPDATE notes SET userId = :newUserId, isSynced = 0 WHERE userId = :oldUserId")
    suspend fun reassignNotes(oldUserId: String, newUserId: String)

    @Query("UPDATE documents SET userId = :newUserId WHERE userId = :oldUserId")
    suspend fun reassignDocuments(oldUserId: String, newUserId: String)

    @Query("UPDATE ai_conversations SET userId = :newUserId WHERE userId = :oldUserId")
    suspend fun reassignConversations(oldUserId: String, newUserId: String)

    @Query("UPDATE OR IGNORE categories SET userId = :newUserId WHERE userId = :oldUserId")
    suspend fun reassignCategories(oldUserId: String, newUserId: String)

    @Query("UPDATE OR IGNORE budgets SET userId = :newUserId WHERE userId = :oldUserId")
    suspend fun reassignBudgets(oldUserId: String, newUserId: String)

    @Query("UPDATE ai_activity SET userId = :newUserId WHERE userId = :oldUserId")
    suspend fun reassignActivity(oldUserId: String, newUserId: String)

    @Query("DELETE FROM tasks WHERE userId = :userId")
    suspend fun deleteTasks(userId: String)

    @Query("DELETE FROM reminders WHERE userId = :userId")
    suspend fun deleteReminders(userId: String)

    @Query("DELETE FROM expenses WHERE userId = :userId")
    suspend fun deleteExpenses(userId: String)

    @Query("DELETE FROM notes WHERE userId = :userId")
    suspend fun deleteNotes(userId: String)

    @Query("DELETE FROM documents WHERE userId = :userId")
    suspend fun deleteDocuments(userId: String)

    @Query("DELETE FROM ai_conversations WHERE userId = :userId")
    suspend fun deleteConversations(userId: String)

    @Query("DELETE FROM categories WHERE userId = :userId")
    suspend fun deleteCategories(userId: String)

    @Query("DELETE FROM budgets WHERE userId = :userId")
    suspend fun deleteBudgets(userId: String)

    @Query("DELETE FROM ai_activity WHERE userId = :userId")
    suspend fun deleteActivity(userId: String)

    @Query("DELETE FROM sync_tombstones WHERE userId = :userId")
    suspend fun deleteTombstones(userId: String)
}
