package dev.agentdeck.companion.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.agentdeck.companion.data.FleetGroup

/** How one group's mark is drawn. Shape carries the state where colour cannot. */
enum class MarkShape { CIRCLE, DIAMOND, SQUARE, ROUNDED }

/** Which theme role tints it — a role, not a literal, so Material You can restyle it. */
enum class MarkTint { ERROR, PRIMARY, SECONDARY, TERTIARY, OUTLINE }

/** [filled] false draws the outline only, which is the sixth form four shapes cannot give. */
data class Mark(val shape: MarkShape, val tint: MarkTint, val filled: Boolean = true) {
    /** What survives greyscale: the pair that must be unique across every group. */
    val silhouette: Pair<MarkShape, Boolean> get() = shape to filled
}

/**
 * The one declaration of what each attention state looks like. The Composable below renders
 * it and `FleetTriageTest` asserts it; neither restates it, so a group that stops being
 * distinguishable fails the test instead of quietly painting like its neighbour.
 *
 * **Never tint alone.** The first cut gave Waiting a circle and Failed a 25%-cut square, both
 * in `error` — and the screenshot (docs/img/2026-07-31-mobile-fleet-capped.png's first
 * revision) showed the cut corners vanish at 10 dp: two red dots, separated by colour only,
 * in the one app whose whole job is telling those two states apart. So [silhouette] is the
 * invariant now, and it is asserted rather than described.
 */
fun markOf(group: FleetGroup): Mark = when (group) {
    FleetGroup.WAITING -> Mark(MarkShape.DIAMOND, MarkTint.ERROR)
    FleetGroup.FAILED -> Mark(MarkShape.SQUARE, MarkTint.ERROR)
    FleetGroup.RUNNING -> Mark(MarkShape.CIRCLE, MarkTint.PRIMARY)
    FleetGroup.RECENT -> Mark(MarkShape.ROUNDED, MarkTint.SECONDARY)
    FleetGroup.DONE_UNREVIEWED -> Mark(MarkShape.CIRCLE, MarkTint.TERTIARY, filled = false)
    FleetGroup.OTHER -> Mark(MarkShape.ROUNDED, MarkTint.OUTLINE, filled = false)
}

/**
 * A row's attention state, on the row.
 *
 * It used to be carried only by the section heading, so the one fact that decides where a row
 * *is* became unreadable the moment that heading scrolled off — and in a 167-row backlog the
 * heading is off screen almost always. The group title rides along as the mark's
 * `contentDescription`, which is what TalkBack reads.
 */
@Composable
fun StateMark(group: FleetGroup, modifier: Modifier = Modifier) {
    val mark = markOf(group)
    val shape = mark.shape.asShape()
    val color = mark.tint.asColor()
    Box(
        modifier
            .size(11.dp)
            .then(if (mark.filled) Modifier.clip(shape).background(color) else Modifier.border(2.dp, color, shape))
            .semantics { contentDescription = group.title },
    )
}

private fun MarkShape.asShape(): Shape = when (this) {
    MarkShape.CIRCLE -> RoundedCornerShape(percent = 50)
    // A square on its corner. `CutCornerShape(50%)` removes half of each edge, which meets in
    // the middle of every side — the corners are gone and a diamond is what is left.
    MarkShape.DIAMOND -> CutCornerShape(percent = 50)
    MarkShape.ROUNDED -> RoundedCornerShape(2.dp)
    MarkShape.SQUARE -> RectangleShape
}

@Composable
private fun MarkTint.asColor(): Color = when (this) {
    MarkTint.ERROR -> MaterialTheme.colorScheme.error
    MarkTint.PRIMARY -> MaterialTheme.colorScheme.primary
    MarkTint.SECONDARY -> MaterialTheme.colorScheme.secondary
    MarkTint.TERTIARY -> MaterialTheme.colorScheme.tertiary
    MarkTint.OUTLINE -> MaterialTheme.colorScheme.outlineVariant
}
