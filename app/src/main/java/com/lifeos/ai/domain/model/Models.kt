package com.lifeos.ai.domain.model

import kotlinx.serialization.Serializable

enum class Priority { LOW, MEDIUM, HIGH }

data class Task(
    val id: String,
    val title: String,
    val description: String = "",
    val priority: Priority = Priority.MEDIUM,
    val category: String = "",
    /** Due instant in epoch millis. If [hasDueTime] is false only the date part is meaningful. */
    val dueAt: Long? = null,
    val hasDueTime: Boolean = false,
    val completed: Boolean = false,
    val completedAt: Long? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

enum class RepeatType { NONE, DAILY, WEEKLY, MONTHLY, CUSTOM }

data class Reminder(
    val id: String,
    val title: String,
    val description: String = "",
    val scheduledAt: Long,
    val repeatType: RepeatType = RepeatType.NONE,
    /** For [RepeatType.CUSTOM]: repeat every N days. */
    val repeatIntervalDays: Int = 1,
    val enabled: Boolean = true,
    val completed: Boolean = false,
    val lastTriggeredAt: Long? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

enum class TransactionType { INCOME, EXPENSE }

enum class PaymentMethod { CASH, UPI, CARD, BANK_TRANSFER, WALLET, OTHER }

data class Transaction(
    val id: String,
    val amountMinor: Long,
    val type: TransactionType,
    val category: String,
    val paymentMethod: PaymentMethod = PaymentMethod.CASH,
    val description: String = "",
    val date: Long,
    val createdAt: Long,
    val updatedAt: Long,
)

/** Built-in categories are stored by key and localized in the UI; custom categories are stored by name. */
object DefaultCategories {
    const val FOOD = "food"
    const val SHOPPING = "shopping"
    const val TRAVEL = "travel"
    const val EDUCATION = "education"
    const val BILLS = "bills"
    const val HEALTH = "health"
    const val ENTERTAINMENT = "entertainment"
    const val OTHER = "other"
    const val SALARY = "salary"
    const val BUSINESS = "business"
    const val GIFT = "gift"

    val expense = listOf(FOOD, SHOPPING, TRAVEL, EDUCATION, BILLS, HEALTH, ENTERTAINMENT, OTHER)
    val income = listOf(SALARY, BUSINESS, GIFT, OTHER)
    val all = (expense + income).distinct()
}

@Serializable
data class ChecklistItem(val text: String, val checked: Boolean = false)

data class Note(
    val id: String,
    val title: String,
    val content: String = "",
    val category: String = "",
    val isPinned: Boolean = false,
    val isArchived: Boolean = false,
    val isChecklist: Boolean = false,
    val checklist: List<ChecklistItem> = emptyList(),
    val createdAt: Long,
    val updatedAt: Long,
)

data class Document(
    val id: String,
    val name: String,
    /** File name inside the app's private documents directory. */
    val storedFileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val folder: String = "",
    val expiryAt: Long? = null,
    val expiryReminderId: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
) {
    val isPdf: Boolean get() = mimeType == "application/pdf"
    val isImage: Boolean get() = mimeType.startsWith("image/")
}

data class Conversation(val id: String, val title: String, val createdAt: Long, val updatedAt: Long)

enum class MessageRole { USER, ASSISTANT }

data class ChatMessage(
    val id: String,
    val conversationId: String,
    val role: MessageRole,
    val content: String,
    val timestamp: Long,
    val isError: Boolean = false,
)

enum class ActivityType { TASK, REMINDER, EXPENSE, NOTE, AI }

data class ActivityItem(
    val id: String,
    val type: ActivityType,
    val title: String,
    val subtitle: String,
    val timestamp: Long,
)

data class UserProfile(
    val userId: String,
    val name: String,
    val email: String?,
    val photoPath: String?,
    val isGuest: Boolean,
    val emailVerified: Boolean,
)

data class MonthlySummary(
    val incomeMinor: Long,
    val expenseMinor: Long,
    val byCategory: List<Pair<String, Long>>,
    val budgetMinor: Long?,
) {
    val savingsMinor: Long get() = incomeMinor - expenseMinor
    val budgetUsedFraction: Float?
        get() = budgetMinor?.takeIf { it > 0 }?.let { (expenseMinor.toDouble() / it).toFloat() }
}
