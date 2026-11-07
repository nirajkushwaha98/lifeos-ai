package com.lifeos.ai.data.local

import com.lifeos.ai.data.local.entity.AIConversationEntity
import com.lifeos.ai.data.local.entity.AIMessageEntity
import com.lifeos.ai.data.local.entity.DocumentEntity
import com.lifeos.ai.data.local.entity.ExpenseEntity
import com.lifeos.ai.data.local.entity.NoteEntity
import com.lifeos.ai.data.local.entity.ReminderEntity
import com.lifeos.ai.data.local.entity.TaskEntity
import com.lifeos.ai.domain.model.ChatMessage
import com.lifeos.ai.domain.model.ChecklistItem
import com.lifeos.ai.domain.model.Conversation
import com.lifeos.ai.domain.model.Document
import com.lifeos.ai.domain.model.MessageRole
import com.lifeos.ai.domain.model.Note
import com.lifeos.ai.domain.model.PaymentMethod
import com.lifeos.ai.domain.model.Priority
import com.lifeos.ai.domain.model.Reminder
import com.lifeos.ai.domain.model.RepeatType
import com.lifeos.ai.domain.model.Task
import com.lifeos.ai.domain.model.Transaction
import com.lifeos.ai.domain.model.TransactionType
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

internal val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

private inline fun <reified T : Enum<T>> enumOr(value: String, default: T): T =
    runCatching { enumValueOf<T>(value) }.getOrDefault(default)

fun TaskEntity.toDomain() = Task(
    id = taskId, title = title, description = description,
    priority = enumOr(priority, Priority.MEDIUM), category = category,
    dueAt = dueAt, hasDueTime = hasDueTime, completed = completed, completedAt = completedAt,
    createdAt = createdAt, updatedAt = updatedAt,
)

fun Task.toEntity(userId: String) = TaskEntity(
    taskId = id, userId = userId, title = title, description = description,
    priority = priority.name, category = category, dueAt = dueAt, hasDueTime = hasDueTime,
    completed = completed, completedAt = completedAt, createdAt = createdAt, updatedAt = updatedAt,
    isSynced = false,
)

fun ReminderEntity.toDomain() = Reminder(
    id = reminderId, title = title, description = description, scheduledAt = scheduledTime,
    repeatType = enumOr(repeatType, RepeatType.NONE), repeatIntervalDays = repeatIntervalDays,
    enabled = enabled, completed = completed, lastTriggeredAt = lastTriggeredAt,
    createdAt = createdAt, updatedAt = updatedAt,
)

fun Reminder.toEntity(userId: String) = ReminderEntity(
    reminderId = id, userId = userId, title = title, description = description,
    scheduledTime = scheduledAt, repeatType = repeatType.name, repeatIntervalDays = repeatIntervalDays,
    enabled = enabled, completed = completed, lastTriggeredAt = lastTriggeredAt,
    createdAt = createdAt, updatedAt = updatedAt, isSynced = false,
)

fun ExpenseEntity.toDomain() = Transaction(
    id = expenseId, amountMinor = amount, type = enumOr(type, TransactionType.EXPENSE),
    category = category, paymentMethod = enumOr(paymentMethod, PaymentMethod.OTHER),
    description = description, date = date, createdAt = createdAt, updatedAt = updatedAt,
)

fun Transaction.toEntity(userId: String) = ExpenseEntity(
    expenseId = id, userId = userId, amount = amountMinor, type = type.name, category = category,
    paymentMethod = paymentMethod.name, description = description, date = date,
    createdAt = createdAt, updatedAt = updatedAt, isSynced = false,
)

private val checklistSerializer = ListSerializer(ChecklistItem.serializer())

fun encodeChecklist(items: List<ChecklistItem>): String = json.encodeToString(checklistSerializer, items)
fun decodeChecklist(raw: String): List<ChecklistItem> =
    if (raw.isBlank()) emptyList() else runCatching { json.decodeFromString(checklistSerializer, raw) }.getOrDefault(emptyList())

fun NoteEntity.toDomain() = Note(
    id = noteId, title = title, content = content, category = category, isPinned = isPinned,
    isArchived = isArchived, isChecklist = isChecklist, checklist = decodeChecklist(checklistJson),
    createdAt = createdAt, updatedAt = updatedAt,
)

fun Note.toEntity(userId: String) = NoteEntity(
    noteId = id, userId = userId, title = title, content = content, category = category,
    isPinned = isPinned, isArchived = isArchived, isChecklist = isChecklist,
    checklistJson = encodeChecklist(checklist), createdAt = createdAt, updatedAt = updatedAt, isSynced = false,
)

fun DocumentEntity.toDomain() = Document(
    id = documentId, name = name, storedFileName = fileUri, mimeType = fileType, sizeBytes = sizeBytes,
    folder = folder, expiryAt = expiryDate, expiryReminderId = expiryReminderId,
    createdAt = createdAt, updatedAt = updatedAt,
)

fun Document.toEntity(userId: String) = DocumentEntity(
    documentId = id, userId = userId, name = name, fileUri = storedFileName, fileType = mimeType,
    sizeBytes = sizeBytes, folder = folder, expiryDate = expiryAt, expiryReminderId = expiryReminderId,
    createdAt = createdAt, updatedAt = updatedAt,
)

fun AIConversationEntity.toDomain() = Conversation(conversationId, title, createdAt, updatedAt)

fun AIMessageEntity.toDomain() = ChatMessage(
    id = messageId, conversationId = conversationId,
    role = if (role == "user") MessageRole.USER else MessageRole.ASSISTANT,
    content = content, timestamp = timestamp, isError = isError,
)

fun ChatMessage.toEntity() = AIMessageEntity(
    messageId = id, conversationId = conversationId,
    role = if (role == MessageRole.USER) "user" else "assistant",
    content = content, timestamp = timestamp, isError = isError,
)
