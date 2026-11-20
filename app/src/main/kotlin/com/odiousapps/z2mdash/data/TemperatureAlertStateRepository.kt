package com.odiousapps.z2mdash.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * TemperatureAlertManager's crossing state - which side of each limit every panel was last seen
 * on, the limits it was classified against, and when each notification last fired. Kept on disk
 * because periodic background checks (see TemperatureCheckWorker) usually run in a freshly started
 * process: held only in memory, every check would be a first observation that just resyncs, so a
 * crossing while the app was closed would never notify, and the cooldown would reset every time.
 */
@Serializable
data class TemperatureAlertState(
    val aboveHigh: Set<String> = emptySet(),
    val belowLow: Set<String> = emptySet(),
    val limits: Map<String, TemperatureAlertLimits> = emptyMap(),
    val lastNotifiedAt: Map<String, Long> = emptyMap()
)

class TemperatureAlertStateRepository(context: Context) {
    private val file = File(context.filesDir, "temperature_alert_state.json")
    private val json = Json { ignoreUnknownKeys = true }

    fun load(): TemperatureAlertState = try {
        if (file.exists()) json.decodeFromString(TemperatureAlertState.serializer(), file.readText()) else TemperatureAlertState()
    } catch (_: Exception) {
        TemperatureAlertState()
    }

    // Temp file then rename, same as PayloadCacheRepository.save - a check can be killed mid-write.
    fun save(state: TemperatureAlertState) {
        try {
            val tempFile = File(file.parentFile, "${file.name}.${System.nanoTime()}.tmp")
            tempFile.writeText(json.encodeToString(TemperatureAlertState.serializer(), state))
            tempFile.renameTo(file)
        } catch (_: Exception) {
            // Losing a write only costs one resync on the next observation.
        }
    }
}
