package com.lifeos.ai.feature.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lifeos.ai.R
import com.lifeos.ai.core.navigation.Routes
import com.lifeos.ai.core.theme.LifeColors
import com.lifeos.ai.core.ui.CustomTopBar
import com.lifeos.ai.core.ui.GlassCard
import com.lifeos.ai.core.ui.LifeIcons
import com.lifeos.ai.core.ui.NeonIcon
import com.lifeos.ai.core.ui.SecondaryText

private data class Tool(val icon: ImageVector, val title: Int, val subtitle: Int, val color: Color, val route: Any)

@Composable
fun ToolsScreen(navigate: (Any) -> Unit) {
    val tools = listOf(
        Tool(LifeIcons.Reminder, R.string.reminders_title, R.string.tools_reminders_sub, LifeColors.Purple, Routes.Reminders),
        Tool(LifeIcons.Expense, R.string.expenses_title, R.string.tools_expenses_sub, LifeColors.Success, Routes.Expenses),
        Tool(LifeIcons.Note, R.string.notes_title, R.string.tools_notes_sub, LifeColors.Warning, Routes.Notes),
        Tool(LifeIcons.Task, R.string.tasks_title, R.string.tools_tasks_sub, LifeColors.Blue, Routes.Tasks),
        Tool(LifeIcons.Documents, R.string.documents_title, R.string.tools_documents_sub, LifeColors.Cyan, Routes.Documents),
        Tool(LifeIcons.Mic, R.string.voice_title, R.string.tools_voice_sub, LifeColors.Pink, Routes.Voice),
    )
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        CustomTopBar(stringResource(R.string.tab_tools))
        LazyVerticalGrid(
            columns = GridCells.Adaptive(150.dp),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) { SecondaryText(stringResource(R.string.tools_subtitle)) }
            items(tools) { t ->
                GlassCard(Modifier.fillMaxWidth().height(150.dp), onClick = { navigate(t.route) }) {
                    NeonIcon(t.icon, t.color, size = 50.dp)
                    Spacer(Modifier.weight(1f))
                    Text(stringResource(t.title), style = MaterialTheme.typography.titleMedium)
                    SecondaryText(stringResource(t.subtitle), maxLines = 2)
                }
            }
        }
    }
}
