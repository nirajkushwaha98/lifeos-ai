package com.lifeos.ai.core.navigation

import kotlinx.serialization.Serializable

/** Type-safe navigation destinations. */
object Routes {
    @Serializable data object Splash
    @Serializable data object Onboarding
    @Serializable data object Login
    @Serializable data object Register
    @Serializable data object ForgotPassword
    @Serializable data object VerifyEmail

    // Bottom navigation tabs
    @Serializable data object Home
    @Serializable data class Assistant(val prompt: String? = null, val conversationId: String? = null)
    @Serializable data object Tools
    @Serializable data object Activity
    @Serializable data object Profile

    // Feature screens
    @Serializable data object Tasks
    @Serializable data class TaskEditor(val taskId: String? = null)
    @Serializable data object Reminders
    @Serializable data class ReminderEditor(val reminderId: String? = null)
    @Serializable data object Expenses
    @Serializable data class TransactionEditor(val transactionId: String? = null, val income: Boolean = false)
    @Serializable data object ExpenseReports
    @Serializable data object Notes
    @Serializable data class NoteEditor(val noteId: String? = null, val checklist: Boolean = false)
    @Serializable data object Documents
    @Serializable data class DocumentPreview(val documentId: String)
    @Serializable data object Voice
    @Serializable data object Settings
    @Serializable data object PrivacyPolicy
}
