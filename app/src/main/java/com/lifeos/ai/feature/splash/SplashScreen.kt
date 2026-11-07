package com.lifeos.ai.feature.splash

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.lifeos.ai.R
import com.lifeos.ai.core.theme.LifeColors
import com.lifeos.ai.core.theme.LocalLifeColors
import com.lifeos.ai.data.preferences.UserPreferencesRepository
import com.lifeos.ai.data.repository.SessionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import javax.inject.Inject
import kotlin.math.cos
import kotlin.math.sin

enum class StartDestination { ONBOARDING, LOGIN, MAIN }

@HiltViewModel
class SplashViewModel @Inject constructor(
    private val prefs: UserPreferencesRepository,
    private val session: SessionManager,
) : ViewModel() {
    suspend fun resolve(): StartDestination {
        val s = prefs.current()
        return when {
            !s.onboardingDone -> StartDestination.ONBOARDING
            session.isSignedIn || s.guestMode -> StartDestination.MAIN
            else -> StartDestination.LOGIN
        }
    }
}

@Composable
fun SplashScreen(
    onOnboarding: () -> Unit,
    onLogin: () -> Unit,
    onMain: () -> Unit,
    viewModel: SplashViewModel = hiltViewModel(),
) {
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        reveal.animateTo(1f, tween(650))
        val dest = viewModel.resolve()
        delay(450)
        when (dest) {
            StartDestination.ONBOARDING -> onOnboarding()
            StartDestination.LOGIN -> onLogin()
            StartDestination.MAIN -> onMain()
        }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AiOrbBackground(Modifier.fillMaxSize())
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.alpha(reveal.value).scale(0.9f + 0.1f * reveal.value)) {
            LifeOSLogo(size = 96.dp)
            Spacer(Modifier.height(20.dp))
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.tagline), color = LocalLifeColors.current.textSecondary, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

/** Animated neural "orbit" background used on splash/onboarding. */
@Composable
fun AiOrbBackground(modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "orb")
    val phase by t.animateFloat(0f, 360f, infiniteRepeatable(tween(14000, easing = LinearEasing)), label = "phase")
    Canvas(modifier) {
        drawCircle(
            Brush.radialGradient(listOf(LifeColors.Purple.copy(0.25f), Color.Transparent), center = Offset(size.width * 0.2f, size.height * 0.25f), radius = size.width * 0.7f),
            radius = size.width * 0.7f, center = Offset(size.width * 0.2f, size.height * 0.25f),
        )
        drawCircle(
            Brush.radialGradient(listOf(LifeColors.Blue.copy(0.22f), Color.Transparent), center = Offset(size.width * 0.85f, size.height * 0.75f), radius = size.width * 0.8f),
            radius = size.width * 0.8f, center = Offset(size.width * 0.85f, size.height * 0.75f),
        )
        drawOrbitNodes(phase)
    }
}

private fun DrawScope.drawOrbitNodes(phase: Float) {
    val c = center
    val rings = listOf(size.minDimension * 0.28f, size.minDimension * 0.4f)
    rings.forEachIndexed { i, r ->
        drawCircle(LifeColors.Blue.copy(alpha = 0.10f), radius = r, center = c, style = Stroke(1.5f))
        repeat(5) { k ->
            val a = Math.toRadians((phase * (if (i == 0) 1f else -0.6f) + k * 72f).toDouble())
            val p = Offset(c.x + r * cos(a).toFloat(), c.y + r * sin(a).toFloat())
            drawCircle(if (k % 2 == 0) LifeColors.Cyan.copy(0.6f) else LifeColors.Purple.copy(0.6f), radius = 4f, center = p)
        }
    }
}

/**
 * The LifeOS AI mark, drawn from the same vector as the launcher icon foreground so the app icon,
 * system splash and in-app logo always match. The adaptive-icon canvas keeps the mark inside the
 * central ~60%, so it is scaled up to fill [size].
 */
@Composable
fun LifeOSLogo(size: Dp) {
    Image(
        painter = painterResource(R.drawable.ic_launcher_foreground),
        contentDescription = null,
        modifier = Modifier.size(size).graphicsLayer {
            scaleX = LOGO_SCALE
            scaleY = LOGO_SCALE
        },
    )
}

private const val LOGO_SCALE = 1.6f
