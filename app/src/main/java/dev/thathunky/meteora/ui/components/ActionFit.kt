package dev.thathunky.meteora.ui.components

/*
 * Which actions of a row keep their full (text) form, which fall back to their compact (icon)
 * form, and how they break into lines. Pure, so it is tested on the JVM; [ActionRow] measures
 * the two forms and lays out what this decides. All widths are pixels.
 */

/**
 * One action as measured. [full] and [compact] are its natural widths in each form (equal when it
 * has no other form). [priority] 0 is the primary action: kept in full form longest. A [weight]
 * above 0 takes a share of the width left over, as Row's weight does.
 */
data class FitItem(val full: Int, val compact: Int = full, val priority: Int = 0, val weight: Float = 0f)

/** The decision: per item whether it is compact and its width; [lines] holds item indexes in order. */
data class ActionFit(val compact: List<Boolean>, val widths: List<Int>, val lines: List<List<Int>>)

/**
 * Fits [items] into [available] with [gap] between neighbours.
 *
 * 1. Everything in full form, if it fits.
 * 2. Otherwise priority levels go compact, least important first, all items of a level at once
 *    (so Previous and Next change together). Priority 0 stays in full form when [wrap] is allowed.
 * 3. Still too wide: with [wrap], greedy lines in the items' order; without, priority 0 goes
 *    compact too, and as a last resort every width shrinks in proportion.
 *
 * On each line the spare width goes to the weighted items. When Row's own rule (spare split by
 * weight, natural widths ignored) leaves every weighted item at least its natural width, that rule
 * is used, so a row that already fitted lays out exactly as a Row with weights did.
 */
fun fitActions(items: List<FitItem>, available: Int, gap: Int, wrap: Boolean): ActionFit {
    if (items.isEmpty()) return ActionFit(emptyList(), emptyList(), emptyList())
    val compact = BooleanArray(items.size)
    fun width(i: Int) = if (compact[i]) items[i].compact else items[i].full
    fun total(indexes: List<Int>) = indexes.sumOf { width(it) } + gap * (indexes.size - 1).coerceAtLeast(0)
    val all = items.indices.toList()

    if (total(all) > available) {
        val levels = items.filter { it.compact < it.full }.map { it.priority }.distinct().sortedDescending()
        for (level in levels) {
            if (level == 0 && wrap) break
            items.forEachIndexed { i, it -> if (it.priority == level && it.compact < it.full) compact[i] = true }
            if (total(all) <= available) break
        }
    }

    val lines: List<List<Int>> = if (!wrap || total(all) <= available) {
        listOf(all)
    } else {
        val out = mutableListOf<MutableList<Int>>()
        var line = mutableListOf<Int>()
        for (i in all) {
            if (line.isNotEmpty() && total(line + i) > available) {
                out += line
                line = mutableListOf()
            }
            line += i
        }
        if (line.isNotEmpty()) out += line
        out
    }

    val widths = IntArray(items.size) { width(it) }
    for (line in lines) distribute(line, items, compact, widths, available, gap)
    return ActionFit(compact.toList(), widths.toList(), lines)
}

/**
 * Spare width to the weighted items of [line] that kept their full form (an icon is not
 * stretched); over-full lines shrink every item in proportion.
 */
private fun distribute(line: List<Int>, items: List<FitItem>, compact: BooleanArray, widths: IntArray, available: Int, gap: Int) {
    val gaps = gap * (line.size - 1).coerceAtLeast(0)
    val used = line.sumOf { widths[it] } + gaps
    if (used > available) {
        val room = (available - gaps).coerceAtLeast(0)
        val natural = line.sumOf { widths[it] }.coerceAtLeast(1)
        var left = room
        line.forEachIndexed { k, i ->
            val w = if (k == line.lastIndex) left else (widths[i].toLong() * room / natural).toInt()
            widths[i] = w.coerceAtLeast(0)
            left -= widths[i]
        }
        return
    }
    val weighted = line.filter { items[it].weight > 0f && !compact[it] }
    if (weighted.isEmpty()) return
    val sumWeights = weighted.sumOf { items[it].weight.toDouble() }
    // Row's rule: what the unweighted items and the gaps leave, split by weight.
    val rest = available - gaps - line.filter { it !in weighted }.sumOf { widths[it] }
    val rowStyle = weighted.associateWith { (rest * items[it].weight / sumWeights).toInt() }
    val share = if (weighted.all { rowStyle.getValue(it) >= widths[it] }) {
        rowStyle
    } else {
        val spare = available - used
        weighted.associateWith { widths[it] + (spare * items[it].weight / sumWeights).toInt() }
    }
    // Whole pixels: the rounding remainder goes to the last weighted item, so the line is exactly full.
    var left = rest
    weighted.forEachIndexed { k, i ->
        widths[i] = if (k == weighted.lastIndex) left else share.getValue(i)
        left -= widths[i]
    }
}
