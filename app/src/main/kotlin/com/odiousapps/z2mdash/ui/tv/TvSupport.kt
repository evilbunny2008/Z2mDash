package com.odiousapps.z2mdash.ui.tv

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.border
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ColorScheme as TvColorScheme

/** True when running on an actual Android TV device, false for phones/tablets. */
fun isTelevision(context: Context): Boolean {
    val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
    return uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
}

/**
 * Set once, near the root, from [isTelevision] - lets every screen branch its
 * chrome (nav shell, tile focus styling) on device type without each one
 * re-querying UiModeManager itself.
 */
val LocalIsTv = staticCompositionLocalOf { false }

/**
 * Some tv-material components' own built-in click handling only responds to Enter, not D-pad
 * centre/OK (confirmed on-device: `androidx.tv.material3.NavigationDrawerItem` ignored
 * `KEYCODE_DPAD_CENTER` entirely, while `KEYCODE_ENTER` activated it). That gap matters beyond
 * just a test remote: this household's actual TV remote goes through a companion app
 * (MX3ButtonMapper) that remaps its physical OK button's scancode straight to
 * `KeyEvent.KEYCODE_DPAD_CENTER` (see that project's `ButtonMapperService.kt`,
 * `SCANCODE_TO_KEYCODE`), not `KEYCODE_ENTER` - so without this, the real remote's OK button
 * couldn't activate the nav rail at all.
 *
 * Uses `onPreviewKeyEvent` (fires top-down, before the component's own handling ever sees the
 * event) rather than `onKeyEvent` (bubbles bottom-up, only reaching an ancestor if nothing below
 * already consumed it) - confirmed on-device that `onKeyEvent` alone still didn't work here, most
 * likely because the component's own focus/press-visual handling consumes D-pad centre for its
 * own indication tracking even though it never acts on it. Intercepting in preview and consuming
 * it outright sidesteps that entirely. Fires on key-down (not key-up) for the same reason - no
 * need to race whatever the component's own internal state tracking does on release. Enter is
 * deliberately left alone here (returns false) since that key already reaches the component's own
 * working handler; only DirectionCenter needs this supplement.
 */
fun Modifier.onDpadSelect(onClick: () -> Unit): Modifier = onPreviewKeyEvent { event ->
    if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionCenter) {
        onClick()
        true
    } else {
        false
    }
}

/**
 * Escape hatch for a focused single-line text field on TV. Handles two separate, both-confirmed
 * gaps:
 *
 * 1. Back: forces focus to clear (see the `force = true` comment below). This alone isn't enough
 *    on its own, though - when the on-screen keyboard is actually showing, Android's IME consumes
 *    the FIRST Back press itself to dismiss the keyboard, and that press never even reaches this
 *    handler; it only takes effect on a second, separate Back press once the keyboard is already
 *    gone. That's an OS-level behavior this modifier can't intercept earlier than this.
 * 2. Up/Down: moves focus directly, unconditionally, on the very first press - a single-line text
 *    field has no legitimate use for vertical cursor movement, so there's no ambiguity to worry
 *    about here (unlike Left/Right, which Compose's own built-in TV text field handling already
 *    uses to move the cursor before eventually handing off focus once it reaches the field's
 *    edge). Added as a deterministic, always-works alternative after a report that focus could
 *    still feel "stuck" with no reliable way out even after the Back fix above - Up/Down doesn't
 *    depend on the IME's own Back-consuming behavior at all, so it works the same whether the
 *    keyboard is currently showing or not.
 */
fun Modifier.clearFocusOnBack(): Modifier = composed {
    val focusManager = LocalFocusManager.current
    onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        when (event.key) {
            Key.Back -> {
                // force = true is not optional here - FocusManager.clearFocus()'s own docs say a
                // text field with an active IME action can specifically refuse to give up focus
                // unless forced (to let the user finish that action first). Without it,
                // clearFocus() was a silent no-op exactly whenever the on-screen keyboard had
                // actually been used, which is the one time this modifier's whole reason for
                // existing actually matters - confirmed as the cause of a real "focus is stuck on
                // this text field, no D-pad button does anything" report on-device.
                focusManager.clearFocus(force = true)
                false
            }
            Key.DirectionUp -> {
                focusManager.moveFocus(FocusDirection.Up)
                true
            }
            Key.DirectionDown -> {
                focusManager.moveFocus(FocusDirection.Down)
                true
            }
            else -> false
        }
    }
}

/**
 * Maps this app's own Material3 color scheme (light/dark/dynamic - see Z2mDashTheme) onto
 * tv-material3's own, unrelated ColorScheme type, so the TV nav rail's colors actually track the
 * app's current theme instead of tv-material3's own baked-in default tokens. Confirmed on-device
 * (TV set to system dark mode): without this, `androidx.tv.material3.MaterialTheme { ... }`'s
 * default colors had nothing to do with the app's real (dark) theme, and combined with a separate
 * bug where the rail's own icon/label used the WRONG library's Text/Icon (see AppNavHost.kt),
 * rendered as close to illegible dark-on-dark.
 */
fun ColorScheme.toTvColorScheme(): TvColorScheme = TvColorScheme(
    primary = primary,
    onPrimary = onPrimary,
    primaryContainer = primaryContainer,
    onPrimaryContainer = onPrimaryContainer,
    inversePrimary = inversePrimary,
    secondary = secondary,
    onSecondary = onSecondary,
    secondaryContainer = secondaryContainer,
    onSecondaryContainer = onSecondaryContainer,
    tertiary = tertiary,
    onTertiary = onTertiary,
    tertiaryContainer = tertiaryContainer,
    onTertiaryContainer = onTertiaryContainer,
    background = background,
    onBackground = onBackground,
    surface = surface,
    onSurface = onSurface,
    surfaceVariant = surfaceVariant,
    onSurfaceVariant = onSurfaceVariant,
    surfaceTint = surfaceTint,
    inverseSurface = inverseSurface,
    inverseOnSurface = inverseOnSurface,
    error = error,
    onError = onError,
    errorContainer = errorContainer,
    onErrorContainer = onErrorContainer,
    border = outline,
    borderVariant = outlineVariant,
    scrim = scrim,
)

/**
 * A visible "focus is here" ring, applied to a container that either is itself clickable/focusable
 * (e.g. SensorTile's whole Surface) or merely wraps separate focusable children (e.g. ToggleTile's
 * icon and Switch, kept as two distinct tap targets - see that composable's own comment) - hence
 * `hasFocus` rather than `isFocused`, which only reports this exact node's own focus
 * and would miss the latter case entirely. `clickable` only grants focus in non-touch (keyboard/
 * D-pad) mode, so on a touchscreen this stays invisible in practice - safe to apply
 * unconditionally rather than gating on [LocalIsTv], which keeps every tile's modifier chain
 * identical across phone, tablet and TV.
 */
fun Modifier.tvFocusIndicator(shape: Shape = RoundedCornerShape(12.dp)): Modifier = composed {
    var hasFocus by remember { mutableStateOf(false) }
    this
        .onFocusChanged { hasFocus = it.hasFocus }
        .then(
            if (hasFocus) {
                Modifier.border(3.dp, MaterialTheme.colorScheme.primary, shape)
            } else {
                Modifier
            }
        )
}
