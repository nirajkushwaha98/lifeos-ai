package com.lifeos.ai.feature.onboarding

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lifeos.ai.R
import com.lifeos.ai.core.theme.LifeColors
import com.lifeos.ai.core.theme.LocalLifeColors
import com.lifeos.ai.core.ui.GradientButton
import com.lifeos.ai.core.ui.LifeIcons
import com.lifeos.ai.data.preferences.UserPreferencesRepository
import com.lifeos.ai.feature.splash.AiOrbBackground
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class OnboardingViewModel @Inject constructor(private val prefs: UserPreferencesRepository) : ViewModel() {
    fun complete(then: () -> Unit) {
        viewModelScope.launch { prefs.setOnboardingDone(); then() }
    }
}

private data class Page(val icon: ImageVector, val title: Int, val body: Int, val accent: Color)

private val pages = listOf(
    Page(LifeIcons.Task, R.string.onboarding_title_1, R.string.onboarding_body_1, LifeColors.Blue),
    Page(LifeIcons.Spark, R.string.onboarding_title_2, R.string.onboarding_body_2, LifeColors.Purple),
    Page(LifeIcons.Tools, R.string.onboarding_title_3, R.string.onboarding_body_3, LifeColors.Cyan),
)

@Composable
fun OnboardingScreen(onDone: () -> Unit, viewModel: OnboardingViewModel = hiltViewModel()) {
    val pager = rememberPagerState { pages.size }
    val scope = rememberCoroutineScope()
    val finish = { viewModel.complete(onDone) }
    Box(Modifier.fillMaxSize()) {
        AiOrbBackground(Modifier.fillMaxSize())
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(24.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (pager.currentPage < pages.lastIndex) {
                    TextButton(onClick = finish) { Text(stringResource(R.string.action_skip)) }
                } else Spacer(Modifier.height(48.dp))
            }
            HorizontalPager(state = pager, modifier = Modifier.weight(1f)) { index ->
                val page = pages[index]
                Column(
                    Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Illustration(page.icon, page.accent)
                    Spacer(Modifier.height(40.dp))
                    Text(stringResource(page.title), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(page.body), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center,
                        color = LocalLifeColors.current.textSecondary,
                    )
                }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 20.dp), horizontalArrangement = Arrangement.Center) {
                pages.indices.forEach { i ->
                    val w by animateDpAsState(if (i == pager.currentPage) 24.dp else 8.dp, label = "dot")
                    Box(
                        Modifier.padding(horizontal = 4.dp).height(8.dp).width(w).clip(CircleShape)
                            .background(if (i == pager.currentPage) LifeColors.Cyan else LocalLifeColors.current.textSecondary.copy(0.4f)),
                    )
                }
            }
            val last = pager.currentPage == pages.lastIndex
            GradientButton(
                text = stringResource(if (last) R.string.action_get_started else R.string.action_next),
                onClick = { if (last) finish() else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun Illustration(icon: ImageVector, accent: Color) {
    Box(Modifier.size(220.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(220.dp)) {
            drawCircle(Brush.radialGradient(listOf(accent.copy(0.35f), Color.Transparent)), radius = size.minDimension / 2)
            drawCircle(accent.copy(0.25f), radius = size.minDimension * 0.34f, style = androidx.compose.ui.graphics.drawscope.Stroke(2f))
            drawCircle(accent.copy(0.15f), radius = size.minDimension * 0.44f, style = androidx.compose.ui.graphics.drawscope.Stroke(1.5f))
            drawCircle(LifeColors.Cyan, radius = 6f, center = Offset(size.width * 0.82f, size.height * 0.3f))
            drawCircle(LifeColors.Purple, radius = 5f, center = Offset(size.width * 0.18f, size.height * 0.7f))
        }
        Box(
            Modifier.size(112.dp).clip(RoundedCornerShape(34.dp))
                .background(Brush.linearGradient(listOf(accent, LifeColors.Purple)))
                .border(1.5.dp, Brush.linearGradient(listOf(Color.White.copy(alpha = 0.6f), Color.White.copy(alpha = 0.05f))), RoundedCornerShape(34.dp)),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(60.dp)) }
    }
}
