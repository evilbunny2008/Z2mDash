package com.odiousapps.z2mdash.data

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Shared logic for the "Permit Join" toggle (AddEditBrokerScreen and HomeScreen's own
 * quick-access banner both read/write the same Zigbee2MQTT bridge topics) - kept in one
 * place so both call sites stay in sync with each other and with Zigbee2MQTT's own API shape.
 */
object PermitJoin {

    /** Same normalisation as MqttConnectionManager's own (private) wildcardTopicFor(). */
    fun normalizedBaseTopic(rawBaseTopic: String): String =
        rawBaseTopic.trim().trim('/').ifBlank { "zigbee2mqtt" }

    fun requestTopic(baseTopic: String) = "$baseTopic/bridge/request/permit_join"
    fun infoTopic(baseTopic: String) = "$baseTopic/bridge/info"

    data class Status(val isOn: Boolean, val remainingSeconds: Int)

    /**
     * Reads current permit-join state from Zigbee2MQTT's own "bridge/info", not the one-shot
     * "bridge/response/permit_join" acknowledgement - that response only ever reflects the
     * single request it answered and never updates again afterwards, so a toggle driven by it
     * alone would show a frozen "time" instead of one that actually counts down (or notices
     * joining being closed/opened by another client, e.g. Zigbee2MQTT's own frontend).
     * bridge/info is republished by the bridge whenever this state changes and carries a live
     * "permit_join_end" timestamp for exactly this purpose - already epoch MILLISECONDS (it's
     * `Date.now() + time*1000` straight from zigbee-herdsman, not epoch seconds despite what the
     * Zigbee2MQTT docs' wording might suggest).
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
