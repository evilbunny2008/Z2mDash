package com.odiousapps.z2mdash.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

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
        // Optional. [group]'s own position among every top-level dashboard group (lower sorts
        // first) - distinct from groupOrder, which only ranks clusters within one group. Lets
        // phones sharing a broker stay in sync on the order groups themselves appear in, the same
        // way groupOrder/panel_order sync cluster/panel order - see
        // AutoConfigPush.pushDashboardGroupOrderUpdates and DeviceAutoConfigManager's use of it.
        val dashboardOrder: Int?,
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
        // Optional, parallel to panelFields - unit override, else suggestedUnit(field).
        val panelUnits: List<String> = emptyList(),
        // Optional, parallel to panelFields - icon override (TileIcon name, case-insensitive),
        // else suggestedIcon(field).
        val panelIcons: List<String> = emptyList(),
        // Optional, parallel to panelFields - ideal-range topic/min-path/max-path overrides, each
        // taking priority over the auto-derived default (this device's own appConfigTopic, when a
        // matching "<x>_min"/"<x>_max" pair is found in rangePairs) independently of one another.
        val panelIdealTopics: List<String> = emptyList(),
        val panelIdealMinPaths: List<String> = emptyList(),
        val panelIdealMaxPaths: List<String> = emptyList(),
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
        val momentary: Boolean = false,
        // Optional. Overrides Panel.Toggle/Button's icon (TileIcon name, case-insensitive), else
        // the hardcoded TileIcon.POWER default - unlike a Sensor's icon, a control's icon has no
        // field name to derive a suggestion from.
        val icon: String? = null
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
     * Rewrites a device's "/app" payload with "dashboard_order" updated and "order_version"
     * stamped (see updateOrderingInAppPayload's doc) - everything else passes through unchanged.
     * Used when a top-level dashboard group reorder shifts [group]'s position among sibling
     * groups. Distinct from updateGroupOrderInAppPayload, which ranks clusters *within* a group.
     * Returns null if the payload isn't a JSON object.
     */
    fun updateDashboardGroupOrderInAppPayload(currentPayload: String, newDashboardOrder: Int, orderVersion: Long): String? = try {
        val obj = Json.parseToJsonElement(currentPayload) as? JsonObject
        if (obj == null) {
            null
        } else {
            val mutableFields = obj.toMutableMap()
            mutableFields["dashboard_order"] = JsonPrimitive(newDashboardOrder)
            mutableFields["order_version"] = JsonPrimitive(orderVersion)
            Json.encodeToString(JsonElement.serializer(), JsonObject(mutableFields))
        }
    } catch (_: Exception) {
        null
    }

    // Every parallel array that describes one entry per "panels" field - kept in one place so
    // removeSensorFieldFromAppPayload strips the same index from all of them, including any added
    // later, without a matching addition being forgotten there.
    private val sensorFieldParallelArrayKeys = listOf(
        "labels", "panel_clusters", "panel_order", "panel_decimals",
        "panel_units", "panel_icons", "panel_ideal_topics", "panel_ideal_min_paths", "panel_ideal_max_paths"
    )

    /**
     * Rewrites a device's "/app" payload with the sensor field at [fieldIndex] (into "panels")
     * removed, along with the same index from every parallel array that describes it (see
     * sensorFieldParallelArrayKeys) so they stay aligned, plus "order_version" stamped. Used when
     * the user deletes a single panel that came from this device's own declared field list:
     * without this, the retained payload keeps declaring a field this phone (and any other phone
     * sharing the broker) no longer shows a tile for, so the next time anything reconciles this
     * device - even just the echo of a sibling panel's own next edit - buildPanels silently
     * recreates the deleted field as a brand-new panel, undoing the delete. Returns null if the
     * payload isn't a JSON object, has no "panels" array, or [fieldIndex] is out of range.
     */
    fun removeSensorFieldFromAppPayload(currentPayload: String, fieldIndex: Int, orderVersion: Long): String? = try {
        val obj = Json.parseToJsonElement(currentPayload) as? JsonObject
        val panelsArray = obj?.get("panels") as? JsonArray
        if (obj == null || panelsArray == null || fieldIndex !in panelsArray.indices) {
            null
        } else {
            fun JsonArray.withoutIndex() = JsonArray(filterIndexed { i, _ -> i != fieldIndex })
            val mutableFields = obj.toMutableMap()
            mutableFields["panels"] = panelsArray.withoutIndex()
            sensorFieldParallelArrayKeys.forEach { key ->
                (obj[key] as? JsonArray)?.let { mutableFields[key] = it.withoutIndex() }
            }
            mutableFields["order_version"] = JsonPrimitive(orderVersion)
            Json.encodeToString(JsonElement.serializer(), JsonObject(mutableFields))
        }
    } catch (_: Exception) {
        null
    }

    /**
     * Rewrites a device's "/app" payload with the control at [controlIndex] (into "controls")
     * removed, and "order_version" stamped - the controls counterpart to
     * removeSensorFieldFromAppPayload, for the same reason (a deleted Toggle/Button panel must
     * stop being declared, or a later reconcile would recreate it). Returns null if the payload
     * isn't a JSON object, has no "controls" array, or [controlIndex] is out of range.
     */
    fun removeControlFromAppPayload(currentPayload: String, controlIndex: Int, orderVersion: Long): String? = try {
        val obj = Json.parseToJsonElement(currentPayload) as? JsonObject
        val controlsArray = obj?.get("controls") as? JsonArray
        if (obj == null || controlsArray == null || controlIndex !in controlsArray.indices) {
            null
        } else {
            val mutableFields = obj.toMutableMap()
            mutableFields["controls"] = JsonArray(controlsArray.filterIndexed { i, _ -> i != controlIndex })
            mutableFields["order_version"] = JsonPrimitive(orderVersion)
            Json.encodeToString(JsonElement.serializer(), JsonObject(mutableFields))
        }
    } catch (_: Exception) {
        null
    }

    /**
     * Rewrites a device's "/app" payload to reflect local edits made in the app - a field's or
     * control's label/cluster/appearance override, the device's own "name" (which every field/
     * control without its own cluster override falls back to as its cluster), and/or the shared
     * "group" every cluster this payload describes belongs to. Fields/controls are matched by
     * their index into "panels"/"controls" (not by field name/command_topic - either can
     * legitimately repeat, e.g. one shared "linkquality" reading per outlet, or two outlets whose
     * commands both go to the same "<state_topic>/set" - see sensorFieldIndex/controlIndex for
     * resolving the right index for a given local panel), so passing a partial update leaves
     * everything else - including keys this app doesn't recognise - untouched. Doesn't touch
     * "order_version"; that's unrelated to these fields. Returns null if the payload isn't a JSON
     * object.
     */
    fun updateDescriptionInAppPayload(
        currentPayload: String,
        fieldLabelUpdates: Map<Int, String> = emptyMap(),
        fieldClusterUpdates: Map<Int, String> = emptyMap(),
        fieldUnitUpdates: Map<Int, String> = emptyMap(),
        fieldIconUpdates: Map<Int, String> = emptyMap(),
        fieldDecimalUpdates: Map<Int, Int> = emptyMap(),
        fieldIdealTopicUpdates: Map<Int, String> = emptyMap(),
        fieldIdealMinPathUpdates: Map<Int, String> = emptyMap(),
        fieldIdealMaxPathUpdates: Map<Int, String> = emptyMap(),
        controlLabelUpdates: Map<Int, String> = emptyMap(),
        controlClusterUpdates: Map<Int, String> = emptyMap(),
        controlCommandTopicUpdates: Map<Int, String> = emptyMap(),
        controlOnPayloadUpdates: Map<Int, String> = emptyMap(),
        controlOffPayloadUpdates: Map<Int, String> = emptyMap(),
        controlStateTopicUpdates: Map<Int, String> = emptyMap(),
        controlStateFieldUpdates: Map<Int, String> = emptyMap(),
        controlIconUpdates: Map<Int, String> = emptyMap(),
        newDeviceName: String? = null,
        newGroup: String? = null
    ): String? = try {
        val obj = Json.parseToJsonElement(currentPayload) as? JsonObject
        if (obj == null) {
            null
        } else {
            val mutableFields = obj.toMutableMap()
            val fieldCount = (obj["panels"] as? JsonArray)?.size ?: 0

            // Splices [updates] into the existing string array at [key] (padded/truncated to
            // fieldCount, same "parallel to panels" convention every per-field override already
            // uses) - shared by every field-level override below, in place of a hand-written
            // read-merge-write block per key.
            fun applyFieldArray(key: String, updates: Map<Int, String>) {
                if (updates.isEmpty()) return
                val existing = (obj[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                val merged = (0 until fieldCount).map { i -> updates[i] ?: existing?.getOrNull(i) ?: "" }
                mutableFields[key] = JsonArray(merged.map { JsonPrimitive(it) })
            }
            applyFieldArray("labels", fieldLabelUpdates)
            applyFieldArray("panel_clusters", fieldClusterUpdates)
            applyFieldArray("panel_units", fieldUnitUpdates)
            applyFieldArray("panel_icons", fieldIconUpdates)
            applyFieldArray("panel_ideal_topics", fieldIdealTopicUpdates)
            applyFieldArray("panel_ideal_min_paths", fieldIdealMinPathUpdates)
            applyFieldArray("panel_ideal_max_paths", fieldIdealMaxPathUpdates)

            if (fieldDecimalUpdates.isNotEmpty()) {
                val existing = (obj["panel_decimals"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.intOrNull }
                val merged = (0 until fieldCount).map { i -> fieldDecimalUpdates[i] ?: existing?.getOrNull(i) }
                mutableFields["panel_decimals"] = JsonArray(merged.map { if (it != null) JsonPrimitive(it) else JsonNull })
            }

            // Every control-object key update funnels through one map-of-maps (controlIndex ->
            // {jsonKey -> value}), so a control needing several updates at once (e.g. commandTopic
            // + icon) only gets its JsonObject rebuilt once.
            val controlUpdatesByIndex = mutableMapOf<Int, MutableMap<String, String>>()
            fun queueControlUpdates(updates: Map<Int, String>, key: String) {
                updates.forEach { (i, value) -> controlUpdatesByIndex.getOrPut(i) { mutableMapOf() }[key] = value }
            }
            queueControlUpdates(controlLabelUpdates, "label")
            queueControlUpdates(controlClusterUpdates, "cluster")
            queueControlUpdates(controlCommandTopicUpdates, "command_topic")
            queueControlUpdates(controlOnPayloadUpdates, "on_payload")
            queueControlUpdates(controlOffPayloadUpdates, "off_payload")
            queueControlUpdates(controlStateTopicUpdates, "state_topic")
            queueControlUpdates(controlStateFieldUpdates, "state_field")
            queueControlUpdates(controlIconUpdates, "icon")
            if (controlUpdatesByIndex.isNotEmpty()) {
                val controlsArray = obj["controls"] as? JsonArray
                if (controlsArray != null) {
                    val updatedControls = controlsArray.mapIndexed { i, element ->
                        val controlObj = element as? JsonObject ?: return@mapIndexed element
                        val updates = controlUpdatesByIndex[i] ?: return@mapIndexed element
                        val updatedControl = controlObj.toMutableMap()
                        updates.forEach { (key, value) -> updatedControl[key] = JsonPrimitive(value) }
                        JsonObject(updatedControl)
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

    /**
     * Finds [target]'s index into [deviceConfig].panelFields, for use with
     * updateDescriptionInAppPayload. A field name can appear more than once (e.g. one shared
     * "linkquality" reading shown once per outlet) - resolved by matching [target]'s occurrence
     * among same-field siblings within [orderedDevicePanels] (that device's own panels, in
     * original declaration order - AutoConfiguredDevice.createdPanelIds already preserves this)
     * to the same occurrence within panelFields. Returns null if not found.
     */
    fun sensorFieldIndex(deviceConfig: DeviceAppConfig, orderedDevicePanels: List<Panel>, target: Panel.Sensor): Int? {
        var occurrence = 0
        for (p in orderedDevicePanels) {
            if (p is Panel.Sensor && p.jsonPath == target.jsonPath) {
                if (p.id == target.id) break
                occurrence++
            }
        }
        var seen = 0
        deviceConfig.panelFields.forEachIndexed { index, field ->
            if (field == target.jsonPath) {
                if (seen == occurrence) return index
                seen++
            }
        }
        return null
    }

    /** Same idea as sensorFieldIndex, but finds a Toggle/Button's index into [deviceConfig].controls, matched by command topic. */
    fun controlIndex(deviceConfig: DeviceAppConfig, orderedDevicePanels: List<Panel>, target: Panel): Int? {
        val targetCommandTopic = when (target) {
            is Panel.Toggle -> target.commandTopic
            is Panel.Button -> target.commandTopic
            else -> return null
        }
        var occurrence = 0
        for (p in orderedDevicePanels) {
            val commandTopic = (p as? Panel.Toggle)?.commandTopic ?: (p as? Panel.Button)?.commandTopic
            if (commandTopic == targetCommandTopic) {
                if (p.id == target.id) break
                occurrence++
            }
        }
        var seen = 0
        deviceConfig.controls.forEachIndexed { index, control ->
            if (control.commandTopic == targetCommandTopic) {
                if (seen == occurrence) return index
                seen++
            }
        }
        return null
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
            val dashboardOrder = (obj["dashboard_order"] as? JsonPrimitive)?.intOrNull
            val labelsArray = obj["labels"] as? JsonArray
            val labels = labelsArray?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()
            val panelClustersArray = obj["panel_clusters"] as? JsonArray
            val panelClusters = panelClustersArray?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()
            val panelOrdersArray = obj["panel_order"] as? JsonArray
            val panelOrders = panelOrdersArray?.map { (it as? JsonPrimitive)?.intOrNull } ?: emptyList()
            val panelDecimalsArray = obj["panel_decimals"] as? JsonArray
            val panelDecimals = panelDecimalsArray?.map { (it as? JsonPrimitive)?.intOrNull } ?: emptyList()
            fun stringArray(key: String) =
                (obj[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()
            val panelUnits = stringArray("panel_units")
            val panelIcons = stringArray("panel_icons")
            val panelIdealTopics = stringArray("panel_ideal_topics")
            val panelIdealMinPaths = stringArray("panel_ideal_min_paths")
            val panelIdealMaxPaths = stringArray("panel_ideal_max_paths")
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
                dashboardOrder = dashboardOrder,
                panelFields = panelFields,
                labels = labels,
                rangePairs = rangePairs,
                panelClusters = panelClusters,
                panelOrders = panelOrders,
                panelDecimals = panelDecimals,
                panelUnits = panelUnits,
                panelIcons = panelIcons,
                panelIdealTopics = panelIdealTopics,
                panelIdealMinPaths = panelIdealMinPaths,
                panelIdealMaxPaths = panelIdealMaxPaths,
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
        val icon = (obj["icon"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        // Zigbee2MQTT convention: commands go to "<state topic>/set" unless the
        // device explicitly overrides it with its own command_topic.
        val commandTopic = (obj["command_topic"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: stateTopic?.let { "$it/set" }
            ?: return null
        return ControlConfig(label, commandTopic, onPayload, offPayload, stateTopic, stateField, cluster, order, momentary, icon)
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
            val unit = deviceConfig.panelUnits.getOrNull(index)?.takeIf { it.isNotBlank() }
                ?: suggestedUnit(field)
            val icon = deviceConfig.panelIcons.getOrNull(index)?.takeIf { it.isNotBlank() }
                ?.let { name -> TileIcon.entries.find { it.name.equals(name, ignoreCase = true) } }
                ?: suggestedIcon(field)
            val idealTopicOverride = deviceConfig.panelIdealTopics.getOrNull(index)?.takeIf { it.isNotBlank() }

            Panel.Sensor(
                id = java.util.UUID.randomUUID().toString(),
                label = label,
                brokerId = brokerId,
                topic = topic,
                jsonPath = field,
                unit = unit,
                icon = icon,
                idealRangeTopic = idealTopicOverride ?: (if (rangeBase != null) appConfigTopic else ""),
                idealMinPath = deviceConfig.panelIdealMinPaths.getOrNull(index)?.takeIf { it.isNotBlank() }
                    ?: rangeBase?.let { deviceConfig.rangePairs[it]!!.first } ?: "min",
                idealMaxPath = deviceConfig.panelIdealMaxPaths.getOrNull(index)?.takeIf { it.isNotBlank() }
                    ?: rangeBase?.let { deviceConfig.rangePairs[it]!!.second } ?: "max",
                clusterName = clusterName,
                displayOrder = composedDisplayOrder(deviceConfig.groupOrder, deviceConfig.panelOrders.getOrNull(index), index),
                decimals = deviceConfig.panelDecimals.getOrNull(index) ?: suggestedDecimals(field)
            )
        }

        val controlPanels: List<Panel> = deviceConfig.controls.mapIndexed { controlIndex, control ->
            // Sensor panels are always built before controls (see ControlConfig.order), so a
            // control with no explicit order falls in right after them by default.
            val fallbackWithin = sensorPanels.size + controlIndex
            val icon = control.icon?.let { name -> TileIcon.entries.find { it.name.equals(name, ignoreCase = true) } }
                ?: TileIcon.POWER
            if (control.momentary) {
                Panel.Button(
                    id = java.util.UUID.randomUUID().toString(),
                    label = control.label,
                    brokerId = brokerId,
                    commandTopic = control.commandTopic,
                    payload = control.onPayload,
                    icon = icon,
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
                    icon = icon,
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
        key.contains("contact", ignoreCase = true) -> TileIcon.CONTACT
        key.contains("lock", ignoreCase = true) -> TileIcon.LOCK
        key.contains("leak", ignoreCase = true) -> TileIcon.WATER_LEAK
        key.contains("smoke", ignoreCase = true) -> TileIcon.SMOKE
        key.contains("gas", ignoreCase = true) -> TileIcon.GAS
        key.contains("vibration", ignoreCase = true) || key.contains("tamper", ignoreCase = true) -> TileIcon.VIBRATION
        key.contains("co2", ignoreCase = true) || key.contains("voc", ignoreCase = true) ||
            key.contains("pm25", ignoreCase = true) || key.contains("aqi", ignoreCase = true) -> TileIcon.AIR_QUALITY
        key.contains("siren", ignoreCase = true) || key.contains("alarm", ignoreCase = true) ||
            key.contains("warning", ignoreCase = true) -> TileIcon.SIREN
        key.contains("action", ignoreCase = true) -> TileIcon.BUTTON
        key.contains("energy", ignoreCase = true) || key.contains("current", ignoreCase = true) ||
            key.contains("voltage", ignoreCase = true) || key == "power" -> TileIcon.ENERGY
        key.contains("color", ignoreCase = true) -> TileIcon.COLOR
        key.contains("position", ignoreCase = true) -> TileIcon.COVER
        else -> TileIcon.GAUGE
    }

    fun suggestedUnit(key: String): String = when {
        key.contains("temperature", ignoreCase = true) -> "\u00b0C"
        key.contains("humidity", ignoreCase = true) -> "%"
        key.contains("moisture", ignoreCase = true) -> "%"
        key.contains("battery", ignoreCase = true) -> "%"
        key.contains("illuminance", ignoreCase = true) -> "lx"
        key.contains("co2", ignoreCase = true) -> "ppm"
        key.contains("voltage", ignoreCase = true) -> "V"
        key.contains("current", ignoreCase = true) -> "A"
        key == "power" -> "W"
        key.contains("energy", ignoreCase = true) -> "kWh"
        else -> ""
    }

    /** Default decimal places for a field when the device's own "/app" config doesn't specify one via panel_decimals - temperature benefits from a decimal (e.g. "21.5"), everything else defaults to whole numbers. */
    fun suggestedDecimals(key: String): Int = when {
        key.contains("temperature", ignoreCase = true) -> 1
        else -> 0
    }

    /**
     * Best-effort common prefix across [panels]' topic-like fields (Sensor.topic, Toggle.command/
     * stateTopic, Button.commandTopic) - used to prefill the duplicate-cluster dialog's topic
     * field (and as the substring its edits replace), and to find the one topic a locally-created
     * cluster's own "/app" payload should be published to (see buildAppConfigPayload). Trimmed
     * back to the last "/" only when the raw prefix stops mid-segment (some topic continues past
     * it with a non-"/" character) - a clean, ordinary device topic like "zigbee2mqtt/Green Hose"
     * (shared by a "…/Green Hose" sensor topic and a "…/Green Hose/set" command topic) is returned
     * whole rather than chopped down to "zigbee2mqtt".
     */
    fun commonTopicPrefix(panels: List<Panel>): String {
        val topics = panels.flatMap { panel ->
            when (panel) {
                is Panel.Sensor -> listOfNotNull(panel.topic.takeIf { it.isNotBlank() })
                is Panel.Toggle -> listOfNotNull(
                    panel.commandTopic.takeIf { it.isNotBlank() },
                    panel.stateTopic.takeIf { it.isNotBlank() }
                )
                is Panel.Button -> listOfNotNull(panel.commandTopic.takeIf { it.isNotBlank() })
            }
        }
        if (topics.isEmpty()) return ""
        var prefix = topics.first()
        for (topic in topics.drop(1)) {
            prefix = prefix.commonPrefixWith(topic)
            if (prefix.isEmpty()) return ""
        }
        val endsCleanly = topics.all { it.length == prefix.length || it.getOrNull(prefix.length) == '/' }
        if (endsCleanly) return prefix
        val lastSlash = prefix.lastIndexOf('/')
        return if (lastSlash >= 0) prefix.substring(0, lastSlash) else prefix
    }

    /**
     * The write-side counterpart to [parseDeviceAppConfig]/[buildPanels]: serialises [panels]
     * (all sharing [clusterName] and, per [commonTopicPrefix], one clean common topic) into a
     * fresh "<topic>/app" payload - so a cluster that only ever existed in this phone's local
     * config.json (e.g. one just created by duplicating another) can publish its own
     * self-describing device-style config, the same as a real device would. A Sensor field whose
     * own topic *is* [appTopic] itself (e.g. an editable min/max threshold) has nowhere else to
     * source its current value from, so [seedValuesByPanelId] (keyed by that panel's id) supplies
     * one - falling back to "0" - typically copied from whatever the cluster was duplicated from.
     */
    fun buildAppConfigPayload(
        panels: List<Panel>,
        clusterName: String,
        groupName: String,
        appTopic: String,
        seedValuesByPanelId: Map<String, String> = emptyMap()
    ): String {
        val ordered = panels.sortedBy { it.displayOrder }
        val sensors = ordered.filterIsInstance<Panel.Sensor>()
        val controls = ordered.filter { it is Panel.Toggle || it is Panel.Button }

        val obj = buildJsonObject {
            put("name", clusterName)
            put("group", groupName)
            putJsonArray("panels") { sensors.forEach { add(it.jsonPath) } }
            putJsonArray("labels") { sensors.forEach { add(it.label) } }
            putJsonArray("panel_decimals") { sensors.forEach { add(it.decimals) } }
            putJsonArray("panel_units") { sensors.forEach { add(it.unit) } }
            putJsonArray("panel_icons") { sensors.forEach { add(it.icon.name) } }
            putJsonArray("panel_ideal_topics") { sensors.forEach { add(it.idealRangeTopic) } }
            putJsonArray("panel_ideal_min_paths") { sensors.forEach { add(it.idealMinPath) } }
            putJsonArray("panel_ideal_max_paths") { sensors.forEach { add(it.idealMaxPath) } }
            putJsonArray("controls") {
                controls.forEach { panel ->
                    addJsonObject {
                        when (panel) {
                            is Panel.Toggle -> {
                                put("label", panel.label)
                                put("command_topic", panel.commandTopic)
                                put("on_payload", panel.onPayload)
                                put("off_payload", panel.offPayload)
                                if (panel.stateTopic.isNotBlank()) put("state_topic", panel.stateTopic)
                                if (panel.stateJsonPath.isNotBlank()) put("state_field", panel.stateJsonPath)
                                put("icon", panel.icon.name)
                            }
                            is Panel.Button -> {
                                put("label", panel.label)
                                put("command_topic", panel.commandTopic)
                                put("on_payload", panel.payload)
                                put("icon", panel.icon.name)
                            }
                            else -> {}
                        }
                    }
                }
            }
            // A field sourced from this very topic (not the device's separate main sensor
            // topic) has to have its current value embedded right here, or it'd read back as
            // "--" the moment this payload is retained.
            sensors.filter { it.topic == appTopic }.forEach { panel ->
                val seed = seedValuesByPanelId[panel.id] ?: "0"
                val numeric = seed.toDoubleOrNull()
                if (numeric != null) put(panel.jsonPath, numeric) else put(panel.jsonPath, seed)
            }
            put("order_version", System.currentTimeMillis())
        }
        return Json.encodeToString(JsonElement.serializer(), obj)
    }
}
