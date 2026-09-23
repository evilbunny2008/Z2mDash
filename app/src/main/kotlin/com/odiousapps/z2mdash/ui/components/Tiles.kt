package com.odiousapps.z2mdash.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Blinds
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Co2
import androidx.compose.material.icons.filled.DeviceThermostat
import androidx.compose.material.icons.filled.Doorbell
import androidx.compose.material.icons.filled.GasMeter
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Opacity
import androidx.compose.material.icons.filled.Outlet
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Plumbing
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.SensorDoor
import androidx.compose.material.icons.filled.SignalWifi4Bar
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.WaterDamage
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.odiousapps.z2mdash.data.TileIcon
import com.odiousapps.z2mdash.ui.tv.tvFocusIndicator

/** Whether a sensor's current value sits inside, above, or below its configured ideal range. */
enum class SensorAlert { NONE, IN_RANGE, BELOW_MIN, ABOVE_MAX }

private val AlertRed = Color(0xFFE53935)
private val AlertBlue = Color(0xFF1E88E5)
private val AlertGreen = Color(0xFF43A047)

private fun iconFor(tileIcon: TileIcon): ImageVector = when (tileIcon) {
    TileIcon.HUMIDITY -> Icons.Default.WaterDrop
    TileIcon.MOISTURE -> Icons.Default.Opacity
    TileIcon.TEMPERATURE -> Icons.Default.Thermostat
    TileIcon.SIGNAL -> Icons.Default.SignalWifi4Bar
    TileIcon.POWER -> Icons.Default.Power
    TileIcon.GAUGE -> Icons.Default.Speed
    TileIcon.BATTERY -> Icons.Default.BatteryFull
    TileIcon.LIGHT -> Icons.Default.Lightbulb
    TileIcon.PRESENCE -> Icons.Default.Person
    TileIcon.CONTACT -> Icons.Default.SensorDoor
    TileIcon.LOCK -> Icons.Default.Lock
    TileIcon.WATER_LEAK -> Icons.Default.WaterDamage
    TileIcon.SMOKE -> Icons.Default.LocalFireDepartment
    TileIcon.GAS -> Icons.Default.GasMeter
    TileIcon.VIBRATION -> Icons.Default.Vibration
    TileIcon.AIR_QUALITY -> Icons.Default.Co2
    TileIcon.SIREN -> Icons.Default.NotificationsActive
    TileIcon.WARNING -> Icons.Default.Warning
    TileIcon.BUTTON -> Icons.Default.TouchApp
    TileIcon.OUTLET -> Icons.Default.Outlet
    TileIcon.ENERGY -> Icons.Default.Bolt
    TileIcon.COLOR -> Icons.Default.Palette
    TileIcon.COVER -> Icons.Default.Blinds
    TileIcon.CLIMATE -> Icons.Default.DeviceThermostat
    TileIcon.FAN -> Icons.Default.Air
    TileIcon.ROUTER -> Icons.Default.Router
    TileIcon.VALVE -> Icons.Default.Plumbing
    TileIcon.DOORBELL -> Icons.Default.Doorbell
}

@Composable
fun SensorTile(
    modifier: Modifier = Modifier,
    icon: TileIcon,
    value: String,
    unit: String,
    label: String,
    alert: SensorAlert = SensorAlert.NONE,
    // When false, an alert shows as solid colour instead of pulsing - governed by the same
    // Blink Stale Data Indicator setting used for cluster-level stale-data blink too.
    blinkEnabled: Boolean = true,
    // Only meaningful for TileIcon.PRESENCE - tints the icon so an active "someone's here"
    // state stands out without needing a separate glyph for "clear".
    iconTint: Color? = null,
    // Proportional to the tile's configured width (1f at the 110dp default) - shrinks text,
    // icon, height and spacing together for TV's narrower tiles instead of keeping phone-sized
    // proportions in a smaller box.
    scale: Float = 1f,
    onEdit: () -> Unit = {},
    // See Panel.Sensor's editable doc. When true, the icon becomes the config-edit affordance
    // (matching ToggleTile/ButtonTile's own icon-is-edit convention) and the rest of the tile
    // opens onEditValue instead - a plain (non-editable) tile is unaffected, keeping its whole
    // Surface as the config-edit click target exactly as before.
    editable: Boolean = false,
    onEditValue: () -> Unit = {}
) {
    val flashingAlertColor = when (alert) {
        SensorAlert.BELOW_MIN -> AlertRed
        SensorAlert.ABOVE_MAX -> AlertBlue
        SensorAlert.IN_RANGE, SensorAlert.NONE -> null
    }

    val backgroundColor = when {
        flashingAlertColor != null && blinkEnabled -> {
            val infiniteTransition = rememberInfiniteTransition(label = "sensorAlert")
            val flashFraction = infiniteTransition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 600, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "flashFraction"
            ).value
            lerp(MaterialTheme.colorScheme.surfaceVariant, flashingAlertColor, flashFraction)
        }
        flashingAlertColor != null -> flashingAlertColor
        alert == SensorAlert.IN_RANGE -> AlertGreen
        else -> MaterialTheme.colorScheme.surfaceVariant
    }

    Surface(
        modifier = modifier
            .heightIn(min = 120.dp * scale)
            .tvFocusIndicator()
            .clickable(onClick = if (editable) onEditValue else onEdit),
        shape = RoundedCornerShape(12.dp),
        color = backgroundColor,
        tonalElevation = if (flashingAlertColor != null || alert == SensorAlert.IN_RANGE) 0.dp else 1.dp
    ) {
        Column(
            modifier = Modifier.padding(12.dp * scale).fillMaxWidth().fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                iconFor(icon),
                contentDescription = null,
                tint = iconTint ?: LocalContentColor.current,
                modifier = Modifier.size(24.dp * scale)
                    .then(if (editable) Modifier.clickable(onClick = onEdit) else Modifier)
            )
            Spacer(Modifier.height(8.dp * scale))
            Text(
                if (unit.isNotBlank()) "$value$unit" else value,
                // lineHeight scaled alongside fontSize - Material3's typography styles set an
                // explicit lineHeight that doesn't shrink with fontSize on its own, leaving
                // reserved vertical space even as the tile shrank (confirmed: ~25dp box for ~12sp
                // text), the real cause of a "spare space" complaint at small tile sizes.
                style = MaterialTheme.typography.titleLarge.copy(
                    fontSize = MaterialTheme.typography.titleLarge.fontSize * scale,
                    lineHeight = MaterialTheme.typography.titleLarge.lineHeight * scale
                ),
                textAlign = TextAlign.Center,
                maxLines = 1,
                // Clip rather than Ellipsis - the unit suffix is a nice-to-have, but "…" eating
                // into the number itself (e.g. "30.0°C" -> "30…") is worse than clipping the unit
                // ("30.0°"), which keeps the number fully visible.
                overflow = TextOverflow.Clip,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(4.dp * scale))
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = MaterialTheme.typography.bodyMedium.fontSize * scale,
                    lineHeight = MaterialTheme.typography.bodyMedium.lineHeight * scale
                ),
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
fun ToggleTile(
    modifier: Modifier = Modifier,
    icon: TileIcon,
    label: String,
    isOn: Boolean,
    onToggle: () -> Unit,
    // See SensorTile's `scale` param - same proportional sizing. The Switch itself stays full
    // size regardless of scale - it's an interactive control with a mandated touch target, not
    // decorative, so shrinking it would only make it harder to hit for no benefit.
    scale: Float = 1f,
    onEdit: () -> Unit = {}
) {
    // The edit click is deliberately NOT on the whole Surface - Switch's Material-mandated 48dp
    // touch target can swallow most of a tile this small, making a whole-card edit click
    // unreachable. Icon and label get their own clickable regions instead. Long-press anywhere
    // still reaches the drag detector from the outer modifier (clickable doesn't intercept it).
    Surface(
        modifier = modifier.heightIn(min = 120.dp * scale).tvFocusIndicator(),
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier.padding(12.dp * scale).fillMaxWidth().fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                iconFor(icon),
                contentDescription = null,
                modifier = Modifier.size(24.dp * scale).clickable(onClick = onEdit)
            )
            Spacer(Modifier.height(4.dp * scale))
            Switch(checked = isOn, onCheckedChange = { onToggle() })
            Spacer(Modifier.height(8.dp * scale))
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = MaterialTheme.typography.bodyMedium.fontSize * scale,
                    lineHeight = MaterialTheme.typography.bodyMedium.lineHeight * scale
                ),
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/** A momentary action tile - tapping the button sends its payload immediately; there's no on/off state to reflect, unlike ToggleTile. */
@Composable
fun ButtonTile(
    modifier: Modifier = Modifier,
    icon: TileIcon,
    label: String,
    onPress: () -> Unit,
    // See SensorTile's `scale` param - same proportional sizing. The Button itself stays full
    // size regardless of scale, same reasoning as ToggleTile's Switch.
    scale: Float = 1f,
    onEdit: () -> Unit = {}
) {
    Surface(
        modifier = modifier.heightIn(min = 120.dp * scale).tvFocusIndicator(),
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier.padding(12.dp * scale).fillMaxWidth().fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Same separation as ToggleTile: icon is the edit affordance, Button is the actual
            // action and should be the dominant tap target, not competing with a whole-card edit.
            Icon(
                iconFor(icon),
                contentDescription = null,
                modifier = Modifier.size(24.dp * scale).clickable(onClick = onEdit)
            )
            Spacer(Modifier.height(8.dp * scale))
            Button(onClick = onPress) {
                Text(label, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
