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
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds

/**
 * Persists the whole app configuration (brokers, groups, panels) as a single JSON file
 * under private storage. Also doubles as the export format for Settings' Configuration
 * Backup/Recovery - plain JSON, no proprietary format or lock-in.
 */
class ConfigRepository(private val context: Context, private val scope: CoroutineScope) {

    private val file: File get() = File(context.filesDir, "config.json")
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    private val _config = MutableStateFlow(AppConfig())
    val config: StateFlow<AppConfig> = _config

    // True once the on-disk config has actually been read (or found not to exist) - callers that
    // must not act on the still-loading default config (e.g. HomeScreen's "no brokers, go to
    // Welcome" redirect) can wait for this before reading config.value.
    private val _isLoaded = MutableStateFlow(false)
    val isLoaded: StateFlow<Boolean> = _isLoaded

    init {
        // Off the main thread: this constructor runs from Application.onCreate() before the UI
        // exists, and synchronous file I/O + JSON-decoding here caused a >12s time-to-first-frame
        // on an underpowered TV (same class of issue as MqttConnectionManager's cache seeding).
        scope.launch(Dispatchers.IO) {
            _config.value = load()
            _isLoaded.value = true
        }
    }

    private fun load(): AppConfig = try {
        val loaded = if (file.exists()) json.decodeFromString(AppConfig.serializer(), file.readText())
            else AppConfig()
        migrateDecimalsIfNeeded(loaded)
    } catch (_: Exception) {
        AppConfig()
    }

    /**
     * One-time migration: sensor panels still on the old uniform default of 1 decimal get
     * moved to SensorDiscovery's now field-aware suggestion. Guarded by decimalsMigrationApplied
     * so a later deliberate choice of "1" isn't silently reverted on every launch.
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
            // Best-effort: _config in memory (what the UI reads) is already up to date regardless.
        }
    }

    // Debounced and off the main thread, since update() can fire in rapid bursts (e.g. a slider's
    // continuous onValueChange, or a drag-reorder) and each write JSON-encodes the entire config.
    // Reading _config.value fresh inside the delayed job coalesces a burst into one final write.
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

    // Persisted independently of upsertBroker/"Done" so flipping the Permit Join toggle is
    // remembered immediately, without needing (or triggering) the edit screen's other unsaved edits.
    fun updatePermitJoinDevice(brokerId: String, device: String) = update { cfg ->
        cfg.copy(brokers = cfg.brokers.map { if (it.id == brokerId) it.copy(permitJoinDevice = device) else it })
    }

    fun deleteBroker(id: String) = update { cfg ->
        // Pending/ignored device prompts are scoped to a broker - clear both so Home doesn't
        // keep showing stale add/ignore banners for a broker that's gone.
        val prefix = "$id|"
        // Also drop the broker's panels/auto-config tracking - otherwise re-adding the broker
        // (fresh random id) would rebuild them as new, doubling every cluster's tiles alongside
        // the orphaned originals.
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
     * Removes panel/auto-config tracking tagged with a brokerId that no longer matches any
     * configured broker (leftovers from before this cleanup existed - see deleteBroker()).
     * No-op if nothing's orphaned. Returns the number of panels removed.
     */
    fun pruneOrphanedBrokerData(): Int {
        var removedCount = 0
        update { cfg ->
            // Reset each call: StateFlow.update retries this transform on a concurrent write,
            // and without resetting, a retry would double-count.
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
        // A deleted group takes its panels with it - also drop tracking for any device left with
        // no panels, or Discover would keep hiding a topic that has nothing to show any more.
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

    /**
     * Resolves the target group for a device's declared "group" name (case-insensitive), creating
     * one if none exists yet - the shared fallback chain every first-time auto-config entry point
     * (DeviceAutoConfigManager.autoAcceptDevice, HomeScreen.addPendingDevice) uses: the declared
     * group if named, else the first existing group, else a fresh "Discovered Sensors". Callers
     * that also learned a dashboard_order for this device should call resyncDashboardGroupOrder()
     * afterward, once the device is actually registered (see that function's doc).
     */
    fun resolveOrCreateGroup(declaredGroupName: String?): String {
        val cfg = _config.value
        return if (!declaredGroupName.isNullOrBlank()) {
            cfg.groups.find { it.name.equals(declaredGroupName, ignoreCase = true) }?.id
                ?: UUID.randomUUID().toString().also { upsertGroup(PanelGroup(id = it, name = declaredGroupName)) }
        } else {
            cfg.groups.firstOrNull()?.id
                ?: UUID.randomUUID().toString().also { upsertGroup(PanelGroup(id = it, name = "Discovered Sensors")) }
        }
    }

    /**
     * Resorts top-level dashboard groups to match every auto-configured device's
     * lastKnownDashboardOrder (lowest first) - a group missing that info entirely (manually
     * created, or none of its member devices has adopted one yet) keeps its current relative
     * position, interleaved wherever a plain sort-by-key naturally puts it. Recomputed fresh from
     * scratch each call (a full stable resort, not moving one group at a time to a fixed index),
     * so the result doesn't depend on what order devices/groups happened to be discovered or
     * reconciled in - important right after wiping app data, when a flood of retained "/app"
     * messages can arrive in any order. Call after anything that could have taught the app a
     * device's dashboard_order: a payload reconciling, or a brand-new device being auto- or
     * manually-accepted.
     */
    fun resyncDashboardGroupOrder() = update { cfg ->
        val orderByGroupId = mutableMapOf<String, Int>()
        cfg.autoConfiguredDevices.forEach { device ->
            val order = device.lastKnownDashboardOrder ?: return@forEach
            val groupId = cfg.groups.find { g -> g.panels.any { it.id in device.createdPanelIds } }?.id ?: return@forEach
            val existing = orderByGroupId[groupId]
            if (existing == null || order < existing) orderByGroupId[groupId] = order
        }
        if (orderByGroupId.isEmpty()) return@update cfg
        val sorted = cfg.groups.sortedBy { orderByGroupId[it.id] ?: Int.MAX_VALUE }
        if (sorted.map { it.id } == cfg.groups.map { it.id }) cfg else cfg.copy(groups = sorted)
    }

    fun setGroupCollapsed(groupId: String, collapsed: Boolean) = update { cfg ->
        cfg.copy(groups = cfg.groups.map { if (it.id == groupId) it.copy(collapsed = collapsed) else it })
    }

    /**
     * Reassigns displayOrder for every panel in one cluster to match [orderedPanelIds]
     * (first = lowest), leaving other clusters/panels untouched. Preserves the cluster's
     * existing minimum displayOrder (rather than resetting to 0) so its position among
     * other clusters doesn't shift.
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
     * Reassigns displayOrder so a group's clusters follow [orderedClusterKeys] (clusterName, or
     * "__single__<panelId>" for a standalone panel - same convention HomeScreen uses). Panels
     * keep their relative order within each cluster. A step of 1000 between clusters leaves
     * room for later within-cluster reordering without renumbering neighbours.
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

    /** Renames a cluster: every panel in [groupId] named [oldClusterName] switches to [newClusterName]. */
    fun renameCluster(groupId: String, oldClusterName: String, newClusterName: String) = update { cfg ->
        if (oldClusterName.isBlank() || oldClusterName == newClusterName) return@update cfg
        cfg.copy(groups = cfg.groups.map { g ->
            if (g.id != groupId) return@map g
            g.copy(panels = g.panels.map { panel ->
                if (panel.clusterName != oldClusterName) return@map panel
                when (panel) {
                    is Panel.Sensor -> panel.copy(clusterName = newClusterName)
                    is Panel.Toggle -> panel.copy(clusterName = newClusterName)
                    is Panel.Button -> panel.copy(clusterName = newClusterName)
                }
            })
        })
    }

    /** Moves every panel of [clusterName] from [fromGroupId] to the end of [toGroupId]. */
    fun moveClusterToGroup(fromGroupId: String, toGroupId: String, clusterName: String) = update { cfg ->
        if (fromGroupId == toGroupId || clusterName.isBlank()) return@update cfg
        val fromGroup = cfg.groups.find { it.id == fromGroupId } ?: return@update cfg
        val toGroup = cfg.groups.find { it.id == toGroupId } ?: return@update cfg
        val moving = fromGroup.panels.filter { it.clusterName == clusterName }.sortedBy { it.displayOrder }
        if (moving.isEmpty()) return@update cfg
        val base = (toGroup.panels.filter { it.displayOrder != Int.MAX_VALUE }.maxOfOrNull { it.displayOrder } ?: -1) + 1
        val relocated = moving.mapIndexed { index, panel ->
            val newOrder = base + index
            when (panel) {
                is Panel.Sensor -> panel.copy(displayOrder = newOrder)
                is Panel.Toggle -> panel.copy(displayOrder = newOrder)
                is Panel.Button -> panel.copy(displayOrder = newOrder)
            }
        }
        cfg.copy(groups = cfg.groups.map { g ->
            when (g.id) {
                fromGroupId -> g.copy(panels = g.panels.filterNot { it.clusterName == clusterName })
                toGroupId -> g.copy(panels = g.panels + relocated)
                else -> g
            }
        })
    }

    /**
     * Clones the panels identified by [sourcePanelIds] into a new cluster named [newClusterName],
     * appended after everything else already in [groupId]. When [topicReplacement] (old, new) is
     * given, that exact substring is replaced in every clone's topic-like fields
     * (Sensor.topic/idealRangeTopic, Toggle.commandTopic/stateTopic, Button.commandTopic) - lets a
     * duplicate be retargeted at a different physical device/topic in one step instead of editing
     * each tile individually afterward. The clone is always a plain, independent cluster (never
     * auto-configured) even if the source was, since it has no device payload of its own to track.
     */
    fun duplicateCluster(
        groupId: String,
        sourcePanelIds: List<String>,
        newClusterName: String,
        topicReplacement: Pair<String, String>?
    ) = update { cfg ->
        if (newClusterName.isBlank()) return@update cfg
        val group = cfg.groups.find { it.id == groupId } ?: return@update cfg
        val sourcePanels = group.panels.filter { it.id in sourcePanelIds }.sortedBy { it.displayOrder }
        if (sourcePanels.isEmpty()) return@update cfg

        fun retopic(topic: String): String =
            if (topicReplacement != null && topicReplacement.first.isNotEmpty()) {
                topic.replace(topicReplacement.first, topicReplacement.second)
            } else {
                topic
            }

        val base = (group.panels.filter { it.displayOrder != Int.MAX_VALUE }.maxOfOrNull { it.displayOrder } ?: -1) + 1
        val cloned = sourcePanels.mapIndexed { index, panel ->
            val newId = java.util.UUID.randomUUID().toString()
            val newOrder = base + index
            when (panel) {
                is Panel.Sensor -> panel.copy(
                    id = newId,
                    clusterName = newClusterName,
                    displayOrder = newOrder,
                    topic = retopic(panel.topic),
                    idealRangeTopic = retopic(panel.idealRangeTopic)
                )
                is Panel.Toggle -> panel.copy(
                    id = newId,
                    clusterName = newClusterName,
                    displayOrder = newOrder,
                    commandTopic = retopic(panel.commandTopic),
                    stateTopic = retopic(panel.stateTopic)
                )
                is Panel.Button -> panel.copy(
                    id = newId,
                    clusterName = newClusterName,
                    displayOrder = newOrder,
                    commandTopic = retopic(panel.commandTopic)
                )
            }
        }
        cfg.copy(groups = cfg.groups.map { g ->
            if (g.id == groupId) g.copy(panels = g.panels + cloned) else g
        })
    }

    /**
     * Pulls one panel out of whatever cluster it currently shares with siblings, giving it
     * [newClusterName] as its own - so it renders in its own titled card instead of being stuck
     * reordering only among the panels it was grouped with. Appended after every other panel in
     * the group (a fresh displayOrder past the current max), so it lands at the end rather than
     * wherever its old cluster happened to sort.
     */
    fun movePanelToOwnCluster(groupId: String, panelId: String, newClusterName: String) = update { cfg ->
        if (newClusterName.isBlank()) return@update cfg
        cfg.copy(groups = cfg.groups.map { g ->
            if (g.id != groupId) return@map g
            val newOrder = (g.panels.filter { it.displayOrder != Int.MAX_VALUE }.maxOfOrNull { it.displayOrder } ?: -1) + 1
            g.copy(panels = g.panels.map { panel ->
                if (panel.id != panelId) return@map panel
                when (panel) {
                    is Panel.Sensor -> panel.copy(clusterName = newClusterName, displayOrder = newOrder)
                    is Panel.Toggle -> panel.copy(clusterName = newClusterName, displayOrder = newOrder)
                    is Panel.Button -> panel.copy(clusterName = newClusterName, displayOrder = newOrder)
                }
            })
        })
    }

    fun removePanel(groupId: String, panelId: String) = update { cfg ->
        val updatedGroups = cfg.groups.map { g ->
            if (g.id == groupId) g.copy(panels = g.panels.filterNot { it.id == panelId }) else g
        }
        // Same reasoning as deleteGroup: drop tracking if that was the device's last panel.
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
     * Atomically replaces everything owned by an auto-configured device: strips [oldPanelIds]
     * out of every group (wherever they live, in case the device's group changed), adds
     * [newPanels] into [targetGroupId], and updates the tracking entry ([updatedDevice],
     * whose createdPanelIds should be the new panels' IDs) - all in one state transition.
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
     * Upserts a device tracking entry (by brokerId + appConfigTopic) without touching any group's
     * panels - unlike applyDeviceAutoConfig, which is for when a device's payload is rebuilding
     * the panels themselves. This is for the opposite case: the panels already exist locally (a
     * duplicated cluster, say) and just need to start being tracked against a "/app" topic this
     * phone itself just published for them (see AutoConfigPush.publishAppTopicForClusterIfMissing).
     */
    fun registerAutoConfiguredDevice(device: AutoConfiguredDevice) = update { cfg ->
        val existingIndex = cfg.autoConfiguredDevices.indexOfFirst {
            it.brokerId == device.brokerId && it.appConfigTopic == device.appConfigTopic
        }
        val updatedDevices = if (existingIndex >= 0) {
            cfg.autoConfiguredDevices.toMutableList().also { it[existingIndex] = device }
        } else {
            cfg.autoConfiguredDevices + device
        }
        cfg.copy(autoConfiguredDevices = updatedDevices)
    }

    /**
     * Marks [payload]/[orderVersion] as already applied for a device, without rebuilding panels.
     * Called right after the app itself publishes a retained "<topic>/app" update, since the "#"
     * subscription echoes that publish straight back to DeviceAutoConfigManager - pre-marking it
     * means the echo is skipped, and a later payload with an older/missing order_version (a stale
     * retained redelivery, or another phone sharing the broker) is ignored rather than clobbering
     * this state, while a genuinely newer one still gets adopted. See
     * DeviceAutoConfigManager.reconcileKnownDevices.
     */
    fun markAutoConfiguredDevicePayloadApplied(
        brokerId: String,
        appConfigTopic: String,
        payload: String,
        orderVersion: Long,
        // Set only by a dashboard-group-order push (see HomeScreen.pushDashboardGroupOrderUpdates)
        // - every other caller leaves this null, which keeps the device's existing value as-is.
        dashboardOrder: Int? = null
    ) = update { cfg ->
            val updatedDevices = cfg.autoConfiguredDevices.map { device ->
                if (device.brokerId == brokerId && device.appConfigTopic == appConfigTopic) {
                    device.copy(
                        lastAppliedPayload = payload,
                        lastKnownOrderVersion = maxOf(device.lastKnownOrderVersion, orderVersion),
                        lastKnownDashboardOrder = dashboardOrder ?: device.lastKnownDashboardOrder
                    )
                } else {
                    device
                }
            }
            cfg.copy(autoConfiguredDevices = updatedDevices)
        }

    /**
     * Drops [panelId] from the tracking entry for the auto-configured device at (brokerId,
     * appConfigTopic), if present - keeps AutoConfiguredDevice.createdPanelIds in sync when the
     * user deletes just one of a multi-panel device's fields/controls (see
     * AutoConfigPush.pushPanelRemovalIfAutoConfigured). Leaves the device's other panels/tracking
     * untouched; if that was the device's last panel, the caller's own removePanel/removePanels
     * call already drops the whole tracking entry separately.
     */
    fun pruneAutoConfiguredDevicePanelId(brokerId: String, appConfigTopic: String, panelId: String) = update { cfg ->
        val updatedDevices = cfg.autoConfiguredDevices.map { device ->
            if (device.brokerId == brokerId && device.appConfigTopic == appConfigTopic) {
                device.copy(createdPanelIds = device.createdPanelIds - panelId)
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
     * @param includeBrokers Set false for the MQTT backup path - broker credentials shouldn't be
     * published over MQTT in case that topic is less secure than the device itself.
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
     * Restores a brokers-only backup: only the brokers list changes. Merged by id - an imported
     * broker replaces an existing one with the same id, new ids are added, and brokers not
     * mentioned in the import are left untouched.
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
     * Restores a brokerless MQTT backup: everything but brokers comes from [text]; the current
     * device's brokers are kept. Only round-trips cleanly on the same device/broker setup - panels
     * still reference the original brokerId, so a different device's brokers won't reconnect them.
     */
    fun importJsonPreservingBrokers(text: String) {
        val imported = json.decodeFromString(AppConfig.serializer(), text)
        val merged = imported.copy(brokers = _config.value.brokers)
        persist(merged)
        _config.value = merged
    }
}
