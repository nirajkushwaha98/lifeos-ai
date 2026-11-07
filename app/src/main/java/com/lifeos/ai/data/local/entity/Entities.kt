package com.lifeos.ai.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/*
 * All user-owned rows carry `userId` (Firebase uid, or [LOCAL_USER_ID] for guest/offline use)
 * and an `isSynced` flag used by the Firestore sync worker. IDs are client-generated UUIDs,
 * so the same id is used as the Firestore document id - syncing is idempotent and cannot
 * create duplicates.
 */
const val LOCAL_USER_ID = "local"

@Entity(tableName = "users")
data class UserEntity(
    @PrimaryKey val userId: String,
    val name: String,
    val email: String?,
    val profileImage: String?,
    val createdAt: Long,
)

@Entity(
    tableName = "tasks",
    indices = [Index("userId"), Index("userId", "completed"), Index("dueAt")],
)
data class TaskEntity(
    @PrimaryKey val taskId: String,
    val userId: String,
    val title: String,
    val description: String,
    val priority: String,
    val category: String,
    val dueAt: Long?,
    val hasDueTime: Boolean,
    val completed: Boolean,
    val completedAt: Long?,
    val createdAt: Long,
    val updatedAt: Long,
    val isSynced: Boolean = false,
)

@Entity(
    tableName = "reminders",
    indices = [Index("userId"), Index("scheduledTime")],
)
data class ReminderEntity(
    @PrimaryKey val reminderId: String,
    val userId: String,
    val title: String,
    val description: String,
    val scheduledTime: Long,
    val repeatType: String,
    val repeatIntervalDays: Int,
    val enabled: Boolean,
    val completed: Boolean,
    val lastTriggeredAt: Long?,
    val createdAt: Long,
    val updatedAt: Long,
    val isSynced: Boolean = false,
)

@Entity(
    tableName = "expenses",
    indices = [Index("userId"), Index("userId", "date"), Index("category")],
)
data class ExpenseEntity(
    @PrimaryKey val expenseId: String,
    val userId: String,
    /** Amount in minor units (paise). */
    val amount: Long,
    /** INCOME or EXPENSE */
    val type: String,
    val category: String,
    val paymentMethod: String,
    val description: String,
    val date: Long,
    val createdAt: Long,
    val updatedAt: Long,
    val isSynced: Boolean = false,
)

@Entity(
    tableName = "categories",
    indices = [Index(value = ["userId", "name"], unique = true)],
)
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val userId: String,
    val name: String,
)

@Entity(tableName = "budgets", primaryKeys = ["userId", "yearMonth"])
data class BudgetEntity(
    val userId: String,
    /** ISO year-month, e.g. 2026-11 */
    val yearMonth: String,
    val amount: Long,
)

@Entity(
    tableName = "notes",
    indices = [Index("userId"), Index("userId", "isArchived")],
)
data class NoteEntity(
    @PrimaryKey val noteId: String,
    val userId: String,
    val title: String,
    val content: String,
    val category: String,
    val isPinned: Boolean,
    val isArchived: Boolean,
    val isChecklist: Boolean,
    /** JSON array of checklist items. */
    val checklistJson: String,
    val createdAt: Long,
    val updatedAt: Long,
    val isSynced: Boolean = false,
)

@Entity(tableName = "documents", indices = [Index("userId")])
data class DocumentEntity(
    @PrimaryKey val documentId: String,
    val userId: String,
    val name: String,
    /** File name inside app-private storage (filesDir/documents). Never a public URI. */
    val fileUri: String,
    val fileType: String,
    val sizeBytes: Long,
    val folder: String,
    val expiryDate: Long?,
    val expiryReminderId: String?,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(tableName = "ai_conversations", indices = [Index("userId")])
data class AIConversationEntity(
    @PrimaryKey val conversationId: String,
    val userId: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "ai_messages",
    foreignKeys = [
        ForeignKey(
            entity = AIConversationEntity::class,
            parentColumns = ["conversationId"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("conversationId"), Index("timestamp")],
)
data class AIMessageEntity(
    @PrimaryKey val messageId: String,
    val conversationId: String,
    /** user | assistant */
    val role: String,
    val content: String,
    val timestamp: Long,
    val isError: Boolean,
)

/** Log of actions the AI/voice assistant executed after user confirmation. */
@Entity(tableName = "ai_activity", indices = [Index("userId"), Index("timestamp")])
data class AIActivityEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val action: String,
    val detail: String,
    val timestamp: Long,
)

/** Pending remote deletions for records deleted locally while signed in. */
@Entity(tableName = "sync_tombstones", primaryKeys = ["collection", "recordId"])
data class TombstoneEntity(
    val collection: String,
    val recordId: String,
    val userId: String,
    val deletedAt: Long,
)
