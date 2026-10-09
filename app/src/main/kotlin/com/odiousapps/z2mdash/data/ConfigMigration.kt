package com.odiousapps.z2mdash.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Upgrades a config.json/backup written before groups and clusters had ids of their own (panels
 * were bucketed into clusters purely by a shared "clusterName" string) into the current shape:
 * each group gets a "clusters" list of {id, name}, each panel a "clusterId" pointing into it.
 *
 * Done on the raw JSON rather than via a leftover field on Panel, since decoding with
 * ignoreUnknownKeys would otherwise silently drop "clusterName" before anything could read it.
 * Applied to everything that decodes an AppConfig (load, every import path), so an old backup
 * restores just as cleanly as an old config.json. A no-op for anything already migrated.
 *
 * Legacy groups are re-keyed to StableIds.legacyGroupId(name), and legacy clusters get
 * StableIds.legacyClusterId(groupName, clusterName) - the same derivation DeviceAutoConfigManager
 * uses when stamping ids into a legacy "/app" payload, so every phone sharing a broker lands on
 * the same ids for the same group/cluster without having to coordinate.
 */
object ConfigMigration {

    fun migrate(text: String): String {
        val root = Json.parseToJsonElement(text) as? JsonObject ?: return text
        val groups = root["groups"] as? JsonArray ?: return text
        if (groups.none { needsMigration(it as? JsonObject) }) return text

        val usedGroupIds = groups.mapNotNull { (it as? JsonObject)?.string("id") }.toMutableSet()
        val migratedGroups = groups.map { element ->
            val group = element as? JsonObject ?: return@map element
            if (!needsMigration(group)) return@map group
            migrateGroup(group, usedGroupIds)
        }
        return Json.encodeToString(
            JsonElement.serializer(),
            JsonObject(root.toMutableMap().apply { put("groups", JsonArray(migratedGroups)) })
        )
    }

    private fun needsMigration(group: JsonObject?): Boolean {
        if (group == null) return false
        if (group["clusters"] == null) return true
        val panels = group["panels"] as? JsonArray ?: return false
        return panels.any { (it as? JsonObject)?.let { p -> p["clusterId"] == null && p["clusterName"] != null } == true }
    }

    private fun migrateGroup(group: JsonObject, usedGroupIds: MutableSet<String>): JsonObject {
        val groupName = group.string("name").orEmpty()
        val oldId = group.string("id").orEmpty()
        // Only re-keyed when the group has never been migrated before (no "clusters" key at all) -
        // a group that already has one was created with its own id after this migration existed.
        val groupId = if (group["clusters"] == null) {
            val derived = StableIds.legacyGroupId(groupName)
            if (derived == oldId || derived !in usedGroupIds) {
                usedGroupIds.remove(oldId)
                usedGroupIds.add(derived)
                derived
            } else {
                // Two local groups sharing one name - only the first can take the derived id.
                oldId
            }
        } else {
            oldId
        }

        val clusters = (group["clusters"] as? JsonArray)
            ?.filterIsInstance<JsonObject>()
            ?.mapNotNull { c -> c.string("id")?.let { id -> id to c.string("name").orEmpty() } }
            ?.toMap(LinkedHashMap())
            ?: linkedMapOf()

        val panels = (group["panels"] as? JsonArray).orEmpty().map { element ->
            val panel = element as? JsonObject ?: return@map element
            if (panel["clusterId"] != null) return@map panel
            val clusterName = panel.string("clusterName").orEmpty()
            val fields = panel.toMutableMap()
            fields.remove("clusterName")
            if (clusterName.isNotBlank()) {
                val clusterId = clusters.entries.firstOrNull { it.value == clusterName }?.key
                    ?: StableIds.legacyClusterId(groupName, clusterName).also { clusters[it] = clusterName }
                fields["clusterId"] = JsonPrimitive(clusterId)
            } else {
                fields["clusterId"] = JsonPrimitive("")
            }
            JsonObject(fields)
        }

        val clustersJson = JsonArray(clusters.map { (id, name) ->
            JsonObject(mapOf("id" to JsonPrimitive(id), "name" to JsonPrimitive(name)))
        })
        return JsonObject(group.toMutableMap().apply {
            put("id", JsonPrimitive(groupId))
            put("panels", JsonArray(panels))
            put("clusters", clustersJson)
        })
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
}
