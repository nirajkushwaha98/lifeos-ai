package com.lifeos.ai.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.outlined.AttachMoney
import androidx.compose.material.icons.outlined.Business
import androidx.compose.material.icons.outlined.CardGiftcard
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.LocalHospital
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Receipt
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.ShoppingBag
import androidx.compose.material.icons.outlined.Work
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lifeos.ai.R
import com.lifeos.ai.core.common.Money
import com.lifeos.ai.core.common.TimeUtils
import com.lifeos.ai.core.theme.LifeColors
import com.lifeos.ai.core.theme.LocalLifeColors
import com.lifeos.ai.domain.model.ChatMessage
import com.lifeos.ai.domain.model.DefaultCategories
import com.lifeos.ai.domain.model.MessageRole
import com.lifeos.ai.domain.model.Note
import com.lifeos.ai.domain.model.PaymentMethod
import com.lifeos.ai.domain.model.Priority
import com.lifeos.ai.domain.model.Reminder
import com.lifeos.ai.domain.model.RepeatType
import com.lifeos.ai.domain.model.Task
import com.lifeos.ai.domain.model.Transaction
import com.lifeos.ai.domain.model.TransactionType

// ------------------------------------------------------------------ labels

@Composable
fun categoryLabel(key: String): String = when (key) {
    DefaultCategories.FOOD -> stringResource(R.string.cat_food)
    DefaultCategories.SHOPPING -> stringResource(R.string.cat_shopping)
    DefaultCategories.TRAVEL -> stringResource(R.string.cat_travel)
    DefaultCategories.EDUCATION -> stringResource(R.string.cat_education)
    DefaultCategories.BILLS -> stringResource(R.string.cat_bills)
    DefaultCategories.HEALTH -> stringResource(R.string.cat_health)
    DefaultCategories.ENTERTAINMENT -> stringResource(R.string.cat_entertainment)
    DefaultCategories.OTHER -> stringResource(R.string.cat_other)
    DefaultCategories.SALARY -> stringResource(R.string.cat_salary)
    DefaultCategories.BUSINESS -> stringResource(R.string.cat_business)
    DefaultCategories.GIFT -> stringResource(R.string.cat_gift)
    else -> key
}

/** Category glyphs use Material's outlined (line) style to sit with the LifeIcons set. */
fun categoryIcon(key: String): ImageVector = when (key) {
    DefaultCategories.FOOD -> Icons.Outlined.Restaurant
    DefaultCategories.SHOPPING -> Icons.Outlined.ShoppingBag
    DefaultCategories.TRAVEL -> Icons.Outlined.DirectionsCar
    DefaultCategories.EDUCATION -> Icons.AutoMirrored.Outlined.MenuBook
    DefaultCategories.BILLS -> Icons.Outlined.Receipt
    DefaultCategories.HEALTH -> Icons.Outlined.LocalHospital
    DefaultCategories.ENTERTAINMENT -> Icons.Outlined.Movie
    DefaultCategories.SALARY -> Icons.Outlined.Work
    DefaultCategories.BUSINESS -> Icons.Outlined.Business
    DefaultCategories.GIFT -> Icons.Outlined.CardGiftcard
    DefaultCategories.OTHER -> Icons.Outlined.Category
    else -> Icons.Outlined.AttachMoney
}

fun categoryColor(key: String): Color {
    val idx = DefaultCategories.all.indexOf(key).takeIf { it >= 0 } ?: (key.hashCode() and 0x7fffffff)
    return LifeColors.ChartPalette[idx % LifeColors.ChartPalette.size]
}

@Composable
fun paymentLabel(method: PaymentMethod): String = stringResource(
    when (method) {
        PaymentMethod.CASH -> R.string.pay_cash
        PaymentMethod.UPI -> R.string.pay_upi
        PaymentMethod.CARD -> R.string.pay_card
        PaymentMethod.BANK_TRANSFER -> R.string.pay_bank
        PaymentMethod.WALLET -> R.string.pay_wallet
        PaymentMethod.OTHER -> R.string.pay_other
    }
)

@Composable
fun priorityLabel(p: Priority): String = stringResource(
    when (p) { Priority.LOW -> R.string.priority_low; Priority.MEDIUM -> R.string.priority_medium; Priority.HIGH -> R.string.priority_high }
)

fun priorityColor(p: Priority): Color = when (p) {
    Priority.LOW -> LifeColors.Success
    Priority.MEDIUM -> LifeColors.Warning
    Priority.HIGH -> LifeColors.Error
}

@Composable
fun repeatLabel(type: RepeatType, interval: Int): String = when (type) {
    RepeatType.NONE -> stringResource(R.string.repeat_none)
    RepeatType.DAILY -> stringResource(R.string.repeat_daily)
    RepeatType.WEEKLY -> stringResource(R.string.repeat_weekly)
    RepeatType.MONTHLY -> stringResource(R.string.repeat_monthly)
    RepeatType.CUSTOM -> stringResource(R.string.repeat_every_n_days, interval)
}

// ------------------------------------------------------------------ cards

@Composable
fun AIChatBubble(
    message: ChatMessage,
    onCopy: () -> Unit,
    onRegenerate: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val isUser = message.role == MessageRole.USER
    val colors = LocalLifeColors.current
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        if (!isUser) {
            Box(
                Modifier.padding(top = 4.dp).size(30.dp).clip(RoundedCornerShape(10.dp)).background(colors.brandGradient),
                contentAlignment = Alignment.Center,
            ) { Icon(LifeIcons.Spark, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp)) }
            Spacer(Modifier.width(8.dp))
        }
        Column(horizontalAlignment = if (isUser) Alignment.End else Alignment.Start, modifier = Modifier.widthIn(max = 320.dp)) {
            val shape = RoundedCornerShape(
                topStart = 18.dp, topEnd = 18.dp,
                bottomStart = if (isUser) 18.dp else 4.dp, bottomEnd = if (isUser) 4.dp else 18.dp,
            )
            Box(
                Modifier.clip(shape)
                    .background(
                        when {
                            isUser -> colors.brandGradient
                            message.isError -> androidx.compose.ui.graphics.Brush.linearGradient(listOf(LifeColors.Error.copy(0.15f), LifeColors.Error.copy(0.15f)))
                            else -> androidx.compose.ui.graphics.Brush.linearGradient(listOf(colors.cardBackground, colors.cardBackground))
                        }
                    )
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                if (isUser) Text(message.content, color = Color.White, style = MaterialTheme.typography.bodyMedium)
                else MarkdownText(message.content, color = if (message.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            }
            if (!isUser && !message.isError) {
                Row {
                    IconButton(onClick = onCopy, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Outlined.ContentCopy, stringResource(R.string.cd_copy), Modifier.size(16.dp), tint = colors.textSecondary)
                    }
                    if (onRegenerate != null) {
                        IconButton(onClick = onRegenerate, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Outlined.Refresh, stringResource(R.string.cd_regenerate), Modifier.size(16.dp), tint = colors.textSecondary)
                        }
                    }
                }
            } else if (message.isError && onRegenerate != null) {
                IconButton(onClick = onRegenerate, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Outlined.Refresh, stringResource(R.string.action_retry), Modifier.size(16.dp), tint = colors.textSecondary)
                }
            }
        }
    }
}

@Composable
fun ExpenseCard(transaction: Transaction, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val isIncome = transaction.type == TransactionType.INCOME
    GlassCard(modifier.fillMaxWidth(), onClick = onClick, contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(categoryIcon(transaction.category), categoryColor(transaction.category))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    transaction.description.ifBlank { categoryLabel(transaction.category) },
                    style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                SecondaryText("${categoryLabel(transaction.category)} · ${paymentLabel(transaction.paymentMethod)} · ${TimeUtils.formatDate(transaction.date)}", maxLines = 1)
            }
            Text(
                (if (isIncome) "+" else "−") + Money.format(transaction.amountMinor),
                color = if (isIncome) LocalLifeColors.current.success else MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleSmall,
            )
        }
    }
}

@Composable
fun ReminderCard(
    reminder: Reminder,
    onClick: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val muted = reminder.completed || !reminder.enabled
    GlassCard(modifier.fillMaxWidth(), onClick = onClick, contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(
                if (reminder.completed) LifeIcons.Task else LifeIcons.Reminder,
                if (muted) LocalLifeColors.current.textSecondary else LifeColors.Purple,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    reminder.title, style = MaterialTheme.typography.titleSmall, maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textDecoration = if (reminder.completed) TextDecoration.LineThrough else null,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SecondaryText(TimeUtils.formatDateTime(reminder.scheduledAt))
                    if (reminder.repeatType != RepeatType.NONE) {
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.Filled.Repeat, null, Modifier.size(12.dp), tint = LifeColors.Cyan)
                        Spacer(Modifier.width(2.dp))
                        SecondaryText(repeatLabel(reminder.repeatType, reminder.repeatIntervalDays))
                    }
                }
            }
            if (!reminder.completed) {
                Switch(
                    checked = reminder.enabled, onCheckedChange = onToggleEnabled,
                    colors = SwitchDefaults.colors(checkedTrackColor = LifeColors.Blue),
                )
            }
        }
    }
}

@Composable
fun TaskCard(task: Task, overdue: Boolean, onToggle: () -> Unit, onClick: () -> Unit, modifier: Modifier = Modifier) {
    GlassCard(modifier.fillMaxWidth(), onClick = onClick, contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onToggle) {
                Icon(
                    if (task.completed) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                    contentDescription = stringResource(if (task.completed) R.string.cd_mark_incomplete else R.string.cd_mark_complete),
                    tint = if (task.completed) LocalLifeColors.current.success else LifeColors.Blue,
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    task.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    textDecoration = if (task.completed) TextDecoration.LineThrough else null,
                    color = if (task.completed) LocalLifeColors.current.textSecondary else MaterialTheme.colorScheme.onSurface,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Flag, null, Modifier.size(12.dp), tint = priorityColor(task.priority))
                    Spacer(Modifier.width(4.dp))
                    SecondaryText(priorityLabel(task.priority))
                    task.dueAt?.let { due ->
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (task.hasDueTime) TimeUtils.formatDateTime(due) else TimeUtils.formatDate(due),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (overdue) MaterialTheme.colorScheme.error else LocalLifeColors.current.textSecondary,
                        )
                    }
                    if (task.category.isNotBlank()) {
                        Spacer(Modifier.width(8.dp))
                        SecondaryText("#${task.category}", maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
fun NoteCard(note: Note, onClick: () -> Unit, modifier: Modifier = Modifier) {
    GlassCard(modifier.fillMaxWidth(), onClick = onClick, highlight = note.isPinned) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                note.title.ifBlank { stringResource(R.string.note_untitled) },
                style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (note.isPinned) Icon(Icons.Filled.PushPin, stringResource(R.string.note_pinned), Modifier.size(16.dp), tint = LifeColors.Cyan)
        }
        Spacer(Modifier.size(4.dp))
        if (note.isChecklist) {
            note.checklist.take(4).forEach { item ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (item.checked) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked, null,
                        Modifier.size(14.dp), tint = if (item.checked) LocalLifeColors.current.success else LocalLifeColors.current.textSecondary,
                    )
                    Spacer(Modifier.width(6.dp))
                    SecondaryText(item.text, maxLines = 1)
                }
            }
            if (note.checklist.size > 4) SecondaryText("+${note.checklist.size - 4}")
        } else {
            SecondaryText(note.content, maxLines = 3)
        }
        Spacer(Modifier.size(6.dp))
        Row {
            if (note.category.isNotBlank()) {
                Text(
                    note.category, style = MaterialTheme.typography.labelSmall, color = LifeColors.Cyan,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(LifeColors.Cyan.copy(0.12f)).padding(horizontal = 6.dp, vertical = 2.dp),
                )
                Spacer(Modifier.width(8.dp))
            }
            SecondaryText(TimeUtils.formatDate(note.updatedAt))
        }
    }
}

@Composable
fun FilterChipRow(options: List<Pair<String, Boolean>>, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    androidx.compose.foundation.lazy.LazyRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(options.size) { i ->
            val (label, selected) = options[i]
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) Color.White else LocalLifeColors.current.textSecondary,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (selected) LocalLifeColors.current.brandGradient
                        else androidx.compose.ui.graphics.Brush.linearGradient(listOf(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.colorScheme.surfaceContainerHigh))
                    )
                    .clickable { onSelect(i) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}
