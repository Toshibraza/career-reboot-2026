package com.nova.assistant.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** What the orb is reacting to. */
enum class OrbMode { IDLE, LISTENING, THINKING, SPEAKING }

/**
 * A floating sphere whose surface ripples with the voice.
 *
 * Three stacked wave layers at different frequencies, phases and speeds. One sine alone reads as
 * a wobble; three at odd ratios never repeat visibly, which is what makes it look alive rather
 * than animated.
 *
 * [amplitude] drives it — the real microphone level while listening, and a synthetic breath
 * while speaking, since Android's TTS gives no output amplitude to follow. The distinction is
 * deliberate: the orb should never imply it is hearing something when it is only talking.
 */
@Composable
fun VoiceOrb(
    mode: OrbMode,
    amplitude: Float,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 168.dp,
) {
    val transition = rememberInfiniteTransition(label = "orb")

    // Three phases at deliberately non-integer ratios so the crests never line up twice.
    val phaseA by transition.animateFloat(
        initialValue = 0f,
        targetValue = TWO_PI,
        animationSpec = infiniteRepeatable(tween(4200, easing = LinearEasing), RepeatMode.Restart),
        label = "phaseA",
    )
    val phaseB by transition.animateFloat(
        initialValue = 0f,
        targetValue = TWO_PI,
        animationSpec = infiniteRepeatable(tween(6700, easing = LinearEasing), RepeatMode.Restart),
        label = "phaseB",
    )
    val phaseC by transition.animateFloat(
        initialValue = 0f,
        targetValue = TWO_PI,
        animationSpec = infiniteRepeatable(tween(9300, easing = LinearEasing), RepeatMode.Restart),
        label = "phaseC",
    )

    // Colour travels around the palette continuously, so the orb never sits on one hue.
    val drift by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(11_000, easing = LinearEasing), RepeatMode.Reverse),
        label = "drift",
    )

    // A slow breath while speaking, because TTS exposes no amplitude. While listening the real
    // level is used, so the orb genuinely tracks the voice rather than miming it.
    val breath by transition.animateFloat(
        initialValue = 0f,
        targetValue = TWO_PI,
        animationSpec = infiniteRepeatable(tween(1500, easing = LinearEasing), RepeatMode.Restart),
        label = "breath",
    )

    val target = when (mode) {
        // Floored, not raw: a quiet room reads as zero and the orb would freeze mid-listen,
        // which looks broken rather than attentive.
        OrbMode.LISTENING -> 0.30f + amplitude.coerceIn(0f, 1f) * 0.70f
        OrbMode.SPEAKING -> 0.48f + 0.30f * sin(breath).toFloat()
        OrbMode.THINKING -> 0.28f + 0.14f * sin(breath * 0.6f).toFloat()
        // Never fully still. A resting orb that drifts says "ready"; one that stops says
        // "crashed".
        OrbMode.IDLE -> 0.22f + 0.06f * sin(breath * 0.35f).toFloat()
    }

    // Springy rather than linear: a voice peaks faster than it decays, and a linear follow
    // makes the orb feel like a progress bar.
    val energy by animateFloatAsState(
        targetValue = target,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 380f),
        label = "energy",
    )

    val palette = paletteFor(mode)

    Canvas(
        modifier = modifier
            .size(size)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onTap,
            ),
    ) {
        val centre = Offset(this.size.width / 2f, this.size.height / 2f)
        val base = this.size.minDimension / 2f * 0.62f

        // Outer glow first, so every wave layer sits on top of it.
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(palette[0].copy(alpha = 0.34f + energy * 0.28f), Color.Transparent),
                center = centre,
                radius = base * (1.85f + energy * 0.45f),
            ),
            radius = base * (1.85f + energy * 0.45f),
            center = centre,
        )

        // Back to front, largest and faintest first.
        drawWaveBlob(centre, base * (1.30f + energy * 0.30f), phaseC, energy * 0.55f, palette[2], 0.26f, lobes = 3)
        drawWaveBlob(centre, base * (1.14f + energy * 0.26f), phaseB, energy * 0.70f, palette[1], 0.38f, lobes = 5)
        drawWaveBlob(centre, base * (1.00f + energy * 0.22f), phaseA, energy * 0.85f, palette[0], 0.92f, lobes = 4)

        // Highlight, offset up-left, which is what makes it read as a sphere rather than a disc.
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color.White.copy(alpha = 0.34f), Color.Transparent),
                center = centre + Offset(-base * 0.32f, -base * 0.38f),
                radius = base * 0.75f,
            ),
            radius = base * 0.75f,
            center = centre + Offset(-base * 0.32f, -base * 0.38f),
        )

        // Colour drift shows as a faint travelling sheen rather than the whole orb changing.
        drawCircle(
            brush = Brush.linearGradient(
                colors = listOf(Color.Transparent, palette[1].copy(alpha = 0.20f), Color.Transparent),
                start = Offset(centre.x - base, centre.y - base + drift * base * 2f),
                end = Offset(centre.x + base, centre.y + base - drift * base * 2f),
            ),
            radius = base * (1f + energy * 0.22f),
            center = centre,
        )
    }
}

/**
 * One rippling layer.
 *
 * The radius at each angle is perturbed by two sines — [lobes] gives the overall shape and a
 * faster harmonic keeps the edge from looking mechanical.
 */
private fun DrawScope.drawWaveBlob(
    centre: Offset,
    radius: Float,
    phase: Float,
    wobble: Float,
    colour: Color,
    alpha: Float,
    lobes: Int,
) {
    val path = Path()
    var angle = 0f

    while (angle <= 360f) {
        val radians = angle * PI.toFloat() / 180f

        // A constant term keeps the outline visibly wavy even when the voice is quiet; the
        // wobble term is what makes it surge when it is not.
        val ripple = 1f +
            (0.05f + wobble * 0.20f) * sin(radians * lobes + phase) +
            (0.02f + wobble * 0.09f) * sin(radians * (lobes * 2 + 1) - phase * 1.7f)

        val r = radius * ripple
        val point = Offset(centre.x + r * cos(radians), centre.y + r * sin(radians))

        if (angle == 0f) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
        angle += ANGLE_STEP
    }
    path.close()

    drawPath(
        path = path,
        brush = Brush.radialGradient(
            colors = listOf(colour.copy(alpha = alpha), colour.copy(alpha = alpha * 0.45f)),
            center = centre,
            radius = radius * 1.3f,
        ),
    )
}

/**
 * Colour says what the orb is doing, before any text is read.
 *
 * Idle is deliberately still colourful rather than grey — grey reads as disabled, and the orb
 * being alive at rest is what makes tapping it feel like the obvious thing to do.
 */
private fun paletteFor(mode: OrbMode): List<Color> = when (mode) {
    // Bright and cool while listening: it must be unmistakable that the microphone is open.
    OrbMode.LISTENING -> listOf(Color(0xFF22D3EE), Color(0xFFA855F7), Color(0xFFEC4899))
    OrbMode.SPEAKING -> listOf(Color(0xFF34D399), Color(0xFF22D3EE), Color(0xFF818CF8))
    OrbMode.THINKING -> listOf(Color(0xFFFBBF24), Color(0xFFF97316), Color(0xFFF43F5E))
    OrbMode.IDLE -> listOf(Color(0xFF6366F1), Color(0xFF8B5CF6), Color(0xFF38BDF8))
}

private const val TWO_PI = (2 * PI).toFloat()

/** Fine enough that the outline reads as a curve, coarse enough to stay cheap per frame. */
private const val ANGLE_STEP = 4f

private operator fun Offset.plus(other: Offset) = Offset(x + other.x, y + other.y)

private val Size.minDimension: Float get() = kotlin.math.min(width, height)
