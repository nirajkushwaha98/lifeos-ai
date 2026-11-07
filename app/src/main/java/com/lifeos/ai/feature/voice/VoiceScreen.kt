package com.lifeos.ai.feature.voice

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lifeos.ai.R
import com.lifeos.ai.core.theme.LifeColors
import com.lifeos.ai.core.theme.LifeOSTheme
import com.lifeos.ai.core.theme.ThemeMode
import com.lifeos.ai.core.ui.CustomTextField
import com.lifeos.ai.core.ui.GlassCard
import com.lifeos.ai.core.ui.GradientButton
import com.lifeos.ai.core.ui.LifeIcons
import com.lifeos.ai.core.ui.NeonIcon
import com.lifeos.ai.feature.assistant.PendingAction

/**
 * Full-screen voice mode: a living dot-sphere in the middle, live captions underneath, and three
 * controls (continue in chat · mic · end). Always dark, like a call screen, whatever the app theme.
 */
@Composable
fun VoiceScreen(
    onBack: () -> Unit,
    navigate: (Any) -> Unit,
    onOpenChat: (conversationId: String?) -> Unit,
    viewModel: VoiceViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val level = viewModel.level.collectAsStateWithLifecycle()
    val pulse = viewModel.wordPulse.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var micGranted by remember { mutableStateOf(context.hasMicPermission()) }
    var askedForMic by rememberSaveable { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        micGranted = granted
        viewModel.onPermissionResult(granted)
    }
    val requestMic: () -> Unit = {
        val activity = context.findActivity()
        val canAsk = !askedForMic ||
            (activity != null && ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.RECORD_AUDIO))
        if (canAsk) {
            askedForMic = true
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            context.openAppSettings() // "Don't ask again" was chosen, so the system dialog can't appear.
        }
    }

    LaunchedEffect(Unit) { viewModel.navigation.collect { navigate(it) } }
    // "End" in the notification closes voice mode.
    LaunchedEffect(Unit) { viewModel.closeRequests.collect { onBack() } }
    // Leaving voice mode ends the conversation; a configuration change (e.g. rotation) doesn't.
    DisposableEffect(Unit) {
        onDispose { if (context.findActivity()?.isChangingConfigurations != true) viewModel.endSession() }
    }
    // Voice mode opens ready to talk, so the microphone is requested right away.
    LaunchedEffect(Unit) { if (!micGranted && !askedForMic) requestMic() }
    LifecycleStartEffect(Unit) {
        micGranted = context.hasMicPermission()
        viewModel.onForeground(micGranted)
        onStopOrDispose { viewModel.onBackground() }
    }
    VoiceWindowEffects()

    LifeOSTheme(themeMode = ThemeMode.DARK) {
        VoiceContent(
            state = state,
            level = { level.value },
            pulse = { pulse.value },
            onOrbTap = { if (micGranted) viewModel.onOrbTap() else requestMic() },
            onMic = { if (micGranted) viewModel.onMicButton() else requestMic() },
            onToggleLanguage = viewModel::toggleLanguage,
            onPrompt = viewModel::handleUtterance,
            onPendingTitle = viewModel::updatePendingTitle,
            onConfirm = viewModel::confirmPending,
            onCancelPending = viewModel::cancelPending,
            onOpenChat = { onOpenChat(state.conversationId) },
            onEnd = onBack,
        )
    }
}

@Composable
private fun VoiceContent(
    state: VoiceUiState,
    level: () -> Float,
    pulse: () -> Int,
    onOrbTap: () -> Unit,
    onMic: () -> Unit,
    onToggleLanguage: () -> Unit,
    onPrompt: (String) -> Unit,
    onPendingTitle: (String) -> Unit,
    onConfirm: () -> Unit,
    onCancelPending: () -> Unit,
    onOpenChat: () -> Unit,
    onEnd: () -> Unit,
) {
    val wash by animateColorAsState(washFor(state.orbMode), tween(900), label = "wash")
    val status = statusText(state)
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(VoiceTop, VoiceBottom)))
            .drawBehind {
                drawRect(
                    Brush.radialGradient(
                        listOf(wash, Color.Transparent),
                        center = Offset(size.width / 2f, size.height * 0.36f),
                        radius = size.maxDimension * 0.62f,
                    ),
                )
            },
    ) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            VoiceTopBar(state.language, live = state.phase != VoicePhase.IDLE, onToggleLanguage = onToggleLanguage)
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val orbSize = minOf(maxWidth * 0.92f, maxHeight * 0.58f, 380.dp)
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                    ParticleOrb(
                        mode = state.orbMode,
                        level = level,
                        pulse = pulse,
                        onTap = onOrbTap,
                        contentDescription = stringResource(R.string.voice_orb_label, stringResource(status)),
                        modifier = Modifier.size(orbSize),
                    )
                    StatusLine(status, thinking = state.phase == VoicePhase.THINKING)
                    Captions(state, Modifier.weight(1f).fillMaxWidth())
                }
            }

            // Keep the last confirmation on screen while it animates away.
            val retained = remember { mutableStateOf<PendingAction?>(null) }
            SideEffect { state.pending?.let { retained.value = it } }
            AnimatedVisibility(
                visible = state.pending != null,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                (state.pending ?: retained.value)?.let { ConfirmPanel(it, onPendingTitle, onConfirm, onCancelPending) }
            }
            AnimatedVisibility(
                visible = state.conversationId == null && state.pending == null,
                enter = fadeIn(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                StarterPrompts(onPrompt)
            }
            VoiceControls(state, onMic = onMic, onOpenChat = onOpenChat, onEnd = onEnd)
        }
    }
}

@StringRes
private fun statusText(state: VoiceUiState): Int = when {
    !state.recognizerAvailable -> R.string.voice_not_available
    state.needsPermission -> R.string.voice_need_mic
    state.phase == VoicePhase.LISTENING -> R.string.voice_listening
    state.phase == VoicePhase.THINKING -> R.string.voice_thinking
    state.phase == VoicePhase.SPEAKING -> R.string.voice_speaking
    state.pending != null -> R.string.voice_confirm_below
    state.muted -> R.string.voice_muted
    state.hint != null -> state.hint
    else -> R.string.voice_tap_orb
}

@Composable
private fun VoiceTopBar(language: VoiceLanguage, live: Boolean, onToggleLanguage: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LiveDot(live)
        Spacer(Modifier.width(8.dp))
        Text(
            stringResource(R.string.voice_mode_title),
            style = MaterialTheme.typography.titleSmall,
            color = Color.White.copy(alpha = 0.92f),
        )
        Spacer(Modifier.weight(1f))
        LanguageSwitch(language, onToggleLanguage)
    }
}

@Composable
private fun LiveDot(live: Boolean) {
    val transition = rememberInfiniteTransition(label = "live")
    val ring by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "ring")
    val color = if (live) LifeColors.Cyan else Color.White.copy(alpha = 0.35f)
    Canvas(Modifier.size(16.dp)) {
        if (live) drawCircle(color.copy(alpha = 0.45f * (1f - ring)), radius = size.minDimension / 2f * (0.35f + 0.65f * ring))
        drawCircle(color, radius = 3.5.dp.toPx())
    }
}

@Composable
private fun LanguageSwitch(language: VoiceLanguage, onToggle: () -> Unit) {
    val description = stringResource(R.string.voice_language_switch, stringResource(language.label))
    Row(
        Modifier
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.07f))
            .border(1.dp, Color.White.copy(alpha = 0.12f), CircleShape)
            .clearAndSetSemantics {
                contentDescription = description
                role = Role.Button
                onClick { onToggle(); true }
            }
            .clickable(onClick = onToggle)
            .padding(3.dp),
    ) {
        VoiceLanguage.entries.forEach { option ->
            val selected = option == language
            Text(
                stringResource(option.label),
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) Color.White else Color.White.copy(alpha = 0.5f),
                modifier = Modifier
                    .clip(CircleShape)
                    .then(if (selected) Modifier.background(SelectedLanguage) else Modifier)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun StatusLine(@StringRes text: Int, thinking: Boolean) {
    AnimatedContent(
        targetState = text,
        transitionSpec = { fadeIn(tween(200, delayMillis = 90)) togetherWith fadeOut(tween(90)) },
        label = "status",
    ) { res ->
        val label = stringResource(res)
        val style = MaterialTheme.typography.titleMedium
        Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp), contentAlignment = Alignment.Center) {
            if (thinking && res == R.string.voice_thinking) {
                ShimmerText(label, style)
            } else {
                Text(label, style = style, color = Color.White.copy(alpha = 0.8f), textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun ShimmerText(text: String, style: TextStyle) {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val x by transition.animateFloat(-260f, 900f, infiniteRepeatable(tween(1500, easing = LinearEasing)), label = "shimmerX")
    val brush = Brush.linearGradient(
        listOf(Color.White.copy(alpha = 0.35f), Color.White, Color.White.copy(alpha = 0.35f)),
        start = Offset(x, 0f),
        end = Offset(x + 240f, 0f),
    )
    Text(text, style = style.merge(TextStyle(brush = brush)), textAlign = TextAlign.Center)
}

/** What you said (dim) and the reply, which lights up word by word as it is spoken. */
@Composable
private fun Captions(state: VoiceUiState, modifier: Modifier = Modifier) {
    val scroll = rememberScrollState()
    LaunchedEffect(state.reply) { scroll.scrollTo(0) }
    val listening = state.phase == VoicePhase.LISTENING
    // The last exchange stays readable while listening, until the user actually starts speaking.
    val speakingNow = listening && state.partial.isNotBlank()
    val userLine = if (speakingNow) state.partial else state.userText
    val showReply = !speakingNow && state.pending == null && state.reply.isNotBlank()
    Column(
        modifier
            .fadingEdges()
            .verticalScroll(scroll)
            .padding(horizontal = 28.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (userLine.isNotBlank()) {
            Text(
                userLine,
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = if (speakingNow) 0.95f else 0.5f),
                textAlign = TextAlign.Center,
            )
        }
        if (showReply) {
            Spacer(Modifier.height(14.dp))
            val base = if (state.replyIsError) VoiceWarning else Color.White
            Text(
                spokenProgress(state.reply, state.spokenUpTo, if (listening) base.copy(alpha = 0.72f) else base),
                style = MaterialTheme.typography.titleLarge.copy(fontSize = 21.sp, lineHeight = 30.sp, fontWeight = FontWeight.Normal),
                textAlign = if (state.reply.length > LONG_REPLY_CHARS) TextAlign.Start else TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private fun spokenProgress(reply: String, spokenUpTo: Int, base: Color): AnnotatedString = buildAnnotatedString {
    if (spokenUpTo !in 0 until reply.length) {
        withStyle(SpanStyle(color = base)) { append(reply) }
    } else {
        withStyle(SpanStyle(color = base)) { append(reply, 0, spokenUpTo) }
        withStyle(SpanStyle(color = base.copy(alpha = 0.32f))) { append(reply, spokenUpTo, reply.length) }
    }
}

@Composable
private fun StarterPrompts(onPick: (String) -> Unit) {
    val prompts = listOf(
        R.string.voice_example_6, R.string.voice_example_5, R.string.voice_example_2, R.string.voice_example_1, R.string.voice_example_4,
    ).map { stringResource(it) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            prompts.forEach { prompt ->
                Text(
                    prompt,
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White.copy(alpha = 0.86f),
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.07f))
                        .border(1.dp, Color.White.copy(alpha = 0.12f), CircleShape)
                        .clickable(role = Role.Button) { onPick(prompt) }
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                )
            }
        }
        Text(
            stringResource(R.string.voice_privacy),
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.42f),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 10.dp),
        )
    }
}

/** Spoken commands are only saved after an explicit tap on Confirm. */
@Composable
private fun ConfirmPanel(pending: PendingAction, onTitle: (String) -> Unit, onConfirm: () -> Unit, onCancel: () -> Unit) {
    GlassCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), highlight = true) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            NeonIcon(LifeIcons.Task, LifeColors.Cyan, size = 34.dp)
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.confirm_action_title), style = MaterialTheme.typography.titleSmall)
        }
        Spacer(Modifier.height(10.dp))
        Text(pending.summary, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.86f))
        pending.editableTitle?.let { title ->
            Spacer(Modifier.height(10.dp))
            CustomTextField(title, onTitle, stringResource(R.string.field_title_or_note))
        }
        Spacer(Modifier.height(14.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f).height(54.dp), shape = RoundedCornerShape(16.dp)) {
                Text(stringResource(R.string.action_cancel), color = Color.White)
            }
            GradientButton(stringResource(R.string.action_confirm), onConfirm, Modifier.weight(1f))
        }
    }
}

@Composable
private fun VoiceControls(state: VoiceUiState, onMic: () -> Unit, onOpenChat: () -> Unit, onEnd: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 36.dp, end = 36.dp, top = 6.dp, bottom = 18.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RoundControl(LifeIcons.Chat, stringResource(R.string.voice_open_chat), onOpenChat)
        MicControl(state, onMic)
        RoundControl(Icons.Rounded.Close, stringResource(R.string.voice_end), onEnd, danger = true)
    }
}

@Composable
private fun MicControl(state: VoiceUiState, onClick: () -> Unit) {
    val muted = state.muted || state.needsPermission
    val listening = state.phase == VoicePhase.LISTENING
    val label = stringResource(
        when {
            muted -> R.string.voice_unmute
            state.phase == VoicePhase.IDLE -> R.string.voice_start
            else -> R.string.voice_mute
        },
    )
    val halo by animateFloatAsState(if (listening) 1f else 0f, tween(300), label = "micHalo")
    Box(Modifier.size(96.dp), contentAlignment = Alignment.Center) {
        if (halo > 0.01f) {
            Box(
                Modifier.matchParentSize().drawBehind {
                    drawCircle(Brush.radialGradient(listOf(LifeColors.Cyan.copy(alpha = 0.32f * halo), Color.Transparent)))
                },
            )
        }
        val face = when {
            muted -> Modifier.background(LifeColors.Error.copy(alpha = 0.2f)).border(1.dp, LifeColors.Error.copy(alpha = 0.55f), CircleShape)
            listening -> Modifier.background(Brush.linearGradient(listOf(LifeColors.Cyan, LifeColors.Blue, LifeColors.Purple)))
            else -> Modifier.background(Color.White.copy(alpha = 0.1f)).border(1.dp, Color.White.copy(alpha = 0.18f), CircleShape)
        }
        Box(
            Modifier.size(74.dp).clip(CircleShape).then(face).clickable(role = Role.Button, onClickLabel = label, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (muted) LifeIcons.MicOff else LifeIcons.Mic,
                contentDescription = label,
                tint = if (muted) VoiceDanger else Color.White,
                modifier = Modifier.size(30.dp),
            )
        }
    }
}

@Composable
private fun RoundControl(icon: ImageVector, label: String, onClick: () -> Unit, danger: Boolean = false) {
    Box(
        Modifier
            .size(58.dp)
            .clip(CircleShape)
            .background(if (danger) LifeColors.Error.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.08f))
            .border(1.dp, if (danger) LifeColors.Error.copy(alpha = 0.45f) else Color.White.copy(alpha = 0.14f), CircleShape)
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = if (danger) VoiceDanger else Color.White.copy(alpha = 0.9f), modifier = Modifier.size(24.dp))
    }
}

/** Light system-bar icons over the dark backdrop, and the screen stays on while you talk. */
@Composable
private fun VoiceWindowEffects() {
    val view = LocalView.current
    DisposableEffect(view) {
        val controller = view.context.findActivity()?.window?.let { WindowCompat.getInsetsController(it, view) }
        val lightStatus = controller?.isAppearanceLightStatusBars
        val lightNavigation = controller?.isAppearanceLightNavigationBars
        controller?.isAppearanceLightStatusBars = false
        controller?.isAppearanceLightNavigationBars = false
        view.keepScreenOn = true
        onDispose {
            view.keepScreenOn = false
            lightStatus?.let { controller?.isAppearanceLightStatusBars = it }
            lightNavigation?.let { controller?.isAppearanceLightNavigationBars = it }
        }
    }
}

private fun Modifier.fadingEdges(edge: Dp = 22.dp): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val fade = edge.toPx()
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black), startY = 0f, endY = fade), blendMode = BlendMode.DstIn)
        drawRect(
            Brush.verticalGradient(listOf(Color.Black, Color.Transparent), startY = size.height - fade, endY = size.height),
            blendMode = BlendMode.DstIn,
        )
    }

private fun washFor(mode: OrbMode): Color = when (mode) {
    OrbMode.LISTENING -> LifeColors.Cyan.copy(alpha = 0.16f)
    OrbMode.THINKING -> LifeColors.Purple.copy(alpha = 0.18f)
    OrbMode.SPEAKING -> LifeColors.Blue.copy(alpha = 0.18f)
    OrbMode.IDLE -> LifeColors.Blue.copy(alpha = 0.10f)
    OrbMode.MUTED -> Color(0xFF334155).copy(alpha = 0.10f)
}

private fun Context.hasMicPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun Context.openAppSettings() {
    runCatching {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

private const val LONG_REPLY_CHARS = 140
private val VoiceTop = Color(0xFF03050B)
private val VoiceBottom = Color(0xFF070B17)
private val VoiceWarning = Color(0xFFFCD34D)
private val VoiceDanger = Color(0xFFFCA5A5)
private val SelectedLanguage = Brush.linearGradient(listOf(Color(0x6122D3EE), Color(0x618B5CF6)))
