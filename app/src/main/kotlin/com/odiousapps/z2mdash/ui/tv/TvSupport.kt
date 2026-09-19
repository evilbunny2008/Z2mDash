package com.odiousapps.z2mdash.ui.tv

import android.annotation.SuppressLint
import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ColorScheme as TvColorScheme

/** True when running on an actual Android TV device, false for phones/tablets. */
fun isTelevision(context: Context): Boolean {
    val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
    return uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
}

/**
 * Set once, near the root, from [isTelevision] - lets every screen branch its chrome (nav shell,
 * tile focus styling) without each one re-querying UiModeManager itself.
 */
val LocalIsTv = staticCompositionLocalOf { false }

/**
 * Swallows Down on the LAST item in the TV nav rail (see AppNavHost's TvNavShell), where there's
 * nothing below to move focus to. Confirmed on-device as a serious bug, not cosmetic: an unhandled
 * Down there lost focus entirely, with no button combination able to recover it short of
 * force-closing the app. Intercepting in preview sidesteps whatever NavigationDrawer/ListItem does
 * with it - same strategy as [onDpadSelect] for a different gap in the same component.
 */
fun Modifier.blockDirectionDown(): Modifier = onPreviewKeyEvent { event ->
    event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown
}

/**
 * Material3's [androidx.compose.material3.Slider] treats Up/Down as synonyms for Right/Left (its
 * own D-pad design, meant for vertical sliders), adjusting value instead of moving focus - a real
 * inconsistency here since every other element in this app treats Up/Down as "move to next/
 * previous" (see [clearFocusOnBack]). Intercepted in preview (same strategy as [onDpadSelect]/
 * [blockDirectionDown]) so Up/Down move focus away, leaving Left/Right as the slider's own axis.
 */
@SuppressLint("UnnecessaryComposedModifier")
fun Modifier.horizontalSliderDpadFocusNav(): Modifier = composed {
    val focusManager = LocalFocusManager.current
    onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        when (event.key) {
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
 * Some tv-material components only respond to Enter, not D-pad centre/OK (confirmed on-device:
 * NavigationDrawerItem ignored KEYCODE_DPAD_CENTER). This matters because this household's real
 * remote maps it's OK button to KEYCODE_DPAD_CENTER via a companion app (MX3ButtonMapper), not
 * KEYCODE_ENTER - so without this, the real remote's OK couldn't activate the nav rail at all.
 *
 * Uses onPreviewKeyEvent (fires before the component's own handling) rather than onKeyEvent
 * (bubbles up only if unconsumed) - confirmed on-device that onKeyEvent alone didn't work, likely
 * because the component consumes D-pad centre for its own press-visual tracking without acting on
 * it. Fires on key-down only; Enter is left alone since it already reaches the component's handler.
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
 * Escape hatch for a focused single-line text field on TV, handling two confirmed gaps:
 *
 * 1. Back: forces focus to clear (see `force = true` below). Not sufficient alone - when the
 *    on-screen keyboard is showing, Android's IME consumes the FIRST Back to dismiss it, so this
 *    handler only takes effect on a second Back press once the keyboard is already gone.
 * 2. Up/Down: moves focus unconditionally on first press - a single-line field has no vertical
 *    cursor use, unlike Left/Right which Compose's own TV handling uses to move the cursor first.
 *    Added since Up/Down works the same whether the keyboard is showing or not, unlike Back.
 *
 * @param onDirectionDown Overrides the default "move focus down" - e.g. an autocomplete field
 *   (AddEditBrokerScreen's "Permit join via") needs Down to open its suggestion list instead.
 *   Return `true` to suppress the default focus move; `false`/omit to keep it.
 */
@SuppressLint("UnnecessaryComposedModifier")
fun Modifier.clearFocusOnBack(onDirectionDown: (() -> Boolean)? = null): Modifier = composed {
    val focusManager = LocalFocusManager.current
    onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        when (event.key) {
            Key.Back -> {
                // force = true is required - clearFocus() docs say a field with an active IME
                // action can refuse to give up focus unless forced. Without it, this was a silent
                // no-op exactly when the keyboard had been used - confirmed as the cause of a real
                // "focus is stuck" on-device report.
                focusManager.clearFocus(force = true)
                false
            }
            Key.DirectionUp -> {
                focusManager.moveFocus(FocusDirection.Up)
                true
            }
            Key.DirectionDown -> {
                if (onDirectionDown?.invoke() != true) {
                    focusManager.moveFocus(FocusDirection.Down)
                }
                true
            }
            else -> false
        }
    }
}

/**
 * On TV, prevents a text field from popping up the keyboard merely by *gaining focus* while
 * D-pad-navigating through a screen - confirmed-on-device annoyance where passing through fields
 * en route elsewhere briefly summoned the keyboard, needing a Back press to dismiss.
 *
 * Uses `KeyboardOptions.showKeyboardOnFocus = false`, which stops focus alone from showing the
 * keyboard but still lets a genuine tap or DPAD_CENTER/OK on an already-focused field show it
 * (Compose's own key handling calls `keyboardController.show()` regardless of this setting) -
 * exactly the desired behaviour: OK explicitly summons the keyboard, passing through does not.
 *
 * Gated on [LocalIsTv] since on phone/tablet this setting would also suppress the keyboard
 * reappearing when moving between fields via the IME's "Next" action - a real regression for
 * touch/keyboard users that TV's remote-only input doesn't have to weigh against.
 */
@Composable
fun tvAwareKeyboardOptions(base: KeyboardOptions = KeyboardOptions.Default): KeyboardOptions {
    if (!LocalIsTv.current) return base
    return base.copy(showKeyboardOnFocus = false)
}

/**
 * Makes a whole "label + Switch" row a single click/focus target instead of only the Switch -
 * the recommended Material pattern, and also fixes a confirmed TV bug: Compose's directional
 * focus search reliably jumped past a lone, narrow, right-aligned Switch when moving Up/Down,
 * skipping the row entirely. A full-width row target is found reliably instead.
 *
 * The Switch in such a row should pass `onCheckedChange = null` (decorative only) so there's
 * exactly one click/focus target for the row, not two competing ones.
 */
fun Modifier.toggleableRow(
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
): Modifier = toggleable(value = checked, enabled = enabled, onValueChange = onCheckedChange, role = Role.Switch)

/**
 * Maps this app's Material3 colour scheme (light/dark/dynamic - see Z2mDashTheme) onto
 * tv-material3's unrelated ColorScheme type, so the TV nav rail tracks the app's actual theme
 * instead of tv-material3's default tokens. Confirmed on-device: without this, combined with a
 * separate wrong-library Icon/Text bug (see AppNavHost.kt), the rail rendered near-illegible
 * dark-on-dark in system dark mode.
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
 * A visible "focus is here" ring for a container that's either itself focusable (SensorTile's
 * Surface) or wraps separate focusable children (ToggleTile's icon/Switch) - hence `hasFocus`
 * rather than `isFocused`, which would miss the latter case. `clickable` only grants focus in
 * non-touch mode, so this stays invisible on touchscreens - safe to apply unconditionally rather
 * than gating on [LocalIsTv].
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
