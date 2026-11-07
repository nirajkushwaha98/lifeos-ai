package com.lifeos.ai.data.sync

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.lifeos.ai.data.local.database.LifeOSDatabase
import com.lifeos.ai.data.local.entity.ExpenseEntity
import com.lifeos.ai.data.local.entity.LOCAL_USER_ID
import com.lifeos.ai.data.local.entity.NoteEntity
import com.lifeos.ai.data.local.entity.ReminderEntity
import com.lifeos.ai.data.local.entity.TaskEntity
import com.lifeos.ai.data.local.entity.TombstoneEntity
import com.lifeos.ai.data.preferences.UserPreferencesRepository
import com.lifeos.ai.data.remote.firebase.FirebaseProvider
import com.lifeos.ai.data.repository.SessionManager
import com.lifeos.ai.data.repository.SyncCollections
import com.lifeos.ai.feature.reminders.alarm.ReminderScheduler
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.tasks.await
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val firebase: FirebaseProvider,
    private val session: SessionManager,
) {
    /** Debounced background sync; runs only when online and signed in to a configured Firebase. */
    fun requestSync(immediate: Boolean = false) {
        if (!firebase.isConfigured || !session.isSignedIn) return
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay(if (immediate) 0 else 10, TimeUnit.SECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    fun cancel() {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    companion object { const val WORK_NAME = "lifeos_sync" }
}

/**
 * Two-way, last-write-wins sync between Room and Firestore at `users/{uid}/{collection}/{id}`.
 *  - Push: rows with isSynced = 0 are written with their client UUID as document id (idempotent).
 *  - Deletes: local tombstones become `{deleted: true}` docs so other devices remove them too.
 *  - Pull: documents changed since the last pull replace local rows only when newer.
 */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val firebase: FirebaseProvider,
    private val session: SessionManager,
    private val db: LifeOSDatabase,
    private val prefs: UserPreferencesRepository,
    private val reminderScheduler: ReminderScheduler,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val fs = firebase.firestore ?: return Result.success()
        val uid = session.currentUserId()
        if (uid == LOCAL_USER_ID) return Result.success()
        if (!prefs.current().cloudSyncEnabled) return Result.success()
        return try {
            pushTombstones(fs, uid)
            pushAll(fs, uid)
            val pulledReminders = pullAll(fs, uid)
            if (pulledReminders) reminderScheduler.rescheduleAll()
            prefs.setLastSyncAt(System.currentTimeMillis())
            Result.success()
        } catch (e: Exception) {
            Log.w(TAG, "Sync failed (attempt $runAttemptCount)", e)
            if (runAttemptCount < 5) Result.retry() else Result.failure()
        }
    }

    private fun col(fs: FirebaseFirestore, uid: String, name: String) =
        fs.collection("users").document(uid).collection(name)

    private suspend fun pushTombstones(fs: FirebaseFirestore, uid: String) {
        db.syncDao().tombstones(uid).forEach { t: TombstoneEntity ->
            col(fs, uid, t.collection).document(t.recordId)
                .set(mapOf("deleted" to true, "updatedAt" to t.deletedAt), SetOptions.merge()).await()
            db.syncDao().removeTombstone(t.collection, t.recordId)
        }
    }

    private suspend fun pushAll(fs: FirebaseFirestore, uid: String) {
        db.taskDao().unsynced(uid).forEach {
            col(fs, uid, SyncCollections.TASKS).document(it.taskId).set(FirestoreMappers.task(it)).await()
            db.taskDao().markSynced(it.taskId, it.updatedAt)
        }
        db.reminderDao().unsynced(uid).forEach {
            col(fs, uid, SyncCollections.REMINDERS).document(it.reminderId).set(FirestoreMappers.reminder(it)).await()
            db.reminderDao().markSynced(it.reminderId, it.updatedAt)
        }
        db.expenseDao().unsynced(uid).forEach {
            col(fs, uid, SyncCollections.EXPENSES).document(it.expenseId).set(FirestoreMappers.expense(it)).await()
            db.expenseDao().markSynced(it.expenseId, it.updatedAt)
        }
        db.noteDao().unsynced(uid).forEach {
            col(fs, uid, SyncCollections.NOTES).document(it.noteId).set(FirestoreMappers.note(it)).await()
            db.noteDao().markSynced(it.noteId, it.updatedAt)
        }
    }

    /** Returns true if any reminder changed (alarms must be refreshed). */
    private suspend fun pullAll(fs: FirebaseFirestore, uid: String): Boolean {
        var remindersChanged = false
        pull(fs, uid, SyncCollections.TASKS) { doc, deleted, updatedAt ->
            val local = db.taskDao().get(doc.id)
            if (deleted) { if (local != null && local.isSynced) db.taskDao().delete(doc.id) }
            else if (local == null || (local.isSynced && updatedAt > local.updatedAt)) {
                FirestoreMappers.toTask(doc, uid)?.let { db.taskDao().upsert(it) }
            }
        }
        pull(fs, uid, SyncCollections.REMINDERS) { doc, deleted, updatedAt ->
            val local = db.reminderDao().get(doc.id)
            if (deleted) {
                if (local != null && local.isSynced) { db.reminderDao().delete(doc.id); reminderScheduler.cancel(doc.id); remindersChanged = true }
            } else if (local == null || (local.isSynced && updatedAt > local.updatedAt)) {
                FirestoreMappers.toReminder(doc, uid)?.let { db.reminderDao().upsert(it); remindersChanged = true }
            }
        }
        pull(fs, uid, SyncCollections.EXPENSES) { doc, deleted, updatedAt ->
            val local = db.expenseDao().get(doc.id)
            if (deleted) { if (local != null && local.isSynced) db.expenseDao().delete(doc.id) }
            else if (local == null || (local.isSynced && updatedAt > local.updatedAt)) {
                FirestoreMappers.toExpense(doc, uid)?.let { db.expenseDao().upsert(it) }
            }
        }
        pull(fs, uid, SyncCollections.NOTES) { doc, deleted, updatedAt ->
            val local = db.noteDao().get(doc.id)
            if (deleted) { if (local != null && local.isSynced) db.noteDao().delete(doc.id) }
            else if (local == null || (local.isSynced && updatedAt > local.updatedAt)) {
                FirestoreMappers.toNote(doc, uid)?.let { db.noteDao().upsert(it) }
            }
        }
        return remindersChanged
    }

    private suspend fun pull(
        fs: FirebaseFirestore,
        uid: String,
        collection: String,
        apply: suspend (DocumentSnapshot, Boolean, Long) -> Unit,
    ) {
        val since = prefs.lastPull(collection)
        val snapshot = col(fs, uid, collection).whereGreaterThan("updatedAt", since).get().await()
        var maxSeen = since
        for (doc in snapshot.documents) {
            val updatedAt = doc.getLong("updatedAt") ?: continue
            apply(doc, doc.getBoolean("deleted") == true, updatedAt)
            if (updatedAt > maxSeen) maxSeen = updatedAt
        }
        prefs.setLastPull(collection, maxSeen)
    }

    companion object { private const val TAG = "SyncWorker" }
}

/** Explicit field mapping (no reflection), so R8 and schema changes are safe. */
object FirestoreMappers {
    fun task(e: TaskEntity) = mapOf(
        "title" to e.title, "description" to e.description, "priority" to e.priority,
        "category" to e.category, "dueAt" to e.dueAt, "hasDueTime" to e.hasDueTime,
        "completed" to e.completed, "completedAt" to e.completedAt,
        "createdAt" to e.createdAt, "updatedAt" to e.updatedAt, "deleted" to false,
    )

    fun reminder(e: ReminderEntity) = mapOf(
        "title" to e.title, "description" to e.description, "scheduledTime" to e.scheduledTime,
        "repeatType" to e.repeatType, "repeatIntervalDays" to e.repeatIntervalDays,
        "enabled" to e.enabled, "completed" to e.completed, "lastTriggeredAt" to e.lastTriggeredAt,
        "createdAt" to e.createdAt, "updatedAt" to e.updatedAt, "deleted" to false,
    )

    fun expense(e: ExpenseEntity) = mapOf(
        "amount" to e.amount, "type" to e.type, "category" to e.category,
        "paymentMethod" to e.paymentMethod, "description" to e.description, "date" to e.date,
        "createdAt" to e.createdAt, "updatedAt" to e.updatedAt, "deleted" to false,
    )

    fun note(e: NoteEntity) = mapOf(
        "title" to e.title, "content" to e.content, "category" to e.category,
        "isPinned" to e.isPinned, "isArchived" to e.isArchived, "isChecklist" to e.isChecklist,
        "checklistJson" to e.checklistJson, "createdAt" to e.createdAt, "updatedAt" to e.updatedAt,
        "deleted" to false,
    )

    fun toTask(d: DocumentSnapshot, uid: String): TaskEntity? = runCatching {
        TaskEntity(
            taskId = d.id, userId = uid, title = d.getString("title")!!,
            description = d.getString("description").orEmpty(), priority = d.getString("priority") ?: "MEDIUM",
            category = d.getString("category").orEmpty(), dueAt = d.getLong("dueAt"),
            hasDueTime = d.getBoolean("hasDueTime") ?: false, completed = d.getBoolean("completed") ?: false,
            completedAt = d.getLong("completedAt"), createdAt = d.getLong("createdAt") ?: 0L,
            updatedAt = d.getLong("updatedAt")!!, isSynced = true,
        )
    }.getOrNull()

    fun toReminder(d: DocumentSnapshot, uid: String): ReminderEntity? = runCatching {
        ReminderEntity(
            reminderId = d.id, userId = uid, title = d.getString("title")!!,
            description = d.getString("description").orEmpty(), scheduledTime = d.getLong("scheduledTime")!!,
            repeatType = d.getString("repeatType") ?: "NONE",
            repeatIntervalDays = (d.getLong("repeatIntervalDays") ?: 1L).toInt(),
            enabled = d.getBoolean("enabled") ?: true, completed = d.getBoolean("completed") ?: false,
            lastTriggeredAt = d.getLong("lastTriggeredAt"), createdAt = d.getLong("createdAt") ?: 0L,
            updatedAt = d.getLong("updatedAt")!!, isSynced = true,
        )
    }.getOrNull()

    fun toExpense(d: DocumentSnapshot, uid: String): ExpenseEntity? = runCatching {
        ExpenseEntity(
            expenseId = d.id, userId = uid, amount = d.getLong("amount")!!, type = d.getString("type")!!,
            category = d.getString("category") ?: "other", paymentMethod = d.getString("paymentMethod") ?: "OTHER",
            description = d.getString("description").orEmpty(), date = d.getLong("date")!!,
            createdAt = d.getLong("createdAt") ?: 0L, updatedAt = d.getLong("updatedAt")!!, isSynced = true,
        )
    }.getOrNull()

    fun toNote(d: DocumentSnapshot, uid: String): NoteEntity? = runCatching {
        NoteEntity(
            noteId = d.id, userId = uid, title = d.getString("title").orEmpty(),
            content = d.getString("content").orEmpty(), category = d.getString("category").orEmpty(),
            isPinned = d.getBoolean("isPinned") ?: false, isArchived = d.getBoolean("isArchived") ?: false,
            isChecklist = d.getBoolean("isChecklist") ?: false, checklistJson = d.getString("checklistJson") ?: "[]",
            createdAt = d.getLong("createdAt") ?: 0L, updatedAt = d.getLong("updatedAt")!!, isSynced = true,
        )
    }.getOrNull()
}
