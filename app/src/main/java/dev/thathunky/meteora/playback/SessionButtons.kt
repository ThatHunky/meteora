package dev.thathunky.meteora.playback

import android.content.Context
import android.os.Bundle
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.SessionCommand
import dev.thathunky.meteora.R
import dev.thathunky.meteora.companion.Command
import dev.thathunky.meteora.companion.RepeatMode
import dev.thathunky.meteora.domain.LikeStatus
import dev.thathunky.meteora.domain.PlaybackState

/**
 * The extra buttons of the media notification, the lock screen and anything else that shows the
 * session (a watch, a car, Bluetooth remotes that list custom actions): dislike and like beside
 * the transport controls, shuffle and repeat after them. The official app has like and dislike
 * there; shuffle and repeat are the "but better".
 */
@UnstableApi
internal object SessionButtons {
    const val LIKE = "dev.thathunky.meteora.LIKE"
    const val DISLIKE = "dev.thathunky.meteora.DISLIKE"
    const val SHUFFLE = "dev.thathunky.meteora.SHUFFLE"
    const val REPEAT = "dev.thathunky.meteora.REPEAT"

    // Lazy: the pure parts of this object are unit-tested on the JVM, where Bundle is a stub.
    val commands: List<SessionCommand> by lazy { listOf(LIKE, DISLIKE, SHUFFLE, REPEAT).map { SessionCommand(it, Bundle.EMPTY) } }

    /** What the buttons depend on. Compared, so the layout is rebuilt only when one of these changes. */
    data class Shown(val hasTrack: Boolean, val like: LikeStatus, val shuffle: Boolean, val repeat: RepeatMode)

    fun shown(s: PlaybackState?) = Shown(
        hasTrack = s?.track != null,
        like = s?.track?.like ?: LikeStatus.Unknown,
        shuffle = s?.shuffle == true,
        repeat = s?.repeat ?: RepeatMode.Unknown,
    )

    /** The repeat mode a tap moves to: off, all, one, off. The same order as the button in Now Playing. */
    fun nextRepeat(current: RepeatMode): RepeatMode = when (current) {
        RepeatMode.None, RepeatMode.Unknown -> RepeatMode.All
        RepeatMode.All -> RepeatMode.One
        RepeatMode.One -> RepeatMode.None
    }

    /** The command a button sends, given the state it was pressed in; null for an unknown action. */
    fun commandFor(action: String, s: PlaybackState?): Command? = when (action) {
        LIKE -> Command.ToggleLike
        DISLIKE -> Command.ToggleDislike
        SHUFFLE -> Command.ToggleShuffle
        REPEAT -> Command.SetRepeat(nextRepeat(s?.repeat ?: RepeatMode.Unknown))
        else -> null
    }

    fun buttons(context: Context, shown: Shown): List<CommandButton> {
        if (!shown.hasTrack) return emptyList()
        fun button(action: String, icon: Int, label: Int, slot: Int) = CommandButton.Builder(icon)
            .setDisplayName(context.getString(label))
            .setSessionCommand(SessionCommand(action, Bundle.EMPTY))
            .setSlots(slot)
            .build()
        return listOf(
            // Order matters: Android's media notification has room for two buttons besides
            // previous, play and next, and takes the first two of this list. Tried on the phone with
            // dislike and like in the "secondary" slots beside the transport controls: the
            // notification then showed shuffle and repeat and left the two out.
            button(
                DISLIKE,
                if (shown.like == LikeStatus.Dislike) CommandButton.ICON_THUMB_DOWN_FILLED else CommandButton.ICON_THUMB_DOWN_UNFILLED,
                if (shown.like == LikeStatus.Dislike) R.string.session_undo_dislike else R.string.dislike,
                CommandButton.SLOT_OVERFLOW,
            ),
            button(
                LIKE,
                if (shown.like == LikeStatus.Like) CommandButton.ICON_THUMB_UP_FILLED else CommandButton.ICON_THUMB_UP_UNFILLED,
                if (shown.like == LikeStatus.Like) R.string.session_undo_like else R.string.like,
                CommandButton.SLOT_OVERFLOW,
            ),
            button(
                SHUFFLE,
                if (shown.shuffle) CommandButton.ICON_SHUFFLE_ON else CommandButton.ICON_SHUFFLE_OFF,
                if (shown.shuffle) R.string.session_shuffle_on else R.string.session_shuffle_off,
                CommandButton.SLOT_OVERFLOW,
            ),
            button(
                REPEAT,
                when (shown.repeat) {
                    RepeatMode.All -> CommandButton.ICON_REPEAT_ALL
                    RepeatMode.One -> CommandButton.ICON_REPEAT_ONE
                    else -> CommandButton.ICON_REPEAT_OFF
                },
                when (shown.repeat) {
                    RepeatMode.All -> R.string.session_repeat_all
                    RepeatMode.One -> R.string.session_repeat_one
                    else -> R.string.session_repeat_off
                },
                CommandButton.SLOT_OVERFLOW,
            ),
        )
    }
}
