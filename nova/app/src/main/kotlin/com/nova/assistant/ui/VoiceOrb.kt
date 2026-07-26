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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
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
 * A neon waveform with a sphere at its centre.
 *
 * Two things drawn together. Ribbons sweep the full width, tallest at the middle and tapering
 * to nothing at the edges, which is what makes a flat line read as sound. The sphere sits on
 * top so there is still a single obvious thing to press.
 *
 * Everything is driven by [amplitude] — the real microphone level while listening, and a
 * synthetic breath while speaking, since Android's TTS exposes no output amplitude. The two are
 * kept distinct deliberately: the visual should never imply it is hearing something when it is
 * only talking.
 */
@Composable
fun VoiceOrb(
    mode: OrbMode,
    amplitude: Float,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 190.dp,
) {
    val transition = rememberInfiniteTransition(label = "orb")

    // Non-integer period ratios, so crests never line up twice and the motion never visibly
    // repeats.
    val phaseA by transition.animateFloat(
        0f, TWO_PI,
        infiniteRepeatable(tween(4200, easing = LinearEasing), RepeatMode.Restart),
        label = "phaseA",
    )
    val phaseB by transition.animateFloat(
        0f, TWO_PI,
        infiniteRepeatable(tween(6700, easing = LinearEasing), RepeatMode.Restart),
        label = "phaseB",
    )
    val phaseC by transition.animateFloat(
        0f, TWO_PI,
        infiniteRepeatable(tween(9300, easing = LinearEasing), RepeatMode.Restart),
        label = "phaseC",
    )
    val breath by transition.animateFloat(
        0f, TWO_PI,
        infiniteRepeatable(tween(1500, easing = LinearEasing), RepeatMode.Restart),
        label = "breath",
    )

    val target = when (mode) {
        // Floored, not raw: a quiet room reads as zero and the waves would flatten mid-listen,
        // which looks broken rather than attentive.
        OrbMode.LISTENING -> 0.32f + amplitude.coerceIn(0f, 1f) * 0.68f
        OrbMode.SPEAKING -> 0.50f + 0.30f * sin(breath).toFloat()
        OrbMode.THINKING -> 0.28f + 0.14f * sin(breath * 0.6f).toFloat()
        // Never fully still. A resting waveform that drifts says "ready"; a flat one says
        // "crashed".
        OrbMode.IDLE -> 0.22f + 0.06f * sin(breath * 0.35f).toFloat()
    }

    // Springy rather than linear: a voice peaks faster than it decays, and a linear follow
    // makes it feel like a progress bar.
    val energy by animateFloatAsState(
        targetValue = target,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 380f),
        label = "energy",
    )

    val palette = paletteFor(mode)

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onTap,
            ),
    ) {
        val midY = size.height / 2f
        val centre = Offset(size.width / 2f, midY)

        // Dark backdrop so the neon actually reads. Without it these colours wash out
        // completely on a light background. Rounded, so it sits in the layout as a panel
        // rather than a band cut across the screen.
        drawRoundRect(
            brush = Brush.radialGradient(
                colors = listOf(BACKDROP_CORE, BACKDROP_EDGE),
                center = centre,
                radius = size.width * 0.75f,
            ),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height * 0.16f),
        )

        // Nearly half the panel height. The first values here were a third of this, and the
        // ribbons were invisible behind the sphere — a waveform that cannot be seen is just an
        // expensive rectangle.
        val maxWave = size.height * 0.46f

        // Back to front: wide and faint first, tight and bright last.
        drawRibbon(midY, maxWave * (0.62f + energy * 0.60f), 1.4f, phaseC, palette, 0.42f)
        drawRibbon(midY, maxWave * (0.48f + energy * 0.85f), 2.3f, phaseB, palette, 0.55f)
        drawRibbon(midY, maxWave * (0.34f + energy * 1.10f), 3.7f, phaseA, palette, 0.80f)

        // Smaller than before, so the ribbons read on both sides instead of being covered.
        drawSphere(centre, size.height * 0.148f * (1f + energy * 0.20f), phaseA, energy, palette)
    }
}

/**
 * One ribbon of the waveform.
 *
 * Mirrored about the centre line and enveloped so it swells in the middle and vanishes at the
 * edges — a constant-height wave reads as a graph, not as a voice.
 */
private fun DrawScope.drawRibbon(
    midY: Float,
    amplitude: Float,
    frequency: Float,
    phase: Float,
    palette: List<Color>,
    alpha: Float,
) {
    val width = size.width
    val path = Path()

    fun waveAt(x: Float): Float {
        val t = x / width

        // A plateau, not a peak. sin(pi*t) alone puts the tallest point exactly where the
        // sphere sits, so the wave was hidden behind it and faded out before it cleared the
        // glow. Saturating keeps full height across the middle and only tapers near the edges.
        val envelope = (sin(PI.toFloat() * t) * 2.4f).coerceAtMost(1f)

        val w = sin(t * TWO_PI * frequency + phase) +
            0.45f * sin(t * TWO_PI * frequency * 2.3f - phase * 1.6f) +
            0.25f * sin(t * TWO_PI * frequency * 3.9f + phase * 0.7f)
        return w * amplitude * envelope * 0.95f
    }

    var x = 0f
    path.moveTo(0f, midY)
    while (x <= width) {
        path.lineTo(x, midY + waveAt(x))
        x += STEP
    }
    // Back along the mirror image, closing the ribbon into a solid shape.
    x = width
    while (x >= 0f) {
        path.lineTo(x, midY - waveAt(x) * 0.85f)
        x -= STEP
    }
    path.close()

    drawPath(
        path = path,
        brush = Brush.horizontalGradient(
            colors = listOf(
                palette[2].copy(alpha = alpha * 0.5f),
                palette[0].copy(alpha = alpha),
                palette[1].copy(alpha = alpha),
                palette[2].copy(alpha = alpha * 0.5f),
            ),
        ),
    )
}

/** The sphere, kept from the original so there is still one obvious thing to press. */
private fun DrawScope.drawSphere(
    centre: Offset,
    radius: Float,
    phase: Float,
    energy: Float,
    palette: List<Color>,
) {
    // Tighter and fainter than before: a wide bright glow washed out the very waves it was
    // meant to sit among.
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(palette[0].copy(alpha = 0.30f), Color.Transparent),
            center = centre,
            radius = radius * (1.5f + energy * 0.4f),
        ),
        radius = radius * (1.5f + energy * 0.4f),
        center = centre,
    )

    val path = Path()
    var angle = 0f
    while (angle <= 360f) {
        val radians = angle * PI.toFloat() / 180f
        // A constant term keeps the edge visibly wavy in silence; the energy term makes it
        // surge when it is not.
        val ripple = 1f +
            (0.04f + energy * 0.14f) * sin(radians * 4f + phase) +
            (0.02f + energy * 0.07f) * sin(radians * 7f - phase * 1.7f)
        val r = radius * ripple
        val point = Offset(centre.x + r * cos(radians), centre.y + r * sin(radians))
        if (angle == 0f) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
        angle += ANGLE_STEP
    }
    path.close()

    drawPath(
        path = path,
        brush = Brush.linearGradient(
            colors = listOf(palette[0], palette[1], palette[2]),
            start = Offset(centre.x - radius, centre.y - radius),
            end = Offset(centre.x + radius, centre.y + radius),
        ),
    )

    // Offset highlight — what makes it read as a sphere rather than a disc.
    val highlight = Offset(centre.x - radius * 0.34f, centre.y - radius * 0.38f)
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(Color.White.copy(alpha = 0.40f), Color.Transparent),
            center = highlight,
            radius = radius * 0.8f,
        ),
        radius = radius * 0.8f,
        center = highlight,
    )

    drawMicGlyph(centre, radius * 0.42f)
}

/** A small mic, so the sphere says what tapping it does without a label. */
private fun DrawScope.drawMicGlyph(centre: Offset, size: Float) {
    val capsuleWidth = size * 0.52f
    val capsuleTop = centre.y - size * 0.62f
    val capsuleHeight = size * 0.86f

    drawRoundRect(
        color = Color.White.copy(alpha = 0.92f),
        topLeft = Offset(centre.x - capsuleWidth / 2f, capsuleTop),
        size = androidx.compose.ui.geometry.Size(capsuleWidth, capsuleHeight),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(capsuleWidth / 2f),
    )

    // Cradle: an arc drawn as a stroked path, plus the stem below it.
    val cradle = Path().apply {
        val r = size * 0.62f
        moveTo(centre.x - r, centre.y + size * 0.10f)
        quadraticBezierTo(centre.x, centre.y + size * 0.95f, centre.x + r, centre.y + size * 0.10f)
    }
    drawPath(
        path = cradle,
        color = Color.White.copy(alpha = 0.92f),
        style = androidx.compose.ui.graphics.drawscope.Stroke(width = size * 0.14f),
    )
    drawLine(
        color = Color.White.copy(alpha = 0.92f),
        start = Offset(centre.x, centre.y + size * 0.62f),
        end = Offset(centre.x, centre.y + size * 0.98f),
        strokeWidth = size * 0.14f,
    )
}

/**
 * Colour states the mode before any text is read.
 *
 * Built on the neon magenta-to-blue range rather than muted tones, because these sit on a dark
 * backdrop where anything desaturated disappears.
 */
private fun paletteFor(mode: OrbMode): List<Color> = when (mode) {
    // Unmistakable while the microphone is open.
    OrbMode.LISTENING -> listOf(Color(0xFFFF2D9B), Color(0xFFC13AF0), Color(0xFF3B5BFF))
    OrbMode.SPEAKING -> listOf(Color(0xFF22D3EE), Color(0xFF6366F1), Color(0xFFC13AF0))
    OrbMode.THINKING -> listOf(Color(0xFFFBBF24), Color(0xFFF43F5E), Color(0xFFC13AF0))
    OrbMode.IDLE -> listOf(Color(0xFFE040FB), Color(0xFF7C3AED), Color(0xFF2563EB))
}

private val BACKDROP_CORE = Color(0xFF1B1B44)
private val BACKDROP_EDGE = Color(0xFF0E0E2A)

private const val TWO_PI = (2 * PI).toFloat()

/** Fine enough to read as a curve, coarse enough to stay cheap every frame. */
private const val STEP = 6f
private const val ANGLE_STEP = 4f
