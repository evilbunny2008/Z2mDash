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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.layout.LazyLayoutCacheWindow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.UnfoldLess
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import com.odiousapps.z2mdash.Z2mDashApplication
import com.odiousapps.z2mdash.data.AppConfig
import com.odiousapps.z2mdash.data.AutoConfiguredDevice
import com.odiousapps.z2mdash.data.JsonPath
import com.odiousapps.z2mdash.data.Panel
import com.odiousapps.z2mdash.data.PanelGroup
import com.odiousapps.z2mdash.data.PendingAutoConfigDevice
import com.odiousapps.z2mdash.data.PermitJoin
import com.odiousapps.z2mdash.data.SensorDiscovery
import com.odiousapps.z2mdash.data.clearRetainedAppTopicsForOrphanedDevices
import com.odiousapps.z2mdash.data.forceRepublishGroupAppTopics
import com.odiousapps.z2mdash.data.publishAppTopicForClusterIfMissing
import com.odiousapps.z2mdash.data.pushGroupMoveForAutoConfiguredDevices
import com.odiousapps.z2mdash.data.pushGroupRenameForAutoConfiguredDevices
import com.odiousapps.z2mdash.data.pushPanelClusterOverrideIfAutoConfigured
import com.odiousapps.z2mdash.data.retopicClusterAndPublish
import com.odiousapps.z2mdash.data.retopicGroupTopicPrefix
import com.odiousapps.z2mdash.ui.components.ButtonTile
import com.odiousapps.z2mdash.ui.components.SensorAlert
import com.odiousapps.z2mdash.ui.components.SensorTile
import com.odiousapps.z2mdash.ui.components.ToggleTile
import com.odiousapps.z2mdash.ui.tv.LocalIsTv
import com.odiousapps.z2mdash.ui.tv.clearFocusOnBack
import com.odiousapps.z2mdash.ui.tv.onDpadSelect
import com.odiousapps.z2mdash.ui.tv.toggleableRow
import com.odiousapps.z2mdash.ui.tv.tvAwareKeyboardOptions
import com.odiousapps.z2mdash.ui.tv.tvFocusIndicator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(navController: NavController, backStackEntry: NavBackStackEntry) {
    val context = LocalContext.current
    val app = context.applicationContext as Z2mDashApplication
    val config by app.configRepository.config.collectAsState()

    // A generous cache window (not just the default, near-zero buffer) keeps groups scrolled
    // well out of view still composed rather than disposed - needed for cluster/group drag with
    // auto-scroll: the dragged item's own long-press gesture runs as a coroutine tied to its
    // composable, so disposing it mid-drag (confirmed on-device: holding near a screen edge for
    // several seconds, auto-scrolling the origin group far enough away) cancels the gesture
    // outright, with nothing moved - looking like the drag "just failed" partway through a hold.
    val listState = rememberLazyListState(
        cacheWindow = LazyLayoutCacheWindow(ahead = 4000.dp, behind = 4000.dp)
    )
    // AddPanelScreen sets this on save (new panel, not edit) via Navigation Compose's result
    // passing pattern, so the newly-added panel's group can scroll into view.
    //
    // Uses this screen's own NavBackStackEntry rather than navController.currentBackStackEntry -
    // that property can be null depending on timing, and calling collectAsState() through a
    // nullable chain violates Compose's rule that composable calls happen unconditionally in the
    // same position every recomposition (a real bug here, since this screen recomposes on every
    // MQTT payload). backStackEntry.savedStateHandle is always non-null, so this is safe.
    val scrollToGroupId by backStackEntry.savedStateHandle
        .getStateFlow<String?>("scrollToGroupId", null)
        .collectAsState()
    LaunchedEffect(scrollToGroupId) {
        val targetGroupId = scrollToGroupId ?: return@LaunchedEffect
        val groupIndex = config.groups.indexOfFirst { it.id == targetGroupId }
        if (groupIndex >= 0) {
            // Permit-join and pending-device banners occupy LazyColumn items ahead of the
            // groups, so the target index must account for however many are currently showing.
            // Each group itself contributes its own sticky header item plus - only when
            // expanded - a second item for its content (see the stickyHeader/item pair below),
            // so a preceding expanded group must count as 2, not 1: undercounting here (as an
            // earlier version did, counting exactly 1 per preceding group) lands the scroll
            // short of the real target by one item per expanded group in between - with several
            // expanded groups above the target, that shortfall was enough to look like it
            // scrolled to the top of the screen instead.
            var targetIndex = config.brokers.size + config.pendingAutoConfigDevices.size
            for (i in 0 until groupIndex) {
                targetIndex += if (config.groups[i].collapsed) 1 else 2
            }
            listState.requestScrollToItem(targetIndex)
        }
        backStackEntry.savedStateHandle.set<String?>("scrollToGroupId", null)
    }
    // Deliberately NOT unwrapped via "by" - HomeScreen never reads .value directly, only passes
    // the State object down to PanelTile/ClusterCard, which each do their own narrowly-scoped
    // derivedStateOf read. Reading .value here would subscribe HomeScreen's whole composable
    // scope to EVERY MQTT message on EVERY topic (latestPayloads' identity changes on each one) -
    // previously, one sensor reporting recomposed every tile on the dashboard, not just its own.
    val payloadsState = app.connectionManager.latestPayloads.collectAsState()
    val timestampsState = app.connectionManager.latestPayloadTimestamps.collectAsState()

    // Ticks every second so "N seconds ago" counts up smoothly, and resets automatically when a
    // fresher timestamp arrives since ageText always re-derives from whichever is most recent.
    //
    // Kept as a State object, not unwrapped via "by", for the same reason as payloadsState/
    // timestampsState above - so each ClusterCard's own derivedStateOf reads .value itself,
    // instead of HomeScreen's whole scope re-running every second regardless of whether any
    // cluster's age text actually changed.
    val nowMillisState = remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000.milliseconds)
            nowMillisState.longValue = System.currentTimeMillis()
        }
    }

    // Nothing works without a broker - send the user to Welcome rather than showing an unusable
    // empty Home. Gated on isLoaded so this doesn't fire during the brief window while
    // ConfigRepository's on-disk config is still loading (config.value is momentarily empty until
    // then) - without that, a user with real brokers could get bounced to Welcome on cold start.
    val isConfigLoaded by app.configRepository.isLoaded.collectAsState()
    LaunchedEffect(config.brokers.isEmpty(), isConfigLoaded) {
        if (isConfigLoaded && config.brokers.isEmpty()) {
            navController.navigate("welcome")
        }
    }

    var pendingGroupDelete by remember { mutableStateOf<String?>(null) }
    var pendingClusterDelete by remember { mutableStateOf<PendingClusterDelete?>(null) }
    var pendingClusterDuplicate by remember { mutableStateOf<PendingClusterDuplicate?>(null) }
    var duplicateTopicText by remember { mutableStateOf("") }
    var duplicateClusterNameText by remember { mutableStateOf("") }
    var pendingClusterRetopic by remember { mutableStateOf<PendingClusterRetopic?>(null) }
    var retopicNewTopicText by remember { mutableStateOf("") }
    var pendingValueEdit by remember { mutableStateOf<Panel.Sensor?>(null) }
    var valueEditText by remember { mutableStateOf("") }
    // Shared by every PanelTile (standalone or inside a ClusterCard) - reads the sensor's current
    // raw value fresh rather than trusting any composed/cached display string, so the dialog
    // prefills with what's actually retained right now.
    val onEditSensorValue: (Panel.Sensor) -> Unit = { sensorPanel ->
        val currentPayload = app.connectionManager.latestPayloads.value["${sensorPanel.brokerId}|${sensorPanel.topic}"]
        valueEditText = currentPayload?.let { JsonPath.extract(it, sensorPanel.jsonPath) } ?: ""
        pendingValueEdit = sensorPanel
    }
    var renamingGroup by remember { mutableStateOf<PanelGroup?>(null) }
    var renameText by remember { mutableStateOf("") }
    // Confirmation step for the "force upload" action on the group edit dialog - kept as its own
    // pending state (rather than a button directly inside that dialog) so the destructive-ish
    // "overwrite whatever's on the broker" warning gets its own explicit confirm/cancel.
    var pendingForceRepublishGroup by remember { mutableStateOf<PanelGroup?>(null) }
    // "Retopic All Clusters" on the group edit dialog - retopicGroupTopicPrefix's own UI, for
    // moving every cluster on one old topic namespace in a group (e.g. after splitting a Zigbee
    // network) without repeating the single-cluster "Change topic" dialog per cluster.
    var pendingGroupRetopic by remember { mutableStateOf<PanelGroup?>(null) }
    var groupRetopicOldTopicText by remember { mutableStateOf("") }
    var groupRetopicNewTopicText by remember { mutableStateOf("") }

    var showClusterSearch by remember { mutableStateOf(false) }
    var clusterSearchQuery by remember { mutableStateOf("") }
    // Filters the dashboard itself down to only clusters/standalone panels that haven't reported
    // in the last hour, rather than opening a separate dialog for it - toggled by its own FAB.
    // rememberSaveable, not remember - this is a deliberate, user-set filter mode, not transient
    // dialog state, so it should survive a screen rotation (which fully recreates the Activity,
    // since this app doesn't handle configChanges) rather than silently reverting to "off".
    var showOnlyStaleClusters by rememberSaveable { mutableStateOf(false) }
    // Single Permit Join bar for the whole screen, regardless of how many brokers/base topics are
    // configured - tapping its text opens a dialog to pick which (broker, base topic) to act on
    // and, once picked, which router to extend joining through. Replaces both the old one-bar-
    // per-broker layout and the "permit join via" section that used to live on the broker edit
    // screen - see PermitJoinDialog's own doc.
    var showPermitJoinDialog by remember { mutableStateOf(false) }
    // Which (broker, base topic) pair - by index into the flattened list built the same way in
    // both the bar and the dialog - the bar's own switch/countdown act on directly, without
    // opening the dialog. Selecting a different base topic inside the dialog updates this too, so
    // the bar always reflects whichever topic/router was picked last. rememberSaveable so it
    // survives rotation the same as showOnlyStaleClusters above, rather than always resetting back
    // to the first topic.
    var permitJoinTopicIndex by rememberSaveable { mutableIntStateOf(0) }

    // Undo prompt for an accidental tile/cluster/group drag. previousGroups is a full snapshot of
    // config.groups taken right before the drag's own mutation, restored wholesale on Undo rather
    // than trying to compute the inverse of each move - simpler, and correct for every move type
    // (reorder, pop-out, cross-group) at once. [repushIfUndone] re-publishes whatever retained MQTT
    // state the original move already pushed (e.g. an order_version or cluster override), using the
    // pre-move values it closed over - without it, a later device reconcile could silently redo the
    // very change Undo just reverted locally.
    val snackbarHostState = remember { SnackbarHostState() }
    val undoCoroutineScope = rememberCoroutineScope()
    val showUndoSnackbar: (String, List<PanelGroup>, () -> Unit) -> Unit =
        { message, previousGroups, repushIfUndone ->
            val seconds = app.configRepository.config.value.undoToastSeconds
            if (seconds > 0) {
                undoCoroutineScope.launch {
                    val result = withTimeoutOrNull((seconds * 1000L).milliseconds) {
                        snackbarHostState.showSnackbar(
                            message = message,
                            actionLabel = "Undo",
                            duration = SnackbarDuration.Indefinite
                        )
                    }
                    if (result == SnackbarResult.ActionPerformed) {
                        app.configRepository.update { it.copy(groups = previousGroups) }
                        repushIfUndone()
                    }
                }
            }
        }

    // Window-space bounds of the LazyColumn's own viewport, captured once below via
    // onGloballyPositioned on its modifier. Used by both computeNearest*Key functions to ignore
    // stale entries in groupCenters/clusterBounds belonging to items that have since scrolled
    // out of composition (LazyColumn disposes off-screen items, so their cached centre just
    // freezes at wherever it last was rather than updating - without this filter, a drag could
    // "snap" to one of those long-stale positions and look like it dropped somewhere random) -
    // and by the auto-scroll effect below to know when a drag has neared the top/bottom edge.
    var viewportBoundsInWindow by remember { mutableStateOf<Rect?>(null) }
    // The LazyColumn's own coordinates, for converting the shared cluster/group drag detector's
    // local touch position into window space - see that detector's own comment for why it lives
    // on the LazyColumn itself rather than on each item.
    var listCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val density = LocalDensity.current
    // How far beyond the viewport's edges a cached centre is still trusted - generous enough that
    // an item just barely scrolled out (or about to scroll in) still counts, but far enough stale
    // entries from earlier in the session don't.
    val staleCenterMarginPx = with(density) { 200.dp.toPx() }
    fun isWithinTrustedBounds(center: Offset): Boolean {
        val bounds = viewportBoundsInWindow ?: return true
        return center.y in (bounds.top - staleCenterMarginPx)..(bounds.bottom + staleCenterMarginPx)
    }

    // Current drag's touch point, in WINDOW coordinates - shared by both group and cluster drags
    // (only one is ever active at a time). Deliberately NOT tracked by accumulating each onDrag
    // tick's local dragAmount, which is how this was first written and which broke once auto-
    // scroll was added: change.position is reported relative to the pointerInput node's *local*
    // layout frame, and that frame itself moves every time the list auto-scrolls underneath the
    // still-held finger. Summing per-tick local deltas across a frame that's simultaneously
    // shifting silently bakes the scroll amount into the running total on top of genuine finger
    // movement - confirmed on-device via logging, where the local position reported mid-drag
    // reached several thousand pixels off-screen and the accumulated total swung by more than a
    // screen height for an ordinary drag, making nearest-match hunt for a target thousands of
    // pixels away from every real candidate (so nothing but the dragged item itself ever "won").
    // localToWindow sidesteps all of this: converting against the *current* actual layout every
    // time yields a true window-space position regardless of how much has scrolled in between,
    // with no running total to corrupt.
    // Used directly (uncalibrated) as the "current position" for nearest-match against every
    // other item's centre - deliberately NOT offset to track "the dragged card's own centre,
    // shifted by however far the finger has moved" (an earlier version tried that, matching this
    // feature's original pre-rewrite behaviour); on-device testing showed that made the
    // highlighted drop target feel disconnected from the actual finger position whenever the
    // touch didn't start exactly at the card's centre (e.g. grabbing its caption strip near the
    // top of a multi-row cluster) - a fixed few-hundred-pixel gap between where the border lit up
    // and where the finger visibly was. Using the raw touch point keeps the highlight tied to
    // whatever's actually under the finger, which is what a user expects from a drag.
    var dragTouchWindowPos by remember { mutableStateOf(Offset.Zero) }
    // Updated on every onDragStart/onDrag tick (group or cluster) - lets the watchdog below
    // detect a drag whose gesture has gone silent (no further onDrag calls) and force it to
    // end. Needed because of a confirmed-on-device Compose pointer-routing edge case: after
    // hovering a dragged cluster over a group's own header/label for a while, onDragEnd and
    // onDragCancel can both simply never fire for the rest of that gesture - the coroutine
    // just stops receiving pointer events, even though the app and its other coroutines (this
    // watchdog included) keep running completely normally. Without this, draggedClusterKey
    // stays set forever - the drop-target border included - recoverable only by restarting
    // the app, since nothing else ever clears it.
    var lastDragTickAtMs by remember { mutableLongStateOf(0L) }
    // TEMPORARY diagnostic - when the auto-scroll loop last actually dispatched a scroll delta,
    // so the watchdog's log line below can say whether the gesture died while auto-scroll was
    // active. Remove once the freeze is root-caused.
    var lastAutoScrollAtMs by remember { mutableLongStateOf(0L) }

    // Group drag-to-reorder state, shared across all groups (only one dragged at a time). Groups
    // vary wildly in height (collapsed, cluster count), so a uniform row-height division won't
    // work - instead this uses the same position-based nearest-match approach as cluster
    // dragging: each group's measured centre (via onGloballyPositioned) compared against the
    // current drag position, re-read fresh at both onDrag and onDragEnd.
    var draggedGroupId by remember { mutableStateOf<String?>(null) }
    var draggedToGroupId by remember { mutableStateOf<String?>(null) }
    val groupCenters = remember { mutableStateMapOf<String, Offset>() }
    // LayoutCoordinates for each group header's drag-handle node, keyed by group id - used only
    // to convert onDrag's local touch position into window space (see dragTouchWindowPos above).
    // Plain (non-Compose-state) map: written every layout pass but read only from inside these
    // gesture callbacks, so it doesn't need to be observable/trigger recomposition.
    val groupHeaderCoordinates = remember { mutableMapOf<String, LayoutCoordinates>() }
    fun computeNearestGroupKey(draggedId: String, currentPosition: Offset): String? {
        return groupCenters.entries
            .filter { (key, center) -> key == draggedId || isWithinTrustedBounds(center) }
            .minByOrNull { (_, center) -> (center - currentPosition).getDistance() }
            ?.key
    }

    // Cluster drag-to-move/reorder state, shared across *all* groups (only one cluster dragged
    // at a time) - lets a cluster be dropped either onto another cluster in the same group
    // (reorders it there) or onto a cluster/header in a different group (moves the whole
    // cluster there, inserted at the exact cluster/header hovered over).
    // Keys are "<groupId>::<clusterKey>", clusterKey being a clusterName or "__header__" for a
    // group's own header (so an otherwise-empty group is still a valid drop target) - group-
    // scoped rather than by clusterKey alone, since two different groups can share a cluster name.
    var draggedClusterKey by remember { mutableStateOf<String?>(null) }
    var draggedToClusterKey by remember { mutableStateOf<String?>(null) }
    // Whole-card window-space bounds, not just a centre point - a dragged cluster should register
    // as "over" a target the moment the finger is anywhere above its card, not only once it nears
    // that card's exact centre.
    val clusterBounds = remember { mutableStateMapOf<String, Rect>() }
    // See groupHeaderCoordinates above - same purpose, for each cluster's caption row.
    val clusterCaptionCoordinates = remember { mutableMapOf<String, LayoutCoordinates>() }
    // Distance from a point to the nearest point ON or IN the rect - zero anywhere inside it,
    // rather than growing the moment you're off its exact centre. A first attempt at this used
    // "does any rect contain the point, else fall back to nearest centre" as two separate passes,
    // but that broke on-device: whichever stale/off-screen rect happened to be first in the map's
    // iteration order and contain the point won outright, with no comparison against how good a
    // fit any *other* candidate was - so hovering a target cluster sometimes highlighted nothing,
    // and releasing there could resolve to a wrong, unrelated group entirely. A single distance
    // metric compared via one minByOrNull (same shape as the original nearest-centre code) keeps
    // the match always the genuinely closest candidate, while still being zero - and therefore an
    // automatic win - anywhere inside the hovered card, not just at its centre.
    fun Rect.distanceTo(point: Offset): Float {
        val dx = when {
            point.x < left -> left - point.x
            point.x > right -> point.x - right
            else -> 0f
        }
        val dy = when {
            point.y < top -> top - point.y
            point.y > bottom -> point.y - bottom
            else -> 0f
        }
        return kotlin.math.hypot(dx, dy)
    }
    fun computeNearestClusterKey(draggedKey: String, currentPosition: Offset): String? {
        return clusterBounds.entries
            .filter { (key, bounds) -> key == draggedKey || isWithinTrustedBounds(bounds.center) }
            .minByOrNull { (_, bounds) -> bounds.distanceTo(currentPosition) }
            ?.key
    }

    // Panel drag-to-reorder/merge state, shared globally the same way cluster/group drag state is
    // above - and for the same reason: this needs to live on the shared top-level detector rather
    // than a per-tile one, so a drag that needs the list to auto-scroll to reach a distant cluster
    // doesn't die partway through the same way a per-item detector did for clusters/groups.
    var draggedPanelId by remember { mutableStateOf<String?>(null) }
    // The group the dragged panel currently belongs to - needed at drop time to call the
    // repository regardless of which group's content the finger ends up over.
    var draggedPanelFromGroupId by remember { mutableStateOf<String?>(null) }
    // Live target index within the dragged panel's OWN cluster (-1 when not applicable, e.g. a
    // merge into a different cluster, or the panel has no cluster of its own) - same semantics
    // as the old per-ClusterCard draggedToIndex, just hoisted so the top-level detector can set
    // it regardless of which specific ClusterCard actually renders that index's highlight.
    var draggedToPanelIndex by remember { mutableIntStateOf(-1) }
    // True once the drag has left its own cluster's card with no other cluster's card to merge
    // into either - released there, the panel pops out into its own new cluster, same as before.
    var draggedPanelWillPopOut by remember { mutableStateOf(false) }
    // Window-space bounds of every currently-composed panel tile (standalone or within a
    // cluster), keyed by panel id - same shape/purpose as clusterBounds, used to work out which
    // panel a long-press grabbed and, during a within-cluster reorder, which sibling tile the
    // drag is nearest to.
    val panelBounds = remember { mutableStateMapOf<String, Rect>() }

    // Re-derives, from the live config and the drag's current window position, which of the
    // three panel-drop outcomes currently applies - called on every onDrag tick for live
    // highlighting, and again at onDragEnd (rather than trusting onDrag's last value, for the
    // same reason the cluster/group drags recompute fresh there) to decide what actually commits:
    //  - still over its own cluster's card -> reorder among siblings (draggedToPanelIndex set,
    //    the nearest sibling tile's own bounds used the same distance metric as cluster dragging)
    //  - over a *different* named cluster's card -> merge into it (draggedToClusterKey set)
    //  - neither (including a standalone panel, which has no "own cluster" card to begin with) ->
    //    pop out into its own new cluster, same as dragging it past its old cluster's edge always
    //    did - a no-op if it's already standalone.
    fun updatePanelDragTargets() {
        val panelId = draggedPanelId ?: return
        val fromGroupId = draggedPanelFromGroupId ?: return
        val panel = app.configRepository.config.value.groups
            .find { it.id == fromGroupId }?.panels?.find { it.id == panelId } ?: return
        val ownClusterName = panel.clusterName
        val ownClusterKey = ownClusterName.takeIf { it.isNotBlank() }?.let { "$fromGroupId::$it" }
        val ownRect = ownClusterKey?.let { clusterBounds[it] }
        if (ownRect?.contains(dragTouchWindowPos) == true) {
            draggedToClusterKey = null
            draggedPanelWillPopOut = false
            val siblings = app.configRepository.config.value.groups
                .find { it.id == fromGroupId }?.panels
                ?.filter { it.clusterName == ownClusterName }
                ?.sortedBy { it.displayOrder }
                .orEmpty()
            val nearestSiblingId = siblings
                .mapNotNull { p -> panelBounds[p.id]?.let { p.id to it.distanceTo(dragTouchWindowPos) } }
                .minByOrNull { (_, distance) -> distance }
                ?.first
            draggedToPanelIndex = siblings.indexOfFirst { it.id == nearestSiblingId }
        } else {
            val mergeTarget = clusterBounds.entries.firstOrNull { (key, rect) ->
                key != ownClusterKey && key.substringAfter("::") != "__header__" &&
                    rect.contains(dragTouchWindowPos)
            }
            draggedToPanelIndex = -1
            if (mergeTarget != null) {
                draggedToClusterKey = mergeTarget.key
                draggedPanelWillPopOut = false
            } else {
                draggedToClusterKey = null
                draggedPanelWillPopOut = ownClusterKey != null
            }
        }
    }

    // One BringIntoViewRequester per currently-composed cluster card (keyed the same way as
    // clusterBounds), so a completed move can precisely scroll the moved cluster itself into
    // view - not just its group's header - once it's settled into its new spot. Set alongside
    // (not instead of) the existing scrollToGroupId mechanism below: that coarse scroll is what
    // actually gets a far-off-screen destination group's content composed in the first place
    // (a LazyColumn item outside its composition window never runs, so its cluster cards - and
    // their requesters here - wouldn't exist yet for bringIntoView to target); this then fine-
    // tunes the final scroll position once the specific card has mounted.
    val clusterBringIntoViewRequesters = remember { mutableStateMapOf<String, BringIntoViewRequester>() }
    var pendingScrollToClusterKey by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(pendingScrollToClusterKey) {
        val targetKey = pendingScrollToClusterKey ?: return@LaunchedEffect
        // The target card may not have composed yet (e.g. its group just got expanded, or the
        // coarse group-level scroll above is still landing) - poll briefly rather than give up
        // on the first miss.
        var requester: BringIntoViewRequester? = null
        var attempts = 0
        while (requester == null && attempts < 30) {
            requester = clusterBringIntoViewRequesters[targetKey]
            if (requester == null) {
                delay(16.milliseconds)
                attempts++
            }
        }
        requester?.bringIntoView()
        pendingScrollToClusterKey = null
    }

    // Auto-scrolls the LazyColumn while a group or cluster drag's virtual position nears the top/
    // bottom edge of the viewport - without this, a drag aimed at a group scrolled out of view had
    // no way to reach it (the list never moved), so users either gave up short of the target or
    // kept dragging past the screen edge, at which point computeNearest*Key above would match
    // whatever stale/off-screen entry happened to be numerically closest, dropping the cluster
    // into an unrelated group. Speed ramps up the closer the drag sits to the edge.
    val isDraggingAnything = draggedGroupId != null || draggedClusterKey != null || draggedPanelId != null
    val autoScrollEdgePx = with(density) { 72.dp.toPx() }
    val autoScrollMaxSpeedPx = with(density) { 22.dp.toPx() }
    LaunchedEffect(isDraggingAnything) {
        if (!isDraggingAnything) return@LaunchedEffect
        while (isActive) {
            // Watchdog: a genuine active drag ticks onDrag ~60 times/sec, so 1.5s of silence
            // reliably means the gesture has gone dead - see lastDragTickAtMs's own comment for
            // why this is needed (a confirmed-on-device Compose pointer-routing edge case where
            // onDragEnd/onDragCancel can simply never fire again for the rest of a gesture,
            // otherwise leaving this state - and the drop-target border with it - stuck forever
            // with no recovery short of restarting the app). This coroutine is what's still
            // reliably running to notice and recover from that; the drag itself is not.
            if (System.currentTimeMillis() - lastDragTickAtMs > 1_500) {
                Log.w("Z2mDash", "Drag watchdog: gesture went silent, forcing drag state to reset " +
                    "(msSinceLastAutoScroll=${System.currentTimeMillis() - lastAutoScrollAtMs})")
                draggedGroupId = null
                draggedToGroupId = null
                draggedClusterKey = null
                draggedToClusterKey = null
                draggedPanelId = null
                draggedPanelFromGroupId = null
                draggedToPanelIndex = -1
                draggedPanelWillPopOut = false
                return@LaunchedEffect
            }
            val bounds = viewportBoundsInWindow
            val currentY = dragTouchWindowPos.y
            if (bounds != null) {
                val distanceFromTop = currentY - bounds.top
                val distanceFromBottom = bounds.bottom - currentY
                val scrollDelta = when {
                    distanceFromTop < autoScrollEdgePx ->
                        -autoScrollMaxSpeedPx * (1f - (distanceFromTop.coerceAtLeast(0f) / autoScrollEdgePx))
                    distanceFromBottom < autoScrollEdgePx ->
                        autoScrollMaxSpeedPx * (1f - (distanceFromBottom.coerceAtLeast(0f) / autoScrollEdgePx))
                    else -> 0f
                }
                // dispatchRawDelta, not scrollBy - scrollBy goes through ScrollableState.scroll(),
                // which cancels any other "ongoing scroll" at an equal-or-higher priority, and
                // the active long-press drag gesture apparently gets caught up in that even
                // though it never calls scroll() itself (both live under the same LazyColumn's
                // nested-scroll machinery). With scrollBy here, on-device testing showed the
                // drag's own pointer-event stream getting cut mid-gesture once auto-scroll
                // kicked in near an edge - onDrag stopped firing (frozen on whichever cluster it
                // last highlighted) while onDragEnd/onDragCancel never ran either, leaving
                // draggedClusterKey stuck set long after the finger lifted. dispatchRawDelta is
                // documented to bypass that mutual-exclusion path entirely and never touch an
                // ongoing gesture, which is exactly what's needed here.
                if (scrollDelta != 0f) {
                    lastAutoScrollAtMs = System.currentTimeMillis()
                    listState.dispatchRawDelta(scrollDelta)
                }
            }
            delay(16.milliseconds)
        }
    }

    // Both standalone panels and cluster-card panels lay out as an exact 3-column grid. Tile
    // width is capped rather than scaled proportionally to screen size, since a tablet's shorter
    // dimension is still much bigger than a phone's - an uncapped tile would leave no room for a
    // second cluster even on a wide screen. A fixed cap keeps clusters a consistent size, so the
    // manual row-packing below (packedRows) can fit as many side-by-side as actually fit.
    // LocalConfiguration (not LocalWindowInfo) is Android's recommended choice - LocalWindowInfo
    // is primarily for Compose Multiplatform and has documented reliability quirks on Android.
    // The IDE's "ConfigurationScreenWidthHeight" nudge towards LocalWindowInfo is intentionally
    // suppressed rather than followed, since containerSize returns raw pixels (not dp) and would
    // need extra density conversion for no behavioural benefit here.
    val configuration = LocalConfiguration.current
    @Suppress("ConfigurationScreenWidthHeight")
    val screenWidthDp = configuration.screenWidthDp.dp
    @Suppress("ConfigurationScreenWidthHeight")
    val screenHeightDp = configuration.screenHeightDp.dp
    val columnsPerRow = 3
    val standaloneTileWidth = run {
        val referenceWidthDp = minOf(screenWidthDp, screenHeightDp)
        val groupHorizontalPadding = 12.dp * 2
        val gapsBetweenColumns = 8.dp * (columnsPerRow - 1)
        val proportionalWidth = (referenceWidthDp - groupHorizontalPadding - gapsBetweenColumns) / columnsPerRow
        minOf(proportionalWidth, config.tileWidthDp.dp)
    }
    // Scales tile text/icon/height/padding proportionally to actual rendered width (tracks
    // standaloneTileWidth, not the raw slider setting, so it stays correct when the cap above
    // kicks in). 110dp (AppConfig.tileWidthDp's default) maps to scale=1f; a TV tile shrunk for
    // screen density scales everything down together instead of keeping phone-sized text/icons
    // in a smaller box.
    val tileScale = standaloneTileWidth / 110.dp

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        floatingActionButton = {
            // On TV, every FAB below gets the same two treatments a D-pad remote actually needs:
            // full FloatingActionButton size instead of the small variant (a bigger, more legible
            // target from a couch - D-pad selection is a discrete focus step either way, but a
            // small target is harder to tell apart from its neighbours once focused), extra
            // spacing between them (easier to see which one currently has focus when they're not
            // packed tightly), and tvFocusIndicator's visible focus ring, which none of them had
            // before - without it there was no on-screen cue for which FAB (if any) was focused,
            // which is what made them "hard to select" in the first place, not a click that
            // didn't register once actually focused.
            val isTv = LocalIsTv.current
            val fabSpacing = if (isTv) 20.dp else 12.dp
            Column(horizontalAlignment = Alignment.End) {
                // Only worth showing once there's at least one named cluster to actually find -
                // a dashboard of only standalone tiles has nothing for this to search.
                if (config.groups.any { g -> g.panels.any { it.clusterName.isNotBlank() } }) {
                    if (isTv) {
                        FloatingActionButton(
                            onClick = { showClusterSearch = true },
                            modifier = Modifier.tvFocusIndicator()
                        ) {
                            Icon(Icons.Default.Search, contentDescription = "Search clusters")
                        }
                    } else {
                        SmallFloatingActionButton(onClick = { showClusterSearch = true }) {
                            Icon(Icons.Default.Search, contentDescription = "Search clusters")
                        }
                    }
                    Spacer(Modifier.height(fabSpacing))
                }
                // Only worth showing once there's at least one non-editable sensor to actually
                // watch for staleness - an editable panel is a fixed preference value, not a live
                // hardware reading, so it never genuinely "reports" and would just be noise here.
                if (config.groups.any { g -> g.panels.any { it is Panel.Sensor && !it.editable && it.topic.isNotBlank() } }) {
                    val staleToggleContainerColor = if (showOnlyStaleClusters) {
                        MaterialTheme.colorScheme.errorContainer
                    } else {
                        FloatingActionButtonDefaults.containerColor
                    }
                    val staleToggleContentDescription = if (showOnlyStaleClusters) {
                        "Showing only sensors not reporting recently - tap to show everything"
                    } else {
                        "Show only sensors not reporting recently"
                    }
                    if (isTv) {
                        FloatingActionButton(
                            onClick = { showOnlyStaleClusters = !showOnlyStaleClusters },
                            containerColor = staleToggleContainerColor,
                            modifier = Modifier.tvFocusIndicator()
                        ) {
                            Icon(Icons.Default.Warning, contentDescription = staleToggleContentDescription)
                        }
                    } else {
                        SmallFloatingActionButton(
                            onClick = { showOnlyStaleClusters = !showOnlyStaleClusters },
                            containerColor = staleToggleContainerColor
                        ) {
                            Icon(Icons.Default.Warning, contentDescription = staleToggleContentDescription)
                        }
                    }
                    Spacer(Modifier.height(fabSpacing))
                }
                // Only worth showing once there's more than one group to bulk-collapse - with
                // zero or one, per-group collapse (the header's own chevron) already covers it.
                if (config.groups.size > 1) {
                    val anyGroupExpanded = config.groups.any { !it.collapsed }
                    val collapseContentDescription = if (anyGroupExpanded) "Collapse all groups" else "Expand all groups"
                    val collapseIcon = if (anyGroupExpanded) Icons.Default.UnfoldLess else Icons.Default.UnfoldMore
                    if (isTv) {
                        FloatingActionButton(
                            onClick = { app.configRepository.setAllGroupsCollapsed(anyGroupExpanded) },
                            modifier = Modifier.tvFocusIndicator()
                        ) {
                            Icon(collapseIcon, contentDescription = collapseContentDescription)
                        }
                    } else {
                        SmallFloatingActionButton(
                            onClick = { app.configRepository.setAllGroupsCollapsed(anyGroupExpanded) }
                        ) {
                            Icon(collapseIcon, contentDescription = collapseContentDescription)
                        }
                    }
                    Spacer(Modifier.height(fabSpacing))
                }
                FloatingActionButton(
                    onClick = { navController.navigate("addGroup") },
                    modifier = Modifier.tvFocusIndicator()
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Add group")
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
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
                    // config.groups briefly looks empty while ConfigRepository loads from disk -
                    // without this check, a user with real groups could see "add your first
                    // group" flash up on a slow cold start.
                    Text("Loading…", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text("Still reading your saved configuration.")
                }
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.padding(padding).fillMaxSize()
                .onGloballyPositioned { coordinates ->
                    listCoordinates = coordinates
                    viewportBoundsInWindow = coordinates.boundsInWindow()
                }
                // Group, cluster AND panel drag-to-reorder/merge all share ONE long-press-drag
                // detector here, on the LazyColumn itself, rather than one on each group header/
                // cluster caption row/panel tile as originally written. Confirmed on-device: a
                // per-item detector's gesture reliably dies partway through any drag long enough
                // to trigger auto-scroll - the drag watchdog's own diagnostic logging showed the
                // freeze landing within milliseconds of the auto-scroll effect's last
                // dispatchRawDelta call, every time. The LazyColumn's own node never moves or
                // resizes when ITS CONTENT scrolls (only the items inside it do), so a detector
                // anchored there - rather than on one of those items - never has its underlying
                // layout node disturbed by auto-scroll, and survives for the drag's full duration.
                // Hit-testing which item (if any) was actually grabbed happens manually below,
                // against the same bounds/coordinates maps every item already keeps up to date
                // for the drop-target highlighting.
                .pointerInput(Unit) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { startLocalPos ->
                            val coords = listCoordinates ?: return@detectDragGesturesAfterLongPress
                            val windowPos = coords.localToWindow(startLocalPos)
                            val hitPanelId = panelBounds.entries
                                .firstOrNull { (_, b) -> b.contains(windowPos) }
                                ?.key
                            val hitClusterKey = if (hitPanelId == null) {
                                clusterCaptionCoordinates.entries
                                    .firstOrNull { (_, c) -> c.boundsInWindow().contains(windowPos) }
                                    ?.key
                            } else null
                            val hitGroupId = if (hitPanelId == null && hitClusterKey == null) {
                                groupHeaderCoordinates.entries
                                    .firstOrNull { (_, c) -> c.boundsInWindow().contains(windowPos) }
                                    ?.key
                            } else null
                            when {
                                hitPanelId != null -> {
                                    val fromGroupId = app.configRepository.config.value.groups
                                        .find { g -> g.panels.any { it.id == hitPanelId } }?.id
                                    if (fromGroupId != null) {
                                        draggedPanelId = hitPanelId
                                        draggedPanelFromGroupId = fromGroupId
                                        draggedToPanelIndex = -1
                                        draggedPanelWillPopOut = false
                                        draggedToClusterKey = null
                                        dragTouchWindowPos = windowPos
                                        lastDragTickAtMs = System.currentTimeMillis()
                                    }
                                }
                                hitClusterKey != null -> {
                                    draggedClusterKey = hitClusterKey
                                    draggedToClusterKey = hitClusterKey
                                    dragTouchWindowPos = windowPos
                                    lastDragTickAtMs = System.currentTimeMillis()
                                }
                                hitGroupId != null -> {
                                    draggedGroupId = hitGroupId
                                    draggedToGroupId = hitGroupId
                                    dragTouchWindowPos = windowPos
                                    lastDragTickAtMs = System.currentTimeMillis()
                                }
                                else -> Unit
                            }
                        },
                        onDragEnd = {
                            if (draggedClusterKey != null) {
                              try {
                                val fromKey = draggedClusterKey
                                // Recomputed fresh here rather than trusting onDrag's last value -
                                // a quick drag-and-release might not produce enough callbacks for
                                // a still-settling position to have self-corrected by release time.
                                val toKey = fromKey?.let { computeNearestClusterKey(it, dragTouchWindowPos) }
                                if (fromKey != null && toKey != null && fromKey != toKey) {
                                    val fromGroupId = fromKey.substringBefore("::")
                                    val fromClusterName = fromKey.substringAfter("::")
                                    val toGroupId = toKey.substringBefore("::")
                                    val toClusterKey = toKey.substringAfter("::")
                                    if (toGroupId == fromGroupId) {
                                        // Read fresh from the live config rather than closing over
                                        // orderedClusters/group - recomposition may have moved on
                                        // without it by the time the drag ends (same class of bug
                                        // fixed for Terminal's message list).
                                        val currentGroup = app.configRepository.config.value
                                            .groups.find { it.id == fromGroupId }
                                        if (currentGroup != null) {
                                            val panelsByClusterKey = currentGroup.panels
                                                .groupBy { it.clusterName.ifBlank { "__single__${it.id}" } }
                                            val currentOrder = panelsByClusterKey.entries
                                                .sortedBy { (_, ps) -> ps.minOf { it.displayOrder } }
                                                .map { (key, _) -> key }
                                            val fromIndex = currentOrder.indexOf(fromClusterName)
                                            // "__header__" (the group's own header - see headerClusterKey
                                            // above) never appears in currentOrder, so indexOf would return
                                            // -1 and silently no-op the whole drop below - hovering the
                                            // dragged cluster over its own group's label/header is a very
                                            // natural way to aim for "put it first", so treat that
                                            // specifically as index 0 rather than letting it fail quietly.
                                            val toIndex = if (toClusterKey == "__header__") {
                                                0
                                            } else {
                                                currentOrder.indexOf(toClusterKey)
                                            }
                                            if (fromIndex >= 0 && toIndex >= 0) {
                                                val previousGroups = app.configRepository.config.value.groups
                                                val reordered = currentOrder.toMutableList()
                                                reordered.removeAt(fromIndex)
                                                reordered.add(toIndex, fromClusterName)
                                                app.configRepository.reorderClustersInGroup(fromGroupId, reordered)
                                                pushGroupOrderUpdatesForClusters(app, reordered, currentGroup.panels)
                                                publishAppTopicForClusterIfMissing(app, fromGroupId, fromClusterName)
                                                // Names the group in the confirmation and scrolls straight to it -
                                                // otherwise a cluster reordered off the bottom of a tall group (or
                                                // past whatever's currently on screen) just seems to vanish on
                                                // release, with no indication of where it actually landed.
                                                showUndoSnackbar(
                                                    "Moved \"$fromClusterName\" within \"${currentGroup.name}\"",
                                                    previousGroups
                                                ) {
                                                    pushGroupOrderUpdatesForClusters(app, currentOrder, currentGroup.panels)
                                                }
                                                backStackEntry.savedStateHandle["scrollToGroupId"] = fromGroupId
                                                pendingScrollToClusterKey = fromKey
                                            }
                                        }
                                    } else {
                                        // Dropped onto a different group entirely (another group's
                                        // cluster, or its header) - move the whole cluster there,
                                        // inserted at the exact cluster/header hovered over rather
                                        // than always at the end.
                                        val liveGroups = app.configRepository.config.value.groups
                                        val movedPanelIds = liveGroups.find { it.id == fromGroupId }
                                            ?.panels?.filter { it.clusterName == fromClusterName }
                                            ?.map { it.id } ?: emptyList()
                                        val oldGroupName = liveGroups.find { it.id == fromGroupId }?.name
                                        val newGroupName = liveGroups.find { it.id == toGroupId }?.name
                                        app.configRepository.moveClusterToGroup(
                                            fromGroupId, toGroupId, fromClusterName,
                                            insertBeforeClusterKey = toClusterKey
                                        )
                                        if (newGroupName != null) {
                                            pushGroupMoveForAutoConfiguredDevices(app, movedPanelIds, newGroupName)
                                        }
                                        publishAppTopicForClusterIfMissing(app, toGroupId, fromClusterName)
                                        // Names the destination group in the confirmation, expands it if it
                                        // was collapsed, and scrolls straight to it - a cross-group move is
                                        // otherwise invisible: the cluster disappears from where it was
                                        // dragged from with nothing on screen showing where it went.
                                        showUndoSnackbar(
                                            "Moved \"$fromClusterName\" to \"${newGroupName ?: "another group"}\"",
                                            liveGroups
                                        ) {
                                            if (oldGroupName != null) {
                                                pushGroupMoveForAutoConfiguredDevices(app, movedPanelIds, oldGroupName)
                                            }
                                        }
                                        app.configRepository.setGroupCollapsed(toGroupId, false)
                                        backStackEntry.savedStateHandle["scrollToGroupId"] = toGroupId
                                        pendingScrollToClusterKey = "$toGroupId::$fromClusterName"
                                    }
                                }
                              } catch (c: CancellationException) {
                                draggedClusterKey = null
                                draggedToClusterKey = null
                                throw c
                              } catch (e: Exception) {
                                // Guards against draggedClusterKey getting stuck set (leaving the
                                // dimmed/bordered drag visuals frozen on screen) if anything above
                                // throws - state still resets via finally either way.
                                Log.e("Z2mDash", "Cluster drag drop failed", e)
                              } finally {
                                draggedClusterKey = null
                                draggedToClusterKey = null
                              }
                            } else if (draggedGroupId != null) {
                                val fromId = draggedGroupId
                                // Recomputed fresh here rather than trusting onDrag's last value -
                                // a quick drag-and-release might not produce enough callbacks for
                                // a still-settling position to have self-corrected by release time.
                                val toId = fromId?.let { computeNearestGroupKey(it, dragTouchWindowPos) }
                                if (fromId != null && toId != null && fromId != toId) {
                                    val previousGroups = app.configRepository.config.value.groups
                                    val toIndex = previousGroups.indexOfFirst { it.id == toId }
                                    if (toIndex >= 0) {
                                        val movedGroupName = previousGroups.find { it.id == fromId }?.name ?: "Group"
                                        app.configRepository.moveGroupToIndex(fromId, toIndex + 1)
                                        val reorderedGroups = app.configRepository.config.value.groups
                                        pushDashboardGroupOrderUpdates(app, reorderedGroups)
                                        showUndoSnackbar("Moved \"$movedGroupName\"", previousGroups) {
                                            pushDashboardGroupOrderUpdates(app, previousGroups)
                                        }
                                    }
                                }
                                draggedGroupId = null
                                draggedToGroupId = null
                            } else if (draggedPanelId != null) {
                              try {
                                updatePanelDragTargets()
                                val panelId = draggedPanelId
                                val fromGroupId = draggedPanelFromGroupId
                                val mergeTargetKey = draggedToClusterKey
                                val toPanelIndex = draggedToPanelIndex
                                val willPopOut = draggedPanelWillPopOut
                                val panel = if (panelId != null && fromGroupId != null) {
                                    app.configRepository.config.value.groups
                                        .find { it.id == fromGroupId }?.panels?.find { it.id == panelId }
                                } else null
                                if (panel != null && fromGroupId != null) {
                                    when {
                                        mergeTargetKey != null -> {
                                            val toGroupId = mergeTargetKey.substringBefore("::")
                                            val toClusterName = mergeTargetKey.substringAfter("::")
                                            if (!(toGroupId == fromGroupId && toClusterName == panel.clusterName)) {
                                                val previousGroups = app.configRepository.config.value.groups
                                                app.configRepository.movePanelIntoCluster(
                                                    fromGroupId, panel.id, toGroupId, toClusterName
                                                )
                                                pushPanelClusterOverrideIfAutoConfigured(app, panel, toClusterName)
                                                publishAppTopicForClusterIfMissing(app, toGroupId, toClusterName)
                                                showUndoSnackbar("Moved \"${panel.label}\" into \"$toClusterName\"", previousGroups) {
                                                    pushPanelClusterOverrideIfAutoConfigured(app, panel, panel.clusterName)
                                                }
                                                app.configRepository.setGroupCollapsed(toGroupId, false)
                                                backStackEntry.savedStateHandle["scrollToGroupId"] = toGroupId
                                                pendingScrollToClusterKey = "$toGroupId::$toClusterName"
                                            }
                                        }
                                        willPopOut && panel.clusterName.isNotBlank() && panel.label.isNotBlank() -> {
                                            val siblingCount = app.configRepository.config.value.groups
                                                .find { it.id == fromGroupId }?.panels
                                                ?.count { it.clusterName == panel.clusterName } ?: 0
                                            if (siblingCount > 1) {
                                                val previousGroups = app.configRepository.config.value.groups
                                                app.configRepository.movePanelToOwnCluster(fromGroupId, panel.id, panel.label)
                                                pushPanelClusterOverrideIfAutoConfigured(app, panel, panel.label)
                                                publishAppTopicForClusterIfMissing(app, fromGroupId, panel.label)
                                                showUndoSnackbar("Moved \"${panel.label}\"", previousGroups) {
                                                    pushPanelClusterOverrideIfAutoConfigured(app, panel, panel.clusterName)
                                                }
                                            }
                                        }
                                        toPanelIndex >= 0 && panel.clusterName.isNotBlank() -> {
                                            val currentPanels = app.configRepository.config.value.groups
                                                .find { it.id == fromGroupId }?.panels
                                                ?.filter { it.clusterName == panel.clusterName }
                                                ?.sortedBy { it.displayOrder }
                                            if (currentPanels != null && toPanelIndex < currentPanels.size) {
                                                val targetPanelId = currentPanels[toPanelIndex].id
                                                val currentFromIndex = currentPanels.indexOfFirst { it.id == panel.id }
                                                val currentToIndex = currentPanels.indexOfFirst { it.id == targetPanelId }
                                                if (currentFromIndex >= 0 && currentToIndex >= 0 && currentFromIndex != currentToIndex) {
                                                    val previousGroups = app.configRepository.config.value.groups
                                                    val reordered = currentPanels.toMutableList()
                                                    val moved = reordered.removeAt(currentFromIndex)
                                                    reordered.add(currentToIndex, moved)
                                                    val orderedIds = reordered.map { it.id }
                                                    app.configRepository.reorderPanelsInCluster(fromGroupId, orderedIds)
                                                    pushOrderUpdateIfAutoConfigured(app, orderedIds, reordered)
                                                    publishAppTopicForClusterIfMissing(app, fromGroupId, panel.clusterName)
                                                    showUndoSnackbar("Moved \"${panel.label}\"", previousGroups) {
                                                        pushOrderUpdateIfAutoConfigured(
                                                            app, currentPanels.map { it.id }, currentPanels
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                              } catch (c: CancellationException) {
                                draggedPanelId = null
                                draggedPanelFromGroupId = null
                                draggedToPanelIndex = -1
                                draggedPanelWillPopOut = false
                                draggedToClusterKey = null
                                throw c
                              } catch (e: Exception) {
                                Log.e("Z2mDash", "Panel drag drop failed", e)
                              } finally {
                                draggedPanelId = null
                                draggedPanelFromGroupId = null
                                draggedToPanelIndex = -1
                                draggedPanelWillPopOut = false
                                draggedToClusterKey = null
                              }
                            }
                        },
                        onDragCancel = {
                            draggedGroupId = null
                            draggedToGroupId = null
                            draggedClusterKey = null
                            draggedToClusterKey = null
                            draggedPanelId = null
                            draggedPanelFromGroupId = null
                            draggedToPanelIndex = -1
                            draggedPanelWillPopOut = false
                        },
                        onDrag = { change, _ ->
                            val coords = listCoordinates
                            when {
                                draggedClusterKey != null -> {
                                    change.consume()
                                    lastDragTickAtMs = System.currentTimeMillis()
                                    if (coords != null) {
                                        dragTouchWindowPos = coords.localToWindow(change.position)
                                    }
                                    draggedToClusterKey = computeNearestClusterKey(draggedClusterKey!!, dragTouchWindowPos)
                                }
                                draggedGroupId != null -> {
                                    change.consume()
                                    lastDragTickAtMs = System.currentTimeMillis()
                                    if (coords != null) {
                                        dragTouchWindowPos = coords.localToWindow(change.position)
                                    }
                                    draggedToGroupId = computeNearestGroupKey(draggedGroupId!!, dragTouchWindowPos)
                                }
                                draggedPanelId != null -> {
                                    change.consume()
                                    lastDragTickAtMs = System.currentTimeMillis()
                                    if (coords != null) {
                                        dragTouchWindowPos = coords.localToWindow(change.position)
                                    }
                                    updatePanelDragTargets()
                                }
                                else -> Unit
                            }
                        }
                    )
                },
            // Extra bottom padding so the last group's trailing icons can scroll clear of the
            // FAB, which floats on top of content without reserving space for itself.
            contentPadding = PaddingValues(bottom = 96.dp)
        ) {
            if (config.brokers.isNotEmpty()) {
                item(key = "permitJoinBar") {
                    // Same flattening the dialog uses (see PermitJoinDialog's own allTopics), kept
                    // in lockstep by construction (both iterate config.brokers, then
                    // PermitJoin.parseBaseTopics per broker, in the same order) so
                    // permitJoinTopicIndex means the same pair in both places.
                    val allTopics = remember(config.brokers) {
                        config.brokers.flatMap { broker ->
                            PermitJoin.parseBaseTopics(broker.baseTopic).map { broker.id to it }
                        }
                    }
                    val activeTopic = allTopics.getOrNull(permitJoinTopicIndex)
                    val activeStatus by remember(activeTopic) {
                        derivedStateOf {
                            activeTopic?.let { (brokerId, baseTopic) ->
                                PermitJoin.status(payloadsState.value, brokerId, baseTopic, nowMillisState.longValue)
                            }
                        }
                    }
                    val statusText = when {
                        activeTopic == null -> "No brokers configured"
                        activeStatus?.isOn == true ->
                            "Open for ${PermitJoin.formatRemaining(activeStatus!!.remainingSeconds)} more"
                        else -> "Off – new Zigbee devices can't join"
                    }
                    PermitJoinBanner(
                        title = "Permit Join",
                        subtitle = if (allTopics.size > 1 && activeTopic != null) {
                            "${activeTopic.second} · $statusText"
                        } else {
                            statusText
                        },
                        isOn = activeStatus?.isOn == true,
                        onToggle = { enabled ->
                            activeTopic?.let { (brokerId, baseTopic) ->
                                val broker = config.brokers.find { it.id == brokerId }
                                val routerText = broker?.permitJoinDevices?.get(baseTopic).orEmpty()
                                val payload = PermitJoin.requestPayload(routerText, if (enabled) 254 else 0)
                                app.connectionManager.publish(brokerId, PermitJoin.requestTopic(baseTopic), payload)
                            }
                        },
                        onClick = { showPermitJoinDialog = true }
                    )
                }
            }
            items(config.pendingAutoConfigDevices, key = { "${it.brokerId}|${it.appConfigTopic}" }) { pending ->
                PendingDeviceBanner(
                    pending = pending,
                    onAdd = {
                        addPendingDevice(app, payloadsState.value, pending)
                        // Both actions mean the user handled this via the in-app banner, so the
                        // matching system notification (same deviceName-based ID) shouldn't linger.
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
                val headerClusterKey = "${group.id}::__header__"
                // Valid drop target for a cluster from *any* group, including this one's own -
                // dropped on its own group's header, the cluster moves to the front of that group
                // (see the "__header__" handling in the drop-end logic below) rather than being a
                // no-op, so this highlights for that case too.
                val isClusterDropTargetHeader = draggedClusterKey != null &&
                    draggedToClusterKey == headerClusterKey
                // Sticky so the group's name/controls stay reachable while scrolled deep into its
                // clusters. Wrapped in an opaque Surface since stickyHeader only pins position -
                // without an explicit background, content underneath would show through.
                stickyHeader(key = "${group.id}_header") {
                Surface(color = MaterialTheme.colorScheme.background, tonalElevation = 2.dp) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                        .onGloballyPositioned { coordinates ->
                            val bounds = coordinates.boundsInWindow()
                            groupCenters[group.id] = bounds.center
                            // Also a valid drop point for a dragged cluster (see headerClusterKey
                            // above) - lets a cluster be moved into an otherwise-empty group.
                            clusterBounds[headerClusterKey] = bounds
                        }
                        .alpha(if (isDraggingThisGroup) 0.5f else 1f)
                        .then(
                            if (isDropTargetGroup || isClusterDropTargetHeader) {
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
                                // Long-press-drag itself is handled by ONE detector on the LazyColumn
                                // as a whole (see its own modifier) rather than here - see that
                                // detector's comment for why a per-item detector doesn't work.
                                .onGloballyPositioned { groupHeaderCoordinates[group.id] = it },
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
                        // Panels sharing a non-blank clusterName render together in one card;
                        // blank-clusterName panels each get their own unique bucket to stay standalone.
                        val clusters = LinkedHashMap<String, MutableList<Panel>>()
                        group.panels.forEach { panel ->
                            val key = panel.clusterName.ifBlank { "__single__${panel.id}" }
                            clusters.getOrPut(key) { mutableListOf() }.add(panel)
                        }
                        // Sort clusters by lowest displayOrder (falls back to insertion order at
                        // the Int.MAX_VALUE default), and sort each cluster's panels too, so panels
                        // can be reordered *within* a cluster, not just relative to other clusters.
                        val orderedClusters = clusters.values
                            .map { bucket -> bucket.sortedBy { it.displayOrder } }
                            .sortedBy { bucket -> bucket.minOf { it.displayOrder } }
                        // When the "only sensors not reporting recently" toggle is on, clusters
                        // (and standalone tiles, each their own single-panel bucket above) that
                        // HAVE reported within the last hour are left out of row-packing/rendering
                        // entirely, rather than shown dimmed or in a separate dialog. Deliberately
                        // NOT wrapped in remember/derivedStateOf per bucket - calling those inside
                        // a plain .filter{} loop whose iteration count varies is a known Compose
                        // slot-alignment hazard without an explicit key() per item, which isn't
                        // available here. Reading payloadsState/timestampsState directly instead
                        // means every expanded group's content recomposes on every MQTT message
                        // while this toggle is on (unlike everywhere else on this screen), but only
                        // for as long as it's deliberately switched on.
                        val visibleClusters = if (!showOnlyStaleClusters) {
                            orderedClusters
                        } else {
                            val payloads = payloadsState.value
                            val timestamps = timestampsState.value
                            val nowMillis = nowMillisState.longValue
                            orderedClusters.filter { bucket -> isClusterStale(bucket, payloads, timestamps, nowMillis) }
                        }

                        // Cluster drag state (draggedClusterKey/clusterBounds/etc.) is declared
                        // once, shared across all groups - see the comment above its declaration.
                        // Since clusters can sit side by side in a packed row (unlike a fixed
                        // grid), "which cluster is the drag over" is tracked via nearest-centre-
                        // point matching (onGloballyPositioned) rather than a row/column index
                        // delta - this handles irregular, width-based row-packing correctly
                        // whether the target is below/beside/in another group entirely.
                        // Standalone tiles aren't individually draggable, but still participate
                        // in the underlying displayOrder sequence.

                        // Manually pack clusters/tiles into rows rather than relying on FlowRow -
                        // computed against each item's known width so multiple clusters share a
                        // row whenever they fit, on any screen size.
                        // + 16.dp accounts for ClusterCard's own 8dp-each-side padding around its
                        // Row of tiles - without it, the Row's unpadded width silently overflowed
                        // the card by 16dp, clipping the rightmost column's tile.
                        val clusterCardWidth = standaloneTileWidth * columnsPerRow + 8.dp * (columnsPerRow - 1) + 16.dp
                        val availableRowWidth = screenWidthDp - 24.dp
                        val packedRows = remember(visibleClusters, standaloneTileWidth, availableRowWidth) {
                            val rows = mutableListOf<MutableList<List<Panel>>>()
                            var currentRow = mutableListOf<List<Panel>>()
                            var usedWidth = 0.dp
                            visibleClusters.forEach { panelsInCluster ->
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
                                        // Stable per-cluster identity, independent of list position -
                                        // without this, Compose can reuse another cluster's remembered
                                        // state (like ageText) when the list reorders.
                                        key(panelsInCluster.first().id) {
                                            if (name.isBlank()) {
                                                val standalonePanel = panelsInCluster.first()
                                                PanelTile(
                                                    panel = standalonePanel,
                                                    groupId = group.id,
                                                    payloadsState = payloadsState,
                                                    app = app,
                                                    navController = navController,
                                                    tileScale = tileScale,
                                                    onEditSensorValue = onEditSensorValue,
                                                    modifier = Modifier.width(standaloneTileWidth)
                                                        .alpha(if (draggedPanelId == standalonePanel.id) 0.5f else 1f)
                                                        .onGloballyPositioned { coordinates ->
                                                            panelBounds[standalonePanel.id] = coordinates.boundsInWindow()
                                                        }
                                                )
                                            } else {
                                                val compoundKey = "${group.id}::$name"
                                                val clusterBringIntoViewRequester = remember(compoundKey) { BringIntoViewRequester() }
                                                DisposableEffect(compoundKey) {
                                                    clusterBringIntoViewRequesters[compoundKey] = clusterBringIntoViewRequester
                                                    onDispose {
                                                        clusterBringIntoViewRequesters.remove(compoundKey)
                                                        clusterCaptionCoordinates.remove(compoundKey)
                                                    }
                                                }
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
                                                    onEditSensorValue = onEditSensorValue,
                                                    onDelete = {
                                                        pendingClusterDelete = PendingClusterDelete(
                                                            groupId = group.id,
                                                            name = name,
                                                            panelIds = panelsInCluster.map { it.id }
                                                        )
                                                    },
                                                    onDuplicate = {
                                                        val prefix = SensorDiscovery.commonTopicPrefix(panelsInCluster)
                                                        pendingClusterDuplicate = PendingClusterDuplicate(
                                                            groupId = group.id,
                                                            name = name,
                                                            panelIds = panelsInCluster.map { it.id },
                                                            originalTopicPrefix = prefix
                                                        )
                                                        duplicateClusterNameText = "$name copy"
                                                        duplicateTopicText = prefix
                                                    },
                                                    onRetopic = {
                                                        val prefix = SensorDiscovery.commonTopicPrefix(panelsInCluster)
                                                        pendingClusterRetopic = PendingClusterRetopic(
                                                            groupId = group.id,
                                                            clusterName = name,
                                                            currentTopicPrefix = prefix
                                                        )
                                                        retopicNewTopicText = prefix
                                                    },
                                                    isDraggingCluster = draggedClusterKey == compoundKey,
                                                    isClusterDropTarget = (draggedClusterKey != null &&
                                                        draggedClusterKey != compoundKey &&
                                                        draggedToClusterKey == compoundKey) ||
                                                        (draggedPanelId != null && draggedToClusterKey == compoundKey),
                                                    modifier = Modifier.width(clusterCardWidth)
                                                        .onGloballyPositioned { coordinates ->
                                                        clusterBounds[compoundKey] = coordinates.boundsInWindow()
                                                    }
                                                        .bringIntoViewRequester(clusterBringIntoViewRequester),
                                                    // Long-press-drag itself is handled by ONE detector on the
                                                    // LazyColumn as a whole (see its own modifier) rather than
                                                    // here - see that detector's own comment for why a per-item
                                                    // detector doesn't survive a drag long enough to auto-scroll.
                                                    captionRowModifier = Modifier
                                                        .onGloballyPositioned { clusterCaptionCoordinates[compoundKey] = it },
                                                    panelBounds = panelBounds,
                                                    draggedPanelId = draggedPanelId,
                                                    draggedToPanelIndex = if (panelsInCluster.any { it.id == draggedPanelId }) {
                                                        draggedToPanelIndex
                                                    } else {
                                                        -1
                                                    },
                                                    draggedPanelWillPopOut = draggedPanelWillPopOut,
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

    if (showPermitJoinDialog) {
        PermitJoinDialog(
            app = app,
            config = config,
            payloadsState = payloadsState,
            nowMillisState = nowMillisState,
            selectedIndex = permitJoinTopicIndex,
            onSelectedIndexChange = { permitJoinTopicIndex = it },
            onDismiss = { showPermitJoinDialog = false }
        )
    }

    if (showClusterSearch) {
        // (groupId, groupName, clusterName) for every distinct non-blank cluster name across every
        // group, matched case-insensitively as a substring - recomputed only while the dialog is
        // actually open and the query changes, not on every recomposition of the screen itself.
        val clusterSearchResults = remember(config, clusterSearchQuery) {
            if (clusterSearchQuery.isBlank()) {
                emptyList()
            } else {
                config.groups.flatMap { group ->
                    group.panels.asSequence().map { it.clusterName }.filter { it.isNotBlank() }.distinct()
                        .filter { it.contains(clusterSearchQuery, ignoreCase = true) }
                        .map { clusterName -> Triple(group.id, group.name, clusterName) }.toList()
                }.sortedBy { (_, _, clusterName) -> clusterName.lowercase() }
            }
        }
        fun jumpToCluster(groupId: String, clusterName: String) {
            // Same scroll mechanism a cross-group cluster drag already uses to reveal where a
            // moved cluster landed - a coarse index-based scroll to get the group's content
            // composed, then a precise bringIntoView once the specific card has mounted.
            app.configRepository.setGroupCollapsed(groupId, false)
            backStackEntry.savedStateHandle["scrollToGroupId"] = groupId
            pendingScrollToClusterKey = "$groupId::$clusterName"
            showClusterSearch = false
            clusterSearchQuery = ""
        }
        AlertDialog(
            onDismissRequest = {
                showClusterSearch = false
                clusterSearchQuery = ""
            },
            title = { Text("Search clusters") },
            text = {
                Column {
                    OutlinedTextField(
                        value = clusterSearchQuery,
                        onValueChange = { clusterSearchQuery = it },
                        label = { Text("Cluster name") },
                        singleLine = true,
                        keyboardOptions = tvAwareKeyboardOptions(),
                        modifier = Modifier.fillMaxWidth().clearFocusOnBack()
                    )
                    Spacer(Modifier.height(8.dp))
                    if (clusterSearchQuery.isNotBlank() && clusterSearchResults.isEmpty()) {
                        Text("No matching clusters", style = MaterialTheme.typography.bodySmall)
                    } else {
                        LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                            items(
                                clusterSearchResults,
                                key = { (groupId, _, clusterName) -> "$groupId::$clusterName" }
                            ) { (groupId, groupName, clusterName) ->
                                ListItem(
                                    headlineContent = { Text(clusterName) },
                                    supportingContent = { Text(groupName) },
                                    modifier = Modifier.clickable { jumpToCluster(groupId, clusterName) }
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showClusterSearch = false
                    clusterSearchQuery = ""
                }) { Text("Close") }
            }
        )
    }

    pendingGroupDelete?.let { groupId ->
        AlertDialog(
            onDismissRequest = { pendingGroupDelete = null },
            title = { Text("Delete group?") },
            text = { Text("This removes the group and every panel in it.") },
            confirmButton = {
                TextButton(onClick = {
                    val devicesBefore = app.configRepository.config.value.autoConfiguredDevices
                    app.configRepository.deleteGroup(groupId)
                    clearRetainedAppTopicsForOrphanedDevices(app, devicesBefore)
                    pendingGroupDelete = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { pendingGroupDelete = null }) { Text("Cancel") } }
        )
    }

    renamingGroup?.let { group ->
        AlertDialog(
            onDismissRequest = { renamingGroup = null },
            title = { Text("Edit group") },
            text = {
                Column {
                    OutlinedTextField(
                        value = renameText,
                        onValueChange = { renameText = it },
                        label = { Text("Name") },
                        keyboardOptions = tvAwareKeyboardOptions(),
                        modifier = Modifier.clearFocusOnBack()
                    )
                    Spacer(Modifier.height(16.dp))
                    OutlinedButton(
                        onClick = {
                            renamingGroup = null
                            pendingForceRepublishGroup = group
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Force Upload New Copy") }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Rebuilds and republishes every cluster in this group's device config from " +
                            "this phone's current settings, overwriting whatever's currently on the " +
                            "broker - use this if another phone or a broker issue left something out " +
                            "of sync.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(16.dp))
                    OutlinedButton(
                        onClick = {
                            renamingGroup = null
                            groupRetopicOldTopicText = ""
                            groupRetopicNewTopicText = ""
                            pendingGroupRetopic = group
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Retopic All Clusters") }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Moves every cluster in this group currently on one old MQTT topic onto a " +
                            "new one, in one go - e.g. after splitting a Zigbee network onto a " +
                            "second bridge, for every device that moved. Clusters on a different " +
                            "topic are untouched.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (renameText.isNotBlank() && renameText != group.name) {
                        app.configRepository.upsertGroup(group.copy(name = renameText))
                        pushGroupRenameForAutoConfiguredDevices(app, group.name, renameText)
                    }
                    renamingGroup = null
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { renamingGroup = null }) { Text("Cancel") }
            }
        )
    }

    pendingForceRepublishGroup?.let { group ->
        AlertDialog(
            onDismissRequest = { pendingForceRepublishGroup = null },
            title = { Text("Force upload \"${group.name}\"?") },
            text = {
                Text(
                    "This overwrites the retained device config on the broker for every cluster in " +
                        "this group with a fresh copy built from this phone's current settings. Any " +
                        "different config currently on the broker (e.g. from another phone) will be " +
                        "replaced, not merged. This can't be undone automatically."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    forceRepublishGroupAppTopics(app, group.id)
                    pendingForceRepublishGroup = null
                    undoCoroutineScope.launch {
                        snackbarHostState.showSnackbar("Re-published \"${group.name}\" to the broker")
                    }
                }) { Text("Force Upload") }
            },
            dismissButton = {
                TextButton(onClick = { pendingForceRepublishGroup = null }) { Text("Cancel") }
            }
        )
    }

    pendingGroupRetopic?.let { group ->
        AlertDialog(
            onDismissRequest = { pendingGroupRetopic = null },
            title = { Text("Retopic all clusters in \"${group.name}\"?") },
            text = {
                Column {
                    Text(
                        "Moves every cluster in this group whose topic exactly matches \"Old topic\" " +
                            "onto \"New topic\" - the rest of this group's clusters are untouched. " +
                            "This can't be undone automatically.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = groupRetopicOldTopicText,
                        onValueChange = { groupRetopicOldTopicText = it },
                        label = { Text("Old topic") },
                        singleLine = true,
                        keyboardOptions = tvAwareKeyboardOptions(),
                        modifier = Modifier.clearFocusOnBack()
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = groupRetopicNewTopicText,
                        onValueChange = { groupRetopicNewTopicText = it },
                        label = { Text("New topic") },
                        singleLine = true,
                        keyboardOptions = tvAwareKeyboardOptions(),
                        modifier = Modifier.clearFocusOnBack()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val movedClusters = retopicGroupTopicPrefix(
                            app, group.id, groupRetopicOldTopicText, groupRetopicNewTopicText
                        )
                        pendingGroupRetopic = null
                        undoCoroutineScope.launch {
                            snackbarHostState.showSnackbar(
                                if (movedClusters.isEmpty()) {
                                    "No clusters in \"${group.name}\" were on that topic"
                                } else {
                                    "Moved ${movedClusters.size} cluster(s) to \"$groupRetopicNewTopicText\""
                                }
                            )
                        }
                        groupRetopicOldTopicText = ""
                        groupRetopicNewTopicText = ""
                    },
                    enabled = groupRetopicOldTopicText.isNotBlank() && groupRetopicNewTopicText.isNotBlank() &&
                        groupRetopicOldTopicText != groupRetopicNewTopicText
                ) { Text("Retopic") }
            },
            dismissButton = {
                TextButton(onClick = { pendingGroupRetopic = null }) { Text("Cancel") }
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
                    val devicesBefore = app.configRepository.config.value.autoConfiguredDevices
                    app.configRepository.removePanels(pending.groupId, pending.panelIds)
                    clearRetainedAppTopicsForOrphanedDevices(app, devicesBefore)
                    pendingClusterDelete = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { pendingClusterDelete = null }) { Text("Cancel") } }
        )
    }

    pendingClusterDuplicate?.let { pending ->
        AlertDialog(
            onDismissRequest = { pendingClusterDuplicate = null },
            title = { Text("Duplicate \"${pending.name}\"") },
            text = {
                Column {
                    OutlinedTextField(
                        value = duplicateTopicText,
                        onValueChange = { duplicateTopicText = it },
                        label = { Text("Topic") },
                        singleLine = true
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = duplicateClusterNameText,
                        onValueChange = { duplicateClusterNameText = it },
                        label = { Text("Cluster name") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        // Captured before duplicateCluster runs, in original displayOrder - zipped
                        // positionally against the freshly-cloned panels below (same order,
                        // guaranteed by duplicateCluster) to carry each field's current value over
                        // to seed the new "/app" payload with, since the new topic has none of its
                        // own yet.
                        val sourcePanels = app.configRepository.config.value.groups
                            .find { it.id == pending.groupId }?.panels
                            ?.filter { it.id in pending.panelIds }
                            ?.sortedBy { it.displayOrder } ?: emptyList()
                        app.configRepository.duplicateCluster(
                            groupId = pending.groupId,
                            sourcePanelIds = pending.panelIds,
                            newClusterName = duplicateClusterNameText,
                            topicReplacement = if (duplicateTopicText != pending.originalTopicPrefix) {
                                pending.originalTopicPrefix to duplicateTopicText
                            } else {
                                null
                            }
                        )
                        val newPanels = app.configRepository.config.value.groups
                            .find { it.id == pending.groupId }?.panels
                            ?.filter { it.clusterName == duplicateClusterNameText }
                            ?.sortedBy { it.displayOrder } ?: emptyList()
                        val seedValues = sourcePanels.zip(newPanels).mapNotNull { (source, new) ->
                            if (source !is Panel.Sensor) return@mapNotNull null
                            val raw = app.connectionManager.latestPayloads.value["${source.brokerId}|${source.topic}"]
                            val value = raw?.let { JsonPath.extract(it, source.jsonPath) } ?: return@mapNotNull null
                            new.id to value
                        }.toMap()
                        publishAppTopicForClusterIfMissing(app, pending.groupId, duplicateClusterNameText, seedValues)
                        pendingClusterDuplicate = null
                    },
                    enabled = duplicateClusterNameText.isNotBlank()
                ) { Text("Duplicate") }
            },
            dismissButton = { TextButton(onClick = { pendingClusterDuplicate = null }) { Text("Cancel") } }
        )
    }

    pendingClusterRetopic?.let { pending ->
        AlertDialog(
            onDismissRequest = { pendingClusterRetopic = null },
            title = { Text("Change topic for \"${pending.clusterName}\"") },
            text = {
                Column {
                    Text(
                        "Moves every panel in this cluster onto a new MQTT topic - e.g. after " +
                            "splitting a Zigbee network onto a second bridge, for just the devices " +
                            "that actually moved. Other clusters are untouched.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Current topic: ${pending.currentTopicPrefix.ifBlank { "(none)" }}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = retopicNewTopicText,
                        onValueChange = { retopicNewTopicText = it },
                        label = { Text("New topic") },
                        singleLine = true,
                        keyboardOptions = tvAwareKeyboardOptions(),
                        modifier = Modifier.clearFocusOnBack()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        retopicClusterAndPublish(
                            app, pending.groupId, pending.clusterName, pending.currentTopicPrefix, retopicNewTopicText
                        )
                        pendingClusterRetopic = null
                        retopicNewTopicText = ""
                    },
                    enabled = retopicNewTopicText.isNotBlank() && retopicNewTopicText != pending.currentTopicPrefix
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { pendingClusterRetopic = null }) { Text("Cancel") } }
        )
    }

    pendingValueEdit?.let { panel ->
        AlertDialog(
            onDismissRequest = { pendingValueEdit = null },
            title = { Text("Edit \"${panel.label}\"") },
            text = {
                OutlinedTextField(
                    value = valueEditText,
                    onValueChange = { valueEditText = it },
                    label = { Text("Value") },
                    singleLine = true,
                    keyboardOptions = tvAwareKeyboardOptions(KeyboardOptions(keyboardType = KeyboardType.Number))
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val currentPayload = app.connectionManager.latestPayloads.value["${panel.brokerId}|${panel.topic}"]
                        val updatedPayload = JsonPath.withValueAt(currentPayload, panel.jsonPath, valueEditText)
                        app.connectionManager.publish(panel.brokerId, panel.topic, updatedPayload, retain = true)
                        pendingValueEdit = null
                    },
                    enabled = valueEditText.isNotBlank()
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { pendingValueEdit = null }) { Text("Cancel") } }
        )
    }
}

private data class PendingClusterDelete(val groupId: String, val name: String, val panelIds: List<String>)

private data class PendingClusterDuplicate(
    val groupId: String,
    val name: String,
    val panelIds: List<String>,
    // Captured once when the dialog opens - the substring duplicateTopicText's edits replace at
    // confirm time. Kept separate from duplicateTopicText itself, which the user goes on to edit.
    val originalTopicPrefix: String
)

private data class PendingClusterRetopic(
    val groupId: String,
    val clusterName: String,
    // Captured once when the dialog opens, same reasoning as PendingClusterDuplicate's own
    // originalTopicPrefix - the current shared topic prefix, replaced with retopicNewTopicText's
    // value at confirm time.
    val currentTopicPrefix: String
)

/**
 * If this cluster belongs to an auto-configured device, publishes an updated retained "/app"
 * payload reflecting the new panel order, so a future republish of that topic doesn't silently
 * rebuild panels in the device's original order.
 *
 * Reads app.connectionManager.latestPayloads.value directly rather than accepting a parameter -
 * this runs from a pointerInput block that launches once per tile, so a captured parameter would
 * go stale on every call after the first, risking a stale republish overwriting a later reorder.
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
 * Reordering shifts every cluster's relative position, not just the dragged one - so every
 * affected auto-configured cluster gets its own retained "/app" update with its new group_order.
 *
 * Same reasoning as pushOrderUpdateIfAutoConfigured for reading latestPayloads.value directly
 * rather than accepting a parameter, which would go stale after this pointerInput's first launch.
 */
private fun pushGroupOrderUpdatesForClusters(
    app: Z2mDashApplication,
    orderedClusterKeys: List<String>,
    groupPanels: List<Panel>
) {
    val config = app.configRepository.config.value
    val payloads = app.connectionManager.latestPayloads.value
    val panelsByCluster = groupPanels.groupBy { it.clusterName.ifBlank { "__single__${it.id}" } }
    // One shared timestamp for every cluster this drag touches, so a phone reconciling any of
    // them later treats the whole batch as one logical write.
    val orderVersion = System.currentTimeMillis()

    orderedClusterKeys.forEachIndexed { index, clusterKey ->
        val clusterPanelIds = panelsByCluster[clusterKey]?.map { it.id }?.toSet() ?: return@forEachIndexed
        val device = config.autoConfiguredDevices.find { it.createdPanelIds.any { id -> id in clusterPanelIds } }
            ?: return@forEachIndexed
        val currentPayload = payloads["${device.brokerId}|${device.appConfigTopic}"] ?: return@forEachIndexed
        val updatedPayload = SensorDiscovery.updateGroupOrderInAppPayload(currentPayload, index + 1, orderVersion)
            ?: return@forEachIndexed
        app.connectionManager.publish(device.brokerId, device.appConfigTopic, updatedPayload, retain = true)
        // Every broker subscribes to "#", so this publish echoes back to DeviceAutoConfigManager,
        // which would otherwise reconcile it - fine normally (how another phone picks up the new
        // order), but a *stale* retained redelivery could clobber this fresher reorder. Pre-marking
        // the payload as applied with this order_version lets the echo be recognised as already
        // current and skipped, while a genuinely newer order_version still gets adopted. See
        // DeviceAutoConfigManager.reconcileKnownDevices / AutoConfiguredDevice.lastKnownOrderVersion.
        app.configRepository.markAutoConfiguredDevicePayloadApplied(
            device.brokerId, device.appConfigTopic, updatedPayload, orderVersion
        )
    }
}

/**
 * Reordering top-level dashboard groups shifts every group's relative position, not just the
 * dragged one - so every auto-configured device belonging to any group in [orderedGroups] gets
 * its own retained "/app" update with its new dashboard_order. Unlike
 * pushGroupOrderUpdatesForClusters (one device per cluster), a single dashboard group can contain
 * several different devices' clusters, so every owning device is pushed, not just the first found.
 *
 * Same reasoning as pushOrderUpdateIfAutoConfigured for reading latestPayloads.value directly
 * rather than accepting a parameter, which would go stale after this pointerInput's first launch.
 */
private fun pushDashboardGroupOrderUpdates(app: Z2mDashApplication, orderedGroups: List<PanelGroup>) {
    val config = app.configRepository.config.value
    val payloads = app.connectionManager.latestPayloads.value
    // One shared timestamp for every device this drag touches, so a phone reconciling any of
    // them later treats the whole batch as one logical write.
    val orderVersion = System.currentTimeMillis()

    orderedGroups.forEachIndexed { index, group ->
        val panelIds = group.panels.map { it.id }.toSet()
        val devices = config.autoConfiguredDevices.filter { device -> device.createdPanelIds.any { it in panelIds } }
        devices.forEach { device ->
            val currentPayload = payloads["${device.brokerId}|${device.appConfigTopic}"] ?: return@forEach
            val updatedPayload = SensorDiscovery.updateDashboardGroupOrderInAppPayload(currentPayload, index + 1, orderVersion)
                ?: return@forEach
            app.connectionManager.publish(device.brokerId, device.appConfigTopic, updatedPayload, retain = true)
            // See the matching comment in pushGroupOrderUpdatesForClusters above - without this,
            // the "#"-subscribed echo of our own publish (or a stale retained redelivery on any
            // phone sharing this broker) could race DeviceAutoConfigManager into re-reconciling
            // this exact change right back. Also records this device's new dashboard_order
            // directly (rather than waiting for ConfigRepository.resyncDashboardGroupOrder to be
            // triggered by some later, unrelated reconcile pass) so a resync run in the meantime
            // - say, another group's device joining - doesn't compute this group's position from
            // stale data.
            app.configRepository.markAutoConfiguredDevicePayloadApplied(
                device.brokerId, device.appConfigTopic, updatedPayload, orderVersion, dashboardOrder = index + 1
            )
        }
    }
}

/**
 * Whether [panels]' most recent reading (preferring each panel's own device-reported "last_seen"
 * over receipt time, falling back to receipt time only when none of them have one) is more than
 * an hour old, or there's no reading for any of them at all. Same precedence/threshold as
 * ClusterCard's own ageState below, kept as a separate top-level function (rather than factored
 * out of ageState directly) so the "only sensors not reporting recently" filter above can call it
 * without needing ageState's relative-time-text half, which it has no use for.
 */
private fun isClusterStale(
    panels: List<Panel>,
    payloads: Map<String, String>,
    timestamps: Map<String, Long>,
    nowMillis: Long
): Boolean {
    fun topicFor(panel: Panel): String? = when (panel) {
        is Panel.Sensor -> panel.topic
        is Panel.Toggle -> panel.stateTopic.takeIf { it.isNotBlank() }
        is Panel.Button -> null
    }
    val deviceReportedTimestamps = panels.mapNotNull { panel ->
        val topic = topicFor(panel) ?: return@mapNotNull null
        payloads["${panel.brokerId}|$topic"]
            ?.let { JsonPath.extract(it, "last_seen") }
            ?.let { JsonPath.parseIso8601(it) }
    }
    val latestTimestamp = if (deviceReportedTimestamps.isNotEmpty()) {
        deviceReportedTimestamps.max()
    } else {
        panels.mapNotNull { panel ->
            val topic = topicFor(panel) ?: return@mapNotNull null
            timestamps["${panel.brokerId}|$topic"]
        }.maxOrNull()
    }
    return latestTimestamp == null || (nowMillis - latestTimestamp) > 60 * 60 * 1000L
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
    onDuplicate: () -> Unit,
    onRetopic: () -> Unit,
    onEditSensorValue: (Panel.Sensor) -> Unit,
    // Cross-cluster and panel drag state all live one level up (the group section sees every
    // cluster at once, and a dragged panel can end up merged into any of them) and is threaded
    // in here - see the LazyColumn's own shared long-press-drag detector for why none of this
    // lives in a gesture detector of ClusterCard's own any more.
    modifier: Modifier = Modifier,
    captionRowModifier: Modifier = Modifier,
    isDraggingCluster: Boolean = false,
    isClusterDropTarget: Boolean = false,
    // Window-space bounds of every panel tile in this cluster, keyed by panel id - written here,
    // read by the shared top-level drag detector for hit-testing/highlighting. Shared mutable
    // map reference, same pattern as the cluster-level bounds maps one level up.
    panelBounds: MutableMap<String, Rect> = mutableMapOf(),
    draggedPanelId: String? = null,
    draggedToPanelIndex: Int = -1,
    draggedPanelWillPopOut: Boolean = false
) {
    // A single long-lived derivedStateOf (keyed only on panels; payloads/timestamps/now are read
    // from their State objects inside the lambda) so its equality check works: ageText/isStale
    // only triggers recomposition when the COMPUTED result changes, not on every unrelated MQTT
    // message or every nowMillisState tick where the relative-time string reads the same.
    val ageState = remember(panels) {
        derivedStateOf {
            fun topicFor(panel: Panel): String? = when (panel) {
                is Panel.Sensor -> panel.topic
                is Panel.Toggle -> panel.stateTopic.takeIf { it.isNotBlank() }
                // A momentary command has nothing to report "last seen" for.
                is Panel.Button -> null
            }

            val payloads = payloadsState.value
            val timestamps = timestampsState.value
            val nowMillis = nowMillisState.value

            // Prefer the device's own "last_seen" over receipt time - receipt time can be
            // bumped by things unrelated to freshness, like a broker redelivering on resubscribe.
            val deviceReportedTimestamps = panels.mapNotNull { panel ->
                val topic = topicFor(panel) ?: return@mapNotNull null
                payloads["${panel.brokerId}|$topic"]
                    ?.let { JsonPath.extract(it, "last_seen") }
                    ?.let { JsonPath.parseIso8601(it) }
            }

            // Only fall back to receipt time if none of this cluster's panels have a genuine
            // last_seen - otherwise a config-only topic (like "/app") without one could drag
            // down displayed freshness just because it happened to update.
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
        // Every row gets the same fixed 3-column width regardless of how many panels land in
        // it, so a short trailing row centers instead of bunching to the left.
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
                            panelIndex == draggedToPanelIndex
                        val isPoppingOut = isDragging && draggedPanelWillPopOut

                        Box(
                            modifier = Modifier
                                .width(tileWidth)
                                .then(
                                    if (isPoppingOut) {
                                        // Dashed-look substitute (Compose border has no dash param) -
                                        // the error colour alone reads as "about to leave" against the
                                        // steady primary-coloured within-cluster drop-target border.
                                        Modifier.border(2.dp, MaterialTheme.colorScheme.error, RoundedCornerShape(12.dp))
                                    } else if (isDropTarget) {
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
                                onEditSensorValue = onEditSensorValue,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .alpha(if (isDragging) 0.5f else 1f)
                                    .onGloballyPositioned { coordinates ->
                                        panelBounds[panel.id] = coordinates.boundsInWindow()
                                    }
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                // fillMaxWidth so the whole caption bar is a drag touch target, not just the
                // name/age text width - otherwise a long-press elsewhere along the row (where
                // someone would naturally try "holding the cluster") misses the gesture detector.
                modifier = captionRowModifier.fillMaxWidth()
            ) {
                // maxLines/overflow/softWrap=false are a deliberate safety net: confirmed on-device
                // that an uncapped Text could wrap one character per line into a tall vertical
                // strip resembling a scrollbar when the Row's width momentarily collapsed. This
                // doesn't fix the collapse, but ensures it truncates with "…" instead.
                // captionScale is floored well above tileScale's own range - a caption is a
                // heading read once, not per-tile decoration, so it shouldn't shrink as
                // aggressively as the tiles before becoming illegible.
                val captionScale = tileScale.coerceAtLeast(0.85f)
                Text(
                    name,
                    // Scales fontSize AND lineHeight (see SensorTile's comment on why) so the
                    // caption shrinks with its tiles instead of staying phone-sized above them.
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
                IconButton(onClick = onDuplicate, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = "Duplicate $name",
                        modifier = Modifier.size(16.dp)
                    )
                }
                IconButton(onClick = onRetopic, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Default.SwapHoriz,
                        contentDescription = "Change topic for $name",
                        modifier = Modifier.size(16.dp)
                    )
                }
                IconButton(
                    onClick = {
                        // Prefills the new panel's cluster/broker/topic with this cluster's own
                        // values - see AddPanelScreen's own doc on these preset* savedStateHandle
                        // keys. Set on currentBackStackEntry (this screen's own entry) rather than
                        // previousBackStackEntry, since we're passing forward to a screen that
                        // doesn't exist yet - it reads them back via ITS previousBackStackEntry,
                        // which is this same entry.
                        val handle = navController.currentBackStackEntry?.savedStateHandle
                        handle?.set("presetClusterName", name)
                        panels.firstOrNull()?.brokerId?.let { handle?.set("presetBrokerId", it) }
                        SensorDiscovery.commonTopicPrefix(panels).takeIf { it.isNotBlank() }
                            ?.let { handle?.set("presetTopic", it) }
                        navController.navigate("group/$groupId/panel/new")
                    },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = "Add tile to $name",
                        modifier = Modifier.size(16.dp)
                    )
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
    modifier: Modifier = Modifier,
    tileScale: Float = 1f,
    onEditSensorValue: (Panel.Sensor) -> Unit = {}
) {
    val config by app.configRepository.config.collectAsState()
    when (panel) {
        is Panel.Sensor -> {
            // Same derivedStateOf reasoning as ClusterCard's ageState above - this tile only
            // recomposes when its own computed value/alert/presence changes, not on every MQTT
            // message for every other topic (as reading payloadsState.value directly used to).
            val derived by remember(panel) {
                derivedStateOf {
                    val payloads = payloadsState.value
                    val raw = payloads["${panel.brokerId}|${panel.topic}"]
                    val extracted = raw?.let { JsonPath.extract(it, panel.jsonPath) }
                    // Zigbee2MQTT uses "occupancy" for PIR sensors and "presence" for mmWave ones -
                    // both mean "someone detected" here, so both are treated identically.
                    val isPresenceField = panel.jsonPath.equals("occupancy", ignoreCase = true) ||
                        panel.jsonPath.equals("presence", ignoreCase = true)
                    val isPresent = extracted?.equals("true", ignoreCase = true) == true
                    // Only reformat numeric values - non-numeric text (e.g. "online") passes
                    // through as-is. Presence fields get a dedicated label instead of raw
                    // "true"/"false", with the icon itself carrying the detected state.
                    val value = when {
                        isPresenceField -> if (extracted != null) { if (isPresent) "Detected" else "Clear" } else "--"
                        extracted != null -> extracted.toDoubleOrNull()?.let { num -> "%.${panel.decimals}f".format(num) } ?: extracted
                        else -> "--"
                    }
                    val alert = if (panel.idealRangeTopic.isBlank()) {
                        SensorAlert.NONE
                    } else {
                        // Reparses the original extracted text, not the rounded display value -
                        // comparing a rounded number could misclassify a borderline reading.
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
                onEdit = { navController.navigate("group/$groupId/panel/${panel.id}") },
                editable = panel.editable,
                onEditValue = { onEditSensorValue(panel) }
            )
        }

        is Panel.Toggle -> {
            // Same derivedStateOf reasoning as the Sensor branch above.
            val isOn by remember(panel) {
                derivedStateOf {
                    val statePayload = payloadsState.value["${panel.brokerId}|${panel.stateTopic}"]
                    val resolvedState = statePayload?.let { JsonPath.extract(it, panel.stateJsonPath) }
                    // onPayload might be a full JSON command like {"state":"OPEN"}, not the bare
                    // value the state topic reports - extract via the same stateJsonPath for a
                    // fair comparison, falling back to the raw string for simple commands like "ON".
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

        val targetGroupId = app.configRepository.resolveOrCreateGroup(deviceConfig.group)

        val device = AutoConfiguredDevice(
            brokerId = pending.brokerId,
            sensorTopic = pending.sensorTopic,
            appConfigTopic = pending.appConfigTopic,
            lastAppliedPayload = appConfigPayload,
            createdPanelIds = newPanels.map { it.id },
            lastKnownDashboardOrder = deviceConfig.dashboardOrder
        )
        app.configRepository.applyDeviceAutoConfig(
            oldPanelIds = emptySet(),
            updatedDevice = device,
            targetGroupId = targetGroupId,
            newPanels = newPanels
        )
        app.configRepository.resyncDashboardGroupOrder()
        app.configRepository.removePendingAutoConfigDevice(pending.brokerId, pending.appConfigTopic)
        Log.i(tag, "Added ${newPanels.size} panels for ${pending.deviceName} into group $targetGroupId")
    } catch (e: Exception) {
        // Caught and logged rather than propagated - an uncaught exception here would be an
        // unexplained crash, worse than the silent early-returns already possible elsewhere.
        Log.e(tag, "addPendingDevice failed for ${pending.deviceName}", e)
    }
}

/**
 * The one whole-screen Permit Join bar. Its switch and countdown act directly on whichever
 * (broker, base topic) pair is currently selected - using that topic's already-saved router, with
 * no need to reopen the dialog just to flick it on/off - while tapping the icon/text still opens
 * [PermitJoinDialog] to change which topic that is or which router it targets. Kept as separate
 * clickable zones (icon+text vs. switch), the same split used to fix TV D-pad ambiguity on
 * ToggleTile, so a D-pad "OK" on the switch toggles it rather than opening the dialog.
 */
@Composable
private fun PermitJoinBanner(
    title: String,
    subtitle: String,
    isOn: Boolean,
    onToggle: (Boolean) -> Unit,
    onClick: () -> Unit
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
            Icon(
                Icons.Default.WifiTethering,
                contentDescription = null,
                modifier = Modifier.tvFocusIndicator(RoundedCornerShape(4.dp)).clickable(onClick = onClick)
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f).clickable(onClick = onClick)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(subtitle, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = isOn,
                onCheckedChange = onToggle,
                modifier = Modifier.tvFocusIndicator(RoundedCornerShape(50))
            )
        }
    }
}

/**
 * Picks which (broker, base topic) to act on - across every broker and every comma-separated base
 * topic it declares, in one dropdown, since permit-join state/routers are genuinely per-topic, not
 * per-broker connection - then, once picked, which specific router to extend joining through (its
 * own dropdown, populated from that topic's own "bridge/devices"), with a toggle+status for
 * whichever topic is currently selected. Replaces both the old one-bar-per-broker/topic dashboard
 * layout and the "permit join via" section that used to live on the broker edit screen - reachable
 * from anywhere on the dashboard now, for every network at once, without navigating away.
 *
 * [selectedIndex]/[onSelectedIndexChange] are hoisted to the caller (rather than local state here)
 * so picking a topic in this dialog also becomes the one the dashboard's own Permit Join bar acts
 * on directly - see permitJoinTopicIndex in HomeScreen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PermitJoinDialog(
    app: Z2mDashApplication,
    config: AppConfig,
    payloadsState: State<Map<String, String>>,
    nowMillisState: State<Long>,
    selectedIndex: Int,
    onSelectedIndexChange: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val showBrokerName = config.brokers.size > 1
    // (brokerId, brokerName, baseTopic) for every base topic on every broker.
    val allTopics = remember(config.brokers) {
        config.brokers.flatMap { broker ->
            PermitJoin.parseBaseTopics(broker.baseTopic).map { topic -> Triple(broker.id, broker.name, topic) }
        }
    }
    fun labelFor(topic: Triple<String, String, String>): String {
        val (_, brokerName, baseTopic) = topic
        return if (showBrokerName) "$baseTopic ($brokerName)" else baseTopic
    }

    val selected = allTopics.getOrNull(selectedIndex)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Permit Join") },
        text = {
            if (allTopics.isEmpty()) {
                Text("No brokers configured.")
            } else {
                Column {
                    var topicExpanded by remember { mutableStateOf(false) }
                    ExposedDropdownMenuBox(
                        expanded = topicExpanded,
                        onExpandedChange = { topicExpanded = it }
                    ) {
                        OutlinedTextField(
                            value = selected?.let { labelFor(it) } ?: "",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Base topic") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = topicExpanded) },
                            modifier = Modifier.fillMaxWidth()
                                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                        )
                        ExposedDropdownMenu(
                            expanded = topicExpanded,
                            onDismissRequest = { topicExpanded = false }
                        ) {
                            allTopics.forEachIndexed { index, topic ->
                                val onTopicClick = {
                                    onSelectedIndexChange(index)
                                    topicExpanded = false
                                }
                                DropdownMenuItem(
                                    text = { Text(labelFor(topic)) },
                                    onClick = onTopicClick,
                                    modifier = Modifier.onDpadSelect(onTopicClick)
                                )
                            }
                        }
                    }
                    if (selected != null) {
                        val (brokerId, _, baseTopic) = selected
                        Spacer(Modifier.height(16.dp))
                        // Only routers/coordinator can be targeted by permit_join's "device" field
                        // (end devices don't route child joins) - see PermitJoin.routerFriendlyNames.
                        val routerNames by remember(brokerId, baseTopic) {
                            derivedStateOf { PermitJoin.routerFriendlyNames(payloadsState.value, brokerId, baseTopic) }
                        }
                        var routerText by remember(brokerId, baseTopic) {
                            val broker = config.brokers.find { it.id == brokerId }
                            mutableStateOf(broker?.permitJoinDevices?.get(baseTopic).orEmpty())
                        }
                        var routerExpanded by remember { mutableStateOf(false) }
                        val filteredRouterNames = remember(routerNames, routerText) {
                            routerNames.filter { it.contains(routerText, ignoreCase = true) }
                        }
                        ExposedDropdownMenuBox(
                            expanded = routerExpanded && filteredRouterNames.isNotEmpty(),
                            onExpandedChange = { routerExpanded = it }
                        ) {
                            OutlinedTextField(
                                value = routerText,
                                onValueChange = {
                                    routerText = it
                                    routerExpanded = true
                                },
                                label = { Text("Permit join via (optional)") },
                                placeholder = { Text("Blank = whole network") },
                                keyboardOptions = tvAwareKeyboardOptions(),
                                trailingIcon = if (routerNames.isNotEmpty()) {
                                    { ExposedDropdownMenuDefaults.TrailingIcon(expanded = routerExpanded) }
                                } else null,
                                modifier = Modifier.fillMaxWidth()
                                    .clearFocusOnBack()
                                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable)
                            )
                            ExposedDropdownMenu(
                                expanded = routerExpanded && filteredRouterNames.isNotEmpty(),
                                onDismissRequest = { routerExpanded = false }
                            ) {
                                filteredRouterNames.forEach { name ->
                                    val onNameClick = {
                                        routerText = name
                                        routerExpanded = false
                                    }
                                    DropdownMenuItem(
                                        text = { Text(name) },
                                        onClick = onNameClick,
                                        modifier = Modifier.onDpadSelect(onNameClick)
                                    )
                                }
                            }
                        }
                        Text(
                            "Friendly name of a specific router to extend joining through, or " +
                                "\"Coordinator\" for just the coordinator. Leave blank to permit " +
                                "joining via every router and the coordinator at once.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(Modifier.height(16.dp))
                        val status by remember(brokerId, baseTopic) {
                            derivedStateOf { PermitJoin.status(payloadsState.value, brokerId, baseTopic, nowMillisState.value) }
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                                .toggleableRow(status.isOn) { enabled ->
                                    app.configRepository.updatePermitJoinDevice(brokerId, baseTopic, routerText)
                                    val payload = PermitJoin.requestPayload(routerText, if (enabled) 254 else 0)
                                    app.connectionManager.publish(brokerId, PermitJoin.requestTopic(baseTopic), payload)
                                }
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("Permit Join", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    if (status.isOn) {
                                        "Open for ${PermitJoin.formatRemaining(status.remainingSeconds)} more"
                                    } else {
                                        "Allow new Zigbee devices to join for a few minutes"
                                    },
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            Switch(checked = status.isOn, onCheckedChange = null)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
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
