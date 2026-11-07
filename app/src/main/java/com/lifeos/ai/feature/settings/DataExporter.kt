package com.lifeos.ai.feature.settings

import android.content.Context
import android.net.Uri
import com.lifeos.ai.data.local.database.LifeOSDatabase
import com.lifeos.ai.data.repository.SessionManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Exports all of the current user's local data as a portable JSON file (user-chosen location). */
@Singleton
class DataExporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: LifeOSDatabase,
    private val session: SessionManager,
) {
    private val json = Json { prettyPrint = true }

    suspend fun exportTo(uri: Uri): Int = withContext(Dispatchers.IO) {
        val uid = session.currentUserId()
        var count = 0
        fun arr(block: kotlinx.serialization.json.JsonArrayBuilder.() -> Unit): JsonArray = buildJsonArray(block)
        val tasks = db.taskDao().all(uid)
        val reminders = db.reminderDao().all(uid)
        val expenses = db.expenseDao().all(uid)
        val notes = db.noteDao().all(uid)
        val docs = db.documentDao().all(uid)
        val convs = db.conversationDao().allConversations(uid)
        val messages = db.conversationDao().allMessages(uid)
        val root: JsonObject = buildJsonObject {
            put("app", "LifeOS AI")
            put("exportedAt", System.currentTimeMillis())
            put("formatVersion", 1)
            put("tasks", arr {
                tasks.forEach { t -> count++; add(buildJsonObject {
                    put("id", t.taskId); put("title", t.title); put("description", t.description); put("priority", t.priority)
                    put("category", t.category); put("dueAt", JsonPrimitive(t.dueAt)); put("completed", t.completed); put("createdAt", t.createdAt)
                }) }
            })
            put("reminders", arr {
                reminders.forEach { r -> count++; add(buildJsonObject {
                    put("id", r.reminderId); put("title", r.title); put("description", r.description); put("scheduledTime", r.scheduledTime)
                    put("repeatType", r.repeatType); put("repeatIntervalDays", r.repeatIntervalDays); put("enabled", r.enabled); put("completed", r.completed)
                }) }
            })
            put("transactions", arr {
                expenses.forEach { e -> count++; add(buildJsonObject {
                    put("id", e.expenseId); put("amount", e.amount / 100.0); put("currency", "INR"); put("type", e.type)
                    put("category", e.category); put("paymentMethod", e.paymentMethod); put("description", e.description); put("date", e.date)
                }) }
            })
            put("notes", arr {
                notes.forEach { n -> count++; add(buildJsonObject {
                    put("id", n.noteId); put("title", n.title); put("content", n.content); put("category", n.category)
                    put("pinned", n.isPinned); put("archived", n.isArchived); put("checklist", json.parseToJsonElement(n.checklistJson.ifBlank { "[]" }))
                    put("createdAt", n.createdAt); put("updatedAt", n.updatedAt)
                }) }
            })
            put("documents", arr {
                docs.forEach { d -> count++; add(buildJsonObject {
                    put("id", d.documentId); put("name", d.name); put("type", d.fileType); put("folder", d.folder); put("expiryDate", JsonPrimitive(d.expiryDate))
                }) }
            })
            put("aiConversations", arr {
                convs.forEach { c -> count++; add(buildJsonObject {
                    put("id", c.conversationId); put("title", c.title); put("createdAt", c.createdAt)
                    put("messages", arr {
                        messages.filter { it.conversationId == c.conversationId }.forEach { m ->
                            add(buildJsonObject { put("role", m.role); put("content", m.content); put("timestamp", m.timestamp) })
                        }
                    })
                }) }
            })
        }
        context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
            out.write(json.encodeToString(JsonObject.serializer(), root).toByteArray())
        } ?: throw IOException("Cannot write export")
        count
    }
}
