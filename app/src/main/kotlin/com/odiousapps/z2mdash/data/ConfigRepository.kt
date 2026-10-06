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

    /**
     * Held by DeviceAutoConfigManager for each whole reconcile/detect pass, and by a restore
     * (AutoConfigPush.restoreConfig) across its import AND its push of the restored layout to the
     * broker - so a reconcile pass can never run in between and re-apply the broker's older
     * layout onto a just-restored config before the restore has had the chance to replace it.
     */
    val reconcileLock = Any()

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
        if (!file.exists()) {
            AppConfig()
        } else {
            val raw = file.readText()
            val migratedText = ConfigMigration.migrate(raw)
            val loaded = decodeMigrated(migratedText)
            if (migratedText != raw) {
                // Keeps the pre-migration file around untouched - the id migration rewrites every
                // group/panel, and load()'s own catch below falls back to an empty config, so a
                // migration bug would otherwise leave nothing to recover the dashboard from.
                try {
                    File(context.filesDir, "config.pre-ids.json").let { if (!it.exists()) it.writeText(raw) }
                } catch (_: Exception) {
                }
                persist(loaded)
            }
            migrateDecimalsIfNeeded(loaded)
        }
    } catch (_: Exception) {
        AppConfig()
    }

    /** Decodes any config/backup JSON, upgrading a pre-id one first - see ConfigMigration. */
    private fun decode(text: String): AppConfig = decodeMigrated(ConfigMigration.migrate(text))

    private fun decodeMigrated(text: String): AppConfig =
        normalizeClusters(json.decodeFromString(AppConfig.serializer(), text))

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
        _config.update { previous -> normalizeClusters(transform(previous), previous) }
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
    /** Remembers [device] as the last-used "permit join via" router for this broker+baseTopic pair. */
    fun updatePermitJoinDevice(brokerId: String, baseTopic: String, device: String) = update { cfg ->
        cfg.copy(brokers = cfg.brokers.map {
            if (it.id != brokerId) return@map it
            it.copy(permitJoinDevices = it.permitJoinDevices + (baseTopic to device))
        })
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
     * Resolves the target group for a device's declared "group_id"/"group", creating one if none
     * exists yet - the shared fallback chain every auto-config entry point (DeviceAutoConfigManager,
     * HomeScreen.addPendingDevice, DiscoverScreen) uses:
     *  - a declared group_id always wins: that exact group, created under that id (named from
     *    [declaredGroupName]) if this phone hasn't seen it yet - never matched by name, so two
     *    same-named groups stay distinct;
     *  - else a legacy, name-only payload: the group with that name (case-insensitive), else a new
     *    one keyed by StableIds.legacyGroupId so every phone creates it under the same id;
     *  - else [fallbackGroupId] (e.g. wherever the device's panels already live), else the first
     *    existing group, else a fresh "Discovered Sensors".
     * Callers that also learned a dashboard_order for this device should call
     * resyncDashboardGroupOrder() afterward, once the device is actually registered.
     */
    fun resolveOrCreateGroup(
        declaredGroupId: String?,
        declaredGroupName: String?,
        fallbackGroupId: String? = null
    ): String {
        val cfg = _config.value
        if (!declaredGroupId.isNullOrBlank()) {
            if (cfg.groups.none { it.id == declaredGroupId }) {
                upsertGroup(PanelGroup(id = declaredGroupId, name = declaredGroupName?.takeIf { it.isNotBlank() } ?: "Discovered Sensors"))
            }
            return declaredGroupId
        }
        if (!declaredGroupName.isNullOrBlank()) {
            cfg.groups.find { it.name.equals(declaredGroupName, ignoreCase = true) }?.let { return it.id }
            val derived = StableIds.legacyGroupId(declaredGroupName)
            val id = if (cfg.groups.any { it.id == derived }) StableIds.newId() else derived
            upsertGroup(PanelGroup(id = id, name = declaredGroupName))
            return id
        }
        fallbackGroupId?.takeIf { id -> cfg.groups.any { it.id == id } }?.let { return it }
        return cfg.groups.firstOrNull()?.id
            ?: StableIds.newId().also { upsertGroup(PanelGroup(id = it, name = "Discovered Sensors")) }
    }

    /**
     * Resorts top-level dashboard groups to match every auto-configured device's
     * lastKnownDashboardOrder (lowest first) - a group missing that info entirely (manually
     * created, or none of its member devices has adopted one yet) stays right after whichever
     * group currently precedes it. Recomputed fresh from
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
        // A group with no known order (manually built, or none of its devices has one yet) sorts
        // with whatever group precedes it, rather than being pushed to the very end - otherwise
        // every manual-only group sank to the bottom on each resync, scrambling a restored or
        // hand-arranged order.
        var previousOrder = Int.MIN_VALUE
        val effectiveOrder = cfg.groups.associate { g ->
            orderByGroupId[g.id]?.let { previousOrder = it }
            g.id to previousOrder
        }
        val sorted = cfg.groups.sortedBy { effectiveOrder.getValue(it.id) }
        if (sorted.map { it.id } == cfg.groups.map { it.id }) cfg else cfg.copy(groups = sorted)
    }

    fun setGroupCollapsed(groupId: String, collapsed: Boolean) = update { cfg ->
        cfg.copy(groups = cfg.groups.map { if (it.id == groupId) it.copy(collapsed = collapsed) else it })
    }

    /** Collapses or expands every group at once - the Home screen's "collapse/expand all" FAB. */
    fun setAllGroupsCollapsed(collapsed: Boolean) = update { cfg ->
        cfg.copy(groups = cfg.groups.map { it.copy(collapsed = collapsed) })
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
                newOrderByPanelId[panel.id]?.let { panel.withDisplayOrder(it) } ?: panel
            })
        }
        cfg.copy(groups = updatedGroups)
    }

    /**
     * Reassigns displayOrder so a group's clusters follow [orderedClusterKeys] (Panel.clusterKey:
     * a clusterId, or "__single__<panelId>" for a standalone panel). Panels keep their relative
     * order within each cluster. A step of 1000 between clusters leaves room for later
     * within-cluster reordering without renumbering neighbours.
     */
    fun reorderClustersInGroup(groupId: String, orderedClusterKeys: List<String>) = update { cfg ->
        val updatedGroups = cfg.groups.map { g ->
            if (g.id != groupId) return@map g
            val panelsByCluster = g.panels.groupBy { it.clusterKey }
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
                newOrderByPanelId[panel.id]?.let { panel.withDisplayOrder(it) } ?: panel
            })
        }
        cfg.copy(groups = updatedGroups)
    }

    /**
     * Adds [panel] to [groupId]. [newCluster] is the entry for a cluster created alongside it (see
     * clusterForName) - added in this same update, since normalizeClusters would otherwise have no
     * name to give the brand-new clusterId [panel] references.
     */
    fun addPanelToGroup(groupId: String, panel: Panel, newCluster: PanelCluster? = null) = update { cfg ->
        cfg.copy(groups = cfg.groups.map { g ->
            if (g.id == groupId) g.copy(panels = g.panels + panel, clusters = g.clusters + listOfNotNull(newCluster)) else g
        })
    }

    /** Replaces [panel] (matched by id) within [groupId]. [newCluster]: see addPanelToGroup. */
    fun updatePanel(groupId: String, panel: Panel, newCluster: PanelCluster? = null) = update { cfg ->
        cfg.copy(groups = cfg.groups.map { g ->
            if (g.id == groupId) {
                g.copy(
                    panels = g.panels.map { if (it.id == panel.id) panel else it },
                    clusters = g.clusters + listOfNotNull(newCluster)
                )
            } else {
                g
            }
        })
    }

    /**
     * The clusterId a panel typed into [groupId] under cluster name [name] should get: "" for a
     * blank name (standalone), else the first existing cluster there with exactly that name, else a
     * brand-new cluster - returned as the second value, for the caller to hand to
     * addPanelToGroup/updatePanel alongside the panel. Typing an existing cluster's name to join it
     * is the Add/Edit Panel screen's own long-standing behaviour, kept as-is.
     */
    fun clusterForName(groupId: String, name: String): Pair<String, PanelCluster?> {
        if (name.isBlank()) return "" to null
        val group = _config.value.groups.find { it.id == groupId }
        group?.clusters?.find { it.name == name }?.let { return it.id to null }
        val created = PanelCluster(StableIds.newId(), name)
        return created.id to created
    }

    /** Renames cluster [clusterId] in [groupId] - one edit to its entry, every member panel follows. */
    fun renameCluster(groupId: String, clusterId: String, newClusterName: String) = update { cfg ->
        if (clusterId.isBlank() || newClusterName.isBlank()) return@update cfg
        cfg.copy(groups = cfg.groups.map { g ->
            if (g.id != groupId) return@map g
            g.copy(clusters = g.clusters.map { if (it.id == clusterId) it.copy(name = newClusterName) else it })
        })
    }

    /**
     * Moves every panel of cluster [clusterId] from [fromGroupId] into [toGroupId] (its cluster
     * entry follows - see normalizeClusters). When [insertBeforeClusterKey] names an existing
     * cluster (or standalone panel, keyed by Panel.clusterKey) already in the destination group,
     * the moved cluster is inserted immediately before it there; the sentinel "__header__" (a drop
     * onto the destination group's own header) inserts it at the very front instead. Leaving it
     * null, or naming a cluster that's no longer there, falls back to appending at the end.
     */
    fun moveClusterToGroup(
        fromGroupId: String,
        toGroupId: String,
        clusterId: String,
        insertBeforeClusterKey: String? = null
    ) = update { cfg ->
        if (fromGroupId == toGroupId || clusterId.isBlank()) return@update cfg
        val fromGroup = cfg.groups.find { it.id == fromGroupId } ?: return@update cfg
        val toGroup = cfg.groups.find { it.id == toGroupId } ?: return@update cfg
        val moving = fromGroup.panels.filter { it.clusterId == clusterId }.sortedBy { it.displayOrder }
        if (moving.isEmpty()) return@update cfg

        val destinationPanelsByKey = toGroup.panels.groupBy { it.clusterKey }
        val destinationOrder = destinationPanelsByKey.entries
            .sortedBy { (_, ps) -> ps.minOf { it.displayOrder } }
            .map { it.key }
        val insertAt = when (insertBeforeClusterKey) {
            null -> destinationOrder.size
            "__header__" -> 0
            else -> destinationOrder.indexOf(insertBeforeClusterKey).takeIf { it >= 0 } ?: destinationOrder.size
        }
        val newOrder = destinationOrder.toMutableList().apply { add(insertAt, clusterId) }

        // Same spacing scheme as reorderClustersInGroup - each cluster (existing or newly
        // inserted) gets a 1000-wide slot, so every one of its panels' displayOrders land
        // consistently relative to the others regardless of where it used to sit.
        val newOrderByPanelId = mutableMapOf<String, Int>()
        newOrder.forEachIndexed { clusterIndex, key ->
            val clusterPanels = if (key == clusterId) moving else destinationPanelsByKey[key].orEmpty()
            clusterPanels.sortedBy { it.displayOrder }.forEachIndexed { withinIndex, panel ->
                newOrderByPanelId[panel.id] = clusterIndex * 1000 + withinIndex
            }
        }

        cfg.copy(groups = cfg.groups.map { g ->
            when (g.id) {
                fromGroupId -> g.copy(panels = g.panels.filterNot { it.clusterId == clusterId })
                toGroupId -> g.copy(panels = (toGroup.panels + moving).map { panel ->
                    panel.withDisplayOrder(newOrderByPanelId[panel.id] ?: panel.displayOrder)
                })
                else -> g
            }
        })
    }

    /**
     * Clones the panels identified by [sourcePanelIds] into a brand-new cluster named
     * [newClusterName], appended after everything else already in [groupId], and returns that new
     * cluster's id (or null if nothing was cloned). Always a separate cluster even when another
     * one in the group already has that name - clusters are matched by id, never by name. When
     * [topicReplacement] (old, new) is given, that exact substring is replaced in every clone's
     * topic-like fields (Sensor.topic/idealRangeTopic, Toggle.commandTopic/stateTopic,
     * Button.commandTopic) - lets a duplicate be retargeted at a different physical device/topic
     * in one step instead of editing each tile individually afterward. The clone is always a
     * plain, independent cluster (never auto-configured) even if the source was, since it has no
     * device payload of its own to track.
     */
    fun duplicateCluster(
        groupId: String,
        sourcePanelIds: List<String>,
        newClusterName: String,
        topicReplacement: Pair<String, String>?
    ): String? {
        if (newClusterName.isBlank()) return null
        val group = _config.value.groups.find { it.id == groupId } ?: return null
        if (group.panels.none { it.id in sourcePanelIds }) return null
        val newCluster = PanelCluster(StableIds.newId(), newClusterName)

        fun retopic(topic: String): String =
            if (topicReplacement != null && topicReplacement.first.isNotEmpty()) {
                topic.replace(topicReplacement.first, topicReplacement.second)
            } else {
                topic
            }

        update { cfg ->
            val liveGroup = cfg.groups.find { it.id == groupId } ?: return@update cfg
            val sourcePanels = liveGroup.panels.filter { it.id in sourcePanelIds }.sortedBy { it.displayOrder }
            if (sourcePanels.isEmpty()) return@update cfg
            val base = (liveGroup.panels.filter { it.displayOrder != Int.MAX_VALUE }.maxOfOrNull { it.displayOrder } ?: -1) + 1
            val cloned = sourcePanels.mapIndexed { index, panel ->
                val newId = StableIds.newId()
                val newOrder = base + index
                when (panel) {
                    is Panel.Sensor -> panel.copy(
                        id = newId,
                        clusterId = newCluster.id,
                        displayOrder = newOrder,
                        topic = retopic(panel.topic),
                        idealRangeTopic = retopic(panel.idealRangeTopic)
                    )
                    is Panel.Toggle -> panel.copy(
                        id = newId,
                        clusterId = newCluster.id,
                        displayOrder = newOrder,
                        commandTopic = retopic(panel.commandTopic),
                        stateTopic = retopic(panel.stateTopic)
                    )
                    is Panel.Button -> panel.copy(
                        id = newId,
                        clusterId = newCluster.id,
                        displayOrder = newOrder,
                        commandTopic = retopic(panel.commandTopic)
                    )
                }
            }
            cfg.copy(groups = cfg.groups.map { g ->
                if (g.id == groupId) g.copy(panels = g.panels + cloned, clusters = g.clusters + newCluster) else g
            })
        }
        return newCluster.id
    }

    /**
     * Rewrites [oldTopicPrefix] to [newTopicPrefix] (plain substring replace, same as
     * duplicateCluster's own retopic() above) across every topic field of every panel in cluster
     * [clusterId] in [groupId] - the in-place counterpart to duplicateCluster's own topic
     * replacement, for moving an existing cluster onto a different MQTT topic (e.g. a Zigbee
     * network split onto a second zigbee2mqtt instance) without cloning it. Deliberately scoped
     * to one cluster's own panels only, not the whole group - a network split typically
     * only moves *some* devices, and this lets each cluster be retargeted independently.
     */
    fun retopicCluster(
        groupId: String,
        clusterId: String,
        oldTopicPrefix: String,
        newTopicPrefix: String
    ) = update { cfg ->
        if (oldTopicPrefix.isBlank() || newTopicPrefix.isBlank() || oldTopicPrefix == newTopicPrefix) {
            return@update cfg
        }
        fun retopic(topic: String): String = topic.replace(oldTopicPrefix, newTopicPrefix)
        cfg.copy(groups = cfg.groups.map { g ->
            if (g.id != groupId) return@map g
            g.copy(panels = g.panels.map { panel ->
                if (panel.clusterId != clusterId) return@map panel
                when (panel) {
                    is Panel.Sensor -> panel.copy(
                        topic = retopic(panel.topic),
                        idealRangeTopic = retopic(panel.idealRangeTopic)
                    )
                    is Panel.Toggle -> panel.copy(
                        commandTopic = retopic(panel.commandTopic),
                        stateTopic = retopic(panel.stateTopic)
                    )
                    is Panel.Button -> panel.copy(commandTopic = retopic(panel.commandTopic))
                }
            })
        })
    }

    /**
     * Re-points an auto-configured device's tracking entry at [newSensorTopic]/[newAppConfigTopic]
     * - called right before the old "/app" payload is copied forward to the new address (see
     * HomeScreen's Change Topic dialog). Without this, that copied-forward payload arrives at a
     * "/app" topic no AutoConfiguredDevice yet claims, so DeviceAutoConfigManager.detectNewDevices
     * treats it as a brand new device and builds a second, duplicate set of panels alongside the
     * ones retopicCluster already moved in place, instead of DeviceAutoConfigManager just
     * reconciling the copied-forward payload back onto those same (already-retopicked) panels via
     * their now-matching (topic, jsonPath) identity.
     */
    fun retopicAutoConfiguredDevice(
        brokerId: String,
        oldAppConfigTopic: String,
        newSensorTopic: String,
        newAppConfigTopic: String
    ) = update { cfg ->
        val updatedDevices = cfg.autoConfiguredDevices.map { device ->
            if (device.brokerId == brokerId && device.appConfigTopic == oldAppConfigTopic) {
                device.copy(sensorTopic = newSensorTopic, appConfigTopic = newAppConfigTopic)
            } else {
                device
            }
        }
        cfg.copy(autoConfiguredDevices = updatedDevices)
    }

    /**
     * Pulls one panel out of whatever cluster it currently shares with siblings into a brand-new
     * cluster named [newClusterName] - so it renders in its own titled card instead of being
     * stuck reordering only among the panels it was grouped with. Appended after every other
     * panel in the group (a fresh displayOrder past the current max), so it lands at the end
     * rather than wherever its old cluster happened to sort. Returns the new cluster's id, or null
     * if nothing changed.
     */
    fun movePanelToOwnCluster(groupId: String, panelId: String, newClusterName: String): String? {
        if (newClusterName.isBlank()) return null
        if (_config.value.groups.find { it.id == groupId }?.panels?.none { it.id == panelId } != false) return null
        val newCluster = PanelCluster(StableIds.newId(), newClusterName)
        update { cfg ->
            cfg.copy(groups = cfg.groups.map { g ->
                if (g.id != groupId) return@map g
                val newOrder = (g.panels.filter { it.displayOrder != Int.MAX_VALUE }.maxOfOrNull { it.displayOrder } ?: -1) + 1
                g.copy(
                    panels = g.panels.map { panel ->
                        if (panel.id != panelId) panel else panel.withClusterId(newCluster.id).withDisplayOrder(newOrder)
                    },
                    clusters = g.clusters + newCluster
                )
            })
        }
        return newCluster.id
    }

    /**
     * Moves [panelId] from [fromGroupId] into the existing cluster [targetClusterId] within
     * [toGroupId] (which may be the same group), appended after that cluster's current panels -
     * the reverse of movePanelToOwnCluster above, for dragging a panel onto a cluster to join it.
     */
    fun movePanelIntoCluster(
        fromGroupId: String,
        panelId: String,
        toGroupId: String,
        targetClusterId: String
    ) = update { cfg ->
        if (targetClusterId.isBlank()) return@update cfg
        val fromGroup = cfg.groups.find { it.id == fromGroupId } ?: return@update cfg
        val panel = fromGroup.panels.find { it.id == panelId } ?: return@update cfg
        val toGroup = cfg.groups.find { it.id == toGroupId } ?: return@update cfg
        val newOrder = (toGroup.panels.filter { it.clusterId == targetClusterId }
            .maxOfOrNull { it.displayOrder } ?: -1) + 1
        val relocated = panel.withClusterId(targetClusterId).withDisplayOrder(newOrder)
        cfg.copy(groups = cfg.groups.map { g ->
            when (g.id) {
                fromGroupId if g.id == toGroupId ->
                    g.copy(panels = g.panels.map { if (it.id == panelId) relocated else it })

                fromGroupId -> g.copy(panels = g.panels.filterNot { it.id == panelId })
                toGroupId -> g.copy(panels = g.panels + relocated)
                else -> g
            }
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
     * [newPanels] into [targetGroupId], upserts [newClusters] (the clusters those panels
     * reference, named as the device's payload currently names them - so a cluster renamed on
     * another phone is renamed here too) into that group, and updates the tracking entry
     * ([updatedDevice], whose createdPanelIds should be the new panels' IDs) - all in one state
     * transition.
     *
     * [isLiveDevice] (passed by every auto-config path) turns on duplicate
     * replacement: an existing panel showing exactly the same data as one of [newPanels] (same
     * Panel.sourceKey) is replaced by it - keeping the old panel's position (and a Sensor's
     * editable flag) - instead of the device's tiles landing alongside it as duplicates, unless
     * that existing panel belongs to another device [isLiveDevice] says is still live on the
     * broker. A device left owning nothing is dropped. Without this, any device that comes back
     * "new" while its tiles still exist (a restored backup tracking a cluster's old topic while
     * the broker now has it on a new one; a cached "/app" payload that was cleared on the broker
     * while this phone was offline; a manually-added tile for the same field) doubled up its tiles.
     */
    fun applyDeviceAutoConfig(
        oldPanelIds: Set<String>,
        updatedDevice: AutoConfiguredDevice,
        targetGroupId: String,
        newPanels: List<Panel>,
        newClusters: List<PanelCluster> = emptyList(),
        isLiveDevice: ((AutoConfiguredDevice) -> Boolean)? = null
    ) = update { previous ->
        var cfg = previous
        var panelsToAdd = newPanels
        if (isLiveDevice != null) {
            val newIds = newPanels.map { it.id }.toSet()
            val ownerByPanelId = previous.autoConfiguredDevices
                .flatMap { d -> d.createdPanelIds.map { it to d } }.toMap()
            val candidates = previous.groups.asSequence().flatMap { it.panels }
                .filter { p ->
                    p.id !in oldPanelIds && p.id !in newIds &&
                        ownerByPanelId[p.id]?.let { owner -> !isLiveDevice(owner) } != false
                }
                .groupByTo(mutableMapOf()) { it.sourceKey }
            val replacedIds = mutableSetOf<String>()
            panelsToAdd = newPanels.map { panel ->
                val duplicate = candidates[panel.sourceKey]?.removeFirstOrNull() ?: return@map panel
                replacedIds += duplicate.id
                val carried = panel.withDisplayOrder(duplicate.displayOrder)
                if (carried is Panel.Sensor && duplicate is Panel.Sensor) carried.copy(editable = duplicate.editable) else carried
            }
            if (replacedIds.isNotEmpty()) {
                cfg = cfg.copy(
                    groups = cfg.groups.map { g -> g.copy(panels = g.panels.filterNot { it.id in replacedIds }) },
                    autoConfiguredDevices = cfg.autoConfiguredDevices.mapNotNull { d ->
                        if (d.createdPanelIds.none { it in replacedIds }) return@mapNotNull d
                        val remaining = d.createdPanelIds - replacedIds
                        if (remaining.isEmpty()) null else d.copy(createdPanelIds = remaining)
                    }
                )
            }
        }
        val strippedGroups = cfg.groups.map { g ->
            if (oldPanelIds.isEmpty()) g else g.copy(panels = g.panels.filterNot { it.id in oldPanelIds })
        }
        val newClustersById = newClusters.associateBy { it.id }
        val groupsWithNewPanels = if (strippedGroups.any { it.id == targetGroupId }) {
            strippedGroups.map { g ->
                if (g.id != targetGroupId) return@map g
                val renamed = g.clusters.map { newClustersById[it.id] ?: it }
                val added = newClusters.filter { c -> renamed.none { it.id == c.id } }
                g.copy(panels = g.panels + panelsToAdd, clusters = renamed + added)
            }
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
     * phone itself just published for them (see AutoConfigPush.publishAppTopicForClusterIfMissing),
     * or a cluster's whole group getting "Force Upload"-ed (forceRepublishGroupAppTopics) - which,
     * unlike publishAppTopicForClusterIfMissing, runs for devices that are already tracked, not
     * just brand-new ones.
     *
     * For an ALREADY-tracked device, [device]'s own lastKnownOrderVersion/lastKnownDashboardOrder
     * are ignored in favour of the existing entry's - callers construct a fresh AutoConfiguredDevice
     * without ever touching these fields, so they're always this class's own 0L/null defaults, and
     * blindly overwriting the existing (correctly-tracked, likely much higher) order_version with
     * them silently discards it. Confirmed via an on-device log capture as a real, reproducible bug:
     * the NEXT reconcile of that topic (e.g. from an unrelated editable-value edit) then reads the
     * retained payload's still-current, now-numerically-"newer" order_version as one this phone has
     * never seen, adopts the freshly-rebuilt payload's own (un-set) field order for every panel, and
     * DeviceAutoConfigManager.composedDisplayOrder's Int.MAX_VALUE fallback sorts the whole cluster
     * to the very end of its group.
     */
    fun registerAutoConfiguredDevice(device: AutoConfiguredDevice) = update { cfg ->
        val existingIndex = cfg.autoConfiguredDevices.indexOfFirst {
            it.brokerId == device.brokerId && it.appConfigTopic == device.appConfigTopic
        }
        val updatedDevices = if (existingIndex >= 0) {
            val existing = cfg.autoConfiguredDevices[existingIndex]
            val merged = device.copy(
                lastKnownOrderVersion = existing.lastKnownOrderVersion,
                lastKnownDashboardOrder = existing.lastKnownDashboardOrder
            )
            cfg.autoConfiguredDevices.toMutableList().also { it[existingIndex] = merged }
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

    /**
     * Replaces the whole config with [text]'s. Returns the restore's order_version - see
     * prepareRestored - for the caller to stamp onto the layout it pushes to the broker.
     */
    fun importJson(text: String): Long {
        val restoredAt = System.currentTimeMillis()
        val imported = prepareRestored(decode(text), restoredAt)
        persist(imported)
        _config.value = imported
        return restoredAt
    }

    /**
     * Restores a brokers-only backup: only the brokers list changes. Merged by id - an imported
     * broker replaces an existing one with the same id, new ids are added, and brokers not
     * mentioned in the import are left untouched.
     */
    fun importBrokersOnlyJson(text: String) {
        val imported = decode(text)
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
    fun importJsonPreservingBrokers(text: String): Long {
        val restoredAt = System.currentTimeMillis()
        val imported = prepareRestored(decode(text), restoredAt)
        val merged = imported.copy(brokers = _config.value.brokers)
        persist(merged)
        _config.value = merged
        return restoredAt
    }

    /**
     * Makes a restored config's layout win over whatever's on the broker. A backup's devices carry
     * the order_version they last saw, which the broker's retained payloads have almost always
     * moved past since - so the very next reconcile would adopt the broker's (newer-looking)
     * order and group placement right back over the restored one. Stamping [restoredAt] as every
     * device's lastKnownOrderVersion makes the restore the newest order this phone knows of, and
     * resetting lastKnownDashboardOrder from the backup's own group list order keeps
     * resyncDashboardGroupOrder from re-sorting groups by stale per-device values.
     */
    private fun prepareRestored(cfg: AppConfig, restoredAt: Long): AppConfig {
        val groupIndexByPanelId = cfg.groups.flatMapIndexed { index, g -> g.panels.map { it.id to index } }.toMap()
        return cfg.copy(autoConfiguredDevices = cfg.autoConfiguredDevices.map { device ->
            val groupIndex = device.createdPanelIds.firstNotNullOfOrNull { groupIndexByPanelId[it] }
            device.copy(
                lastKnownOrderVersion = maxOf(device.lastKnownOrderVersion, restoredAt),
                lastKnownDashboardOrder = groupIndex?.plus(1) ?: device.lastKnownDashboardOrder
            )
        })
    }
}

/**
 * Keeps every group's [PanelGroup.clusters] in step with its panels: exactly one entry per
 * clusterId its panels reference (an entry no panel uses any more is dropped; one a panel
 * references but the group lacks - e.g. a cluster just moved in from another group - is carried
 * over by id from wherever it was, in [previous] or elsewhere in [cfg]), sorted into display
 * order (lowest member displayOrder first). Run after every ConfigRepository update, so no
 * individual mutation has to remember to tidy cluster entries up itself.
 */
internal fun normalizeClusters(cfg: AppConfig, previous: AppConfig? = null): AppConfig {
    val knownNames = HashMap<String, String>()
    previous?.groups?.forEach { g -> g.clusters.forEach { knownNames[it.id] = it.name } }
    cfg.groups.forEach { g -> g.clusters.forEach { knownNames[it.id] = it.name } }
    var changed = false
    val groups = cfg.groups.map { g ->
        // LinkedHashMap, so clusters tying on displayOrder (both Int.MAX_VALUE, say) keep
        // first-appearance order through the stable sort below.
        val minOrderById = LinkedHashMap<String, Int>()
        g.panels.forEach { p ->
            if (p.clusterId.isNotBlank()) minOrderById.merge(p.clusterId, p.displayOrder, ::minOf)
        }
        val ownById = g.clusters.associateBy { it.id }
        val normalized = minOrderById.keys
            .map { id -> ownById[id] ?: PanelCluster(id, knownNames[id].orEmpty()) }
            .sortedBy { minOrderById.getValue(it.id) }
        if (normalized == g.clusters) g else {
            changed = true
            g.copy(clusters = normalized)
        }
    }
    return if (changed) cfg.copy(groups = groups) else cfg
}
