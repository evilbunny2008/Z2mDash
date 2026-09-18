package com.odiousapps.z2mdash.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.time.Duration.Companion.milliseconds

/**
 * Persists the whole app configuration (brokers, groups, panels) as a single
 * plain-JSON file under the app's private storage. Also doubles as the export
 * format for "Configuration Backup" / "Configuration Recovery" in Settings -
 * it's just JSON you own, no proprietary/encrypted format, no lock-in.
 */
class ConfigRepository(private val context: Context, private val scope: CoroutineScope) {

    private val file: File get() = File(context.filesDir, "config.json")
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    private val _config = MutableStateFlow(load())
    val config: StateFlow<AppConfig> = _config

    private fun load(): AppConfig = try {
        val loaded = if (file.exists()) json.decodeFromString(AppConfig.serializer(), file.readText())
            else AppConfig()
        migrateDecimalsIfNeeded(loaded)
    } catch (_: Exception) {
        AppConfig()
    }

    /**
     * One-time migration for sensor panels created before the decimal-places
     * default became field-aware (temperature: 1, everything else: 0) - any
     * existing panel still sitting on the old, uniform default of 1 gets
     * moved over to whatever SensorDiscovery.suggestedDecimals now says for
     * its field, so already-configured tiles benefit from the new default
     * too rather than only new panels going forward. Guarded by
     * decimalsMigrationApplied so this only ever runs once: without it, a
     * later deliberate choice of "1" on a non-temperature field would get
     * silently reverted on every subsequent app launch.
     */
    private fun migrateDecimalsIfNeeded(config: AppConfig): AppConfig {
        if (config.decimalsMigrationApplied) return config
        val migratedGroups = config.groups.map { group ->
            group.copy(panels = group.panels.map { panel ->
                if (panel is Panel.Sensor && panel.decimals == 1) {
                    val suggested = SensorDiscovery.suggestedDecimals(panel.jsonPath)
                    if (suggested != panel.decimals) panel.copy(decimals = suggested) else panel
                } else {
                    panel
                }
            })
        }
        val migrated = config.copy(groups = migratedGroups, decimalsMigrationApplied = true)
        persist(migrated)
        return migrated
    }

    private fun persist(config: AppConfig) {
        try {
            file.writeText(json.encodeToString(AppConfig.serializer(), config))
        } catch (_: Exception) {
            // Best-effort - fine to silently skip a write if it fails; _config (what the UI
            // actually reads) is already up to date in memory regardless, same as the payload
            // cache's own save() already treats a failed write as non-fatal.
        }
    }

    // Debounced and off the main thread - schedulePersist() used to call persist() synchronously,
    // inline, on whatever thread called update() (almost always the main thread, since every
    // caller is a UI event handler). That's a full JSON-encode-and-write of the *entire* config
    // on every single edit - not just occasional ones like renaming a group, but also anything
    // that fires update() repeatedly in a burst, like dragging the "Tile & Cluster Width" slider
    // (Slider.onValueChange fires continuously while dragging, not just on release) or a
    // cluster/panel drag-reorder. Reading _config.value fresh inside the delayed job (rather than
    // capturing the transformed value at call time) also means a rapid burst of update() calls
    // coalesces into one write of the final state instead of one write per call.
    private var persistJob: Job? = null
    private fun schedulePersist() {
        persistJob?.cancel()
        persistJob = scope.launch(Dispatchers.IO) {
            delay(500.milliseconds)
            persist(_config.value)
        }
    }

    fun update(transform: (AppConfig) -> AppConfig) {
        _config.update(transform)
        schedulePersist()
    }

    fun upsertBroker(broker: Broker) = update { cfg ->
        val exists = cfg.brokers.any { it.id == broker.id }
        val newList = if (exists) cfg.brokers.map { if (it.id == broker.id) broker else it }
        else cfg.brokers + broker
        cfg.copy(brokers = newList)
    }

    // Persisted independently of upsertBroker/"Done" so the "Permit Join" toggle (on both the
    // broker edit screen and HomeScreen's own banner) remembers the last device actually used the
    // moment it's used - flipping that switch is a real, meaningful action on its own, and
    // shouldn't need the whole edit screen's "Done" button pressed afterwards to be remembered,
    // nor should using it accidentally persist any of that screen's other still-unsaved edits.
    fun updatePermitJoinDevice(brokerId: String, device: String) = update { cfg ->
        cfg.copy(brokers = cfg.brokers.map { if (it.id == brokerId) it.copy(permitJoinDevice = device) else it })
    }

    fun deleteBroker(id: String) = update { cfg ->
        // Pending/ignored device prompts are scoped to a broker - once it's gone,
        // clear both so the Home screen doesn't keep showing stale "add/ignore"
        // banners (or silently remembering a dismissal) for a broker that no longer exists.
        val prefix = "$id|"
        // A deleted broker takes its own panels and auto-config tracking with
        // it too - without this, they'd linger tagged with a brokerId nothing
        // references any more. That used to bite hardest on exactly the
        // sequence that looks most natural (delete a broker, then re-add it,
        // whether by hand or via a credential import): the new broker gets a
        // fresh random id, so every "<topic>/app" looks brand new again and
        // gets rebuilt right alongside the still-present orphaned originals -
        // every cluster ending up with double the tiles it should have.
        val updatedGroups = cfg.groups.map { g -> g.copy(panels = g.panels.filterNot { it.brokerId == id }) }
        cfg.copy(
            brokers = cfg.brokers.filterNot { it.id == id },
            groups = updatedGroups,
            autoConfiguredDevices = cfg.autoConfiguredDevices.filterNot { it.brokerId == id },
            pendingAutoConfigDevices = cfg.pendingAutoConfigDevices.filterNot { it.brokerId == id },
            ignoredAppConfigTopics = cfg.ignoredAppConfigTopics.filterNot { it.startsWith(prefix) }
        )
    }

    /**
     * Removes any panel/auto-config tracking left tagged with a brokerId
     * that no longer matches any configured broker - a broker deleted
     * before this cleanup was added to deleteBroker() itself left exactly
     * this behind (see that function's own comment). Safe to call any
     * time; a no-op if there's nothing orphaned. Returns how many panels
     * were removed, so a caller can show what it actually did.
     */
    fun pruneOrphanedBrokerData(): Int {
        var removedCount = 0
        update { cfg ->
            // Reset on every invocation, not just the first - update()'s
            // underlying StateFlow.update retries this transform on a
            // concurrent write, and without resetting, a retry would double
            // (or more) count rather than reflect just the call that actually won.
            removedCount = 0
            val brokerIds = cfg.brokers.map { it.id }.toSet()
            val updatedGroups = cfg.groups.map { g ->
                val kept = g.panels.filter { it.brokerId in brokerIds }
                removedCount += g.panels.size - kept.size
                g.copy(panels = kept)
            }
            cfg.copy(
                groups = updatedGroups,
                autoConfiguredDevices = cfg.autoConfiguredDevices.filter { it.brokerId in brokerIds }
            )
        }
        return removedCount
    }

    fun upsertGroup(group: PanelGroup) = update { cfg ->
        val exists = cfg.groups.any { it.id == group.id }
        val newList = if (exists) cfg.groups.map { if (it.id == group.id) group else it }
        else cfg.groups + group
        cfg.copy(groups = newList)
    }

    fun deleteGroup(id: String) = update { cfg ->
        val remainingGroups = cfg.groups.filterNot { it.id == id }
        // A deleted group takes its panels with it. Any autoconfigured device
        // whose panels all lived in that group has nothing left - drop its
        // tracking record too, or the Discover screen would keep that topic
        // hidden forever even though it has no panels any more.
        val remainingPanelIds = remainingGroups.flatMap { it.panels }.map { it.id }.toSet()
        val remainingDevices = cfg.autoConfiguredDevices.filter { device ->
            device.createdPanelIds.any { it in remainingPanelIds }
        }
        cfg.copy(groups = remainingGroups, autoConfiguredDevices = remainingDevices)
    }

    /** Moves a group earlier (offset -1) or later (offset +1) in the display order. */
    @Suppress("unused")
    fun moveGroup(groupId: String, offset: Int) = update { cfg ->
        val index = cfg.groups.indexOfFirst { it.id == groupId }
        if (index < 0) return@update cfg
        val newIndex = (index + offset).coerceIn(0, cfg.groups.lastIndex)
        if (newIndex == index) return@update cfg
        val reordered = cfg.groups.toMutableList()
        val moved = reordered.removeAt(index)
        reordered.add(newIndex, moved)
        cfg.copy(groups = reordered)
    }

    /** Repositions a group to the given 1-based index, clamped to the valid range. */
    fun moveGroupToIndex(groupId: String, oneBasedIndex: Int) = update { cfg ->
        val currentIndex = cfg.groups.indexOfFirst { it.id == groupId }
        if (currentIndex < 0) return@update cfg
        val targetIndex = (oneBasedIndex - 1).coerceIn(0, cfg.groups.lastIndex)
        if (targetIndex == currentIndex) return@update cfg
        val reordered = cfg.groups.toMutableList()
        val moved = reordered.removeAt(currentIndex)
        reordered.add(targetIndex, moved)
        cfg.copy(groups = reordered)
    }

    fun setGroupCollapsed(groupId: String, collapsed: Boolean) = update { cfg ->
        cfg.copy(groups = cfg.groups.map { if (it.id == groupId) it.copy(collapsed = collapsed) else it })
    }

    /**
     * Reassigns displayOrder for every panel in one cluster to match [orderedPanelIds]
     * (first = lowest), leaving every other cluster/panel untouched. Keeps the
     * cluster's *own* overall position among other clusters unchanged - that's
     * driven by the minimum displayOrder among its panels, so this preserves
     * that minimum and only spreads values upward from it, rather than
     * resetting to 0-based values that could accidentally jump the whole
     * cluster to the front of its group.
     */
    fun reorderPanelsInCluster(groupId: String, orderedPanelIds: List<String>) = update { cfg ->
        val updatedGroups = cfg.groups.map { g ->
            if (g.id != groupId) return@map g
            val panelIdSet = orderedPanelIds.toSet()
            val baseOrder = g.panels.filter { it.id in panelIdSet }
                .minOfOrNull { it.displayOrder } ?: 0
            val newOrderByPanelId = orderedPanelIds.withIndex()
                .associate { (index, id) -> id to (baseOrder + index) }
            g.copy(panels = g.panels.map { panel ->
                val newOrder = newOrderByPanelId[panel.id] ?: return@map panel
                when (panel) {
                    is Panel.Sensor -> panel.copy(displayOrder = newOrder)
                    is Panel.Toggle -> panel.copy(displayOrder = newOrder)
                    is Panel.Button -> panel.copy(displayOrder = newOrder)
                }
            })
        }
        cfg.copy(groups = updatedGroups)
    }

    /**
     * Reassigns displayOrder for every panel in a group so its clusters end
     * up in the order given by [orderedClusterKeys] - each key matching a
     * cluster's clusterName, or "__single__<panelId>" for a standalone panel
     * with no cluster name (the same convention HomeScreen uses to group
     * panels into clusters for display). Panels *within* each cluster keep
     * their existing relative order - only which cluster comes before which
     * changes. Uses a generous step (1000) between clusters' base values, so
     * there's room for within-cluster panel reordering later without ever
     * needing to renumber a neighbouring cluster.
     */
    fun reorderClustersInGroup(groupId: String, orderedClusterKeys: List<String>) = update { cfg ->
        val updatedGroups = cfg.groups.map { g ->
            if (g.id != groupId) return@map g
            val panelsByCluster = g.panels.groupBy { it.clusterName.ifBlank { "__single__${it.id}" } }
            val newOrderByPanelId = mutableMapOf<String, Int>()
            orderedClusterKeys.forEachIndexed { clusterIndex, clusterKey ->
                val clusterPanels = panelsByCluster[clusterKey] ?: return@forEachIndexed
                val sortedPanels = clusterPanels.sortedBy { it.displayOrder }
                val base = clusterIndex * 1000
                sortedPanels.forEachIndexed { withinIndex, panel ->
                    newOrderByPanelId[panel.id] = base + withinIndex
                }
            }
            g.copy(panels = g.panels.map { panel ->
                val newOrder = newOrderByPanelId[panel.id] ?: return@map panel
                when (panel) {
                    is Panel.Sensor -> panel.copy(displayOrder = newOrder)
                    is Panel.Toggle -> panel.copy(displayOrder = newOrder)
                    is Panel.Button -> panel.copy(displayOrder = newOrder)
                }
            })
        }
        cfg.copy(groups = updatedGroups)
    }

    fun addPanelToGroup(groupId: String, panel: Panel) = update { cfg ->
        cfg.copy(groups = cfg.groups.map { g ->
            if (g.id == groupId) g.copy(panels = g.panels + panel) else g
        })
    }

    fun updatePanel(groupId: String, panel: Panel) = update { cfg ->
        cfg.copy(groups = cfg.groups.map { g ->
            if (g.id == groupId) g.copy(panels = g.panels.map { if (it.id == panel.id) panel else it }) else g
        })
    }

    fun removePanel(groupId: String, panelId: String) = update { cfg ->
        val updatedGroups = cfg.groups.map { g ->
            if (g.id == groupId) g.copy(panels = g.panels.filterNot { it.id == panelId }) else g
        }
        // Same reasoning as deleteGroup: if that was the last panel an
        // autoconfigured device owned, drop its tracking record too so the
        // Discover screen stops hiding a topic with nothing left to show for it.
        val remainingPanelIds = updatedGroups.flatMap { it.panels }.map { it.id }.toSet()
        val remainingDevices = cfg.autoConfiguredDevices.filter { device ->
            device.createdPanelIds.any { it in remainingPanelIds }
        }
        cfg.copy(groups = updatedGroups, autoConfiguredDevices = remainingDevices)
    }

    /** Removes a whole cluster's panels (e.g. every tile for one device) in a single atomic update. */
    fun removePanels(groupId: String, panelIds: List<String>) = update { cfg ->
        val idsToRemove = panelIds.toSet()
        val updatedGroups = cfg.groups.map { g ->
            if (g.id == groupId) g.copy(panels = g.panels.filterNot { it.id in idsToRemove }) else g
        }
        val remainingPanelIds = updatedGroups.flatMap { it.panels }.map { it.id }.toSet()
        val remainingDevices = cfg.autoConfiguredDevices.filter { device ->
            device.createdPanelIds.any { it in remainingPanelIds }
        }
        cfg.copy(groups = updatedGroups, autoConfiguredDevices = remainingDevices)
    }

    /**
     * Atomically replaces everything owned by an auto-configured device: strips
     * its previous panels ([oldPanelIds] - wherever they currently live, in case
     * the device's declared group changed) out of every group, adds the
     * freshly-built [newPanels] into [targetGroupId], and records/updates the
     * tracking entry ([updatedDevice], whose own createdPanelIds should be the
     * *new* panels' IDs) in one state transition so there's no intermediate
     * inconsistent state.
     */
    fun applyDeviceAutoConfig(
        oldPanelIds: Set<String>,
        updatedDevice: AutoConfiguredDevice,
        targetGroupId: String,
        newPanels: List<Panel>
    ) = update { cfg ->
        val strippedGroups = cfg.groups.map { g ->
            if (oldPanelIds.isEmpty()) g else g.copy(panels = g.panels.filterNot { it.id in oldPanelIds })
        }
        val groupsWithNewPanels = if (strippedGroups.any { it.id == targetGroupId }) {
            strippedGroups.map { g -> if (g.id == targetGroupId) g.copy(panels = g.panels + newPanels) else g }
        } else {
            strippedGroups
        }

        val existingIndex = cfg.autoConfiguredDevices.indexOfFirst {
            it.brokerId == updatedDevice.brokerId && it.appConfigTopic == updatedDevice.appConfigTopic
        }
        val updatedDevices = if (existingIndex >= 0) {
            cfg.autoConfiguredDevices.toMutableList().also { it[existingIndex] = updatedDevice }
        } else {
            cfg.autoConfiguredDevices + updatedDevice
        }

        cfg.copy(groups = groupsWithNewPanels, autoConfiguredDevices = updatedDevices)
    }

    /**
     * Records that [payload] (stamped with [orderVersion]) is already reflected
     * in the app's own state for one auto-configured device, without rebuilding
     * its panels. Used right after the app itself publishes a retained
     * "<topic>/app" update (e.g. a cluster/panel drag reorder writing a new
     * group_order/panel_order) - since every broker is subscribed to "#", that
     * publish echoes straight back to DeviceAutoConfigManager, which would
     * otherwise treat it as a config update to reconcile. Pre-marking the
     * payload as applied here means that exact echo is recognised as already up
     * to date and skipped; recording [orderVersion] as this device's new
     * lastKnownOrderVersion also means any later payload with an
     * older/missing order_version (a stale retained redelivery, e.g. from a
     * reconnect, or another phone sharing this broker that hasn't caught up
     * yet) is recognised as stale and ignored rather than clobbering this
     * reorder - while a genuinely newer order_version still gets adopted. See
     * DeviceAutoConfigManager.reconcileKnownDevices.
     */
    fun markAutoConfiguredDevicePayloadApplied(
        brokerId: String,
        appConfigTopic: String,
        payload: String,
        orderVersion: Long
    ) = update { cfg ->
            val updatedDevices = cfg.autoConfiguredDevices.map { device ->
                if (device.brokerId == brokerId && device.appConfigTopic == appConfigTopic) {
                    device.copy(
                        lastAppliedPayload = payload,
                        lastKnownOrderVersion = maxOf(device.lastKnownOrderVersion, orderVersion)
                    )
                } else {
                    device
                }
            }
            cfg.copy(autoConfiguredDevices = updatedDevices)
        }

    /** Adds a newly-seen "<topic>/app" to the pending list, if not already there. */
    fun addPendingAutoConfigDevice(device: PendingAutoConfigDevice) = update { cfg ->
        val exists = cfg.pendingAutoConfigDevices.any {
            it.brokerId == device.brokerId && it.appConfigTopic == device.appConfigTopic
        }
        if (exists) cfg else cfg.copy(pendingAutoConfigDevices = cfg.pendingAutoConfigDevices + device)
    }

    fun removePendingAutoConfigDevice(brokerId: String, appConfigTopic: String) = update { cfg ->
        cfg.copy(
            pendingAutoConfigDevices = cfg.pendingAutoConfigDevices.filterNot {
                it.brokerId == brokerId && it.appConfigTopic == appConfigTopic
            }
        )
    }

    /** User declined a pending device - drop it from the pending list and remember not to re-prompt. */
    fun ignoreAppConfigTopic(brokerId: String, appConfigTopic: String) = update { cfg ->
        val key = "$brokerId|$appConfigTopic"
        cfg.copy(
            ignoredAppConfigTopics = if (key in cfg.ignoredAppConfigTopics) {
                cfg.ignoredAppConfigTopics
            } else {
                cfg.ignoredAppConfigTopics + key
            },
            pendingAutoConfigDevices = cfg.pendingAutoConfigDevices.filterNot {
                it.brokerId == brokerId && it.appConfigTopic == appConfigTopic
            }
        )
    }

    /**
     * @param includeBrokers Set false for the MQTT backup path - broker host/
     * username/password shouldn't be published over MQTT even compressed, in
     * case that topic isn't as tightly secured as the device itself. The file
     * backup keeps brokers included, since that file stays under your control.
     */
    fun exportJson(includeBrokers: Boolean = true): String {
        val toExport = if (includeBrokers) _config.value else _config.value.copy(brokers = emptyList())
        return json.encodeToString(AppConfig.serializer(), toExport)
    }

    /** Exports just the brokers list - everything else (groups, panels, auto-config state) is left out entirely, for a backup scoped to connection details only. */
    fun exportBrokersOnlyJson(): String {
        val brokersOnly = AppConfig(brokers = _config.value.brokers)
        return json.encodeToString(AppConfig.serializer(), brokersOnly)
    }

    fun importJson(text: String) {
        val imported = json.decodeFromString(AppConfig.serializer(), text)
        persist(imported)
        _config.value = imported
    }

    /**
     * For restoring a brokers-only backup: only the brokers list changes,
     * everything else (groups, panels, auto-config state) stays exactly as
     * it is. Brokers are matched/merged by id - an imported broker with the
     * same id as an existing one replaces it, brokers with new ids are
     * added, and existing brokers not mentioned in the import are left
     * untouched rather than being removed.
     */
    fun importBrokersOnlyJson(text: String) {
        val imported = json.decodeFromString(AppConfig.serializer(), text)
        val importedById = imported.brokers.associateBy { it.id }
        val mergedBrokers = _config.value.brokers.map { existing -> importedById[existing.id] ?: existing } +
            imported.brokers.filter { it.id !in _config.value.brokers.map { b -> b.id } }
        val merged = _config.value.copy(brokers = mergedBrokers)
        persist(merged)
        _config.value = merged
    }

    /**
     * For restoring a brokerless MQTT backup: everything (groups, panels,
     * auto-config state) comes from [text], but the current device's brokers
     * are kept as-is rather than being wiped to an empty list. Note this only
     * round-trips cleanly on the *same* device/broker setup the backup was
     * taken from - panels still reference the original brokerId, so restoring
     * onto a different device's brokers won't reconnect them automatically.
     */
    fun importJsonPreservingBrokers(text: String) {
        val imported = json.decodeFromString(AppConfig.serializer(), text)
        val merged = imported.copy(brokers = _config.value.brokers)
        persist(merged)
        _config.value = merged
    }
}
