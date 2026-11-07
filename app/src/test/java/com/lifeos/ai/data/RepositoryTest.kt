package com.lifeos.ai.data

import android.app.AlarmManager
import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.lifeos.ai.core.common.newId
import com.lifeos.ai.data.local.database.LifeOSDatabase
import com.lifeos.ai.data.local.entity.LOCAL_USER_ID
import com.lifeos.ai.data.local.entity.AIConversationEntity
import com.lifeos.ai.data.local.entity.AIMessageEntity
import com.lifeos.ai.data.local.files.PrivateFileStore
import com.lifeos.ai.data.remote.firebase.FirebaseProvider
import com.lifeos.ai.data.repository.InvalidDataException
import com.lifeos.ai.data.repository.LocalDataManager
import com.lifeos.ai.data.repository.NoteRepositoryImpl
import com.lifeos.ai.data.repository.ReminderRepositoryImpl
import com.lifeos.ai.data.repository.RepoSupport
import com.lifeos.ai.data.repository.SessionManager
import com.lifeos.ai.data.repository.TaskRepositoryImpl
import com.lifeos.ai.data.repository.TransactionRepositoryImpl
import com.lifeos.ai.data.sync.SyncScheduler
import com.lifeos.ai.domain.model.ChecklistItem
import com.lifeos.ai.domain.model.DefaultCategories
import com.lifeos.ai.domain.model.Note
import com.lifeos.ai.domain.model.Priority
import com.lifeos.ai.domain.model.Reminder
import com.lifeos.ai.domain.model.RepeatType
import com.lifeos.ai.domain.model.Task
import com.lifeos.ai.domain.model.Transaction
import com.lifeos.ai.domain.model.TransactionType
import com.lifeos.ai.feature.reminders.alarm.ReminderScheduler
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.YearMonth

/**
 * Exercises the real repositories against an in-memory Room database (no Firebase configured,
 * so data is owned by the local/guest user and sync is a no-op).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RepositoryTest {
    private lateinit var context: Context
    private lateinit var db: LifeOSDatabase
    private lateinit var tasks: TaskRepositoryImpl
    private lateinit var reminders: ReminderRepositoryImpl
    private lateinit var transactions: TransactionRepositoryImpl
    private lateinit var notes: NoteRepositoryImpl
    private lateinit var scheduler: ReminderScheduler

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, LifeOSDatabase::class.java).allowMainThreadQueries().build()
        val firebase = FirebaseProvider(context)
        val session = SessionManager(firebase)
        val support = RepoSupport(session, db, SyncScheduler(context, firebase, session))
        scheduler = ReminderScheduler(context, { db }, { session })
        tasks = TaskRepositoryImpl(db, support)
        reminders = ReminderRepositoryImpl(db, support, scheduler)
        transactions = TransactionRepositoryImpl(db, support)
        notes = NoteRepositoryImpl(db, support)
    }

    @After fun tearDown() = db.close()

    private fun now() = System.currentTimeMillis()

    // ------------------------------------------------------------ tasks

    @Test fun taskCreateEditDelete() = runTest {
        val created = tasks.upsert(Task(newId(), "  Buy milk  ", priority = Priority.HIGH, createdAt = now(), updatedAt = now()))
        assertEquals("Buy milk", created.title)
        assertEquals(1, tasks.observeTasks().first().size)

        tasks.upsert(created.copy(title = "Buy milk and bread", description = "from store"))
        val edited = tasks.getTask(created.id)!!
        assertEquals("Buy milk and bread", edited.title)
        assertEquals(Priority.HIGH, edited.priority)
        assertEquals(1, tasks.observeTasks().first().size) // edited in place, no duplicate

        tasks.setCompleted(created.id, true)
        assertTrue(tasks.getTask(created.id)!!.completed)
        assertNotNull(tasks.getTask(created.id)!!.completedAt)

        tasks.delete(created.id)
        assertNull(tasks.getTask(created.id))
        assertTrue(tasks.observeTasks().first().isEmpty())
    }

    @Test(expected = InvalidDataException::class)
    fun taskWithBlankTitleIsRejected() = runTest {
        tasks.upsert(Task(newId(), "   ", createdAt = now(), updatedAt = now()))
    }

    @Test fun guestTombstonesAreNotCreated() = runTest {
        val t = tasks.upsert(Task(newId(), "x", createdAt = now(), updatedAt = now()))
        tasks.delete(t.id)
        assertTrue(db.syncDao().tombstones(LOCAL_USER_ID).isEmpty())
    }

    // ------------------------------------------------------------ reminders

    @Test fun reminderIsScheduledWithAlarmManagerAndCancelledOnDelete() = runTest {
        val alarm = shadowOf(context.getSystemService(AlarmManager::class.java))
        val at = now() + 60 * 60 * 1000
        val r = reminders.upsert(Reminder(newId(), "Drink water", scheduledAt = at, createdAt = now(), updatedAt = now()))
        assertEquals(1, alarm.scheduledAlarms.size)
        assertEquals(at, alarm.nextScheduledAlarm!!.triggerAtTime)

        reminders.setEnabled(r.id, false)
        assertEquals(0, alarm.scheduledAlarms.size)

        reminders.setEnabled(r.id, true)
        assertEquals(1, alarm.scheduledAlarms.size)

        reminders.delete(r.id)
        assertEquals(0, alarm.scheduledAlarms.size)
        assertNull(reminders.getReminder(r.id))
    }

    @Test fun pastRepeatingReminderSchedulesNextOccurrence() = runTest {
        val alarm = shadowOf(context.getSystemService(AlarmManager::class.java))
        val yesterday = now() - 24 * 60 * 60 * 1000 + 60_000
        reminders.upsert(Reminder(newId(), "Daily standup", scheduledAt = yesterday, repeatType = RepeatType.DAILY, createdAt = now(), updatedAt = now()))
        val next = alarm.nextScheduledAlarm!!.triggerAtTime
        assertTrue(next > now())
    }

    @Test fun completedReminderIsNotScheduled() = runTest {
        val alarm = shadowOf(context.getSystemService(AlarmManager::class.java))
        val r = reminders.upsert(Reminder(newId(), "Pay rent", scheduledAt = now() + 100_000, createdAt = now(), updatedAt = now()))
        reminders.setCompleted(r.id, true)
        assertEquals(0, alarm.scheduledAlarms.size)
        assertTrue(reminders.getReminder(r.id)!!.completed)
    }

    // ------------------------------------------------------------ expenses

    @Test fun transactionsBudgetsAndCategories() = runTest {
        val e = transactions.upsert(Transaction(newId(), 25050, TransactionType.EXPENSE, DefaultCategories.FOOD, date = now(), createdAt = now(), updatedAt = now()))
        transactions.upsert(Transaction(newId(), 5_000_000, TransactionType.INCOME, DefaultCategories.SALARY, date = now(), createdAt = now(), updatedAt = now()))
        assertEquals(2, transactions.observeTransactions().first().size)

        transactions.upsert(e.copy(amountMinor = 30000))
        assertEquals(30000L, transactions.getTransaction(e.id)!!.amountMinor)

        val month = YearMonth.now()
        transactions.setBudget(month, 1_000_000)
        assertEquals(1_000_000L, transactions.observeBudget(month).first())
        transactions.setBudget(month, null)
        assertNull(transactions.observeBudget(month).first())

        transactions.addCustomCategory("Pets")
        transactions.addCustomCategory("Pets") // ignored duplicate
        assertEquals(listOf("Pets"), transactions.observeCustomCategories().first())

        transactions.delete(e.id)
        assertEquals(1, transactions.observeTransactions().first().size)
    }

    @Test(expected = InvalidDataException::class)
    fun nonPositiveAmountRejected() = runTest {
        transactions.upsert(Transaction(newId(), 0, TransactionType.EXPENSE, DefaultCategories.FOOD, date = now(), createdAt = now(), updatedAt = now()))
    }

    // ------------------------------------------------------------ notes

    @Test fun notesCrudPinArchiveChecklist() = runTest {
        val n = notes.upsert(Note(newId(), "Shopping List", isChecklist = true,
            checklist = listOf(ChecklistItem("Milk"), ChecklistItem(""), ChecklistItem("Eggs", true)), createdAt = now(), updatedAt = now()))
        assertEquals(listOf("Milk", "Eggs"), n.checklist.map { it.text }) // blank items dropped
        assertTrue(n.checklist[1].checked)

        assertEquals(n.id, notes.findByTitle("shopping list")!!.id)

        notes.upsert(n.copy(isPinned = true, isArchived = true))
        val updated = notes.getNote(n.id)!!
        assertTrue(updated.isPinned); assertTrue(updated.isArchived)
        assertNull(notes.findByTitle("Shopping List")) // archived notes are not reused

        notes.delete(n.id)
        assertNull(notes.getNote(n.id))
    }

    @Test(expected = InvalidDataException::class)
    fun emptyNoteRejected() = runTest {
        notes.upsert(Note(newId(), " ", createdAt = now(), updatedAt = now()))
    }

    // ------------------------------------------------------------ database

    @Test fun deletingConversationCascadesToMessages() = runTest {
        val dao = db.conversationDao()
        dao.insert(AIConversationEntity("c1", LOCAL_USER_ID, "Hi", 1, 1))
        dao.insertMessage(AIMessageEntity("m1", "c1", "user", "hello", 1, false))
        dao.insertMessage(AIMessageEntity("m2", "c1", "assistant", "hey", 2, false))
        assertEquals(2, dao.recentMessages("c1", 10).size)
        dao.delete("c1")
        assertEquals(0, dao.recentMessages("c1", 10).size)
    }

    @Test fun guestDataIsReassignedOnSignIn() = runTest {
        tasks.upsert(Task(newId(), "guest task", createdAt = now(), updatedAt = now()))
        notes.upsert(Note(newId(), "guest note", createdAt = now(), updatedAt = now()))
        LocalDataManager(db, PrivateFileStore(context)).reassign(LOCAL_USER_ID, "uid-123")
        assertEquals(1, db.taskDao().all("uid-123").size)
        assertEquals(1, db.noteDao().all("uid-123").size)
        assertTrue(db.taskDao().all(LOCAL_USER_ID).isEmpty())
        assertFalse(db.taskDao().unsynced("uid-123").isEmpty()) // will be uploaded
    }

    @Test fun markSyncedOnlyIfUnchanged() = runTest {
        val t = tasks.upsert(Task(newId(), "sync me", createdAt = now(), updatedAt = now()))
        db.taskDao().markSynced(t.id, t.updatedAt - 1) // stale version -> no-op
        assertFalse(db.taskDao().get(t.id)!!.isSynced)
        db.taskDao().markSynced(t.id, t.updatedAt)
        assertTrue(db.taskDao().get(t.id)!!.isSynced)
    }
}
