package dev.thathunky.meteora.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The form a control draws in. [Compact] is its icon form: Terminal's text controls draw their
 * icon instead of `[ text ]`, and a labelled [MButton] with an icon becomes an icon button.
 * Set by [ActionRow] when the text forms do not fit; Full everywhere else.
 */
enum class ControlForm { Full, Compact }

val LocalControlForm = staticCompositionLocalOf { ControlForm.Full }

/**
 * One action in an [ActionRow]. [priority] 0 is the primary action, kept in full form longest;
 * higher numbers go compact first, all of one number together. [weight] shares out the width left
 * over, as Row's weight does. A [minWidth] makes it a filler (the Now Playing target chip): it is
 * not asked for its natural width, needs [minWidth], and gets what the others leave through its
 * weight. [compactable] false skips measuring a compact form it does not have.
 */
class RowAction(
    val priority: Int = 0,
    val weight: Float = 0f,
    val minWidth: Dp? = null,
    val compactable: Boolean = true,
    val content: @Composable () -> Unit,
)

private data class SlotKey(val index: Int, val compact: Boolean)

/**
 * A group of actions laid out by measured width, the same way in every look: each action is
 * measured in its full and its compact form, [fitActions] decides which form each takes and how
 * the group breaks into lines, and every action is centred vertically in its line, so siblings of
 * different heights (a 48 dp icon button among 56 dp buttons) line up. With [wrap] false the
 * group stays on one line (Now Playing's controls).
 *
 * Each action's content is composed in both forms to measure them; only the chosen one is placed.
 */
@Composable
fun ActionRow(
    actions: List<RowAction>,
    modifier: Modifier = Modifier,
    arrangement: Arrangement.Horizontal = Arrangement.spacedBy(8.dp),
    lineSpacing: Dp = 8.dp,
    wrap: Boolean = true,
) {
    SubcomposeLayout(modifier) { constraints ->
        fun slot(i: Int, compact: Boolean) = subcompose(SlotKey(i, compact)) {
            CompositionLocalProvider(LocalControlForm provides if (compact) ControlForm.Compact else ControlForm.Full) {
                // One measurable per action, whatever it emits (a menu's popup anchor, say); the
                // width ActionRow gives a stretched action reaches the control inside.
                Box(propagateMinConstraints = true) { actions[i].content() }
            }
        }.single()

        val gap = arrangement.spacing.roundToPx()
        val full = actions.indices.map { slot(it, false) }
        val compact = actions.mapIndexed { i, a -> if (a.compactable && a.minWidth == null) slot(i, true) else null }
        val items = actions.mapIndexed { i, a ->
            val natural = a.minWidth?.roundToPx() ?: full[i].maxIntrinsicWidth(Constraints.Infinity)
            FitItem(
                full = natural,
                compact = compact[i]?.maxIntrinsicWidth(Constraints.Infinity)?.coerceAtMost(natural) ?: natural,
                priority = a.priority,
                weight = a.weight,
            )
        }
        val available = if (constraints.hasBoundedWidth) constraints.maxWidth
        else items.sumOf { it.full } + gap * (items.size - 1).coerceAtLeast(0)
        val fit = fitActions(items, available, gap, wrap)

        class Line(val indexes: List<Int>, val placeables: List<Placeable>, val height: Int)
        val lines = fit.lines.map { line ->
            val placeables = line.map { i ->
                val w = fit.widths[i]
                val isCompact = fit.compact[i]
                val stretch = actions[i].weight > 0f && !isCompact
                val m = if (isCompact) compact[i]!! else full[i]
                m.measure(Constraints(minWidth = if (stretch) w else 0, maxWidth = w, maxHeight = constraints.maxHeight))
            }
            Line(line, placeables, placeables.maxOf { it.height })
        }
        val spacing = lineSpacing.roundToPx()
        val height = (lines.sumOf { it.height } + spacing * (lines.size - 1).coerceAtLeast(0))
            .coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(available.coerceIn(constraints.minWidth, constraints.maxWidth), height) {
            var y = 0
            for (line in lines) {
                val slots = IntArray(line.indexes.size) { fit.widths[line.indexes[it]] }
                val x = IntArray(slots.size)
                with(arrangement) { arrange(available, slots, layoutDirection, x) }
                line.placeables.forEachIndexed { k, p ->
                    // arrange() has already mirrored the positions for right-to-left, as in Row.
                    // A control narrower than its slot is centred in it.
                    p.place(x[k] + (slots[k] - p.width) / 2, y + (line.height - p.height) / 2)
                }
                y += line.height + spacing
            }
        }
    }
}
