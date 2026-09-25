package dev.agentdeck.companion.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** The plugin's eight rounded spokes, advancing once every 125 ms. */
@Composable
internal fun RunningIndicator(modifier: Modifier = Modifier) {
    val frame = LocalRunningIndicatorFrame.current
    val phase = if (frame == null) runningPhase() else rememberUpdatedState(frame.toFloat())
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(
        modifier.size(16.dp).semantics {
            contentDescription = "Running"
            progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
        },
    ) {
        val unit = size.minDimension / 16f
        repeat(8) { spoke ->
            rotate(-45f * spoke, center) {
                drawLine(
                    color = color.copy(alpha = SpokeAlpha[(spoke + phase.value.toInt()) % 8]),
                    start = Offset(center.x, center.y - 6f * unit),
                    end = Offset(center.x, center.y - 4f * unit),
                    strokeWidth = 2f * unit,
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}

/** Golden captures pin one frame; live rendering and motion tests leave the clock running. */
internal val LocalRunningIndicatorFrame = staticCompositionLocalOf<Int?> { null }

@Composable
private fun runningPhase(): State<Float> {
    val transition = rememberInfiniteTransition(label = "running")
    return transition.animateFloat(
        initialValue = 0f,
        targetValue = 8f,
        animationSpec = infiniteRepeatable(tween(1_000, easing = LinearEasing)),
        label = "running frame",
    )
}

private val SpokeAlpha = floatArrayOf(1f, .93f, .78f, .69f, .62f, .48f, .38f, .3f)
