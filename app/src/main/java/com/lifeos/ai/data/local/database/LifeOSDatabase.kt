package com.lifeos.ai.data.local.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import com.lifeos.ai.data.local.dao.CategoryDao
import com.lifeos.ai.data.local.dao.ConversationDao
import com.lifeos.ai.data.local.dao.DocumentDao
import com.lifeos.ai.data.local.dao.ExpenseDao
import com.lifeos.ai.data.local.dao.NoteDao
import com.lifeos.ai.data.local.dao.ReminderDao
import com.lifeos.ai.data.local.dao.SyncDao
import com.lifeos.ai.data.local.dao.TaskDao
import com.lifeos.ai.data.local.dao.UserDao
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

@Database(
    entities = [
        UserEntity::class, TaskEntity::class, ReminderEntity::class, ExpenseEntity::class,
        CategoryEntity::class, BudgetEntity::class, NoteEntity::class, DocumentEntity::class,
        AIConversationEntity::class, AIMessageEntity::class, AIActivityEntity::class,
        TombstoneEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class LifeOSDatabase : RoomDatabase() {
    abstract fun userDao(): UserDao
    abstract fun taskDao(): TaskDao
    abstract fun reminderDao(): ReminderDao
    abstract fun expenseDao(): ExpenseDao
    abstract fun categoryDao(): CategoryDao
    abstract fun noteDao(): NoteDao
    abstract fun documentDao(): DocumentDao
    abstract fun conversationDao(): ConversationDao
    abstract fun syncDao(): SyncDao

    companion object {
        const val NAME = "lifeos.db"

        /**
         * Schema history lives in app/schemas (exported by Room). Add explicit migrations here
         * for every version bump, e.g. `Migration(1, 2) { db -> db.execSQL("ALTER TABLE ...") }`.
         * Destructive migration is intentionally NOT enabled so user data is never wiped silently.
         */
        val MIGRATIONS: Array<Migration> = emptyArray()
    }
}
