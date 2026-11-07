package com.lifeos.ai.feature.voice

import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.provider.Settings
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** What the voice orb is expressing. */
enum class OrbMode { IDLE, LISTENING, THINKING, SPEAKING, MUTED }

private data class OrbPalette(val first: Color, val middle: Color, val last: Color, val glow: Color)

private fun paletteFor(mode: OrbMode): OrbPalette = when (mode) {
    OrbMode.IDLE -> OrbPalette(Color(0xFF22D3EE), Color(0xFF3B82F6), Color(0xFF8B5CF6), Color(0xFF3B82F6))
    OrbMode.LISTENING -> OrbPalette(Color(0xFFA5F3FC), Color(0xFF22D3EE), Color(0xFF3B82F6), Color(0xFF22D3EE))
    OrbMode.THINKING -> OrbPalette(Color(0xFFC4B5FD), Color(0xFF8B5CF6), Color(0xFF3B82F6), Color(0xFF8B5CF6))
    OrbMode.SPEAKING -> OrbPalette(Color(0xFF67E8F9), Color(0xFF3B82F6), Color(0xFFA78BFA), Color(0xFF60A5FA))
    OrbMode.MUTED -> OrbPalette(Color(0xFF94A3B8), Color(0xFF64748B), Color(0xFF475569), Color(0xFF334155))
}

/**
 * The voice-mode centrepiece: a slowly turning sphere made of glowing dots.
 *
 * - Listening: the surface ripples with the live microphone level.
 * - Thinking: the dots swirl in bands and the sphere draws in slightly.
 * - Speaking: it pulses on every word the text-to-speech engine starts.
 * - Touch: dragging across it scatters the dots, which spring back; a tap bursts them and calls [onTap].
 *
 * [level] and [pulse] are read once per frame (not during composition), so fast-changing audio
 * values never recompose the screen.
 */
@Composable
fun ParticleOrb(
    mode: OrbMode,
    level: () -> Float,
    pulse: () -> Int,
    onTap: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    val sphere = remember { DotSphere() }
    val currentMode by rememberUpdatedState(mode)
    val currentLevel by rememberUpdatedState(level)
    val currentPulse by rememberUpdatedState(pulse)
    val currentOnTap by rememberUpdatedState(onTap)
    val motion = rememberMotionScale()
    var frameNanos by remember { mutableLongStateOf(0L) }
    val lifecycleOwner = LocalLifecycleOwner.current

    // Frame loop: runs only while the screen is visible.
    LaunchedEffect(sphere, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var last = -1L
            while (true) {
                withFrameNanos { now ->
                    val dt = if (last < 0) 0f else ((now - last) / 1_000_000_000f).coerceIn(0f, MAX_STEP_SECONDS)
                    last = now
                    sphere.step(dt, currentMode, currentLevel(), currentPulse(), motion)
                    frameNanos = now
                }
            }
        }
    }

    val palette = paletteFor(mode)
    val first by animateColorAsState(palette.first, tween(COLOR_FADE_MS), label = "orbFirst")
    val middle by animateColorAsState(palette.middle, tween(COLOR_FADE_MS), label = "orbMiddle")
    val last by animateColorAsState(palette.last, tween(COLOR_FADE_MS), label = "orbLast")
    val glow by animateColorAsState(palette.glow, tween(COLOR_FADE_MS), label = "orbGlow")

    Canvas(
        modifier
            .semantics {
                role = Role.Button
                this.contentDescription = contentDescription
                onClick { currentOnTap(); true }
            }
            .pointerInput(sphere) {
                val slop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    sphere.push(down.position.x, down.position.y, 0f, 0f, 36.dp.toPx(), 90.dp.toPx())
                    var travelled = 0f
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            if (travelled < slop) {
                                sphere.push(change.position.x, change.position.y, 0f, 0f, 120.dp.toPx(), 380.dp.toPx())
                                currentOnTap()
                            }
                            break
                        }
                        val delta = change.position - change.previousPosition
                        travelled += delta.getDistance()
                        if (delta != Offset.Zero) {
                            sphere.push(change.position.x, change.position.y, delta.x, delta.y, 58.dp.toPx(), 60.dp.toPx())
                        }
                        if (travelled > slop) change.consume()
                    }
                }
            },
    ) {
        if (frameNanos < 0L) return@Canvas // Reading the frame clock redraws this canvas every frame.
        val radius = size.minDimension * ORB_RADIUS_SHARE
        val energy = sphere.energy
        drawCircle(
            Brush.radialGradient(
                listOf(glow.copy(alpha = 0.14f + 0.30f * energy), glow.copy(alpha = 0.04f), Color.Transparent),
                center = center, radius = radius * 1.9f,
            ),
            radius = radius * 1.9f, center = center,
        )
        drawCircle(
            Brush.radialGradient(listOf(middle.copy(alpha = 0.10f + 0.12f * energy), Color.Transparent), center = center, radius = radius * 1.05f),
            radius = radius * 1.05f, center = center,
        )
        drawIntoCanvas { canvas ->
            sphere.draw(canvas.nativeCanvas, center.x, center.y, radius, DOT_SIZE.toPx(), first.toArgb(), middle.toArgb(), last.toArgb())
        }
    }
}

/** Honours the system "Remove animations" setting with a much calmer orb. */
@Composable
private fun rememberMotionScale(): Float {
    val context = LocalContext.current
    return remember(context) {
        val scale = runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        }.getOrDefault(1f)
        if (scale == 0f) 0.2f else 1f
    }
}

/** Evenly spread points on the unit sphere (Fibonacci lattice), packed as x, y, z triples. */
internal fun fibonacciSphere(count: Int): FloatArray {
    val out = FloatArray(count * 3)
    val golden = (PI * (3.0 - sqrt(5.0))).toFloat()
    for (i in 0 until count) {
        val y = 1f - 2f * (i + 0.5f) / count
        val ring = sqrt(1f - y * y)
        val theta = golden * i
        out[i * 3] = cos(theta) * ring
        out[i * 3 + 1] = y
        out[i * 3 + 2] = sin(theta) * ring
    }
    return out
}

/**
 * Simulation + renderer for [ParticleOrb]. Plain arrays and one draw call per depth layer keep a
 * 1,400-dot sphere at 60 fps without allocating per frame.
 */
internal class DotSphere(private val count: Int = DEFAULT_DOTS) {
    private val base = fibonacciSphere(count)
    private val seed = FloatArray(count)
    private val swirlWeight = FloatArray(count)
    private val swirlCos = FloatArray(count) { 1f }
    private val swirlSin = FloatArray(count)
    private var cachedSwirlPhase = 0f

    // Touch scatter: screen-space offsets on damped springs.
    private val offX = FloatArray(count)
    private val offY = FloatArray(count)
    private val velX = FloatArray(count)
    private val velY = FloatArray(count)
    private var scattering = false

    // Last projected positions, for touch hit-testing.
    private val screenX = FloatArray(count)
    private val screenY = FloatArray(count)
    private var projected = false

    // Draw batches: back-to-front depth layers plus a few bright "spark" dots.
    private val layers = Array(LAYERS) { FloatArray(count * 2) }
    private val layerFill = IntArray(LAYERS)
    private val sparks = FloatArray(count * 2)
    private var sparkFill = 0
    // Created on first draw, so the simulation itself stays plain Kotlin (and unit-testable).
    private val layerPaints by lazy { Array(LAYERS) { dotPaint() } }
    private val sparkPaint by lazy { dotPaint().apply { color = android.graphics.Color.WHITE } }
    private var gradient: Shader? = null
    private val gradientKey = FloatArray(3)
    private val gradientColors = IntArray(3)

    /** 0..1 — how lively the sphere currently is. */
    var energy = 0f
        private set
    private var time = 0.0
    private var rotation = 0f
    private var spin = 0.16f
    private var swirl = 0f
    private var swirlPhase = 0f
    private var contraction = 0f
    private var wordEnergy = 0f
    private var lastPulse = Int.MIN_VALUE

    internal val isScattering: Boolean get() = scattering

    init {
        val random = java.util.Random(7)
        for (i in 0 until count) {
            seed[i] = random.nextFloat()
            val y = base[i * 3 + 1]
            swirlWeight[i] = 1f - y * y // the equator swirls fastest, the poles stay put
        }
    }

    fun step(dt: Float, mode: OrbMode, level: Float, pulse: Int, motion: Float) {
        time += dt
        val targetSpin = when (mode) {
            OrbMode.MUTED -> 0.07f
            OrbMode.IDLE -> 0.16f
            OrbMode.LISTENING -> 0.26f
            OrbMode.SPEAKING -> 0.34f
            OrbMode.THINKING -> 0.7f
        }
        spin += (targetSpin - spin) * min(1f, dt * 2f)
        rotation = (rotation + spin * dt * motion) % TWO_PI
        val thinking = if (mode == OrbMode.THINKING) 1f else 0f
        swirl += (thinking - swirl) * min(1f, dt * 1.8f)
        swirlPhase += dt * 2.6f * swirl * motion
        // Once thinking ends the bands slowly untwist, settling back into the even lattice.
        if (thinking == 0f) swirlPhase -= swirlPhase * min(1f, dt * UNTWIST_RATE)
        if (abs(swirlPhase) < 0.0005f) swirlPhase = 0f
        contraction += (thinking - contraction) * min(1f, dt * 3f)

        if (lastPulse == Int.MIN_VALUE) lastPulse = pulse
        if (pulse != lastPulse) {
            lastPulse = pulse
            wordEnergy = max(wordEnergy, 0.5f + 0.4f * hash01(pulse))
        }
        wordEnergy *= exp(-dt * 3.2f)
        val target = when (mode) {
            OrbMode.LISTENING -> 0.08f + level.coerceIn(0f, 1f) * 0.92f
            OrbMode.SPEAKING -> max(wordEnergy, 0.16f + 0.14f * abs(wave(4.6) * wave(1.7, 0.8)))
            OrbMode.THINKING -> 0.12f
            OrbMode.IDLE -> 0.05f
            OrbMode.MUTED -> 0.02f
        }
        energy += (target - energy) * min(1f, dt * if (target > energy) 16f else 4.5f)

        if (scattering) relax(dt)
    }

    /** Pushes dots near ([x], [y]) away; ([dx], [dy]) is the finger's movement since the last event. */
    fun push(x: Float, y: Float, dx: Float, dy: Float, radius: Float, strength: Float) {
        if (!projected) return
        val r2 = radius * radius
        for (i in 0 until count) {
            val ddx = screenX[i] - x
            val ddy = screenY[i] - y
            val d2 = ddx * ddx + ddy * ddy
            if (d2 >= r2) continue
            val d = sqrt(d2).coerceAtLeast(1f)
            val fall = 1f - d / radius
            val force = fall * fall * strength * (0.7f + 0.6f * seed[i])
            velX[i] += ddx / d * force + dx * fall * DRAG_CARRY
            velY[i] += ddy / d * force + dy * fall * DRAG_CARRY
        }
        scattering = true
    }

    internal fun maxOffset(): Float {
        var m = 0f
        for (i in 0 until count) m = max(m, max(abs(offX[i]), abs(offY[i])))
        return m
    }

    private fun relax(dt: Float) {
        var moving = 0f
        for (i in 0 until count) {
            var vx = velX[i]
            var vy = velY[i]
            vx += (-SPRING * offX[i] - DAMPING * vx) * dt
            vy += (-SPRING * offY[i] - DAMPING * vy) * dt
            val ox = offX[i] + vx * dt
            val oy = offY[i] + vy * dt
            velX[i] = vx; velY[i] = vy; offX[i] = ox; offY[i] = oy
            moving += abs(ox) + abs(oy) + (abs(vx) + abs(vy)) * 0.02f
        }
        if (moving < 0.5f) {
            offX.fill(0f); offY.fill(0f); velX.fill(0f); velY.fill(0f)
            scattering = false
        }
    }

    /** Projects every dot to the screen and sorts it into its depth layer. */
    fun project(cx: Float, cy: Float, radius: Float) {
        if (swirlPhase != cachedSwirlPhase) {
            for (i in 0 until count) {
                val a = swirlPhase * swirlWeight[i]
                swirlCos[i] = cos(a)
                swirlSin[i] = sin(a)
            }
            cachedSwirlPhase = swirlPhase
        }
        val cr = cos(rotation)
        val sr = sin(rotation)
        val tilt = 0.36f + 0.06f * wave(0.31)
        val ct = cos(tilt)
        val st = sin(tilt)
        val breath = 0.014f * wave(1.7)
        val amplitude = 0.17f * energy
        val scale = radius * (1f - 0.07f * contraction)
        val p1 = phase(1.25)
        val p2 = phase(0.95)
        val p3 = phase(2.1)
        layerFill.fill(0)
        sparkFill = 0
        for (i in 0 until count) {
            val j = i * 3
            val bx = base[j]
            val by = base[j + 1]
            val bz = base[j + 2]
            // Turn about the vertical axis (global spin + per-latitude swirl), then tilt toward the viewer.
            val ca = cr * swirlCos[i] - sr * swirlSin[i]
            val sa = sr * swirlCos[i] + cr * swirlSin[i]
            val x1 = bx * ca + bz * sa
            val z1 = bz * ca - bx * sa
            val y2 = by * ct - z1 * st
            val z2 = by * st + z1 * ct
            // Organic surface ripples, anchored to the surface so the lobes travel with it.
            val ripple = sin(bx * 3.1f + p1 + seed[i] * 0.8f) * cos(by * 2.6f - p2) + 0.55f * sin(bz * 4.2f + by * 3.3f + p3)
            val r = scale * (1f + breath + amplitude * ripple)
            val perspective = 1f / (1f - z2 * PERSPECTIVE)
            val sx = cx + x1 * r * perspective + offX[i]
            val sy = cy - y2 * r * perspective + offY[i]
            screenX[i] = sx
            screenY[i] = sy
            val depth = ((z2 + 1f) * 0.5f).coerceIn(0f, 1f)
            if (seed[i] < SPARK_SHARE && depth > 0.55f) {
                sparks[sparkFill] = sx
                sparks[sparkFill + 1] = sy
                sparkFill += 2
            } else {
                val layer = min(LAYERS - 1, (depth * LAYERS).toInt())
                val k = layerFill[layer]
                layers[layer][k] = sx
                layers[layer][k + 1] = sy
                layerFill[layer] = k + 2
            }
        }
        projected = true
    }

    fun draw(canvas: android.graphics.Canvas, cx: Float, cy: Float, radius: Float, dotPx: Float, first: Int, middle: Int, last: Int) {
        project(cx, cy, radius)
        val shader = gradientFor(cx, cy, radius, first, middle, last)
        val boost = 1f + energy * 0.4f
        for (layer in 0 until LAYERS) {
            val n = layerFill[layer]
            if (n == 0) continue
            val depth = (layer + 0.5f) / LAYERS
            val paint = layerPaints[layer]
            paint.shader = shader
            paint.strokeWidth = dotPx * (0.42f + 0.7f * depth) * boost
            paint.alpha = (255f * (0.07f + 0.93f * depth * depth)).toInt()
            canvas.drawPoints(layers[layer], 0, n, paint)
        }
        if (sparkFill > 0) {
            sparkPaint.strokeWidth = dotPx * 1.15f * boost
            sparkPaint.alpha = (200 + 55 * energy).toInt().coerceAtMost(255)
            canvas.drawPoints(sparks, 0, sparkFill, sparkPaint)
        }
    }

    /** A diagonal brand gradient across the sphere, rebuilt only when its geometry or colours change. */
    private fun gradientFor(cx: Float, cy: Float, radius: Float, first: Int, middle: Int, last: Int): Shader {
        val cached = gradient
        if (cached != null && gradientKey[0] == cx && gradientKey[1] == cy && gradientKey[2] == radius &&
            gradientColors[0] == first && gradientColors[1] == middle && gradientColors[2] == last
        ) return cached
        gradientKey[0] = cx; gradientKey[1] = cy; gradientKey[2] = radius
        gradientColors[0] = first; gradientColors[1] = middle; gradientColors[2] = last
        return LinearGradient(cx - radius, cy - radius, cx + radius, cy + radius, gradientColors.copyOf(), null, Shader.TileMode.CLAMP)
            .also { gradient = it }
    }

    /** sin(time × [speed] + [offset]) computed in double precision so long sessions stay smooth. */
    private fun wave(speed: Double, offset: Double = 0.0): Float = sin((time * speed + offset) % TWO_PI_D).toFloat()

    private fun phase(speed: Double): Float = ((time * speed) % TWO_PI_D).toFloat()

    private fun dotPaint() = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private fun hash01(n: Int): Float {
        var x = n * 0x45d9f3b
        x = (x ushr 16) xor x
        x *= 0x45d9f3b
        x = (x ushr 16) xor x
        return (x and 0xFFFF) / 65535f
    }

    private companion object {
        const val DEFAULT_DOTS = 1400
        const val LAYERS = 8
        const val SPARK_SHARE = 0.045f
        const val PERSPECTIVE = 0.22f
        const val SPRING = 58f
        const val DAMPING = 8.5f
        const val DRAG_CARRY = 8f
        const val UNTWIST_RATE = 0.7f
        const val TWO_PI = 6.2831855f
        const val TWO_PI_D = 2 * PI
    }
}

private const val MAX_STEP_SECONDS = 0.05f
private const val COLOR_FADE_MS = 700
private const val ORB_RADIUS_SHARE = 0.33f
private val DOT_SIZE = 2.4.dp
