package com.odiousapps.z2mdash.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Shared logic for the "Permit Join" toggle, used by both AddEditBrokerScreen and HomeScreen's
 * banner, so they stay in sync with each other and with Zigbee2MQTT's bridge topic API.
 */
object PermitJoin {

    /** Same normalisation as MqttConnectionManager's own (private) wildcardTopicFor(). */
    fun normalizedBaseTopic(rawBaseTopic: String): String =
        rawBaseTopic.trim().trim('/').ifBlank { "zigbee2mqtt" }

    /**
     * Splits Broker.baseTopic on commas into one normalised topic per entry - lets a single
     * broker connection watch/permit-join across more than one Zigbee2MQTT namespace at once
     * (e.g. after splitting a large mesh across two bridges sharing the same MQTT broker), while
     * a plain single value with no comma keeps behaving exactly as before (a one-element list).
     * Blank segments (an empty field, a trailing comma, stray whitespace) are dropped rather than
     * each separately falling back to "zigbee2mqtt" - only an entirely empty result does that,
     * matching normalizedBaseTopic's own single-value fallback.
     */
    fun parseBaseTopics(rawBaseTopic: String): List<String> {
        val topics = rawBaseTopic.split(',')
            .map { it.trim().trim('/') }
            .filter { it.isNotBlank() }
            .distinct()
        return topics.ifEmpty { listOf("zigbee2mqtt") }
    }

    fun requestTopic(baseTopic: String) = "$baseTopic/bridge/request/permit_join"
    fun infoTopic(baseTopic: String) = "$baseTopic/bridge/info"

    data class Status(val isOn: Boolean, val remainingSeconds: Int)

    /**
     * Reads permit-join state from "bridge/info", not the one-shot "bridge/response/permit_join"
     * ack (which never updates again, so a toggle driven by it would freeze and miss changes from
     * other clients). bridge/info republishes on every change with a live "permit_join_end" -
     * already epoch MILLISECONDS despite what the Zigbee2MQTT docs might suggest.
     */
    fun status(payloads: Map<String, String>, brokerId: String, baseTopic: String, nowMillis: Long): Status {
        val infoPayload = payloads["$brokerId|${infoTopic(baseTopic)}"] ?: return Status(false, 0)
        val isOn = JsonPath.extract(infoPayload, "permit_join").equals("true", ignoreCase = true)
        if (!isOn) return Status(false, 0)
        val endEpochMillis = JsonPath.extract(infoPayload, "permit_join_end")?.toLongOrNull()
        val remainingSeconds = endEpochMillis
            ?.let { ((it - nowMillis) / 1000).toInt().coerceAtLeast(0) }
            ?: 0
        return Status(remainingSeconds > 0, remainingSeconds)
    }

    /** [device] blank permits joining via every router and the coordinator at once. */
    fun requestPayload(device: String, enableSeconds: Int): String = buildJsonObject {
        if (device.isNotBlank()) put("device", device.trim())
        put("time", enableSeconds)
    }.toString()

    /** "3:47" / "0:09" - minutes:seconds, matching how a countdown timer is normally read. */
    fun formatRemaining(remainingSeconds: Int): String {
        val minutes = remainingSeconds / 60
        val seconds = remainingSeconds % 60
        return "%d:%02d".format(minutes, seconds)
    }

    /**
     * Friendly names of every router/coordinator on [baseTopic]'s network, from its retained
     * "bridge/devices" list - only routers/coordinator can be targeted by permit_join's "device"
     * field (end devices don't route child joins), so anything else is filtered out. Empty if
     * that topic hasn't published its device list yet, or the payload doesn't parse.
     */
    fun routerFriendlyNames(payloads: Map<String, String>, brokerId: String, baseTopic: String): List<String> {
        val devicesPayload = payloads["$brokerId|$baseTopic/bridge/devices"] ?: return emptyList()
        return try {
            Json.parseToJsonElement(devicesPayload).jsonArray.mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                val type = obj["type"]?.jsonPrimitive?.contentOrNull
                if (type == "Router" || type == "Coordinator") {
                    obj["friendly_name"]?.jsonPrimitive?.contentOrNull
                } else {
                    null
                }
            }.sorted()
        } catch (_: Exception) {
            emptyList()
        }
    }
}
