package com.odiousapps.z2mdash.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class PayloadCacheEntry(val payload: String, val timestamp: Long)

/**
 * Persists the last-known payload (and arrival time) for every topic, so the dashboard can show
 * correct "updated N ago" ages immediately on open, rather than a misleading "just now" until
 * fresh MQTT messages repopulate everything after a restart.
 */
class PayloadCacheRepository(context: Context) {
    private val file = File(context.filesDir, "payload_cache.json")
    private val json = Json { ignoreUnknownKeys = true }
    // Explicit serializer instead of the reified extensions - avoids an Android Studio
    // unused-import false positive with those.
    private val entriesSerializer = MapSerializer(String.serializer(), PayloadCacheEntry.serializer())

    fun load(): Map<String, PayloadCacheEntry> = try {
        if (file.exists()) json.decodeFromString(entriesSerializer, file.readText()) else emptyMap()
    } catch (_: Exception) {
        emptyMap()
    }

    fun save(entries: Map<String, PayloadCacheEntry>) {
        try {
            file.writeText(json.encodeToString(entriesSerializer, entries))
        } catch (_: Exception) {
            // Best-effort cache - fine to silently skip a write if it fails.
        }
    }
}
