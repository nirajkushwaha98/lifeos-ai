package com.lifeos.ai.data.repository

import com.lifeos.ai.core.common.newId
import com.lifeos.ai.data.local.database.LifeOSDatabase
import com.lifeos.ai.data.local.entity.AIActivityEntity
import com.lifeos.ai.data.local.entity.AIConversationEntity
import com.lifeos.ai.data.local.entity.BudgetEntity
import com.lifeos.ai.data.local.entity.CategoryEntity
import com.lifeos.ai.data.local.entity.LOCAL_USER_ID
import com.lifeos.ai.data.local.entity.TombstoneEntity
import com.lifeos.ai.data.local.files.PrivateFileStore
import com.lifeos.ai.data.local.toDomain
import com.lifeos.ai.data.local.toEntity
import com.lifeos.ai.data.sync.SyncScheduler
import com.lifeos.ai.domain.model.ChatMessage
import com.lifeos.ai.domain.model.Conversation
import com.lifeos.ai.domain.model.Document
import com.lifeos.ai.domain.model.Note
import com.lifeos.ai.domain.model.Reminder
import com.lifeos.ai.domain.model.Task
import com.lifeos.ai.domain.model.Transaction
import com.lifeos.ai.domain.repository.ConversationRepository
import com.lifeos.ai.domain.repository.DocumentRepository
import com.lifeos.ai.domain.repository.NoteRepository
import com.lifeos.ai.domain.repository.ReminderRepository
import com.lifeos.ai.domain.repository.TaskRepository
import com.lifeos.ai.domain.repository.TransactionRepository
import com.lifeos.ai.feature.reminders.alarm.ReminderScheduler
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import java.time.YearMonth
import javax.inject.Inject
import javax.inject.Singleton

class InvalidDataException(message: String) : IllegalArgumentException(message)

/** Shared helpers for user scoping, sync triggering and delete tombstones. */
@Singleton
class RepoSupport @Inject constructor(
    val session: SessionManager,
    private val db: LifeOSDatabase,
    private val syncScheduler: SyncScheduler,
) {
    fun userId(): String = session.currentUserId()
    fun changed() = syncScheduler.requestSync()
    suspend fun tombstone(collection: String, id: String) {
        val uid = userId()
        if (uid != LOCAL_USER_ID) db.syncDao().addTombstone(TombstoneEntity(collection, id, uid, System.currentTimeMillis()))
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class TaskRepositoryImpl @Inject constructor(
    private val db: LifeOSDatabase,
    private val support: RepoSupport,
) : TaskRepository {
    private val dao get() = db.taskDao()

    override fun observeTasks(): Flow<List<Task>> =
        support.session.userId.flatMapLatest { dao.observeAll(it) }.map { list -> list.map { it.toDomain() } }

    override suspend fun getTask(id: String): Task? = dao.get(id)?.takeIf { it.userId == support.userId() }?.toDomain()

    override suspend fun upsert(task: Task): Task {
        val title = task.title.trim()
        if (title.isEmpty()) throw InvalidDataException("Task title is required")
        if (title.length > 200) throw InvalidDataException("Task title too long")
        val now = System.currentTimeMillis()
        val toSave = task.copy(title = title, description = task.description.trim().take(5000), updatedAt = now)
        dao.upsert(toSave.toEntity(support.userId()))
        support.changed()
        return dao.get(task.id)?.toDomain() ?: error("Task was not saved")
    }

    override suspend fun setCompleted(id: String, completed: Boolean) {
        val now = System.currentTimeMillis()
        dao.setCompleted(id, completed, if (completed) now else null, now)
        support.changed()
    }

    override suspend fun delete(id: String) {
        if (dao.delete(id) > 0) {
            support.tombstone(SyncCollections.TASKS, id)
            support.changed()
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class ReminderRepositoryImpl @Inject constructor(
    private val db: LifeOSDatabase,
    private val support: RepoSupport,
    private val scheduler: ReminderScheduler,
) : ReminderRepository {
    private val dao get() = db.reminderDao()

    override fun observeReminders(): Flow<List<Reminder>> =
        support.session.userId.flatMapLatest { dao.observeAll(it) }.map { list -> list.map { it.toDomain() } }

    override suspend fun getReminder(id: String): Reminder? =
        dao.get(id)?.takeIf { it.userId == support.userId() }?.toDomain()

    override suspend fun upsert(reminder: Reminder): Reminder {
        val title = reminder.title.trim()
        if (title.isEmpty()) throw InvalidDataException("Reminder title is required")
        if (reminder.repeatIntervalDays !in 1..365) throw InvalidDataException("Invalid repeat interval")
        val toSave = reminder.copy(
            title = title.take(200),
            description = reminder.description.trim().take(2000),
            updatedAt = System.currentTimeMillis(),
        )
        dao.upsert(toSave.toEntity(support.userId()))
        val saved = dao.get(reminder.id)?.toDomain() ?: error("Reminder was not saved")
        scheduler.schedule(saved)
        support.changed()
        return saved
    }

    override suspend fun setCompleted(id: String, completed: Boolean) {
        val r = getReminder(id) ?: return
        upsert(r.copy(completed = completed))
    }

    override suspend fun setEnabled(id: String, enabled: Boolean) {
        val r = getReminder(id) ?: return
        upsert(r.copy(enabled = enabled))
    }

    override suspend fun delete(id: String) {
        scheduler.cancel(id)
        if (dao.delete(id) > 0) {
            support.tombstone(SyncCollections.REMINDERS, id)
            support.changed()
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class TransactionRepositoryImpl @Inject constructor(
    private val db: LifeOSDatabase,
    private val support: RepoSupport,
) : TransactionRepository {
    private val dao get() = db.expenseDao()
    private val categories get() = db.categoryDao()

    override fun observeTransactions(): Flow<List<Transaction>> =
        support.session.userId.flatMapLatest { dao.observeAll(it) }.map { list -> list.map { it.toDomain() } }

    override fun observeTransactionsBetween(start: Long, end: Long): Flow<List<Transaction>> =
        support.session.userId.flatMapLatest { dao.observeBetween(it, start, end) }
            .map { list -> list.map { it.toDomain() } }

    override suspend fun getTransaction(id: String): Transaction? =
        dao.get(id)?.takeIf { it.userId == support.userId() }?.toDomain()

    override suspend fun upsert(transaction: Transaction): Transaction {
        if (transaction.amountMinor <= 0) throw InvalidDataException("Amount must be positive")
        if (transaction.amountMinor > 10_000_000_000L) throw InvalidDataException("Amount too large")
        if (transaction.category.isBlank()) throw InvalidDataException("Category is required")
        val toSave = transaction.copy(
            description = transaction.description.trim().take(500),
            updatedAt = System.currentTimeMillis(),
        )
        dao.upsert(toSave.toEntity(support.userId()))
        support.changed()
        return dao.get(transaction.id)?.toDomain() ?: error("Transaction was not saved")
    }

    override suspend fun delete(id: String) {
        if (dao.delete(id) > 0) {
            support.tombstone(SyncCollections.EXPENSES, id)
            support.changed()
        }
    }

    override fun observeCustomCategories(): Flow<List<String>> =
        support.session.userId.flatMapLatest { categories.observe(it) }

    override suspend fun addCustomCategory(name: String) {
        val clean = name.trim().take(30)
        if (clean.isEmpty()) throw InvalidDataException("Category name is required")
        categories.insert(CategoryEntity(userId = support.userId(), name = clean))
    }

    override suspend fun deleteCustomCategory(name: String) = categories.delete(support.userId(), name)

    override fun observeBudget(month: YearMonth): Flow<Long?> =
        support.session.userId.flatMapLatest { categories.observeBudget(it, month.toString()) }
            .map { it?.amount }

    override suspend fun setBudget(month: YearMonth, amountMinor: Long?) {
        if (amountMinor == null || amountMinor <= 0) categories.deleteBudget(support.userId(), month.toString())
        else categories.upsertBudget(BudgetEntity(support.userId(), month.toString(), amountMinor))
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class NoteRepositoryImpl @Inject constructor(
    private val db: LifeOSDatabase,
    private val support: RepoSupport,
) : NoteRepository {
    private val dao get() = db.noteDao()

    override fun observeNotes(): Flow<List<Note>> =
        support.session.userId.flatMapLatest { dao.observeAll(it) }.map { list -> list.map { it.toDomain() } }

    override suspend fun getNote(id: String): Note? = dao.get(id)?.takeIf { it.userId == support.userId() }?.toDomain()

    override suspend fun findByTitle(title: String): Note? = dao.findByTitle(support.userId(), title)?.toDomain()

    override suspend fun upsert(note: Note): Note {
        if (note.title.isBlank() && note.content.isBlank() && note.checklist.isEmpty()) {
            throw InvalidDataException("Note is empty")
        }
        val toSave = note.copy(
            title = note.title.trim().take(200),
            checklist = note.checklist.filter { it.text.isNotBlank() },
            updatedAt = System.currentTimeMillis(),
        )
        dao.upsert(toSave.toEntity(support.userId()))
        support.changed()
        return dao.get(note.id)?.toDomain() ?: error("Note was not saved")
    }

    override suspend fun delete(id: String) {
        if (dao.delete(id) > 0) {
            support.tombstone(SyncCollections.NOTES, id)
            support.changed()
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class DocumentRepositoryImpl @Inject constructor(
    private val db: LifeOSDatabase,
    private val support: RepoSupport,
    private val files: PrivateFileStore,
    private val reminders: ReminderRepository,
) : DocumentRepository {
    private val dao get() = db.documentDao()

    override fun observeDocuments(): Flow<List<Document>> =
        support.session.userId.flatMapLatest { dao.observeAll(it) }.map { list -> list.map { it.toDomain() } }

    override suspend fun getDocument(id: String): Document? =
        dao.get(id)?.takeIf { it.userId == support.userId() }?.toDomain()

    override suspend fun upsert(document: Document): Document {
        val name = document.name.trim()
        if (name.isEmpty()) throw InvalidDataException("Name is required")
        dao.upsert(document.copy(name = name.take(120), updatedAt = System.currentTimeMillis()).toEntity(support.userId()))
        return dao.get(document.id)?.toDomain() ?: error("Document was not saved")
    }

    override suspend fun delete(id: String) {
        val doc = dao.get(id) ?: return
        doc.expiryReminderId?.let { reminders.delete(it) }
        dao.delete(id)
        files.deleteDocument(doc.fileUri)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class ConversationRepositoryImpl @Inject constructor(
    private val db: LifeOSDatabase,
    private val support: RepoSupport,
) : ConversationRepository {
    private val dao get() = db.conversationDao()

    override fun observeConversations(): Flow<List<Conversation>> =
        support.session.userId.flatMapLatest { dao.observeAll(it) }.map { list -> list.map { it.toDomain() } }

    override fun observeMessages(conversationId: String): Flow<List<ChatMessage>> =
        dao.observeMessages(conversationId).map { list -> list.map { it.toDomain() } }

    override suspend fun createConversation(title: String): Conversation {
        val now = System.currentTimeMillis()
        val entity = AIConversationEntity(newId(), support.userId(), title.take(60), now, now)
        dao.insert(entity)
        return entity.toDomain()
    }

    override suspend fun addMessage(message: ChatMessage) {
        dao.insertMessage(message.toEntity())
        dao.touch(message.conversationId, System.currentTimeMillis())
    }

    override suspend fun deleteMessage(id: String) = dao.deleteMessage(id)
    override suspend fun clearMessages(conversationId: String) = dao.clearMessages(conversationId)
    override suspend fun deleteConversation(id: String) = dao.delete(id)
    override suspend fun renameConversation(id: String, title: String) =
        dao.rename(id, title.take(60), System.currentTimeMillis())

    override suspend fun recentMessages(conversationId: String, limit: Int): List<ChatMessage> =
        dao.recentMessages(conversationId, limit).map { it.toDomain() }
}

/** Records confirmed assistant actions for the Activity screen. */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class AiActivityRepository @Inject constructor(
    private val db: LifeOSDatabase,
    private val support: RepoSupport,
) {
    fun observe(): Flow<List<AIActivityEntity>> =
        support.session.userId.flatMapLatest { db.conversationDao().observeActivity(it) }

    suspend fun log(action: String, detail: String) {
        db.conversationDao().insertActivity(
            AIActivityEntity(newId(), support.userId(), action, detail.take(300), System.currentTimeMillis())
        )
    }
}
