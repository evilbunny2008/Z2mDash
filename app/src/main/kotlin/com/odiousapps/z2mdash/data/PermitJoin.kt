package com.odiousapps.z2mdash.data

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Shared logic for the "Permit Join" toggle, used by both AddEditBrokerScreen and HomeScreen's
 * banner, so they stay in sync with each other and with Zigbee2MQTT's bridge topic API.
 */
object PermitJoin {

    /** Same normalisation as MqttConnectionManager's own (private) wildcardTopicFor(). */
    fun normalizedBaseTopic(rawBaseTopic: String): String =
        rawBaseTopic.trim().trim('/').ifBlank { "zigbee2mqtt" }

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
}
