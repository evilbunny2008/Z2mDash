package com.odiousapps.z2mdash.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Turns a raw bag of "topic -> last payload" (from subscribing a broker to "#") into candidate
 * sensors: topics whose payload is a JSON object with at least one numeric field. Commands,
 * availability pings, and bridge chatter are excluded automatically since they won't parse into
 * numeric fields - no allow/deny list needed.
 */
object SensorDiscovery {

    data class DiscoveredField(val key: String, val sampleValue: Double)

    data class DiscoveredSensor(
        val topic: String,
        val fields: List<DiscoveredField>,
        // Present when "<topic>/ideal" also exists among the observed topics.
        val idealRangeTopic: String?,
        // Present when "<topic>/app" also exists among the observed topics -
        // a device-published dashboard config (name, field list, thresholds).
        val appConfigTopic: String?
    )

    /**
     * A device-published dashboard config, e.g.
     * {"name":"Alpinia - 01","moisture_min":60,"moisture_max":75,
     *  "panels":["linkquality","humidity","temperature","soil_moisture"]}
     *
     * [rangePairs] maps a base concept (e.g. "moisture") to its (minKey, maxKey) pair, found
     * generically from any two "<base>_min"/"<base>_max" fields - not hardcoded to "moisture".
     */
    data class DeviceAppConfig(
        val name: String,
        // Optional. When set, panels go into a dashboard group with this name (created if
        // needed), bypassing the UI-picked group - the device fully self-configures.
        val group: String?,
        // Optional. This device's cluster/panel position within [group] (lower sorts first).
        val groupOrder: Int?,
        val panelFields: List<String>,
        // Optional, parallel to panelFields - custom label per field, else suggestedLabel().
        val labels: List<String>,
        val rangePairs: Map<String, Pair<String, String>>,
        // Optional, parallel to panelFields - overrides which cluster the field renders in
        // instead of [name]. Blank/missing falls back to [name].
        val panelClusters: List<String> = emptyList(),
        // Optional, parallel to panelFields - position within its cluster (lower sorts first),
        // else falls back to [groupOrder] then declaration order.
        val panelOrders: List<Int?> = emptyList(),
        // Optional, parallel to panelFields - decimal places for the displayed value, else
        // Panel.Sensor's default (1).
        val panelDecimals: List<Int?> = emptyList(),
        // Optional. Toggle/command panels (blinds, plugs, etc.) declared alongside the sensors.
        val controls: List<ControlConfig> = emptyList(),
        // Optional epoch-millis stamp of when ordering was last set (written by the app on a
        // drag-reorder). Lets DeviceAutoConfigManager tell a genuinely newer order apart from a
        // stale retained redelivery when reconciling. Missing means "no order authority" -
        // the locally-applied order is left alone.
        val orderVersion: Long? = null
    )

    /** One toggle-style control declared in a device's "/app" payload's "controls" array. */
    data class ControlConfig(
        val label: String,
        val commandTopic: String,
        val onPayload: String,
        val offPayload: String,
        val stateTopic: String?,
        val stateField: String?,
        // Optional. Overrides which cluster this control renders in (instead of the device's
        // name) - lets one physical device (e.g. a combo light/fan switch) span multiple clusters.
        val cluster: String? = null,
        // Optional. Position within its cluster, else falls back to group_order then declaration
        // order. The only way to interleave with sensor panels, which are otherwise always built first.
        val order: Int? = null,
        // True when the config omits "off_payload" entirely (a single-press button with no
        // on/off state, e.g. a blind motor's STOP) - built as Panel.Button instead of Panel.Toggle.
        val momentary: Boolean = false
    )

    // Structural topics, not sensor data - skipped even if they contain a stray number
    // (the numeric-field check below already excludes most of these anyway).
    private val ignoredSuffixes = listOf("/set", "/get", "/availability", "/ideal", "/app", "/config")
    private val ignoredSubstrings = listOf("/bridge/")

    fun discoverSensors(payloadsForBroker: Map<String, String>): List<DiscoveredSensor> {
        val allTopics = payloadsForBroker.keys
        return payloadsForBroker.mapNotNull { (topic, payload) ->
            if (isIgnorable(topic)) return@mapNotNull null
            val fields = numericFieldsOf(payload)
            if (fields.isEmpty()) return@mapNotNull null
            val idealTopic = "$topic/ideal".takeIf { it in allTopics }
            val appTopic = "$topic/app".takeIf { it in allTopics }
            DiscoveredSensor(topic, fields, idealTopic, appTopic)
        }.sortedWith(compareByDescending<DiscoveredSensor> { it.appConfigTopic != null }.thenBy { it.topic })
    }

    /**
     * Rewrites a device's "/app" payload with updated ordering - panel_order for sensor fields,
     * order for each control - keyed by [orderByFieldOrLabel] (sensor field name or control
     * label). Everything else in the payload passes through unchanged. Also stamps
     * "order_version" with [orderVersion] (epoch-millis) so phones sharing a broker can tell a
     * genuinely newer order apart from a stale retained redelivery when reconciling - see
     * AutoConfiguredDevice.lastKnownOrderVersion. Returns null if the payload isn't a JSON object.
     */
    fun updateOrderingInAppPayload(
        currentPayload: String,
        orderByFieldOrLabel: Map<String, Int>,
        orderVersion: Long
    ): String? = try {
        val obj = Json.parseToJsonElement(currentPayload) as? JsonObject
        if (obj == null) {
            null
        } else {
            val mutableFields = obj.toMutableMap()

            val panelsArray = obj["panels"] as? JsonArray
            if (panelsArray != null) {
                val panelOrderArray = panelsArray.map { fieldElement ->
                    val fieldName = (fieldElement as? JsonPrimitive)?.contentOrNull
                    val order = fieldName?.let { orderByFieldOrLabel[it] }
                    if (order != null) JsonPrimitive(order) else JsonNull
                }
                mutableFields["panel_order"] = JsonArray(panelOrderArray)
            }

            val controlsArray = obj["controls"] as? JsonArray
            if (controlsArray != null) {
                val updatedControls = controlsArray.map { controlElement ->
                    val controlObj = controlElement as? JsonObject
                    val label = (controlObj?.get("label") as? JsonPrimitive)?.contentOrNull
                    val order = label?.let { orderByFieldOrLabel[it] }
                    if (controlObj != null && order != null) {
                        JsonObject(controlObj.toMutableMap().apply { put("order", JsonPrimitive(order)) })
                    } else {
                        controlElement
                    }
                }
                mutableFields["controls"] = JsonArray(updatedControls)
            }

            mutableFields["order_version"] = JsonPrimitive(orderVersion)
            Json.encodeToString(JsonElement.serializer(), JsonObject(mutableFields))
        }
    } catch (_: Exception) {
        null
    }

    /**
     * Rewrites a device's "/app" payload with "group_order" updated and "order_version" stamped
     * (see updateOrderingInAppPayload's doc) - everything else passes through unchanged. Used
     * when a cluster reorder shifts a device's position among siblings in the same group.
     * Returns null if the payload isn't a JSON object.
     */
    fun updateGroupOrderInAppPayload(currentPayload: String, newGroupOrder: Int, orderVersion: Long): String? = try {
        val obj = Json.parseToJsonElement(currentPayload) as? JsonObject
        if (obj == null) {
            null
        } else {
            val mutableFields = obj.toMutableMap()
            mutableFields["group_order"] = JsonPrimitive(newGroupOrder)
            mutableFields["order_version"] = JsonPrimitive(orderVersion)
            Json.encodeToString(JsonElement.serializer(), JsonObject(mutableFields))
        }
    } catch (_: Exception) {
        null
    }

    /**
     * Rewrites a device's "/app" payload to reflect local edits made in the app - a field's or
     * control's label/cluster override, the device's own "name" (which every field/control
     * without its own cluster override falls back to as its cluster), and/or the shared "group"
     * every cluster this payload describes belongs to. Fields/controls are matched by their
     * stable identity (sensor field key; control by its command_topic, falling back to
     * "<state_topic>/set" the same way parseControlConfig does), not by position, so passing a
     * partial update leaves everything else - including keys this app doesn't recognise -
     * untouched. Doesn't touch "order_version"; that's unrelated to these fields. Returns null if
     * the payload isn't a JSON object.
     */
    fun updateDescriptionInAppPayload(
        currentPayload: String,
        fieldLabelUpdates: Map<String, String> = emptyMap(),
        fieldClusterUpdates: Map<String, String> = emptyMap(),
        controlLabelUpdates: Map<String, String> = emptyMap(),
        controlClusterUpdates: Map<String, String> = emptyMap(),
        newDeviceName: String? = null,
        newGroup: String? = null
    ): String? = try {
        val obj = Json.parseToJsonElement(currentPayload) as? JsonObject
        if (obj == null) {
            null
        } else {
            val mutableFields = obj.toMutableMap()

            if (fieldLabelUpdates.isNotEmpty() || fieldClusterUpdates.isNotEmpty()) {
                val fieldNames = (obj["panels"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                if (fieldNames != null) {
                    if (fieldLabelUpdates.isNotEmpty()) {
                        val existing = (obj["labels"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                        val updated = fieldNames.mapIndexed { i, field ->
                            fieldLabelUpdates[field] ?: existing?.getOrNull(i) ?: ""
                        }
                        mutableFields["labels"] = JsonArray(updated.map { JsonPrimitive(it) })
                    }
                    if (fieldClusterUpdates.isNotEmpty()) {
                        val existing = (obj["panel_clusters"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                        val updated = fieldNames.mapIndexed { i, field ->
                            fieldClusterUpdates[field] ?: existing?.getOrNull(i) ?: ""
                        }
                        mutableFields["panel_clusters"] = JsonArray(updated.map { JsonPrimitive(it) })
                    }
                }
            }

            if (controlLabelUpdates.isNotEmpty() || controlClusterUpdates.isNotEmpty()) {
                val controlsArray = obj["controls"] as? JsonArray
                if (controlsArray != null) {
                    val updatedControls = controlsArray.map { element ->
                        val controlObj = element as? JsonObject ?: return@map element
                        val stateTopic = (controlObj["state_topic"] as? JsonPrimitive)?.contentOrNull
                        val commandTopic = (controlObj["command_topic"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                            ?: stateTopic?.takeIf { it.isNotBlank() }?.let { "$it/set" }
                            ?: return@map element
                        val newLabel = controlLabelUpdates[commandTopic]
                        val newCluster = controlClusterUpdates[commandTopic]
                        if (newLabel == null && newCluster == null) {
                            element
                        } else {
                            val updatedControl = controlObj.toMutableMap()
                            newLabel?.let { updatedControl["label"] = JsonPrimitive(it) }
                            newCluster?.let { updatedControl["cluster"] = JsonPrimitive(it) }
                            JsonObject(updatedControl)
                        }
                    }
                    mutableFields["controls"] = JsonArray(updatedControls)
                }
            }

            newDeviceName?.let { mutableFields["name"] = JsonPrimitive(it) }
            newGroup?.let { mutableFields["group"] = JsonPrimitive(it) }

            Json.encodeToString(JsonElement.serializer(), JsonObject(mutableFields))
        }
    } catch (_: Exception) {
        null
    }

    /** Parses a "<topic>/app" payload into a DeviceAppConfig, or null if it doesn't look like one. */
    fun parseDeviceAppConfig(payload: String): DeviceAppConfig? = try {
        val obj = Json.parseToJsonElement(payload) as? JsonObject
        val panelsArray = obj?.get("panels") as? JsonArray
        val panelFields = panelsArray?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()
        val controlsArray = obj?.get("controls") as? JsonArray
        val controls = controlsArray?.mapNotNull { parseControlConfig(it as? JsonObject) } ?: emptyList()

        if (obj == null || (panelFields.isEmpty() && controls.isEmpty())) {
            null
        } else {
            val name = (obj["name"] as? JsonPrimitive)?.contentOrNull ?: ""
            val group = (obj["group"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            val groupOrder = (obj["group_order"] as? JsonPrimitive)?.intOrNull
            val labelsArray = obj["labels"] as? JsonArray
            val labels = labelsArray?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()
            val panelClustersArray = obj["panel_clusters"] as? JsonArray
            val panelClusters = panelClustersArray?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()
            val panelOrdersArray = obj["panel_order"] as? JsonArray
            val panelOrders = panelOrdersArray?.map { (it as? JsonPrimitive)?.intOrNull } ?: emptyList()
            val panelDecimalsArray = obj["panel_decimals"] as? JsonArray
            val panelDecimals = panelDecimalsArray?.map { (it as? JsonPrimitive)?.intOrNull } ?: emptyList()
            val orderVersion = (obj["order_version"] as? JsonPrimitive)?.longOrNull
            val numericKeys = obj.entries
                .filter { (_, v) -> (v as? JsonPrimitive)?.doubleOrNull != null }
                .map { it.key }
            val rangePairs = mutableMapOf<String, Pair<String, String>>()
            numericKeys.filter { it.endsWith("_min") }.forEach { minKey ->
                val base = minKey.removeSuffix("_min")
                val maxKey = "${base}_max"
                if (maxKey in numericKeys) rangePairs[base] = minKey to maxKey
            }
            DeviceAppConfig(
                name = name,
                group = group,
                groupOrder = groupOrder,
                panelFields = panelFields,
                labels = labels,
                rangePairs = rangePairs,
                panelClusters = panelClusters,
                panelOrders = panelOrders,
                panelDecimals = panelDecimals,
                controls = controls,
                orderVersion = orderVersion
            )
        }
    } catch (_: Exception) {
        null
    }

    private fun parseControlConfig(obj: JsonObject?): ControlConfig? {
        if (obj == null) return null
        val label = (obj["label"] as? JsonPrimitive)?.contentOrNull ?: "Toggle"
        // Accepts either a plain string ("ON") or a real JSON object
        // ({"state":"OPEN"}) - objects get serialized to their compact string
        // form, since that's what actually gets published as the MQTT payload.
        val onPayload = jsonValueToPayloadString(obj["on_payload"]) ?: "ON"
        val momentary = obj["off_payload"] == null
        val offPayload = jsonValueToPayloadString(obj["off_payload"]) ?: "OFF"
        val stateTopic = (obj["state_topic"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        val stateField = (obj["state_field"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        val cluster = (obj["cluster"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        val order = (obj["order"] as? JsonPrimitive)?.intOrNull
        // Zigbee2MQTT convention: commands go to "<state topic>/set" unless the
        // device explicitly overrides it with its own command_topic.
        val commandTopic = (obj["command_topic"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: stateTopic?.let { "$it/set" }
            ?: return null
        return ControlConfig(label, commandTopic, onPayload, offPayload, stateTopic, stateField, cluster, order, momentary)
    }

    /** A JSON string primitive is used as-is; any other element (object/array/etc.) is re-serialized to its compact form. */
    private fun jsonValueToPayloadString(element: JsonElement?): String? = when (element) {
        null -> null
        is JsonPrimitive -> element.contentOrNull ?: element.toString()
        else -> element.toString()
    }

    private fun isIgnorable(topic: String): Boolean {
        if (ignoredSubstrings.any { topic.contains(it) }) return true
        if (ignoredSuffixes.any { topic.endsWith(it) }) return true
        return false
    }

    private fun numericFieldsOf(payload: String): List<DiscoveredField> = try {
        val element = Json.parseToJsonElement(payload)
        val obj = element as? JsonObject
        obj?.entries?.mapNotNull { (key, value) ->
            val num = (value as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
            DiscoveredField(key, num)
        }
            ?: emptyList()
    } catch (_: Exception) {
        emptyList()
    }

    /** A readable default label for a raw JSON key, e.g. "soil_moisture" -> "Soil Moisture". */
    fun suggestedLabel(key: String): String = when (key.lowercase()) {
        "linkquality" -> "Link Quality"
        else -> key.split("_").joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
    }

    /** Numeric field keys present in a raw topic payload - used to decide which
     * topic (main sensor vs the app-config topic itself) a device-declared
     * panel field should be read from. */
    fun fieldKeysOf(payload: String): Set<String> = numericFieldsOf(payload).map { it.key }.toSet()

    /**
     * Builds the panels described by [deviceConfig]: Sensor panels from panelFields, plus
     * Toggle/Button panels from declared controls. A sensor field is pointed at the app-config
     * topic if found there (e.g. "moisture_min"), else the main sensor topic, trusting
     * panelFields' declaration regardless of whether a live message has been seen yet.
     * [sensorFieldKeys] is currently unused (kept to avoid churning call sites) - an earlier
     * version required a field to be confirmed live first, which meant a valid device could never
     * be added if its topic simply hadn't published recently. Fields in a detected
     * "<base>_min"/"<base>_max" pair get an ideal range wired to the app-config topic, except the
     * min/max fields themselves. Pure function: callers decide where panels get stored.
     */
    /**
     * Combines [groupOrder] (a cluster's rank among others in the group) with [within] (a field/
     * control's position inside that cluster) into an absolute Panel.displayOrder, scaling
     * groupOrder up to dominate (same 1000-spacing as ConfigRepository.reorderClustersInGroup).
     * With neither set, the panel falls in after everything ordered. [fallbackWithin] (normally
     * declaration index) keeps same-cluster panels distinct when [within] is absent.
     */
    private fun composedDisplayOrder(groupOrder: Int?, within: Int?, fallbackWithin: Int): Int {
        if (groupOrder == null && within == null) return Int.MAX_VALUE
        return (groupOrder ?: 0) * 1000 + (within ?: fallbackWithin)
    }

    @Suppress("unused", "RedundantSuppression")
    fun buildPanels(
        brokerId: String,
        sensorTopic: String,
        sensorFieldKeys: Set<String>,
        appConfigTopic: String,
        appConfigPayload: String?,
        deviceConfig: DeviceAppConfig
    ): List<Panel> {
        val rangeKeys = deviceConfig.rangePairs.values.flatMap { (min, max) -> listOf(min, max) }.toSet()
        val deviceClusterName = deviceConfig.name.ifBlank { sensorTopic.substringAfterLast("/") }

        val sensorPanels: List<Panel> = deviceConfig.panelFields.mapIndexed { index, field ->
            // Prefer the app-config topic when the field is actually found there (some devices
            // publish live values alongside their config). Otherwise, default to the sensor topic
            // even if it hasn't published yet - the panel shows "--" until a message arrives,
            // rather than refusing to create the panel just because the topic was quiet.
            val topic = if (appConfigPayload != null && JsonPath.extract(appConfigPayload, field) != null) {
                appConfigTopic
            } else {
                sensorTopic
            }

            val rangeBase = if (field !in rangeKeys) {
                deviceConfig.rangePairs.keys.find { base -> field.contains(base, ignoreCase = true) }
            } else null

            val label = deviceConfig.labels.getOrNull(index)?.takeIf { it.isNotBlank() }
                ?: suggestedLabel(field)
            val clusterName = deviceConfig.panelClusters.getOrNull(index)?.takeIf { it.isNotBlank() }
                ?: deviceClusterName

            Panel.Sensor(
                id = java.util.UUID.randomUUID().toString(),
                label = label,
                brokerId = brokerId,
                topic = topic,
                jsonPath = field,
                unit = suggestedUnit(field),
                icon = suggestedIcon(field),
                idealRangeTopic = if (rangeBase != null) appConfigTopic else "",
                idealMinPath = rangeBase?.let { deviceConfig.rangePairs[it]!!.first } ?: "min",
                idealMaxPath = rangeBase?.let { deviceConfig.rangePairs[it]!!.second } ?: "max",
                clusterName = clusterName,
                displayOrder = composedDisplayOrder(deviceConfig.groupOrder, deviceConfig.panelOrders.getOrNull(index), index),
                decimals = deviceConfig.panelDecimals.getOrNull(index) ?: suggestedDecimals(field)
            )
        }

        val controlPanels: List<Panel> = deviceConfig.controls.mapIndexed { controlIndex, control ->
            // Sensor panels are always built before controls (see ControlConfig.order), so a
            // control with no explicit order falls in right after them by default.
            val fallbackWithin = sensorPanels.size + controlIndex
            if (control.momentary) {
                Panel.Button(
                    id = java.util.UUID.randomUUID().toString(),
                    label = control.label,
                    brokerId = brokerId,
                    commandTopic = control.commandTopic,
                    payload = control.onPayload,
                    clusterName = control.cluster?.takeIf { it.isNotBlank() } ?: deviceClusterName,
                    displayOrder = composedDisplayOrder(deviceConfig.groupOrder, control.order, fallbackWithin)
                )
            } else {
                Panel.Toggle(
                    id = java.util.UUID.randomUUID().toString(),
                    label = control.label,
                    brokerId = brokerId,
                    commandTopic = control.commandTopic,
                    onPayload = control.onPayload,
                    offPayload = control.offPayload,
                    stateTopic = control.stateTopic ?: "",
                    stateJsonPath = control.stateField ?: "",
                    clusterName = control.cluster?.takeIf { it.isNotBlank() } ?: deviceClusterName,
                    displayOrder = composedDisplayOrder(deviceConfig.groupOrder, control.order, fallbackWithin)
                )
            }
        }

        return sensorPanels + controlPanels
    }

    fun suggestedIcon(key: String): TileIcon = when {
        key.contains("moisture", ignoreCase = true) -> TileIcon.MOISTURE
        key.contains("humidity", ignoreCase = true) -> TileIcon.HUMIDITY
        key.contains("temperature", ignoreCase = true) -> TileIcon.TEMPERATURE
        key.contains("battery", ignoreCase = true) -> TileIcon.BATTERY
        key.contains("linkquality", ignoreCase = true) -> TileIcon.SIGNAL
        key.contains("occupancy", ignoreCase = true) -> TileIcon.PRESENCE
        key.contains("presence", ignoreCase = true) -> TileIcon.PRESENCE
        key.contains("illuminance", ignoreCase = true) -> TileIcon.LIGHT
        else -> TileIcon.GAUGE
    }

    fun suggestedUnit(key: String): String = when {
        key.contains("temperature", ignoreCase = true) -> "\u00b0C"
        key.contains("humidity", ignoreCase = true) -> "%"
        key.contains("moisture", ignoreCase = true) -> "%"
        key.contains("battery", ignoreCase = true) -> "%"
        key.contains("illuminance", ignoreCase = true) -> "lx"
        else -> ""
    }

    /** Default decimal places for a field when the device's own "/app" config doesn't specify one via panel_decimals - temperature benefits from a decimal (e.g. "21.5"), everything else defaults to whole numbers. */
    fun suggestedDecimals(key: String): Int = when {
        key.contains("temperature", ignoreCase = true) -> 1
        else -> 0
    }
}
