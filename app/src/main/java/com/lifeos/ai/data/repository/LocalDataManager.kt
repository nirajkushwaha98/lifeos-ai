package com.lifeos.ai.data.repository

import androidx.room.withTransaction
import com.lifeos.ai.data.local.database.LifeOSDatabase
import com.lifeos.ai.data.local.files.PrivateFileStore
import javax.inject.Inject
import javax.inject.Singleton

/** Bulk operations over all of a user's local data. */
@Singleton
class LocalDataManager @Inject constructor(
    private val db: LifeOSDatabase,
    private val files: PrivateFileStore,
) {
    suspend fun reassign(fromUserId: String, toUserId: String) {
        if (fromUserId == toUserId) return
        val s = db.syncDao()
        db.withTransaction {
            s.reassignTasks(fromUserId, toUserId)
            s.reassignReminders(fromUserId, toUserId)
            s.reassignExpenses(fromUserId, toUserId)
            s.reassignNotes(fromUserId, toUserId)
            s.reassignDocuments(fromUserId, toUserId)
            s.reassignConversations(fromUserId, toUserId)
            s.reassignCategories(fromUserId, toUserId)
            s.reassignBudgets(fromUserId, toUserId)
            s.reassignActivity(fromUserId, toUserId)
        }
    }

    suspend fun wipeUser(userId: String) {
        val docs = db.documentDao().all(userId)
        val s = db.syncDao()
        db.withTransaction {
            s.deleteTasks(userId)
            s.deleteReminders(userId)
            s.deleteExpenses(userId)
            s.deleteNotes(userId)
            s.deleteDocuments(userId)
            s.deleteConversations(userId)
            s.deleteCategories(userId)
            s.deleteBudgets(userId)
            s.deleteActivity(userId)
            s.deleteTombstones(userId)
            db.userDao().delete(userId)
        }
        docs.forEach { files.deleteDocument(it.fileUri) }
        files.deleteProfileImages()
    }
}
