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

    // Writes to a uniquely-named sibling temp file, then atomically renames it over the real one
    // - two MqttConnectionManager callers (the 20s backstop loop and the 2s-debounced
    // schedulePersist, each running on Dispatchers.Default, which is a real thread pool) can
    // genuinely call save() around the same time, and a direct file.writeText() has no
    // protection against two such writers truncating/writing the same File concurrently, which
    // can corrupt it (one writer's truncate landing mid-write of the other). Each writer gets its
    // OWN temp file (a shared fixed temp name would just move the same race there instead), so
    // its own write() call always completes in full before the rename; a same-filesystem
    // File.renameTo is then atomic, so every reader (load()) only ever sees one writer's complete
    // content, never a partial mix of two - this also covers the process being SIGKILLed
    // mid-write, which this cache's own callers already call out as a real risk for this app.
    fun save(entries: Map<String, PayloadCacheEntry>) {
        try {
            val tempFile = File(file.parentFile, "${file.name}.${System.nanoTime()}.tmp")
            tempFile.writeText(json.encodeToString(entriesSerializer, entries))
            tempFile.renameTo(file)
        } catch (_: Exception) {
            // Best-effort cache - fine to silently skip a write if it fails.
        }
    }
}
