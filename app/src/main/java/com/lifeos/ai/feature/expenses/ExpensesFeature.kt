package com.lifeos.ai.feature.expenses

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.lifeos.ai.R
import com.lifeos.ai.core.ads.AdsManager
import com.lifeos.ai.core.common.Money
import com.lifeos.ai.core.common.TimeUtils
import com.lifeos.ai.core.common.newId
import com.lifeos.ai.core.navigation.Routes
import com.lifeos.ai.core.theme.LifeColors
import com.lifeos.ai.core.theme.LocalLifeColors
import com.lifeos.ai.core.ui.BarGroup
import com.lifeos.ai.core.ui.ChartLegend
import com.lifeos.ai.core.ui.ChartSlice
import com.lifeos.ai.core.ui.ConfirmationDialog
import com.lifeos.ai.core.ui.CustomTextField
import com.lifeos.ai.core.ui.CustomTopBar
import com.lifeos.ai.core.ui.DateTimeRow
import com.lifeos.ai.core.ui.DonutChart
import com.lifeos.ai.core.ui.EmptyState
import com.lifeos.ai.core.ui.ExpenseCard
import com.lifeos.ai.core.ui.FullScreenLoading
import com.lifeos.ai.core.ui.GlassCard
import com.lifeos.ai.core.ui.GradientButton
import com.lifeos.ai.core.ui.GradientProgressBar
import com.lifeos.ai.core.ui.GroupedBarChart
import com.lifeos.ai.core.ui.LifeIcons
import com.lifeos.ai.core.ui.LocalDatePickerDialog
import com.lifeos.ai.core.ui.SecondaryText
import com.lifeos.ai.core.ui.SectionHeader
import com.lifeos.ai.core.ui.categoryColor
import com.lifeos.ai.core.ui.categoryLabel
import com.lifeos.ai.core.ui.paymentLabel
import com.lifeos.ai.domain.model.DefaultCategories
import com.lifeos.ai.domain.model.MonthlySummary
import com.lifeos.ai.domain.model.PaymentMethod
import com.lifeos.ai.domain.model.Transaction
import com.lifeos.ai.domain.model.TransactionType
import com.lifeos.ai.domain.repository.TransactionRepository
import com.lifeos.ai.domain.usecase.ExpenseCalculator
import com.lifeos.ai.domain.usecase.MonthTotals
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import javax.inject.Inject

// ============================================================ list + summary

data class ExpensesUiState(
    val loading: Boolean = true,
    val month: YearMonth = YearMonth.now(),
    val summary: MonthlySummary? = null,
    val transactions: List<Transaction> = emptyList(),
    val typeFilter: TransactionType? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ExpensesViewModel @Inject constructor(private val repo: TransactionRepository) : ViewModel() {
    private val month = MutableStateFlow(YearMonth.now())
    private val typeFilter = MutableStateFlow<TransactionType?>(null)

    val state: StateFlow<ExpensesUiState> = month.flatMapLatest { m ->
        combine(repo.observeTransactions(), repo.observeBudget(m), typeFilter) { all, budget, type ->
            val summary = ExpenseCalculator.monthlySummary(all, m, budget)
            val zone = ZoneId.systemDefault()
            val inMonth = all.filter { YearMonth.from(Instant.ofEpochMilli(it.date).atZone(zone)) == m }
            ExpensesUiState(false, m, summary, inMonth.filter { type == null || it.type == type }, type)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ExpensesUiState())

    fun previousMonth() = month.update { it.minusMonths(1) }
    fun nextMonth() = month.update { if (it < YearMonth.now()) it.plusMonths(1) else it }
    fun setType(t: TransactionType?) { typeFilter.value = t }
    fun setBudget(amountText: String): Boolean {
        val minor = if (amountText.isBlank()) null else Money.parseToMinor(amountText) ?: return false
        viewModelScope.launch { repo.setBudget(month.value, minor) }
        return true
    }
}

@Composable
fun ExpensesScreen(onBack: () -> Unit, navigate: (Any) -> Unit, viewModel: ExpensesViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showBudget by remember { mutableStateOf(false) }
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            Column(Modifier.statusBarsPadding()) {
                CustomTopBar(stringResource(R.string.expenses_title), onBack = onBack, actions = {
                    IconButton(onClick = { navigate(Routes.ExpenseReports) }) { Icon(Icons.Filled.BarChart, stringResource(R.string.expense_reports)) }
                })
            }
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { navigate(Routes.TransactionEditor()) }, containerColor = LifeColors.Success, modifier = Modifier.navigationBarsPadding()) {
                Icon(Icons.Filled.Add, stringResource(R.string.qa_add_expense), tint = Color.White)
            }
        },
    ) { padding ->
        if (state.loading) { FullScreenLoading(); return@Scaffold }
        val summary = state.summary!!
        LazyColumn(
            Modifier.padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 96.dp),
        ) {
            item { MonthSwitcher(state.month, viewModel::previousMonth, viewModel::nextMonth) }
            item {
                GlassCard(Modifier.fillMaxWidth()) {
                    Row {
                        SummaryCell(stringResource(R.string.expense_income), Money.format(summary.incomeMinor), LocalLifeColors.current.success, Modifier.weight(1f))
                        SummaryCell(stringResource(R.string.expense_spent), Money.format(summary.expenseMinor), MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
                        SummaryCell(stringResource(R.string.expense_savings), Money.format(summary.savingsMinor),
                            if (summary.savingsMinor < 0) MaterialTheme.colorScheme.error else LifeColors.Cyan, Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            if (summary.budgetMinor != null) {
                                SecondaryText(stringResource(R.string.expense_budget_used, ((summary.budgetUsedFraction ?: 0f) * 100).toInt(), Money.format(summary.budgetMinor)))
                                Spacer(Modifier.height(6.dp))
                                GradientProgressBar(summary.budgetUsedFraction ?: 0f)
                                if ((summary.budgetUsedFraction ?: 0f) > 1f) {
                                    Text(stringResource(R.string.expense_over_budget), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                                }
                            } else SecondaryText(stringResource(R.string.expense_no_budget))
                        }
                        TextButton(onClick = { showBudget = true }) { Text(stringResource(R.string.expense_set_budget)) }
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(state.typeFilter == null, { viewModel.setType(null) }, { Text(stringResource(R.string.filter_all)) })
                    FilterChip(state.typeFilter == TransactionType.EXPENSE, { viewModel.setType(TransactionType.EXPENSE) }, { Text(stringResource(R.string.expense_type_expense)) })
                    FilterChip(state.typeFilter == TransactionType.INCOME, { viewModel.setType(TransactionType.INCOME) }, { Text(stringResource(R.string.expense_type_income)) })
                }
            }
            if (state.transactions.isEmpty()) {
                item {
                    EmptyState(LifeIcons.Expense, stringResource(R.string.expenses_empty_title), stringResource(R.string.expenses_empty_body),
                        actionLabel = stringResource(R.string.qa_add_expense), onAction = { navigate(Routes.TransactionEditor()) })
                }
            } else {
                items(state.transactions, key = { it.id }) { tx -> ExpenseCard(tx, onClick = { navigate(Routes.TransactionEditor(tx.id)) }) }
            }
            item { SecondaryText(stringResource(R.string.expense_disclaimer), Modifier.padding(top = 8.dp)) }
        }
    }
    if (showBudget) BudgetDialog(state.summary?.budgetMinor, onDismiss = { showBudget = false }, onSave = { text -> if (viewModel.setBudget(text)) showBudget = false })
}

@Composable
private fun BudgetDialog(current: Long?, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(current?.let { Money.toPlain(it) } ?: "") }
    var invalid by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.expense_set_budget)) },
        text = {
            Column {
                SecondaryText(stringResource(R.string.expense_budget_hint))
                Spacer(Modifier.height(8.dp))
                CustomTextField(text, { text = it; invalid = false }, stringResource(R.string.field_amount), keyboardType = KeyboardType.Decimal,
                    error = if (invalid) stringResource(R.string.error_amount_invalid) else null)
            }
        },
        confirmButton = { TextButton(onClick = { invalid = text.isNotBlank() && Money.parseToMinor(text) == null; if (!invalid) onSave(text) }) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun SummaryCell(label: String, value: String, color: Color, modifier: Modifier) {
    Column(modifier) {
        SecondaryText(label)
        Text(value, style = MaterialTheme.typography.titleMedium, color = color, maxLines = 1)
    }
}

@Composable
fun MonthSwitcher(month: YearMonth, onPrev: () -> Unit, onNext: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onPrev) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, stringResource(R.string.cd_previous_month)) }
        Text(TimeUtils.formatMonth(month), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        IconButton(onClick = onNext, enabled = month < YearMonth.now()) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.cd_next_month)) }
    }
}

// ============================================================ editor

data class TransactionEditorState(
    val loading: Boolean = true,
    val isNew: Boolean = true,
    val type: TransactionType = TransactionType.EXPENSE,
    val amount: String = "",
    val category: String = DefaultCategories.FOOD,
    val payment: PaymentMethod = PaymentMethod.UPI,
    val description: String = "",
    val date: LocalDate = LocalDate.now(),
    val customCategories: List<String> = emptyList(),
    val amountError: Boolean = false,
    val saving: Boolean = false,
    val error: String? = null,
    val done: Boolean = false,
)

@HiltViewModel
class TransactionEditorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repo: TransactionRepository,
) : ViewModel() {
    private val route = savedStateHandle.toRoute<Routes.TransactionEditor>()
    private var original: Transaction? = null
    private val _state = MutableStateFlow(TransactionEditorState(type = if (route.income) TransactionType.INCOME else TransactionType.EXPENSE))
    val state: StateFlow<TransactionEditorState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val t = route.transactionId?.let { repo.getTransaction(it) }
            original = t
            _state.update { s ->
                if (t == null) s.copy(loading = false, category = if (s.type == TransactionType.INCOME) DefaultCategories.SALARY else DefaultCategories.FOOD)
                else s.copy(
                    loading = false, isNew = false, type = t.type, amount = Money.toPlain(t.amountMinor), category = t.category,
                    payment = t.paymentMethod, description = t.description, date = TimeUtils.toLocalDate(t.date),
                )
            }
        }
        viewModelScope.launch {
            repo.observeCustomCategories().collect { c -> _state.update { it.copy(customCategories = c) } }
        }
    }

    fun update(t: (TransactionEditorState) -> TransactionEditorState) = _state.update { t(it).copy(error = null) }

    fun setType(type: TransactionType) = _state.update {
        it.copy(type = type, category = if (type == TransactionType.INCOME) DefaultCategories.SALARY else DefaultCategories.FOOD)
    }

    fun addCategory(name: String) = viewModelScope.launch {
        runCatching { repo.addCustomCategory(name) }.onSuccess { _state.update { it.copy(category = name.trim()) } }
    }

    fun deleteCategory(name: String) = viewModelScope.launch {
        repo.deleteCustomCategory(name)
        _state.update { if (it.category == name) it.copy(category = DefaultCategories.OTHER) else it }
    }

    fun save() {
        val s = _state.value
        val minor = Money.parseToMinor(s.amount)
        if (minor == null) { _state.update { it.copy(amountError = true) }; return }
        viewModelScope.launch {
            _state.update { it.copy(saving = true) }
            val now = System.currentTimeMillis()
            // Keep the original time-of-day when editing; new entries use the current time on the chosen date.
            val time = original?.let { TimeUtils.toLocalDateTime(it.date).toLocalTime() } ?: LocalTime.now()
            val base = original ?: Transaction(newId(), 0, s.type, s.category, date = now, createdAt = now, updatedAt = now)
            runCatching {
                repo.upsert(base.copy(amountMinor = minor, type = s.type, category = s.category, paymentMethod = s.payment,
                    description = s.description, date = TimeUtils.toEpoch(s.date, time)))
            }.onSuccess { _state.update { it.copy(saving = false, done = true) } }
                .onFailure { e -> _state.update { it.copy(saving = false, error = e.message) } }
        }
    }

    fun delete() {
        val id = route.transactionId ?: return
        viewModelScope.launch { repo.delete(id); _state.update { it.copy(done = true) } }
    }
}

@Composable
fun TransactionEditorScreen(onBack: () -> Unit, viewModel: TransactionEditorViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showDate by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var newCategory by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(state.done) { if (state.done) onBack() }
    if (state.loading) { FullScreenLoading(); return }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
        CustomTopBar(
            stringResource(if (state.isNew) R.string.tx_new else R.string.tx_edit), onBack = onBack,
            actions = {
                if (!state.isNew) IconButton(onClick = { confirmDelete = true }) {
                    Icon(Icons.Filled.Delete, stringResource(R.string.action_delete), tint = MaterialTheme.colorScheme.error)
                }
            },
        )
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf(TransactionType.EXPENSE, TransactionType.INCOME).forEachIndexed { i, t ->
                    SegmentedButton(
                        selected = state.type == t, onClick = { viewModel.setType(t) },
                        shape = SegmentedButtonDefaults.itemShape(i, 2),
                    ) { Text(stringResource(if (t == TransactionType.EXPENSE) R.string.expense_type_expense else R.string.expense_type_income)) }
                }
            }
            Spacer(Modifier.height(12.dp))
            CustomTextField(state.amount, { v -> viewModel.update { it.copy(amount = v, amountError = false) } }, stringResource(R.string.field_amount),
                keyboardType = KeyboardType.Decimal, error = if (state.amountError) stringResource(R.string.error_amount_invalid) else null, maxLength = 12,
                placeholder = "₹ 0")
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.field_category), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            val defaults = if (state.type == TransactionType.EXPENSE) DefaultCategories.expense else DefaultCategories.income
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                defaults.forEach { c ->
                    FilterChip(state.category == c, { viewModel.update { it.copy(category = c) } }, { Text(categoryLabel(c)) })
                }
                state.customCategories.forEach { c ->
                    InputChip(
                        selected = state.category == c, onClick = { viewModel.update { it.copy(category = c) } },
                        label = { Text(c) },
                        trailingIcon = {
                            Icon(Icons.Filled.Close, stringResource(R.string.action_delete), Modifier.size(16.dp).clickableNoRipple { viewModel.deleteCategory(c) })
                        },
                    )
                }
                FilterChip(false, { newCategory = "" }, { Text(stringResource(R.string.add_category)) }, leadingIcon = { Icon(Icons.Filled.Add, null, Modifier.size(16.dp)) })
            }
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.field_payment_method), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PaymentMethod.entries.forEach { p ->
                    FilterChip(state.payment == p, { viewModel.update { it.copy(payment = p) } }, { Text(paymentLabel(p)) })
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.field_date), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            DateTimeRow(TimeUtils.formatDate(TimeUtils.toEpoch(state.date, LocalTime.NOON)), null, { showDate = true }, null)
            Spacer(Modifier.height(8.dp))
            CustomTextField(state.description, { v -> viewModel.update { it.copy(description = v) } }, stringResource(R.string.field_description), maxLength = 500)
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        GradientButton(stringResource(R.string.action_save), viewModel::save, Modifier.fillMaxWidth().padding(20.dp), loading = state.saving)
    }
    if (showDate) LocalDatePickerDialog(state.date, { showDate = false }) { d ->
        if (!d.isAfter(LocalDate.now())) viewModel.update { it.copy(date = d) }
    }
    if (confirmDelete) ConfirmationDialog(
        title = stringResource(R.string.tx_delete_title), message = stringResource(R.string.delete_irreversible),
        confirmLabel = stringResource(R.string.action_delete), destructive = true,
        onConfirm = { confirmDelete = false; viewModel.delete() }, onDismiss = { confirmDelete = false },
    )
    newCategory?.let { value ->
        AlertDialog(
            onDismissRequest = { newCategory = null },
            title = { Text(stringResource(R.string.add_category)) },
            text = { CustomTextField(value, { newCategory = it }, stringResource(R.string.field_name), maxLength = 30, leadingIcon = Icons.Filled.Edit) },
            confirmButton = {
                TextButton(enabled = value.isNotBlank(), onClick = { viewModel.addCategory(value); newCategory = null }) { Text(stringResource(R.string.action_add)) }
            },
            dismissButton = { TextButton(onClick = { newCategory = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier = this.clickable(onClick = onClick)

// ============================================================ reports

data class ReportsUiState(
    val loading: Boolean = true,
    val month: YearMonth = YearMonth.now(),
    val summary: MonthlySummary? = null,
    val trend: List<MonthTotals> = emptyList(),
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ExpenseReportsViewModel @Inject constructor(private val repo: TransactionRepository) : ViewModel() {
    private val month = MutableStateFlow(YearMonth.now())
    val state: StateFlow<ReportsUiState> = month.flatMapLatest { m ->
        combine(repo.observeTransactions(), repo.observeBudget(m)) { all, budget ->
            ReportsUiState(false, m, ExpenseCalculator.monthlySummary(all, m, budget), ExpenseCalculator.monthlyTotals(all, m, 6))
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReportsUiState())

    fun previousMonth() = month.update { it.minusMonths(1) }
    fun nextMonth() = month.update { if (it < YearMonth.now()) it.plusMonths(1) else it }
}

@Composable
fun ExpenseReportsScreen(onBack: () -> Unit, adsManager: AdsManager, viewModel: ExpenseReportsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val activity = LocalContext.current as? Activity
    // Leaving the report screen is a natural break: eligible for a frequency-capped interstitial.
    val leave = { activity?.let { adsManager.onNaturalTransition(it) }; onBack() }
    BackHandler(onBack = leave)
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        CustomTopBar(stringResource(R.string.expense_reports), onBack = leave)
        if (state.loading) { FullScreenLoading(); return@Column }
        val summary = state.summary!!
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding()) {
            MonthSwitcher(state.month, viewModel::previousMonth, viewModel::nextMonth)
            SectionHeader(stringResource(R.string.report_by_category))
            GlassCard(Modifier.fillMaxWidth()) {
                if (summary.byCategory.isEmpty()) {
                    SecondaryText(stringResource(R.string.report_no_expenses))
                } else {
                    val slices = summary.byCategory.map { (cat, v) -> ChartSlice(categoryLabel(cat), v, categoryColor(cat)) }
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        DonutChart(slices, stringResource(R.string.expense_spent), Money.format(summary.expenseMinor))
                    }
                    Spacer(Modifier.height(16.dp))
                    ChartLegend(slices, { Money.format(it) })
                }
            }
            SectionHeader(stringResource(R.string.report_monthly_expenses))
            GlassCard(Modifier.fillMaxWidth()) {
                GroupedBarChart(
                    state.trend.map { BarGroup(it.month.month.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault()), listOf(it.expenseMinor)) },
                    listOf(LifeColors.Purple),
                )
            }
            SectionHeader(stringResource(R.string.report_income_vs_expense))
            GlassCard(Modifier.fillMaxWidth()) {
                GroupedBarChart(
                    state.trend.map { BarGroup(it.month.month.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault()), listOf(it.incomeMinor, it.expenseMinor)) },
                    listOf(LifeColors.Success, LifeColors.Error),
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    LegendDot(LifeColors.Success, stringResource(R.string.expense_income))
                    LegendDot(LifeColors.Error, stringResource(R.string.expense_spent))
                }
                Spacer(Modifier.height(8.dp))
                state.trend.lastOrNull()?.let {
                    SecondaryText(stringResource(R.string.report_savings_line, Money.format(it.incomeMinor - it.expenseMinor)))
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(color, CircleShape))
        Spacer(Modifier.size(6.dp))
        SecondaryText(label)
    }
}
