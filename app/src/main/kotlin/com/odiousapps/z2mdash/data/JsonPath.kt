package com.odiousapps.z2mdash.data

import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Extracts a value from a JSON MQTT payload using a simple dot path,
 * e.g. "temperature" or "state.battery" for nested objects.
 *
 * If "path" is blank the raw payload is returned as-is (trimmed) - useful for
 * topics that publish a bare value rather than a JSON object.
 * Returns null if the payload isn't valid JSON or the path doesn't resolve,
 * so the UI can show a "--" placeholder instead of crashing.
 */
object JsonPath {
    // Bounded cache of raw payload string -> its parsed JsonElement tree. extract() is called
    // many times against the exact same still-current payload string - once per field a panel
    // reads from it, and, since a cluster's "updated N ago" text re-derives every second off a
    // ticker, potentially once per second for as long as that payload stays the most recent one.
    // Re-parsing identical JSON from scratch on every one of those calls was a real, continuous
    // source of GC pressure - confirmed on an underpowered TV device via `adb logcat`, where the
    // app was triggering a full concurrent-copying GC (freeing anywhere from ~100k to over a
    // million objects each time) roughly once a second, indefinitely, long after startup had
    // finished - not just a one-off cold-start cost. Bounded rather than an ever-growing map,
    // since payload strings churn over a session; synchronized since this can be read from both
    // Compose's main thread and any background coroutine that also calls extract() (e.g.
    // DeviceAutoConfigManager), and LinkedHashMap itself isn't thread-safe.
    private const val PARSE_CACHE_MAX_SIZE = 300
    private val parseCache = object : LinkedHashMap<String, JsonElement>(PARSE_CACHE_MAX_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, JsonElement>?): Boolean =
            size > PARSE_CACHE_MAX_SIZE
    }

    fun extract(rawPayload: String, path: String): String? {
        if (path.isBlank()) return rawPayload.trim()
        return try {
            var element: JsonElement = synchronized(parseCache) {
                parseCache[rawPayload] ?: Json.parseToJsonElement(rawPayload).also { parseCache[rawPayload] = it }
            }
            for (segment in path.split(".")) {
                val obj = element as? JsonObject ?: return null
                element = obj[segment] ?: return null
            }
            when (element) {
                is JsonPrimitive -> element.contentOrNull ?: element.toString()
                else -> element.toString()
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Parses an ISO-8601 timestamp (e.g. Zigbee2MQTT's "last_seen" field,
     * "2026-08-22T13:46:35.354Z") to epoch millis, or null if the string isn't
     * a valid ISO-8601 instant.
     */
    fun parseIso8601(value: String): Long? = try {
        Instant.parse(value).toEpochMilli()
    } catch (_: Exception) {
        null
    }
}
