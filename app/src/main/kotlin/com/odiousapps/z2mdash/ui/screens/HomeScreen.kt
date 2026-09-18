package com.odiousapps.z2mdash.ui.screens

import android.text.format.DateUtils
import android.util.Log
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import com.odiousapps.z2mdash.Z2mDashApplication
import com.odiousapps.z2mdash.data.AppConfig
import com.odiousapps.z2mdash.data.AutoConfiguredDevice
import com.odiousapps.z2mdash.data.Broker
import com.odiousapps.z2mdash.data.JsonPath
import com.odiousapps.z2mdash.data.Panel
import com.odiousapps.z2mdash.data.PanelGroup
import com.odiousapps.z2mdash.data.PendingAutoConfigDevice
import com.odiousapps.z2mdash.data.PermitJoin
import com.odiousapps.z2mdash.data.SensorDiscovery
import com.odiousapps.z2mdash.ui.components.ButtonTile
import com.odiousapps.z2mdash.ui.components.SensorAlert
import com.odiousapps.z2mdash.ui.components.SensorTile
import com.odiousapps.z2mdash.ui.components.ToggleTile
import com.odiousapps.z2mdash.ui.tv.clearFocusOnBack
import com.odiousapps.z2mdash.ui.tv.tvAwareKeyboardOptions
import kotlinx.coroutines.delay
import java.util.UUID
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(navController: NavController, backStackEntry: NavBackStackEntry) {
    val context = LocalContext.current
    val app = context.applicationContext as Z2mDashApplication
    val config by app.configRepository.config.collectAsState()

    val listState = rememberLazyListState()
    // AddPanelScreen sets this on save (for a newly-added panel, not an edit)
    // via the standard Navigation Compose result-passing pattern - reacting
    // to it here lets the just-added panel's group scroll into view, since
    // it's easy for a new cluster to land somewhere already off-screen,
    // especially in a group that already has several clusters.
    //
    // Uses this screen's own NavBackStackEntry (passed in from the nav graph)
    // rather than navController.currentBackStackEntry - that property can be
    // null depending on navigation timing, and calling collectAsState()
    // conditionally through a nullable chain violates Compose's rule that
    // composable calls must happen unconditionally, in the same position,
    // every recomposition. Since this screen recomposes frequently (on every
    // incoming MQTT payload), that mismatch was a real, live bug, not just a
    // theoretical one - backStackEntry.savedStateHandle is always non-null,
    // so collectAsState() below can be called safely and consistently.
    val scrollToGroupId by backStackEntry.savedStateHandle
        .getStateFlow<String?>("scrollToGroupId", null)
        .collectAsState()
    LaunchedEffect(scrollToGroupId) {
        val targetGroupId = scrollToGroupId ?: return@LaunchedEffect
        val groupIndex = config.groups.indexOfFirst { it.id == targetGroupId }
        if (groupIndex >= 0) {
            // The permit-join banner (one per broker) and pending-device banners
            // both occupy LazyColumn items ahead of the groups, so the target
            // index needs to account for however many are currently showing.
            listState.requestScrollToItem(config.brokers.size + config.pendingAutoConfigDevices.size + groupIndex)
        }
        backStackEntry.savedStateHandle.set<String?>("scrollToGroupId", null)
    }
    // Deliberately NOT unwrapped via "by" here - HomeScreen's own composable body never reads
    // .value directly, only passes the State object itself down to PanelTile/ClusterCard, each of
    // which does its own narrowly-scoped derivedStateOf read (see PanelTile/ClusterCard below).
    // Reading .value at this level (the old "by ...collectAsState()" pattern) would subscribe
    // HomeScreen's entire composable scope - and by extension every tile's call site inside it -
    // to EVERY incoming MQTT message on EVERY topic, since latestPayloads is one big shared map
    // whose identity changes on every single message: with the previous pattern, one temperature
    // sensor reporting recomposed literally every tile on the whole dashboard, not just its own.
    val payloadsState = app.connectionManager.latestPayloads.collectAsState()
    val timestampsState = app.connectionManager.latestPayloadTimestamps.collectAsState()

    // Ticks every second so "N seconds ago" counts up smoothly and resets the
    // moment a fresh MQTT message (or last_seen field) actually arrives - the
    // ageText computation below always re-derives from whichever timestamp is
    // most recent, so a new message naturally overrides a stale running count.
    //
    // Kept as a State object (not unwrapped via "by" here) for the same reason as
    // payloadsState/timestampsState above - passed down as-is so each ClusterCard's own
    // derivedStateOf can read .value itself, rather than HomeScreen's own recomposition scope
    // (and everything under it) re-running every single second regardless of whether any
    // cluster's displayed age text actually changed that second.
    val nowMillisState = remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000.milliseconds)
            nowMillisState.value = System.currentTimeMillis()
        }
    }

    // Nothing on this screen can do anything without a broker - send the user
    // straight to Add Broker rather than showing them an unusable empty Home.
    // Only re-fires if brokers go from present to empty again later (e.g. the
    // last one gets deleted), not on every recomposition. Gated on isLoaded so
    // this doesn't fire during the brief window while ConfigRepository's real,
    // on-disk config is still loading off the main thread (config.value is a
    // momentarily-empty default until then) - without that, a user who
    // genuinely has brokers configured could get bounced to Welcome for a
    // moment on every cold start.
    val isConfigLoaded by app.configRepository.isLoaded.collectAsState()
    LaunchedEffect(config.brokers.isEmpty(), isConfigLoaded) {
        if (isConfigLoaded && config.brokers.isEmpty()) {
            navController.navigate("welcome")
        }
    }

    var pendingGroupDelete by remember { mutableStateOf<String?>(null) }
    var pendingClusterDelete by remember { mutableStateOf<PendingClusterDelete?>(null) }
    var renamingGroup by remember { mutableStateOf<PanelGroup?>(null) }
    var renameText by remember { mutableStateOf("") }

    // Group drag-to-reorder state, shared across every group on this screen -
    // only one group can ever be dragged at a time. Unlike the fixed-height
    // rows in the old dedicated Groups screen, each group here can be a
    // wildly different height depending on how many clusters/panels it has
    // and whether it's collapsed, so a uniform "row height" division
    // wouldn't work. Instead, this reuses the same position-based
    // nearest-match approach as cluster dragging: each group's own measured
    // centre point (via onGloballyPositioned), compared against the current
    // drag position, re-read fresh at both onDrag and onDragEnd rather than
    // relying on a value fixed once at drag-start.
    var draggedGroupId by remember { mutableStateOf<String?>(null) }
    var draggedToGroupId by remember { mutableStateOf<String?>(null) }
    var totalGroupDragOffset by remember { mutableStateOf(Offset.Zero) }
    val groupCenters = remember { mutableStateMapOf<String, Offset>() }
    fun computeNearestGroupKey(draggedId: String): String? {
        val draggedBaseline = groupCenters[draggedId] ?: Offset.Zero
        val currentPosition = draggedBaseline + totalGroupDragOffset
        return groupCenters.entries
            .minByOrNull { (_, center) -> (center - currentPosition).getDistance() }
            ?.key
    }

    // Standalone (non-clustered) panels, and panels inside a cluster card, both
    // lay out as an exact 3-column grid. Tile width is capped at a sensible
    // maximum rather than always scaling proportionally to screen size -
    // otherwise a tablet's shorter dimension is still much bigger than a
    // phone's, so tiles (and whole clusters) end up oversized there too,
    // leaving no room for a second cluster even on a wide screen. A fixed cap
    // keeps clusters a consistent, comfortable size on any device, so the
    // manual row-packing below (see packedRows) can fit as many side-by-side
    // as actually fit, rather than relying on FlowRow's own wrapping logic.
    // LocalConfiguration (not LocalWindowInfo) is the officially-recommended
    // choice specifically for pure-Android apps - LocalWindowInfo.containerSize
    // was primarily designed for Compose Multiplatform, where
    // LocalConfiguration isn't available on non-Android targets, and has real
    // documented discrepancies/reliability quirks in various Android contexts
    // that LocalConfiguration doesn't share.
    val configuration = LocalConfiguration.current
    val screenWidthDp = configuration.screenWidthDp.dp
    val screenHeightDp = configuration.screenHeightDp.dp
    val columnsPerRow = 3
    val standaloneTileWidth = run {
        val referenceWidthDp = minOf(screenWidthDp, screenHeightDp)
        val groupHorizontalPadding = 12.dp * 2
        val gapsBetweenColumns = 8.dp * (columnsPerRow - 1)
        val proportionalWidth = (referenceWidthDp - groupHorizontalPadding - gapsBetweenColumns) / columnsPerRow
        minOf(proportionalWidth, config.tileWidthDp.dp)
    }
    // Scales each tile's text/icon/height/padding proportionally to how wide it's actually
    // rendering (not just the raw slider setting - this tracks standaloneTileWidth itself, so it
    // stays correct even when the proportional cap above kicks in on a narrow screen). 110dp -
    // AppConfig.tileWidthDp's own default - is the baseline that maps to scale=1f (today's normal
    // phone/tablet sizing); a tile shrunk for TV (more tiles need to fit on screen, and reading
    // distance makes full phone-sized text unnecessary there) scales everything down together
    // instead of keeping phone-sized text/icons packed into a visibly smaller box.
    val tileScale = standaloneTileWidth / 110.dp

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        floatingActionButton = {
            FloatingActionButton(onClick = { navController.navigate("addGroup") }) {
                Icon(Icons.Default.Add, contentDescription = "Add group")
            }
        }
    ) { padding ->
        if (config.groups.isEmpty()) {
            Column(
                modifier = Modifier.padding(padding).fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                if (isConfigLoaded) {
                    Text("No groups yet", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text("Tap + to create your first group, then add panels to it.")
                } else {
                    // config.groups briefly looks empty while ConfigRepository is still loading
                    // it from disk in the background (see its own init{} comment) - without this,
                    // someone who genuinely has groups configured could see this "add your first
                    // group" message flash up for real on a slow cold start, telling them to do
                    // something they've already done.
                    Text("Loading…", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text("Still reading your saved configuration.")
                }
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.padding(padding).fillMaxSize(),
            // Extra bottom padding so the last group's trailing icons (add
            // panel, delete group) can scroll clear of the FAB rather than
            // sitting underneath it - the FAB floats on top of content and
            // doesn't reserve space for itself otherwise.
            contentPadding = PaddingValues(bottom = 96.dp)
        ) {
            items(config.brokers, key = { "permitJoin_${it.id}" }) { broker ->
                PermitJoinItem(
                    app = app,
                    navController = navController,
                    broker = broker,
                    showBrokerName = config.brokers.size > 1,
                    payloadsState = payloadsState,
                    nowMillisState = nowMillisState
                )
            }
            items(config.pendingAutoConfigDevices, key = { "${it.brokerId}|${it.appConfigTopic}" }) { pending ->
                PendingDeviceBanner(
                    pending = pending,
                    onAdd = {
                        addPendingDevice(app, config, payloadsState.value, pending)
                        // Both actions mean the user has already handled this
                        // prompt via the in-app banner, so the matching system
                        // notification (posted with the same deviceName-based
                        // ID) shouldn't keep lingering in the shade too.
                        NotificationManagerCompat.from(context).cancel(pending.deviceName.hashCode())
                    },
                    onIgnore = {
                        app.configRepository.ignoreAppConfigTopic(pending.brokerId, pending.appConfigTopic)
                        NotificationManagerCompat.from(context).cancel(pending.deviceName.hashCode())
                    }
                )
            }
            config.groups.forEach { group ->
                val isDraggingThisGroup = draggedGroupId == group.id
                val isDropTargetGroup = draggedGroupId != null && draggedGroupId != group.id &&
                    draggedToGroupId == group.id
                // Sticky so the group's own name/controls stay reachable
                // (and orientable - which group's content you're currently
                // looking at) while scrolled deep into a long group's
                // clusters, rather than the header itself scrolling away
                // entirely. Wrapped in an opaque Surface since stickyHeader
                // itself is just a pinning mechanism - without an explicit
                // background, content scrolling underneath would otherwise
                // show through the pinned header.
                stickyHeader(key = "${group.id}_header") {
                Surface(color = MaterialTheme.colorScheme.background, tonalElevation = 2.dp) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                        .onGloballyPositioned { coordinates ->
                            val topLeft = coordinates.positionInWindow()
                            groupCenters[group.id] = Offset(
                                topLeft.x + coordinates.size.width / 2f,
                                topLeft.y + coordinates.size.height / 2f
                            )
                        }
                        .alpha(if (isDraggingThisGroup) 0.5f else 1f)
                        .then(
                            if (isDropTargetGroup) {
                                Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp))
                            } else {
                                Modifier
                            }
                        )
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier.weight(1f)
                                .clickable { app.configRepository.setGroupCollapsed(group.id, !group.collapsed) }
                                // Long-press starts a drag-to-reorder here, layered
                                // alongside the tap-to-collapse clickable above -
                                // the two are distinguishable by Compose's gesture
                                // system since they sit on very different timing
                                // thresholds (a quick tap vs. a sustained hold).
                                .pointerInput(group.id) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = {
                                            draggedGroupId = group.id
                                            draggedToGroupId = group.id
                                            totalGroupDragOffset = Offset.Zero
                                        },
                                        onDragEnd = {
                                            val fromId = draggedGroupId
                                            // Recomputed fresh, right here, rather than
                                            // trusting whatever onDrag last set - see
                                            // the same reasoning applied to cluster
                                            // dragging: a quick drag-and-release might
                                            // not produce enough onDrag callbacks for
                                            // a still-settling position to have
                                            // self-corrected by release time.
                                            val toId = fromId?.let { computeNearestGroupKey(it) }
                                            if (fromId != null && toId != null && fromId != toId) {
                                                val currentGroups = app.configRepository.config.value.groups
                                                val toIndex = currentGroups.indexOfFirst { it.id == toId }
                                                if (toIndex >= 0) {
                                                    app.configRepository.moveGroupToIndex(fromId, toIndex + 1)
                                                }
                                            }
                                            draggedGroupId = null
                                            draggedToGroupId = null
                                        },
                                        onDragCancel = {
                                            draggedGroupId = null
                                            draggedToGroupId = null
                                        },
                                        onDrag = { change, dragAmount ->
                                            change.consume()
                                            totalGroupDragOffset += dragAmount
                                            draggedToGroupId = computeNearestGroupKey(group.id)
                                        }
                                    )
                                },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Text(group.name, style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.width(4.dp))
                            Icon(
                                if (group.collapsed) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                                contentDescription = null
                            )
                        }
                        IconButton(onClick = { renamingGroup = group; renameText = group.name }) {
                            Icon(Icons.Default.Edit, contentDescription = "Rename ${group.name}")
                        }
                        IconButton(onClick = { navController.navigate("group/${group.id}/panel/new") }) {
                            Icon(Icons.Default.Add, contentDescription = "Add panel to ${group.name}")
                        }
                        IconButton(onClick = { pendingGroupDelete = group.id }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete ${group.name}")
                        }
                    }
                } // Column
                } // Surface
                } // stickyHeader

                if (!group.collapsed) {
                    item(key = "${group.id}_content") {
                        // Panels sharing a non-blank clusterName render together in one
                        // card; panels with a blank clusterName stay as standalone tiles,
                        // each getting its own unique bucket so they don't merge together.
                        val clusters = LinkedHashMap<String, MutableList<Panel>>()
                        group.panels.forEach { panel ->
                            val key = panel.clusterName.ifBlank { "__single__${panel.id}" }
                            clusters.getOrPut(key) { mutableListOf() }.add(panel)
                        }
                        // Sort clusters/standalone tiles by their lowest displayOrder (falls
                        // back to insertion order for anything left at the Int.MAX_VALUE default),
                        // and also sort each cluster's own panels by displayOrder so panels can
                        // be reordered *within* a cluster, not just relative to other clusters.
                        val orderedClusters = clusters.values
                            .map { bucket -> bucket.sortedBy { it.displayOrder } }
                            .sortedBy { bucket -> bucket.minOf { it.displayOrder } }

                        // Cluster drag-to-reorder state, scoped to this one group - only one
                        // cluster can ever be dragged at a time, and only within its own
                        // group. Unlike Groups' plain vertical list, or a panel grid's fixed
                        // columns, clusters can sit side by side within a packed row, so
                        // "which cluster is the drag currently over" is tracked by comparing
                        // the current absolute drag position against each cluster's own
                        // last-known centre point (updated via onGloballyPositioned), rather
                        // than computing a row/column index delta - a position-based nearest
                        // match handles the irregular, width-based row-packing correctly
                        // whether the target is directly below, or beside, the dragged
                        // cluster. Standalone tiles (no clusterName) aren't individually
                        // draggable via this mechanism, though they still participate
                        // correctly in the underlying displayOrder sequence either way.
                        var draggedClusterKey by remember(group.id) { mutableStateOf<String?>(null) }
                        var draggedToClusterKey by remember(group.id) { mutableStateOf<String?>(null) }
                        // Tracks only the raw, accumulated finger movement since the drag
                        // started - deliberately NOT combined with the dragged cluster's
                        // starting centre point up front. clusterCenters[key] is re-read
                        // fresh on every single onDrag call below instead, so if it was
                        // still momentarily stale at drag-start (Compose's relayout after
                        // the previous reorder hadn't fully caught up yet) and then
                        // corrects itself mid-drag, the next onDrag call picks up the
                        // corrected baseline automatically rather than the whole
                        // "nearest cluster" calculation suddenly jumping when a late
                        // onGloballyPositioned callback finally fires.
                        var totalDragOffset by remember(group.id) { mutableStateOf(Offset.Zero) }
                        val clusterCenters = remember(group.id) { mutableStateMapOf<String, Offset>() }
                        // Shared by both onDrag (for live visual feedback) and onDragEnd
                        // (for the actual commit) - critically, onDragEnd calls this itself
                        // one final time with the freshest possible clusterCenters read,
                        // rather than trusting whatever draggedToClusterKey was last set by
                        // onDrag. A quick drag-and-release might only produce a handful of
                        // onDrag callbacks, possibly not enough for a still-settling
                        // clusterCenters entry to have self-corrected by the time the user
                        // actually lifts their finger - recomputing at the exact moment of
                        // release closes that remaining timing gap.
                        fun computeNearestClusterKey(draggedKey: String): String? {
                            val draggedBaseline = clusterCenters[draggedKey] ?: Offset.Zero
                            val currentPosition = draggedBaseline + totalDragOffset
                            return clusterCenters.entries
                                .minByOrNull { (_, center) -> (center - currentPosition).getDistance() }
                                ?.key
                        }

                        // Manually pack clusters/tiles into rows rather than relying on
                        // FlowRow's own wrapping - computed directly against each item's
                        // known width, so multiple clusters land on the same row whenever
                        // they actually fit, on any screen size or orientation.
                        // + 16.dp accounts for ClusterCard's own Modifier.padding(8.dp) around its
                        // Row of tiles (8dp each side) - without it, this card width matched the
                        // Row's own content width exactly, leaving no room for that padding once
                        // laid out inside it. The Row still claimed its full (unpadded) width
                        // regardless, so it silently overflowed the card by 16dp and got clipped
                        // on the right by the card's rounded-corner shape - cutting into the
                        // rightmost (3rd) column's tile specifically, while columns 1-2 stayed
                        // fully visible.
                        val clusterCardWidth = standaloneTileWidth * columnsPerRow + 8.dp * (columnsPerRow - 1) + 16.dp
                        val availableRowWidth = screenWidthDp - 24.dp
                        val packedRows = remember(orderedClusters, standaloneTileWidth, availableRowWidth) {
                            val rows = mutableListOf<MutableList<List<Panel>>>()
                            var currentRow = mutableListOf<List<Panel>>()
                            var usedWidth = 0.dp
                            orderedClusters.forEach { panelsInCluster ->
                                val itemWidth = if (panelsInCluster.first().clusterName.isBlank()) {
                                    standaloneTileWidth
                                } else {
                                    clusterCardWidth
                                }
                                val gapNeeded = if (currentRow.isEmpty()) 0.dp else 8.dp
                                if (currentRow.isNotEmpty() && usedWidth + gapNeeded + itemWidth > availableRowWidth) {
                                    rows.add(currentRow)
                                    currentRow = mutableListOf()
                                    usedWidth = 0.dp
                                }
                                if (currentRow.isNotEmpty()) usedWidth += 8.dp
                                currentRow.add(panelsInCluster)
                                usedWidth += itemWidth
                            }
                            if (currentRow.isNotEmpty()) rows.add(currentRow)
                            rows
                        }

                        Column(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            packedRows.forEach { row ->
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    row.forEach { panelsInCluster ->
                                        val name = panelsInCluster.first().clusterName
                                        // Stable per-cluster identity, independent of list
                                        // position - without this, Compose can reuse another
                                        // cluster's remembered state (like ageText) when the
                                        // list reorders as devices are added/removed, since
                                        // it otherwise identifies composables by call position.
                                        key(panelsInCluster.first().id) {
                                            if (name.isBlank()) {
                                                PanelTile(
                                                    panel = panelsInCluster.first(),
                                                    groupId = group.id,
                                                    payloadsState = payloadsState,
                                                    app = app,
                                                    navController = navController,
                                                    tileScale = tileScale,
                                                    modifier = Modifier.width(standaloneTileWidth)
                                                )
                                            } else {
                                                ClusterCard(
                                                    name = name,
                                                    panels = panelsInCluster,
                                                    groupId = group.id,
                                                    payloadsState = payloadsState,
                                                    timestampsState = timestampsState,
                                                    nowMillisState = nowMillisState,
                                                    app = app,
                                                    navController = navController,
                                                    columns = columnsPerRow,
                                                    tileWidth = standaloneTileWidth,
                                                    tileScale = tileScale,
                                                    onDelete = {
                                                        pendingClusterDelete = PendingClusterDelete(
                                                            groupId = group.id,
                                                            name = name,
                                                            panelIds = panelsInCluster.map { it.id }
                                                        )
                                                    },
                                                    isDraggingCluster = draggedClusterKey == name,
                                                    isClusterDropTarget = draggedClusterKey != null &&
                                                        draggedClusterKey != name &&
                                                        draggedToClusterKey == name,
                                                    modifier = Modifier.width(clusterCardWidth)
                                                        .onGloballyPositioned { coordinates ->
                                                        val topLeft = coordinates.positionInWindow()
                                                        clusterCenters[name] = Offset(
                                                            topLeft.x + coordinates.size.width / 2f,
                                                            topLeft.y + coordinates.size.height / 2f
                                                        )
                                                    },
                                                    captionRowModifier = Modifier.pointerInput(name) {
                                                        detectDragGesturesAfterLongPress(
                                                            onDragStart = {
                                                                draggedClusterKey = name
                                                                draggedToClusterKey = name
                                                                totalDragOffset = Offset.Zero
                                                            },
                                                            onDragEnd = {
                                                                val fromKey = draggedClusterKey
                                                                // Recomputed fresh, right here, rather than trusting
                                                                // whatever onDrag last set draggedToClusterKey to - a
                                                                // quick drag-and-release might only produce a
                                                                // handful of onDrag callbacks, possibly not enough
                                                                // for a still-settling clusterCenters entry (from
                                                                // Compose's relayout after a previous reorder not
                                                                // having fully caught up yet) to have self-corrected
                                                                // by the exact moment the user actually lifts their
                                                                // finger. This closes that remaining timing gap.
                                                                val toKey = fromKey?.let { computeNearestClusterKey(it) }
                                                                if (fromKey != null && toKey != null && fromKey != toKey) {
                                                                    // Read fresh from the live config here, rather than
                                                                    // closing over the composable-scope orderedClusters/
                                                                    // group - this pointerInput block only launches once
                                                                    // per cluster card (keyed on its own name, which
                                                                    // rarely changes), so a captured value would stay
                                                                    // frozen at whatever it was during that very first
                                                                    // launch, silently going stale as later
                                                                    // recompositions (e.g. from ongoing MQTT traffic)
                                                                    // moved on without it - the same class of bug fixed
                                                                    // earlier for the Terminal screen's message list.
                                                                    val currentGroup = app.configRepository.config.value
                                                                        .groups.find { it.id == group.id }
                                                                    if (currentGroup != null) {
                                                                        val panelsByClusterKey = currentGroup.panels
                                                                            .groupBy { it.clusterName.ifBlank { "__single__${it.id}" } }
                                                                        val currentOrder = panelsByClusterKey.entries
                                                                            .sortedBy { (_, ps) -> ps.minOf { it.displayOrder } }
                                                                            .map { (key, _) -> key }
                                                                        val fromIndex = currentOrder.indexOf(fromKey)
                                                                        val toIndex = currentOrder.indexOf(toKey)
                                                                        if (fromIndex >= 0 && toIndex >= 0) {
                                                                            val reordered = currentOrder.toMutableList()
                                                                            reordered.removeAt(fromIndex)
                                                                            reordered.add(toIndex, fromKey)
                                                                            app.configRepository.reorderClustersInGroup(group.id, reordered)
                                                                            pushGroupOrderUpdatesForClusters(app, reordered, currentGroup.panels)
                                                                        }
                                                                    }
                                                                }
                                                                draggedClusterKey = null
                                                                draggedToClusterKey = null
                                                            },
                                                            onDragCancel = {
                                                                draggedClusterKey = null
                                                                draggedToClusterKey = null
                                                            },
                                                            onDrag = { change, dragAmount ->
                                                                change.consume()
                                                                totalDragOffset += dragAmount
                                                                draggedToClusterKey = computeNearestClusterKey(name)
                                                            }
                                                        )
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } // item
                }
            }
        }
    }

    pendingGroupDelete?.let { groupId ->
        AlertDialog(
            onDismissRequest = { pendingGroupDelete = null },
            title = { Text("Delete group?") },
            text = { Text("This removes the group and every panel in it.") },
            confirmButton = {
                TextButton(onClick = {
                    app.configRepository.deleteGroup(groupId)
                    pendingGroupDelete = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { pendingGroupDelete = null }) { Text("Cancel") } }
        )
    }

    renamingGroup?.let { group ->
        AlertDialog(
            onDismissRequest = { renamingGroup = null },
            title = { Text("Rename group") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text("Name") },
                    keyboardOptions = tvAwareKeyboardOptions(),
                    modifier = Modifier.clearFocusOnBack()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (renameText.isNotBlank()) {
                        app.configRepository.upsertGroup(group.copy(name = renameText))
                    }
                    renamingGroup = null
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { renamingGroup = null }) { Text("Cancel") }
            }
        )
    }

    pendingClusterDelete?.let { pending ->
        AlertDialog(
            onDismissRequest = { pendingClusterDelete = null },
            title = { Text("Delete \"${pending.name}\"?") },
            text = { Text("This removes all ${pending.panelIds.size} panels for this device.") },
            confirmButton = {
                TextButton(onClick = {
                    app.configRepository.removePanels(pending.groupId, pending.panelIds)
                    pendingClusterDelete = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { pendingClusterDelete = null }) { Text("Cancel") } }
        )
    }
}

private data class PendingClusterDelete(val groupId: String, val name: String, val panelIds: List<String>)

/**
 * If this cluster's panels belong to an auto-configured device, publishes an
 * updated (retained) "/app" payload reflecting the new panel order, so the
 * device's own config topic stays in sync with a manual reorder - otherwise,
 * a future republish of that topic (for an unrelated reason) would rebuild
 * the panels using the device's original order, silently undoing this.
 *
 * Deliberately reads app.connectionManager.latestPayloads.value directly
 * (a StateFlow's current value) rather than accepting a payloads parameter -
 * this is called from inside a pointerInput block that only launches once
 * per panel tile, so a parameter passed in from the composable scope would
 * be captured at that first launch and go stale on every call after,
 * potentially rewriting the /app payload from an outdated base and then
 * having that stale republish silently overwrite a subsequent, correct
 * reorder once the app's own auto-config reconciliation re-consumes it.
 */
private fun pushOrderUpdateIfAutoConfigured(
    app: Z2mDashApplication,
    orderedPanelIds: List<String>,
    clusterPanels: List<Panel>
) {
    val config = app.configRepository.config.value
    val payloads = app.connectionManager.latestPayloads.value
    val panelIdSet = orderedPanelIds.toSet()
    val device = config.autoConfiguredDevices.find { it.createdPanelIds.any { id -> id in panelIdSet } } ?: return
    val currentPayload = payloads["${device.brokerId}|${device.appConfigTopic}"] ?: return

    val orderByFieldOrLabel: Map<String, Int> = orderedPanelIds.withIndex().mapNotNull { (index, id) ->
        val panel = clusterPanels.find { it.id == id } ?: return@mapNotNull null
        val key = when (panel) {
            is Panel.Sensor -> panel.jsonPath
            is Panel.Toggle -> panel.label
            is Panel.Button -> panel.label
        }
        key to index
    }.toMap()

    val orderVersion = System.currentTimeMillis()
    val updatedPayload = SensorDiscovery.updateOrderingInAppPayload(currentPayload, orderByFieldOrLabel, orderVersion)
        ?: return
    app.connectionManager.publish(device.brokerId, device.appConfigTopic, updatedPayload, retain = true)
    // See the matching comment in pushGroupOrderUpdatesForClusters below - without
    // this, the "#"-subscribed echo of our own publish (or a stale retained
    // redelivery on any phone sharing this broker) could race
    // DeviceAutoConfigManager into re-reconciling this exact change right back.
    app.configRepository.markAutoConfiguredDevicePayloadApplied(
        device.brokerId, device.appConfigTopic, updatedPayload, orderVersion
    )
}

/**
 * Reordering clusters shifts every cluster's relative position within the
 * group, not just the one that was dragged - so every affected cluster that
 * belongs to an auto-configured device gets its own retained "/app" update
 * reflecting its new group_order, not just the dragged one.
 *
 * Same reasoning as pushOrderUpdateIfAutoConfigured above for reading
 * app.connectionManager.latestPayloads.value directly rather than accepting
 * a payloads parameter - this is called from inside a pointerInput block
 * that only launches once per cluster card, so a captured parameter would
 * go stale after the first launch.
 */
private fun pushGroupOrderUpdatesForClusters(
    app: Z2mDashApplication,
    orderedClusterKeys: List<String>,
    groupPanels: List<Panel>
) {
    val config = app.configRepository.config.value
    val payloads = app.connectionManager.latestPayloads.value
    val panelsByCluster = groupPanels.groupBy { it.clusterName.ifBlank { "__single__${it.id}" } }
    // One shared timestamp for every cluster this single drag touches, so a
    // phone reconciling any of them later treats the whole batch as one
    // logical write rather than racing itself between clusters.
    val orderVersion = System.currentTimeMillis()

    orderedClusterKeys.forEachIndexed { index, clusterKey ->
        val clusterPanelIds = panelsByCluster[clusterKey]?.map { it.id }?.toSet() ?: return@forEachIndexed
        val device = config.autoConfiguredDevices.find { it.createdPanelIds.any { id -> id in clusterPanelIds } }
            ?: return@forEachIndexed
        val currentPayload = payloads["${device.brokerId}|${device.appConfigTopic}"] ?: return@forEachIndexed
        val updatedPayload = SensorDiscovery.updateGroupOrderInAppPayload(currentPayload, index + 1, orderVersion)
            ?: return@forEachIndexed
        app.connectionManager.publish(device.brokerId, device.appConfigTopic, updatedPayload, retain = true)
        // Since every broker is subscribed to "#", that publish echoes straight back
        // to DeviceAutoConfigManager, which would otherwise see the payload change
        // and reconcile it - normally fine (that's how another phone sharing this
        // broker picks up the new order), but a *stale* retained redelivery of an
        // older payload (e.g. from a reconnect, or another phone that hasn't caught
        // up yet) could just as easily land here and clobber this fresher reorder.
        // Pre-marking the payload as applied, with this order_version, means:
        // this exact echo is recognised as already up to date and skipped, and any
        // later payload with an older/missing order_version is recognised as stale
        // and ignored - while a genuinely newer order_version (a real subsequent
        // reorder, from this phone or another one) still gets adopted normally. See
        // DeviceAutoConfigManager.reconcileKnownDevices/AutoConfiguredDevice.
        // lastKnownOrderVersion.
        app.configRepository.markAutoConfiguredDevicePayloadApplied(
            device.brokerId, device.appConfigTopic, updatedPayload, orderVersion
        )
    }
}

/** Renders a bordered card containing every panel in [panels] ([columns] per row), with [name] as a caption below. */
@Suppress("SameParameterValue")
@Composable
private fun ClusterCard(
    name: String,
    panels: List<Panel>,
    groupId: String,
    payloadsState: State<Map<String, String>>,
    timestampsState: State<Map<String, Long>>,
    nowMillisState: State<Long>,
    app: Z2mDashApplication,
    navController: NavController,
    columns: Int,
    tileWidth: Dp,
    tileScale: Float,
    onDelete: () -> Unit,
    // Cross-cluster drag-to-reorder state/gesture-handling lives one level up
    // (in the group section, which can see every cluster at once) and gets
    // threaded in here, same pattern as how each panel tile receives its own
    // drag detector via an externally-built modifier rather than owning that
    // logic itself.
    modifier: Modifier = Modifier,
    captionRowModifier: Modifier = Modifier,
    isDraggingCluster: Boolean = false,
    isClusterDropTarget: Boolean = false
) {
    // A single long-lived derivedStateOf (not recreated every recomposition, since panels is the
    // only remember() key - payloads/timestamps/now are all read from their State objects inside
    // the lambda itself) so its own equality check can actually do its job: this cluster's
    // ageText/isStale only propagates a recomposition to whatever reads it below when the
    // COMPUTED result changes - not on every unrelated topic's MQTT message elsewhere on the
    // dashboard, and not on every single nowMillisState tick when the relative-time string
    // happens to read the same as last second (e.g. "2 hours ago" doesn't change every second).
    val ageState = remember(panels) {
        derivedStateOf {
            fun topicFor(panel: Panel): String? = when (panel) {
                is Panel.Sensor -> panel.topic
                is Panel.Toggle -> panel.stateTopic.takeIf { it.isNotBlank() }
                // No meaningful state to track age from - a momentary command has
                // nothing to have "last reported" a value for.
                is Panel.Button -> null
            }

            val payloads = payloadsState.value
            val timestamps = timestampsState.value
            val nowMillis = nowMillisState.value

            // Prefer the device's own reported time (Zigbee2MQTT's "last_seen" field)
            // over our app's receipt time - it reflects when the device itself last
            // reported in, not just when this app instance happened to receive a
            // message (which can be bumped by things unrelated to real freshness,
            // like a broker redelivering a retained message on resubscribe).
            val deviceReportedTimestamps = panels.mapNotNull { panel ->
                val topic = topicFor(panel) ?: return@mapNotNull null
                payloads["${panel.brokerId}|$topic"]
                    ?.let { JsonPath.extract(it, "last_seen") }
                    ?.let { JsonPath.parseIso8601(it) }
            }

            // Only fall back to receipt time if NONE of this cluster's panels have a
            // genuine last_seen anywhere - otherwise a config-only topic without one
            // (like the device's own "/app" topic) could drag the cluster's displayed
            // freshness down just because its unrelated topic happened to update.
            val latestTimestamp = if (deviceReportedTimestamps.isNotEmpty()) {
                deviceReportedTimestamps.max()
            } else {
                panels.mapNotNull { panel ->
                    val topic = topicFor(panel) ?: return@mapNotNull null
                    timestamps["${panel.brokerId}|$topic"]
                }.maxOrNull()
            }

            latestTimestamp?.let {
                val text = DateUtils.getRelativeTimeSpanString(it, nowMillis, DateUtils.SECOND_IN_MILLIS).toString()
                val stale = (nowMillis - it) > 60 * 60 * 1000L // more than 1 hour
                text to stale
            } ?: (null to false)
        }
    }
    val (ageText, isStale) = ageState.value

    // Drag-to-reorder state, local to this one cluster card. Long-press
    // directly on a tile starts the drag (via detectDragGesturesAfterLongPress
    // on each tile's own modifier chain, ahead of that tile's plain
    // short-press-to-edit clickable) - no separate reorder-mode toggle or icon
    // needed. Panels render in a plain Column/Row here (not a LazyColumn), so
    // unlike the Groups screen's drag-reorder there's no LazyListState
    // scroll-anchor tracking to fight, but the same "don't actually move
    // anything during the drag" principle still applies for its own sake: it
    // keeps the interaction simple and avoids offset-compensation math
    // entirely. Rows stay static; only the dragged tile is highlighted, and
    // the drop target tile gets an outline. The real reorder - and, if this
    // cluster came from an auto-configured device, a republished /app message
    // reflecting the new order - commits once, when the drag ends.
    var draggedPanelId by remember { mutableStateOf<String?>(null) }
    var draggedFromIndex by remember { mutableIntStateOf(-1) }
    var draggedToIndex by remember { mutableIntStateOf(-1) }
    var dragOffsetX by remember { mutableFloatStateOf(0f) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    var tileWidthPx by remember { mutableFloatStateOf(0f) }
    var tileHeightPx by remember { mutableFloatStateOf(0f) }
    val staleIndicatorColor = if (isStale) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        modifier = modifier
            .alpha(if (isDraggingCluster) 0.5f else 1f)
            .then(
                if (isClusterDropTarget) {
                    Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp))
                } else if (isStale) {
                    Modifier.border(2.dp, staleIndicatorColor, RoundedCornerShape(12.dp))
                } else {
                    Modifier
                }
            ),
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 1.dp
    ) {
        // Every row gets the same fixed width (a full 3-column row), regardless
        // of how many panels actually land in it - a short trailing row, or a
        // whole cluster with fewer than 3 panels, then centers within that
        // fixed width instead of bunching to the left with empty space beside it.
        val fullRowWidth = tileWidth * columns + 8.dp * (columns - 1)
        Column(modifier = Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            panels.chunked(columns).forEachIndexed { rowIndex, row ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    modifier = Modifier.width(fullRowWidth)
                ) {
                    row.forEachIndexed { columnIndex, panel ->
                        val panelIndex = rowIndex * columns + columnIndex
                        val isDragging = draggedPanelId == panel.id
                        val isDropTarget = draggedPanelId != null &&
                            draggedPanelId != panel.id &&
                            panelIndex == draggedToIndex

                        Box(
                            modifier = Modifier
                                .width(tileWidth)
                                .then(
                                    if (isDropTarget) {
                                        Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp))
                                    } else {
                                        Modifier
                                    }
                                )
                        ) {
                            PanelTile(
                                panel = panel,
                                groupId = groupId,
                                payloadsState = payloadsState,
                                app = app,
                                navController = navController,
                                tileScale = tileScale,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .alpha(if (isDragging) 0.5f else 1f)
                                    .onGloballyPositioned { coordinates ->
                                        if (tileWidthPx == 0f) tileWidthPx = coordinates.size.width.toFloat()
                                        if (tileHeightPx == 0f) tileHeightPx = coordinates.size.height.toFloat()
                                    }
                                    .pointerInput(panel.id) {
                                        detectDragGesturesAfterLongPress(
                                            onDragStart = {
                                                draggedPanelId = panel.id
                                                draggedFromIndex = panelIndex
                                                draggedToIndex = panelIndex
                                                dragOffsetX = 0f
                                                dragOffsetY = 0f
                                            },
                                            onDragEnd = {
                                                val fromIndex = draggedFromIndex
                                                val toIndex = draggedToIndex
                                                if (toIndex != fromIndex && fromIndex >= 0 && toIndex >= 0 && toIndex < panels.size) {
                                                    val targetPanelId = panels[toIndex].id
                                                    // Read fresh from the live config for the actual
                                                    // commit, rather than relying purely on the
                                                    // closure-captured `panels` list - this
                                                    // pointerInput block only launches once per tile,
                                                    // so that list would go stale exactly like the
                                                    // cluster-level drag's equivalent bug. Resolving
                                                    // both the dragged and target panels by *id*
                                                    // (rather than trusting the drag's own index
                                                    // bookkeeping, which was itself computed against
                                                    // that same possibly-stale list) means a slightly
                                                    // outdated starting point still resolves correctly
                                                    // against whatever the panel list actually looks
                                                    // like right now.
                                                    val currentPanels = app.configRepository.config.value.groups
                                                        .find { it.id == groupId }?.panels
                                                        ?.filter { it.clusterName == name }
                                                        ?.sortedBy { it.displayOrder }
                                                    if (currentPanels != null) {
                                                        val currentFromIndex = currentPanels.indexOfFirst { it.id == panel.id }
                                                        val currentToIndex = currentPanels.indexOfFirst { it.id == targetPanelId }
                                                        if (currentFromIndex >= 0 && currentToIndex >= 0 && currentFromIndex != currentToIndex) {
                                                            val reordered = currentPanels.toMutableList()
                                                            val moved = reordered.removeAt(currentFromIndex)
                                                            reordered.add(currentToIndex, moved)
                                                            val orderedIds = reordered.map { it.id }
                                                            app.configRepository.reorderPanelsInCluster(groupId, orderedIds)
                                                            pushOrderUpdateIfAutoConfigured(app, orderedIds, reordered)
                                                        }
                                                    }
                                                }
                                                draggedPanelId = null
                                                draggedFromIndex = -1
                                                draggedToIndex = -1
                                                dragOffsetX = 0f
                                                dragOffsetY = 0f
                                            },
                                            onDragCancel = {
                                                draggedPanelId = null
                                                draggedFromIndex = -1
                                                draggedToIndex = -1
                                                dragOffsetX = 0f
                                                dragOffsetY = 0f
                                            },
                                            onDrag = { change, dragAmount ->
                                                change.consume()
                                                dragOffsetX += dragAmount.x
                                                dragOffsetY += dragAmount.y
                                                val w = tileWidthPx
                                                val h = tileHeightPx
                                                if (w > 0f && h > 0f) {
                                                    val columnDelta = (dragOffsetX / w).roundToInt()
                                                    val rowDelta = (dragOffsetY / h).roundToInt()
                                                    val linearDelta = rowDelta * columns + columnDelta
                                                    draggedToIndex = (draggedFromIndex + linearDelta)
                                                        .coerceIn(0, panels.lastIndex)
                                                }
                                            }
                                        )
                                    }
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                // fillMaxWidth so the whole caption bar is a touch target for
                // the drag, not just the width of the name/age text itself -
                // otherwise a long-press anywhere else along this row (which
                // is naturally where someone would try "holding the
                // cluster") lands outside the row's actual bounds and never
                // reaches the gesture detector at all.
                modifier = captionRowModifier.fillMaxWidth()
            ) {
                // maxLines/overflow/softWrap=false are a deliberate safety net, not just cosmetic:
                // confirmed on-device (via `adb shell uiautomator dump`) that this Text could end
                // up wrapping one character per line into a tall, narrow vertical strip that looked
                // like a scrollbar - a device/cluster name with no line cap at all, laid out in
                // whatever width the Row happens to receive, has no defence against a width
                // that's momentarily far too narrow for it. This doesn't fix whatever causes the
                // width itself to collapse, but it does mean that if it ever happens again the
                // name just truncates with "…" instead of turning into that vertical artifact.
                // Floored well above tileScale's own range (which can go well under 0.5 at the
                // smallest tile width) - a cluster's caption is a heading read once for the whole
                // row of tiles below it, not per-tile decoration, so it shouldn't shrink as
                // aggressively as the tiles themselves do before it stops being legible. Still
                // shrinks some at small tile widths, just not all the way down with them.
                val captionScale = tileScale.coerceAtLeast(0.85f)
                Text(
                    name,
                    // Scaled the same way (fontSize AND lineHeight, not just fontSize - see
                    // SensorTile's own comment on why lineHeight can't be left out) as every
                    // tile's own text, so the cluster caption shrinks right along with its tiles
                    // instead of staying phone-sized above a much smaller row of them.
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontSize = MaterialTheme.typography.titleSmall.fontSize * captionScale,
                        lineHeight = MaterialTheme.typography.titleSmall.lineHeight * captionScale
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    softWrap = false
                )
                if (ageText != null) {
                    val ageTextStyle = MaterialTheme.typography.labelSmall.copy(
                        fontSize = MaterialTheme.typography.labelSmall.fontSize * captionScale,
                        lineHeight = MaterialTheme.typography.labelSmall.lineHeight * captionScale,
                        fontWeight = if (isStale) FontWeight.Bold else MaterialTheme.typography.labelSmall.fontWeight
                    )
                    Text(
                        " \u2022 $ageText",
                        style = ageTextStyle,
                        color = staleIndicatorColor
                    )
                    if (isStale) {
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = "Data is more than an hour old",
                            tint = staleIndicatorColor,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Delete $name",
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

/** Output of [PanelTile]'s derived-state computation for a [Panel.Sensor] - see its own comment. */
private data class SensorTileDerived(
    val value: String,
    val alert: SensorAlert,
    val isPresenceField: Boolean,
    val isPresent: Boolean
)

/** Renders a single Sensor or Toggle tile, wired up to its live payload and edit/toggle actions. */
@Composable
private fun PanelTile(
    panel: Panel,
    groupId: String,
    payloadsState: State<Map<String, String>>,
    app: Z2mDashApplication,
    navController: NavController,
    tileScale: Float = 1f,
    modifier: Modifier = Modifier
) {
    val config by app.configRepository.config.collectAsState()
    when (panel) {
        is Panel.Sensor -> {
            // A single long-lived derivedStateOf, same reasoning as ClusterCard's ageState above -
            // this tile only actually recomposes when ITS OWN computed value/alert/presence changes,
            // not on every MQTT message for every other topic on the dashboard (which is what
            // reading payloadsState.value directly and unconditionally, as this used to, caused).
            val derived by remember(panel) {
                derivedStateOf {
                    val payloads = payloadsState.value
                    val raw = payloads["${panel.brokerId}|${panel.topic}"]
                    val extracted = raw?.let { JsonPath.extract(it, panel.jsonPath) }
                    // Zigbee2MQTT uses "occupancy" for PIR-based motion sensors and
                    // "presence" for mmWave/radar-based ones - both mean the same
                    // thing here (is someone currently detected), so both are
                    // treated identically rather than needing the user to know
                    // which convention their specific device uses.
                    val isPresenceField = panel.jsonPath.equals("occupancy", ignoreCase = true) ||
                        panel.jsonPath.equals("presence", ignoreCase = true)
                    val isPresent = extracted?.equals("true", ignoreCase = true) == true
                    // Only reformat genuinely numeric values - a non-numeric extracted
                    // value (e.g. a text state like "online") passes through as-is,
                    // since rounding only makes sense for actual measurements.
                    // Presence/occupancy fields get their own dedicated label instead
                    // of a raw "true"/"false", with the icon itself carrying the
                    // detected-or-not state visually.
                    val value = when {
                        isPresenceField -> if (extracted != null) { if (isPresent) "Detected" else "Clear" } else "--"
                        extracted != null -> extracted.toDoubleOrNull()?.let { num -> "%.${panel.decimals}f".format(num) } ?: extracted
                        else -> "--"
                    }
                    val alert = if (panel.idealRangeTopic.isBlank()) {
                        SensorAlert.NONE
                    } else {
                        // Deliberately reparses the original extracted text, not the
                        // now-rounded display value - comparing against a rounded
                        // number could misclassify a borderline reading (e.g. a true
                        // 45.4 rounding to "45" and appearing further from a 45.5
                        // threshold than it actually is).
                        val numericValue = extracted?.toDoubleOrNull()
                        val idealRaw = payloads["${panel.brokerId}|${panel.idealRangeTopic}"]
                        val min = idealRaw?.let { JsonPath.extract(it, panel.idealMinPath) }?.toDoubleOrNull()
                        val max = idealRaw?.let { JsonPath.extract(it, panel.idealMaxPath) }?.toDoubleOrNull()
                        when {
                            numericValue == null -> SensorAlert.NONE
                            min != null && numericValue < min -> SensorAlert.BELOW_MIN
                            max != null && numericValue > max -> SensorAlert.ABOVE_MAX
                            min != null || max != null -> SensorAlert.IN_RANGE
                            else -> SensorAlert.NONE
                        }
                    }
                    SensorTileDerived(value, alert, isPresenceField, isPresent)
                }
            }
            SensorTile(
                modifier = modifier,
                icon = panel.icon,
                value = derived.value,
                unit = panel.unit,
                alert = derived.alert,
                label = panel.label,
                blinkEnabled = config.staleDataBlinkEnabled,
                iconTint = if (derived.isPresenceField && derived.isPresent) MaterialTheme.colorScheme.primary else null,
                scale = tileScale,
                onEdit = { navController.navigate("group/$groupId/panel/${panel.id}") }
            )
        }

        is Panel.Toggle -> {
            // Same derivedStateOf reasoning as the Sensor branch above.
            val isOn by remember(panel) {
                derivedStateOf {
                    val statePayload = payloadsState.value["${panel.brokerId}|${panel.stateTopic}"]
                    val resolvedState = statePayload?.let { JsonPath.extract(it, panel.stateJsonPath) }
                    // onPayload might be a whole JSON command like {"state":"OPEN"}, not
                    // just the bare value the state topic reports back - pull the same
                    // field back out of it (via the same stateJsonPath) to get a fair
                    // comparison. Falls back to the raw onPayload string for simple
                    // non-JSON commands like a bare "ON", where extraction fails.
                    val expectedOnValue = JsonPath.extract(panel.onPayload, panel.stateJsonPath) ?: panel.onPayload
                    resolvedState != null && resolvedState.equals(expectedOnValue, ignoreCase = true)
                }
            }
            ToggleTile(
                modifier = modifier,
                icon = panel.icon,
                label = panel.label,
                isOn = isOn,
                onToggle = {
                    app.connectionManager.publish(
                        panel.brokerId,
                        panel.commandTopic,
                        if (isOn) panel.offPayload else panel.onPayload
                    )
                },
                scale = tileScale,
                onEdit = { navController.navigate("group/$groupId/panel/${panel.id}") }
            )
        }

        is Panel.Button -> {
            ButtonTile(
                modifier = modifier,
                icon = panel.icon,
                label = panel.label,
                onPress = { app.connectionManager.publish(panel.brokerId, panel.commandTopic, panel.payload) },
                scale = tileScale,
                onEdit = { navController.navigate("group/$groupId/panel/${panel.id}") }
            )
        }
    }
}

/** Builds and stores the panels for a newly-accepted pending device, then clears it from the pending list. */
private fun addPendingDevice(
    app: Z2mDashApplication,
    config: AppConfig,
    payloads: Map<String, String>,
    pending: PendingAutoConfigDevice
) {
    val tag = "Z2mDash-AddDevice"
    try {
        val appConfigPayload = payloads["${pending.brokerId}|${pending.appConfigTopic}"]
        if (appConfigPayload == null) {
            Log.w(tag, "No payload found for ${pending.brokerId}|${pending.appConfigTopic} - aborting add")
            return
        }
        val deviceConfig = SensorDiscovery.parseDeviceAppConfig(appConfigPayload)
        if (deviceConfig == null) {
            Log.w(tag, "parseDeviceAppConfig returned null for payload: $appConfigPayload")
            return
        }
        val sensorPayload = payloads["${pending.brokerId}|${pending.sensorTopic}"]
        val sensorFieldKeys = sensorPayload?.let { SensorDiscovery.fieldKeysOf(it) } ?: emptySet()

        val newPanels = SensorDiscovery.buildPanels(
            brokerId = pending.brokerId,
            sensorTopic = pending.sensorTopic,
            sensorFieldKeys = sensorFieldKeys,
            appConfigTopic = pending.appConfigTopic,
            appConfigPayload = appConfigPayload,
            deviceConfig = deviceConfig
        )
        if (newPanels.isEmpty()) {
            Log.w(tag, "buildPanels produced zero panels for ${pending.deviceName} - aborting add. " +
                "deviceConfig.panelFields=${deviceConfig.panelFields}, sensorFieldKeys=$sensorFieldKeys")
            return
        }

        val targetGroupId = deviceConfig.group?.let { name ->
            config.groups.find { it.name.equals(name, ignoreCase = true) }?.id
                ?: UUID.randomUUID().toString().also { id ->
                    app.configRepository.upsertGroup(PanelGroup(id = id, name = name))
                }
        } ?: config.groups.firstOrNull()?.id
            ?: UUID.randomUUID().toString().also { id ->
                app.configRepository.upsertGroup(PanelGroup(id = id, name = "Discovered Sensors"))
            }

        val device = AutoConfiguredDevice(
            brokerId = pending.brokerId,
            sensorTopic = pending.sensorTopic,
            appConfigTopic = pending.appConfigTopic,
            lastAppliedPayload = appConfigPayload,
            createdPanelIds = newPanels.map { it.id }
        )
        app.configRepository.applyDeviceAutoConfig(
            oldPanelIds = emptySet(),
            updatedDevice = device,
            targetGroupId = targetGroupId,
            newPanels = newPanels
        )
        app.configRepository.removePendingAutoConfigDevice(pending.brokerId, pending.appConfigTopic)
        Log.i(tag, "Added ${newPanels.size} panels for ${pending.deviceName} into group $targetGroupId")
    } catch (e: Exception) {
        // Deliberately caught and logged rather than left to propagate - a
        // silent early-return elsewhere in this function was already easy to
        // mistake for "the button does nothing"; an uncaught exception here
        // would be worse (an unexplained crash), so this at least surfaces
        // what actually went wrong in Logcat.
        Log.e(tag, "addPendingDevice failed for ${pending.deviceName}", e)
    }
}

/**
 * One broker's own item in HomeScreen's LazyColumn - does its own narrowly-scoped
 * derivedStateOf read of payloadsState/nowMillisState (same reasoning as ClusterCard/PanelTile
 * below), so the once-a-second countdown tick only recomposes this one row instead of the whole
 * screen.
 */
@Composable
private fun PermitJoinItem(
    app: Z2mDashApplication,
    navController: NavController,
    broker: Broker,
    showBrokerName: Boolean,
    payloadsState: State<Map<String, String>>,
    nowMillisState: State<Long>
) {
    val baseTopicNormalized = remember(broker.baseTopic) { PermitJoin.normalizedBaseTopic(broker.baseTopic) }
    val status by remember(broker.id, baseTopicNormalized) {
        derivedStateOf { PermitJoin.status(payloadsState.value, broker.id, baseTopicNormalized, nowMillisState.value) }
    }
    PermitJoinBanner(
        brokerName = broker.name,
        showBrokerName = showBrokerName,
        status = status,
        onToggle = { enabled ->
            val payload = PermitJoin.requestPayload(broker.permitJoinDevice, if (enabled) 254 else 0)
            app.connectionManager.publish(broker.id, PermitJoin.requestTopic(baseTopicNormalized), payload)
        },
        // Deep-links straight to this broker's own "Permit Join" section (rather than just the
        // top of its whole edit screen) - e.g. to change which router it's scoped to, or check
        // on it, without hunting back through a long scrolling form to find that section again.
        onInfoClick = { navController.navigate("broker/${broker.id}?focus=permitJoin") }
    )
}

@Composable
private fun PermitJoinBanner(
    brokerName: String,
    showBrokerName: Boolean,
    status: PermitJoin.Status,
    onToggle: (Boolean) -> Unit,
    onInfoClick: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.WifiTethering, contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Column(
                modifier = Modifier.weight(1f).clickable(onClick = onInfoClick)
            ) {
                Text(
                    if (showBrokerName) "Permit Join – $brokerName" else "Permit Join",
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    if (status.isOn) {
                        "Open for ${PermitJoin.formatRemaining(status.remainingSeconds)} more"
                    } else {
                        "Off – new Zigbee devices can't join"
                    },
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Switch(checked = status.isOn, onCheckedChange = onToggle)
        }
    }
}

/** A dismissible card prompting the user to accept or ignore a newly-detected auto-config device. */
@Composable
private fun PendingDeviceBanner(
    pending: PendingAutoConfigDevice,
    onAdd: () -> Unit,
    onIgnore: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 2.dp
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("New device found", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                "\"${pending.deviceName}\" (${pending.appConfigTopic}) published its own dashboard config.",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onIgnore) { Text("Ignore") }
                TextButton(onClick = onAdd) { Text("Add") }
            }
        }
    }
}
