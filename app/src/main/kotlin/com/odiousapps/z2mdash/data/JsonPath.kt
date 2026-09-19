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
    // Bounded cache of raw payload string -> parsed JsonElement. extract() re-reads the same
    // payload often (once per field, plus every second for "updated N ago" ticks), and
    // reparsing each time caused continuous GC pressure on an underpowered TV. Bounded since
    // payloads churn over a session; synchronised since both the main thread and background
    // coroutines (e.g. DeviceAutoConfigManager) call extract().
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

    /** Parses an ISO-8601 timestamp (e.g. Zigbee2MQTT's "last_seen") to epoch millis, or null. */
    fun parseIso8601(value: String): Long? = try {
        Instant.parse(value).toEpochMilli()
    } catch (_: Exception) {
        null
    }
}
