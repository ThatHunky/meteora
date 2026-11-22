package dev.thathunky.meteora.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.semantics.selected
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import dev.thathunky.meteora.R
import dev.thathunky.meteora.ui.theme.HeaderStyle
import dev.thathunky.meteora.ui.theme.LocalLook
import dev.thathunky.meteora.ui.theme.LookId
import kotlin.math.roundToInt

/*
 * Grouped long lists (Library): sticky section headers, collapsible group rows, the sort and
 * group menus, the selection bar and the letter index. Each part reads the look and draws its
 * way; screens never branch on the look themselves. See docs/library-design.md.
 */

/** The touch width of the letter index; lists leave this much free at their end. */
val IndexScrollerWidth: Dp = 48.dp

/** End padding a list needs so its rows' trailing buttons stay clear of the letter index. */
@Composable
fun indexScrollerGap(shown: Boolean): Dp = if (shown) (IndexScrollerWidth - LocalLook.current.screenPadding).coerceAtLeast(0.dp) else 0.dp

/**
 * A section header in a grouped list: a letter, a date, "No album". Opaque, so it can be sticky,
 * and a heading for TalkBack. [count] is drawn after the text. [expanded] non-null makes it
 * fold its section: tapping calls [onToggle].
 */
@Composable
fun GroupHeader(
    text: String,
    modifier: Modifier = Modifier,
    count: Int? = null,
    expanded: Boolean? = null,
    onToggle: (() -> Unit)? = null,
) {
    val look = LocalLook.current
    val type = MaterialTheme.typography
    val scheme = MaterialTheme.colorScheme
    val locale = LocalConfiguration.current.locales[0]
    val p = look.screenPadding
    val label = when (look.header) {
        HeaderStyle.Hash -> "# " + text.lowercase(locale)
        HeaderStyle.SmallCaps -> text.uppercase(locale)
        else -> text
    } + (count?.let { if (look.header == HeaderStyle.Hash) " ($it)" else "  $it" } ?: "")
    val style = when (look.header) {
        HeaderStyle.Emphasized -> type.titleMediumEmphasized
        HeaderStyle.Hash -> type.titleSmall
        HeaderStyle.SmallCaps -> type.labelLarge.copy(letterSpacing = 0.15.em)
        HeaderStyle.SerifRule -> type.titleMedium
        HeaderStyle.Large -> type.titleLarge
    }
    val color = when (look.header) {
        HeaderStyle.Hash, HeaderStyle.Emphasized -> scheme.primary
        HeaderStyle.SmallCaps -> scheme.onSurfaceVariant
        else -> scheme.onSurface
    }
    val toggle = if (expanded != null && onToggle != null) {
        Modifier.clickable(
            onClickLabel = stringResource(if (expanded) R.string.lib_collapse else R.string.lib_expand),
            role = Role.Button,
            onClick = onToggle,
        )
    } else Modifier
    Column(
        modifier
            .fillMaxWidth()
            // Opaque: rows scroll under a sticky header.
            .background(scheme.surface)
            .then(toggle)
            .semantics { heading() },
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = p),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = style, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (expanded != null) ExpandMark(expanded, color)
        }
        when (look.header) {
            HeaderStyle.SerifRule -> Box(Modifier.padding(horizontal = p).fillMaxWidth().height(2.dp).background(scheme.onSurface))
            HeaderStyle.Hash -> Box(Modifier.fillMaxWidth().height(look.outline).background(scheme.outline))
            else -> Unit
        }
    }
}

/** Open or closed, for a folding header or group row. Terminal: `[-]` / `[+]`. Decorative: the row says it. */
@Composable
fun ExpandMark(expanded: Boolean, color: androidx.compose.ui.graphics.Color = androidx.compose.material3.LocalContentColor.current) {
    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
        if (LocalLook.current.textControls) {
            Text(if (expanded) "[-]" else "[+]", style = MaterialTheme.typography.labelLarge, color = color)
        } else {
            Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, tint = color)
        }
    }
}

/**
 * A folded artist or album in a grouped list: artwork, name, a supporting line (count, size)
 * and the open/closed mark. While selecting, the artwork becomes a check box for the whole
 * group. Tap: [onClick] (fold, or select while selecting); long-press: [onLongClick].
 */
@Composable
fun GroupRow(
    title: String,
    supporting: String,
    artwork: Any?,
    artKind: ArtKind,
    expanded: Boolean,
    selecting: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val look = LocalLook.current
    MRow(
        headline = title,
        supporting = supporting,
        leading = {
            if (selecting) SelectionMark(selected, look.rowArt)
            else ArtworkFrame(artwork, artKind, Modifier.size(look.rowArt))
        },
        trailing = { if (!selecting) ExpandMark(expanded) },
        modifier = modifier
            .combinedClickable(
                onClickLabel = if (selecting) null else stringResource(if (expanded) R.string.lib_collapse else R.string.lib_expand),
                onLongClickLabel = stringResource(R.string.lib_select),
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .semantics { if (selecting) this.selected = selected },
    )
}

/** A choice in a [MenuChoice] or a row's overflow menu. */
data class MenuAction(val label: String, val onClick: () -> Unit, val enabled: Boolean = true)

/** The menu itself, drawn with the look's corner and, where the look has one, its outline. */
@Composable
private fun LookMenu(expanded: Boolean, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val look = LocalLook.current
    val scheme = MaterialTheme.colorScheme
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(look.cornerSmall),
        containerColor = if (look.fills) scheme.surfaceContainer else scheme.surface,
        border = if (look.outline > 0.dp) BorderStroke(look.outline, scheme.outline) else null,
        shadowElevation = if (look.fills) 3.dp else 0.dp,
    ) { content() }
}

private val dev.thathunky.meteora.ui.theme.Look.fills get() = surface != dev.thathunky.meteora.ui.theme.SurfaceStyle.Outline

/**
 * A labelled menu button showing its current value, "Group: Artists". Opens a menu of
 * [options] with the chosen one checked. Terminal: `[ group: artists ]`.
 */
@Composable
fun <T> MenuChoice(
    label: String,
    options: List<Choice<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val look = LocalLook.current
    val scheme = MaterialTheme.colorScheme
    var open by remember { mutableStateOf(false) }
    val current = options.firstOrNull { it.value == selected }?.label.orEmpty()
    val text = "$label: $current"
    Box(modifier) {
        when (look.id) {
            LookId.Terminal -> Box(
                Modifier
                    .defaultMinSize(minHeight = 48.dp)
                    .clickable(role = Role.DropdownList, onClick = { open = true })
                    .padding(horizontal = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("[ " + lookLabel(text) + " ]", style = MaterialTheme.typography.labelLarge, color = scheme.onSurface, maxLines = 1)
            }
            LookId.Retro -> Box(
                Modifier
                    .padding(end = look.hardShadow, bottom = look.hardShadow)
                    .defaultMinSize(minHeight = 48.dp)
                    .retroBox(RoundedCornerShape(look.cornerSmall), scheme.surface, pressed = open)
                    .clickable(role = Role.DropdownList, onClick = { open = true })
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(lookLabel(text), style = MaterialTheme.typography.labelLarge, maxLines = 1)
                    Icon(Icons.Filled.ArrowDropDown, null, Modifier.size(18.dp))
                }
            }
            else -> FilterChip(
                selected = false,
                onClick = { open = true },
                label = { Text(text, maxLines = 1) },
                trailingIcon = { Icon(Icons.Filled.ArrowDropDown, null, Modifier.size(FilterChipDefaults.IconSize)) },
                shape = when {
                    look.id == LookId.Expressive -> FilterChipDefaults.shape
                    look.pills -> CircleShape
                    else -> RoundedCornerShape(look.cornerSmall)
                },
                modifier = Modifier.semantics { this.contentDescription = text },
            )
        }
        LookMenu(open, { open = false }) {
            options.forEach { o ->
                val on = o.value == selected
                DropdownMenuItem(
                    text = { Text(if (look.textControls) (if (on) "* " else "  ") + lookLabel(o.label) else o.label) },
                    leadingIcon = if (look.textControls) null else ({ if (on) Icon(Icons.Filled.Check, null) else Spacer(Modifier.size(24.dp)) }),
                    onClick = {
                        open = false
                        onSelect(o.value)
                    },
                    modifier = Modifier.semantics { if (on) stateDescription = current },
                )
            }
        }
    }
}

/** The row of group, sort and filter menus above a grouped list. Scrolls sideways when narrow. */
@Composable
fun SortGroupBar(modifier: Modifier = Modifier, edgePadding: Dp = LocalLook.current.screenPadding, content: @Composable () -> Unit) {
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = edgePadding, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

/** A row's ⋮ menu. Terminal draws `[ more ]`. */
@Composable
fun OverflowMenu(actions: List<MenuAction>, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    val look = LocalLook.current
    Box(modifier) {
        MIconButton(Icons.Outlined.MoreVert, stringResource(R.string.action_more), { open = true }, text = stringResource(R.string.action_more))
        LookMenu(open, { open = false }) {
            actions.forEach { a ->
                DropdownMenuItem(
                    text = { Text(if (look.textControls) lookLabel(a.label) else a.label) },
                    enabled = a.enabled,
                    onClick = {
                        open = false
                        a.onClick()
                    },
                )
            }
        }
    }
}

/**
 * Where a row's artwork goes while selecting: a check box in the look's shape, the same size as
 * the artwork so rows do not move. Terminal: `[x]` / `[ ]`.
 */
@Composable
fun SelectionMark(selected: Boolean, size: Dp, modifier: Modifier = Modifier) {
    val look = LocalLook.current
    val scheme = MaterialTheme.colorScheme
    if (look.textControls) {
        Box(modifier.size(size), contentAlignment = Alignment.Center) {
            Text(if (selected) "[x]" else "[ ]", style = MaterialTheme.typography.bodyLarge, color = if (selected) scheme.primary else scheme.onSurface)
        }
        return
    }
    val shape = if (look.pills && look.id == LookId.Soft) CircleShape else RoundedCornerShape(look.cornerSmall)
    Box(
        modifier
            .size(size)
            .then(if (selected) Modifier.background(scheme.primary, shape) else Modifier.border(maxOf(look.outline, 2.dp), scheme.outline, shape)),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Icon(Icons.Filled.Check, null, tint = scheme.onPrimary)
    }
}

/**
 * Shown instead of the sort bar while songs are selected: close, the count (announced as it
 * changes), Select all and the bulk action.
 */
@Composable
fun SelectionBar(
    count: String,
    onClose: () -> Unit,
    onSelectAll: () -> Unit,
    action: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val look = LocalLook.current
    MSurface(modifier.fillMaxWidth().padding(horizontal = look.screenPadding / 2, vertical = 4.dp), tone = Tone.Primary) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            MIconButton(Icons.Outlined.Close, stringResource(R.string.lib_cancel_selection), onClose, text = "x")
            Text(
                count,
                style = MaterialTheme.typography.titleMedium,
                color = surfaceContentColor(Tone.Primary),
                maxLines = 1,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
            )
            MButton(stringResource(R.string.lib_select_all), onSelectAll, kind = ButtonKind.Text)
            Spacer(Modifier.width(4.dp))
            MButton(action, onAction)
        }
    }
}

/** A search field for filtering a list in place, with a clear button. */
@Composable
fun ListSearchField(state: TextFieldState, hint: String, modifier: Modifier = Modifier) {
    val look = LocalLook.current
    OutlinedTextField(
        state = state,
        placeholder = { Text(hint) },
        leadingIcon = if (look.textControls) null else ({ Icon(Icons.Outlined.Search, null) }),
        trailingIcon = if (state.text.isEmpty()) null else ({
            MIconButton(Icons.Outlined.Close, stringResource(R.string.lib_search_clear), { state.clearText() }, text = "x")
        }),
        lineLimits = TextFieldLineLimits.SingleLine,
        shape = if (look.pills) CircleShape else RoundedCornerShape(look.cornerSmall),
        modifier = modifier.fillMaxWidth().padding(horizontal = look.screenPadding, vertical = 4.dp),
    )
}

/**
 * The letter index along a list's end edge: tap or drag to jump to a letter, with the letter
 * shown large beside the finger. [current] is the letter of the list's first visible row; it is
 * a lambda so the scroll position is read here, not by the screen. TalkBack reads it as one
 * adjustable control: the state is the letter, swiping up or down moves one letter.
 */
@Composable
fun IndexScroller(
    letters: List<String>,
    current: () -> Int,
    onJump: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (letters.isEmpty()) return
    val look = LocalLook.current
    val scheme = MaterialTheme.colorScheme
    val type = MaterialTheme.typography
    var dragging by remember { mutableStateOf(false) }
    var touched by remember { mutableIntStateOf(0) }
    var touchY by remember { mutableStateOf(0f) }
    val n = letters.size
    val indexLabel = stringResource(R.string.lib_index)
    val hint = stringResource(R.string.lib_index_hint)
    // The gesture handlers outlive a recomposition: they must call the latest jump, whose rows
    // may have moved (a group opened) while the letters stayed the same.
    val jump by rememberUpdatedState(onJump)
    val shown = (if (dragging) touched else current()).coerceIn(0, n - 1)
    BoxWithConstraints(
        modifier
            .width(IndexScrollerWidth)
            .fillMaxHeight()
            .semantics(mergeDescendants = false) {
                contentDescription = "$indexLabel. $hint"
                stateDescription = letters[shown]
                progressBarRangeInfo = ProgressBarRangeInfo(shown.toFloat(), 0f..(n - 1).toFloat(), steps = (n - 2).coerceAtLeast(0))
                setProgress { v ->
                    jump(v.roundToInt().coerceIn(0, n - 1))
                    true
                }
            },
    ) {
        val heightPx = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        fun at(y: Float) = ((y / heightPx) * n).toInt().coerceIn(0, n - 1)
        // Letters too close together: draw every k-th one; touch still reaches all of them.
        val step = maxOf(1, kotlin.math.ceil(14f / (maxHeight.value / n)).toInt())
        Column(
            Modifier
                .fillMaxHeight()
                .align(Alignment.CenterEnd)
                .width(IndexScrollerWidth)
                .pointerInput(n, heightPx) {
                    detectTapGestures { o -> jump(at(o.y)) }
                }
                .pointerInput(n, heightPx) {
                    detectVerticalDragGestures(
                        onDragStart = { o ->
                            dragging = true; touchY = o.y; touched = at(o.y); jump(touched)
                        },
                        onDragEnd = { dragging = false },
                        onDragCancel = { dragging = false },
                    ) { change, _ ->
                        touchY = change.position.y
                        val i = at(change.position.y)
                        if (i != touched) {
                            touched = i
                            jump(i)
                        }
                    }
                }
                .clearAndSetSemantics { },
            horizontalAlignment = Alignment.End,
        ) {
            for (i in 0 until n step step) {
                val span = minOf(step, n - i)
                val on = shown in i until i + span
                Box(Modifier.weight(span.toFloat()).width(IndexScrollerWidth / 2).align(Alignment.End), contentAlignment = Alignment.Center) {
                    Text(
                        if (look.textControls) letters[i].lowercase(LocalConfiguration.current.locales[0]) else letters[i],
                        style = type.labelSmall,
                        color = if (on) scheme.primary else scheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                    )
                }
            }
        }
        if (dragging) {
            val bubble = 64.dp
            val y = (touchY - with(androidx.compose.ui.platform.LocalDensity.current) { bubble.toPx() } / 2).coerceIn(0f, (heightPx - with(androidx.compose.ui.platform.LocalDensity.current) { bubble.toPx() }).coerceAtLeast(0f))
            MSurface(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset { IntOffset(-(IndexScrollerWidth + 8.dp).roundToPx(), y.roundToInt()) }
                    .requiredSize(bubble)
                    .clearAndSetSemantics { },
                tone = Tone.Primary,
            ) {
                Box(Modifier.size(bubble), contentAlignment = Alignment.Center) {
                    Text(letters[touched.coerceIn(0, n - 1)], style = type.headlineMedium, color = surfaceContentColor(Tone.Primary))
                }
            }
        }
    }
}

