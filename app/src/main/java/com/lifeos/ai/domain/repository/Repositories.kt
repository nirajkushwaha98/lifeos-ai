package com.lifeos.ai.domain.repository

import com.lifeos.ai.domain.model.ChatMessage
import com.lifeos.ai.domain.model.Conversation
import com.lifeos.ai.domain.model.Document
import com.lifeos.ai.domain.model.Note
import com.lifeos.ai.domain.model.Reminder
import com.lifeos.ai.domain.model.Task
import com.lifeos.ai.domain.model.Transaction
import kotlinx.coroutines.flow.Flow
import java.time.YearMonth

interface TaskRepository {
    fun observeTasks(): Flow<List<Task>>
    suspend fun getTask(id: String): Task?
    /** Inserts or updates. Returns the persisted task (as read back from the database). */
    suspend fun upsert(task: Task): Task
    suspend fun setCompleted(id: String, completed: Boolean)
    suspend fun delete(id: String)
}

interface ReminderRepository {
    fun observeReminders(): Flow<List<Reminder>>
    suspend fun getReminder(id: String): Reminder?
    /** Persists and (re)schedules the alarm. Returns the persisted reminder. */
    suspend fun upsert(reminder: Reminder): Reminder
    suspend fun setCompleted(id: String, completed: Boolean)
    suspend fun setEnabled(id: String, enabled: Boolean)
    suspend fun delete(id: String)
}

interface TransactionRepository {
    fun observeTransactions(): Flow<List<Transaction>>
    fun observeTransactionsBetween(start: Long, end: Long): Flow<List<Transaction>>
    suspend fun getTransaction(id: String): Transaction?
    suspend fun upsert(transaction: Transaction): Transaction
    suspend fun delete(id: String)
    fun observeCustomCategories(): Flow<List<String>>
    suspend fun addCustomCategory(name: String)
    suspend fun deleteCustomCategory(name: String)
    fun observeBudget(month: YearMonth): Flow<Long?>
    suspend fun setBudget(month: YearMonth, amountMinor: Long?)
}

interface NoteRepository {
    fun observeNotes(): Flow<List<Note>>
    suspend fun getNote(id: String): Note?
    suspend fun upsert(note: Note): Note
    suspend fun delete(id: String)
    suspend fun findByTitle(title: String): Note?
}

interface DocumentRepository {
    fun observeDocuments(): Flow<List<Document>>
    suspend fun getDocument(id: String): Document?
    suspend fun upsert(document: Document): Document
    suspend fun delete(id: String)
}

interface ConversationRepository {
    fun observeConversations(): Flow<List<Conversation>>
    fun observeMessages(conversationId: String): Flow<List<ChatMessage>>
    suspend fun createConversation(title: String): Conversation
    suspend fun addMessage(message: ChatMessage)
    suspend fun deleteMessage(id: String)
    suspend fun clearMessages(conversationId: String)
    suspend fun deleteConversation(id: String)
    suspend fun renameConversation(id: String, title: String)
    suspend fun recentMessages(conversationId: String, limit: Int): List<ChatMessage>
}
