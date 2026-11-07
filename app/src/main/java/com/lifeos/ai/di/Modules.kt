package com.lifeos.ai.di

import android.content.Context
import androidx.room.Room
import com.lifeos.ai.data.local.database.LifeOSDatabase
import com.lifeos.ai.data.remote.api.AiService
import com.lifeos.ai.data.remote.api.FirebaseAiService
import com.lifeos.ai.data.repository.ConversationRepositoryImpl
import com.lifeos.ai.data.repository.DocumentRepositoryImpl
import com.lifeos.ai.data.repository.NoteRepositoryImpl
import com.lifeos.ai.data.repository.ReminderRepositoryImpl
import com.lifeos.ai.data.repository.TaskRepositoryImpl
import com.lifeos.ai.data.repository.TransactionRepositoryImpl
import com.lifeos.ai.domain.repository.ConversationRepository
import com.lifeos.ai.domain.repository.DocumentRepository
import com.lifeos.ai.domain.repository.NoteRepository
import com.lifeos.ai.domain.repository.ReminderRepository
import com.lifeos.ai.domain.repository.TaskRepository
import com.lifeos.ai.domain.repository.TransactionRepository
import com.lifeos.ai.domain.usecase.CommandParser
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Provider
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): LifeOSDatabase =
        Room.databaseBuilder(context, LifeOSDatabase::class.java, LifeOSDatabase.NAME)
            .addMigrations(*LifeOSDatabase.MIGRATIONS)
            .build()

    @Provides
    fun provideCommandParser(): CommandParser = CommandParser()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds abstract fun taskRepository(impl: TaskRepositoryImpl): TaskRepository
    @Binds abstract fun reminderRepository(impl: ReminderRepositoryImpl): ReminderRepository
    @Binds abstract fun transactionRepository(impl: TransactionRepositoryImpl): TransactionRepository
    @Binds abstract fun noteRepository(impl: NoteRepositoryImpl): NoteRepository
    @Binds abstract fun documentRepository(impl: DocumentRepositoryImpl): DocumentRepository
    @Binds abstract fun conversationRepository(impl: ConversationRepositoryImpl): ConversationRepository
}

@Module
@InstallIn(SingletonComponent::class)
object AiModule {
    /**
     * Release builds use the secure Firebase backend. A debug build with developer keys in
     * local.properties calls the providers directly for testing (see src/debug DevAiServices).
     */
    @Provides
    @Singleton
    fun provideAiService(backend: Provider<FirebaseAiService>): AiService = devAiService() ?: backend.get()
}
