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
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.FilterListOff
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SignalWifi4Bar
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.UnfoldLess
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Water
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import com.odiousapps.z2mdash.R
import com.odiousapps.z2mdash.Z2mDashApplication
import com.odiousapps.z2mdash.data.AppConfig
import com.odiousapps.z2mdash.data.AutoConfiguredDevice
import com.odiousapps.z2mdash.data.JsonPath
import com.odiousapps.z2mdash.data.Panel
import com.odiousapps.z2mdash.data.PanelGroup
import com.odiousapps.z2mdash.data.PendingAutoConfigDevice
import com.odiousapps.z2mdash.data.PermitJoin
import com.odiousapps.z2mdash.data.SensorDiscovery
import com.odiousapps.z2mdash.data.TileIcon
import com.odiousapps.z2mdash.data.clearRetainedAppTopicsForOrphanedDevices
import com.odiousapps.z2mdash.data.clusterKey
import com.odiousapps.z2mdash.data.clusterName
import com.odiousapps.z2mdash.data.forceRepublishGroupAppTopics
import com.odiousapps.z2mdash.data.orderedPanelsOf
import com.odiousapps.z2mdash.data.publishAppTopicForClusterIfMissing
import com.odiousapps.z2mdash.data.pushGroupMoveForAutoConfiguredDevices
import com.odiousapps.z2mdash.data.pushGroupRenameForAutoConfiguredDevices
import com.odiousapps.z2mdash.data.pushPanelClusterOverrideIfAutoConfigured
import com.odiousapps.z2mdash.data.retopicClusterAndPublish
import com.odiousapps.z2mdash.data.retopicGroupTopicPrefix
import com.odiousapps.z2mdash.mqtt.LowBatteryAlertManager
import com.odiousapps.z2mdash.ui.components.AlertBlue
import com.odiousapps.z2mdash.ui.components.AlertYellow
import com.odiousapps.z2mdash.ui.components.ButtonTile
import com.odiousapps.z2mdash.ui.components.SensorAlert
import com.odiousapps.z2mdash.ui.components.SensorTile
import com.odiousapps.z2mdash.ui.components.ToggleTile
import com.odiousapps.z2mdash.ui.components.iconFor
import com.odiousapps.z2mdash.ui.tv.LocalIsTv
import com.odiousapps.z2mdash.ui.tv.TvListScrollbar
import com.odiousapps.z2mdash.ui.tv.TvScrollbarWidth
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
            // The permit-join banner is ONE item total whenever there's at least one broker (see
            // its own "item(key = "permitJoinBar")" wrapped in "if (config.brokers.isNotEmpty())"
            // below) - not one item per broker. Using config.brokers.size here overcounted it by
            // (brokers.size - 1) for anyone with more than one broker configured (a normal setup
            // in this app, e.g. two bridges on the same dashboard), landing every cross-group
            // cluster-move's post-drop scroll consistently that many items too far down - which
            // looks like "the wrong cluster" nearly every time rather than a one-off glitch.
            // Each group itself contributes its own sticky header item plus - only when
            // expanded - a second item for its content (see the stickyHeader/item pair below),
            // so a preceding expanded group must count as 2, not 1: undercounting here (as an
            // earlier version did, counting exactly 1 per preceding group) lands the scroll
            // short of the real target by one item per expanded group in between - with several
            // expanded groups above the target, that shortfall was enough to look like it
            // scrolled to the top of the screen instead.
            var targetIndex = (if (config.brokers.isNotEmpty()) 1 else 0) + config.pendingAutoConfigDevices.size
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
    // Filters the dashboard itself down to only the clusters/standalone panels matching one
    // DashboardFilter (not reporting recently, low battery, weak signal, low or high moisture), rather
    // than opening a separate dialog for it - picked from the filter FAB's menu. Only one at a time:
    // picking another replaces it. rememberSaveable, not remember - this is a deliberate,
    // user-set filter mode, not transient dialog state, so it should survive a screen rotation
    // (which fully recreates the Activity, since this app doesn't handle configChanges) rather
    // than silently reverting to "off".
    var activeFilter by rememberSaveable { mutableStateOf<DashboardFilter?>(null) }
    var showFilterMenu by remember { mutableStateOf(false) }
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
    // survives rotation the same as activeFilter above, rather than always resetting back
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
                            actionLabel = app.getString(R.string.home_undo),
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
    // Bumped by the watchdog below whenever it force-ends a gesture that went silent - see its
    // own comment for why: forcing just the draggedGroupId/draggedClusterKey/etc. state back to
    // null clears the UI (drop-target border, highlight) but does NOT recover the ability to
    // START a new drag, because the underlying detectDragGesturesAfterLongPress call that died
    // mid-gesture is still the SAME suspended call governing every future touch on this
    // pointerInput - once it's wedged (per the Compose pointer-routing edge case documented
    // above), it never returns to await a fresh initial press, so nothing - including a brand
    // new long-press - ever starts a drag again until the app is restarted. Keying the
    // pointerInput block on this counter makes Compose cancel that stuck coroutine and launch a
    // genuinely fresh one whenever the watchdog fires, which is the only way to actually recover
    // future touches rather than just tidying up the visible state of a now-permanently-dead one.
    var gestureDetectorGeneration by remember { mutableIntStateOf(0) }
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
    // Keys are "<groupId>::<clusterKey>", clusterKey being a clusterId or "__header__" for a
    // group's own header (so an otherwise-empty group is still a valid drop target) - group-
    // scoped too, so the group a drop lands in is readable straight off the key.
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
    // Reading-order test used to position a panel popping out into a new cluster (see the
    // willPopOut drop handler): a cluster counts as "before" drop if its whole row sits above
    // drop's row, or - when drop's own y falls within this cluster's vertical span, i.e. they're
    // in the same packed row - it sits to drop's left. Rows are assumed not to vertically overlap
    // by more than a card's own height, true for this screen's own packedRows layout.
    fun isBeforeDropPoint(rect: Rect, drop: Offset): Boolean = when {
        rect.bottom <= drop.y -> true
        rect.top > drop.y -> false
        else -> rect.right <= drop.x
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
        val ownClusterId = panel.clusterId
        val ownClusterKey = ownClusterId.takeIf { it.isNotBlank() }?.let { "$fromGroupId::$it" }
        val ownRect = ownClusterKey?.let { clusterBounds[it] }
        if (ownRect?.contains(dragTouchWindowPos) == true) {
            draggedToClusterKey = null
            draggedPanelWillPopOut = false
            val siblings = app.configRepository.config.value.groups
                .find { it.id == fromGroupId }?.panels
                ?.filter { it.clusterId == ownClusterId }
                ?.sortedBy { it.displayOrder }
                .orEmpty()
            val nearestSiblingId = siblings
                .mapNotNull { p -> panelBounds[p.id]?.let { p.id to it.distanceTo(dragTouchWindowPos) } }
                .minByOrNull { (_, distance) -> distance }
                ?.first
            draggedToPanelIndex = siblings.indexOfFirst { it.id == nearestSiblingId }
        } else {
            // isWithinTrustedBounds filters the same stale/off-screen clusterBounds entries that
            // computeNearestClusterKey/Group already guard against (see that comment) - without
            // it, a stale frozen rect left behind by a since-scrolled-out cluster could still
            // "contain" the touch point (e.g. a different cluster now renders at that same screen
            // position) and win here via plain iteration order, merging the panel into the wrong
            // cluster.
            val mergeTarget = clusterBounds.entries.firstOrNull { (key, rect) ->
                key != ownClusterKey && key.substringAfter("::") != "__header__" &&
                    isWithinTrustedBounds(rect.center) && rect.contains(dragTouchWindowPos)
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
                // Forces the pointerInput below to cancel its (likely permanently wedged)
                // coroutine and start a fresh one - see gestureDetectorGeneration's own comment.
                gestureDetectorGeneration++
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

    // ---- Floating action buttons ----
    // Every FAB is the same full FloatingActionButton size, on phone and TV alike. On TV,
    // tvFocusIndicator's visible focus ring and the wider spacing below are what a D-pad remote
    // needs: without the ring there was no on-screen cue for which FAB (if any) was focused, which
    // is what made them "hard to select" in the first place, and the extra spacing makes it easier
    // to see which one currently has focus. Worked out here, above the Scaffold, rather than inside
    // its floatingActionButton slot, because the list's own bottom padding (see the LazyColumn's
    // contentPadding) depends on how many rows they wrap onto.
    val isTv = LocalIsTv.current
    val fabSpacing = if (isTv) 20.dp else 8.dp
    // Only worth showing once there's at least one non-editable sensor to actually watch for
    // staleness - an editable panel is a fixed preference value, not a live hardware reading, so
    // it never genuinely "reports" and would just be noise here.
    val hasStaleCandidates = config.groups.any { g -> g.panels.any { it is Panel.Sensor && !it.editable && it.topic.isNotBlank() } }
    // The low-battery and weak-signal FABs are only worth showing once at least one dashboard
    // device actually reports a "battery"/"linkquality" field. A plain substring check rather than
    // a JSON parse - this re-runs on every incoming MQTT message, and only needs to know whether
    // the field exists at all.
    val deviceTopicKeys = remember(config) {
        config.groups.asSequence().flatMap { it.panels }.flatMap { deviceTopicKeysFor(it) }.toSet()
    }
    val anyBatteryDevice by remember(deviceTopicKeys) {
        derivedStateOf {
            val payloads = payloadsState.value
            deviceTopicKeys.any { payloads[it]?.contains("\"battery\"") == true }
        }
    }
    val anyLinkQualityDevice by remember(deviceTopicKeys) {
        derivedStateOf {
            val payloads = payloadsState.value
            deviceTopicKeys.any { payloads[it]?.contains("\"linkquality\"") == true }
        }
    }
    val hasMoistureSensors = config.groups.any { g -> g.panels.any { isMoistureSensor(it) } }
    // The dashboard filters live behind one "filter" FAB's menu rather than a FAB each - four
    // more always-visible FABs didn't fit along the bottom. Each is offered while there's
    // something for it to find, or while it's the active one, so it can always be switched back off.
    // Listed in menu order - moisture (low, then high) first, as the ones checked most often.
    val filterOptions = listOf(
        FilterOption(DashboardFilter.LOW_MOISTURE, iconFor(TileIcon.MOISTURE), stringResource(R.string.filter_low_moisture), hasMoistureSensors),
        // Its own icon (water waves, for too much water) rather than the moisture tile's droplet the
        // low option already uses - the filter FAB shows the active filter's icon, so the two have
        // to look different.
        FilterOption(DashboardFilter.HIGH_MOISTURE, Icons.Default.Water, stringResource(R.string.filter_high_moisture), hasMoistureSensors),
        FilterOption(DashboardFilter.STALE, Icons.Default.Warning, stringResource(R.string.filter_stale), hasStaleCandidates),
        FilterOption(DashboardFilter.LOW_BATTERY, Icons.Default.BatteryAlert, stringResource(R.string.filter_low_battery), anyBatteryDevice),
        FilterOption(DashboardFilter.WEAK_SIGNAL, iconFor(TileIcon.SIGNAL), stringResource(R.string.filter_weak_signal, WEAK_SIGNAL_LQI), anyLinkQualityDevice)
    ).filter { it.available || it.filter == activeFilter }
    val activeFilterOption = filterOptions.find { it.filter == activeFilter }
    // The filter FAB's own menu, shown above it - see filterFab below.
    val filterMenu: @Composable () -> Unit = {
        DropdownMenu(expanded = showFilterMenu, onDismissRequest = { showFilterMenu = false }) {
            filterOptions.forEach { option ->
                val active = option.filter == activeFilter
                DropdownMenuItem(
                    text = { Text(option.label) },
                    leadingIcon = { Icon(option.icon, contentDescription = null) },
                    trailingIcon = if (active) {
                        { Icon(Icons.Default.Check, contentDescription = stringResource(R.string.filter_active)) }
                    } else {
                        null
                    },
                    // Picking a filter replaces whichever one was active; picking the active one
                    // clears it.
                    onClick = {
                        activeFilter = if (active) null else option.filter
                        showFilterMenu = false
                    }
                )
            }
            if (activeFilter != null) {
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.filter_show_everything)) },
                    leadingIcon = { Icon(Icons.Default.FilterListOff, contentDescription = null) },
                    onClick = {
                        activeFilter = null
                        showFilterMenu = false
                    }
                )
            }
        }
    }
    // Shows the active filter's own icon (highlighted, like any active filter) in place of the
    // plain filter icon, so which filter is narrowing the dashboard is visible at a glance.
    val filterFab = FabSpec(
        key = "filter",
        icon = activeFilterOption?.icon ?: Icons.Default.FilterList,
        contentDescription = activeFilterOption?.let { stringResource(R.string.filter_fab_active, it.label.lowercase()) }
            ?: stringResource(R.string.filter_fab),
        highlighted = activeFilterOption != null,
        onClick = { showFilterMenu = true },
        menu = filterMenu
    ).takeIf { filterOptions.isNotEmpty() }
    val anyGroupExpanded = config.groups.any { !it.collapsed }
    val fabSpecs = listOfNotNull(
        // Only worth showing once there's at least one named cluster to actually find - a
        // dashboard of only standalone tiles has nothing for this to search.
        FabSpec("search", Icons.Default.Search, stringResource(R.string.home_search_clusters), onClick = { showClusterSearch = true })
            .takeIf { config.groups.any { g -> g.clusters.isNotEmpty() } },
        filterFab,
        // Only worth showing once there's more than one group to bulk-collapse - with zero or one,
        // per-group collapse (the header's own chevron) already covers it.
        FabSpec(
            "collapse",
            if (anyGroupExpanded) Icons.Default.UnfoldLess else Icons.Default.UnfoldMore,
            stringResource(if (anyGroupExpanded) R.string.home_collapse_all_groups else R.string.home_expand_all_groups),
            onClick = { app.configRepository.setAllGroupsCollapsed(anyGroupExpanded) }
        ).takeIf { config.groups.size > 1 },
        FabSpec("addGroup", Icons.Default.Add, stringResource(R.string.home_add_group), onClick = { navController.navigate("addGroup") })
    )
    // As many FABs per row as fit across the screen (Scaffold end-aligns the FAB slot with a 16dp
    // margin each side - a row any wider ran off the left edge, clipping its first FAB), filled
    // from the bottom-right: "Add group" always ends the bottom row, and whatever doesn't fit
    // wraps onto rows ABOVE it rather than below, since the bottom edge is where they're anchored.
    val fabsPerRow = (((screenWidthDp - 16.dp * 2 + fabSpacing) / (56.dp + fabSpacing)).toInt()).coerceAtLeast(1)
    val fabRows = fabSpecs.reversed().chunked(fabsPerRow).map { it.reversed() }.reversed()
    // Extra list padding for every row of FABs beyond the first, so the list can still scroll its
    // last items clear of them (the first row is already covered by the base 96dp).
    val extraFabRowsPadding = (56.dp + fabSpacing) * (fabRows.size - 1).coerceAtLeast(0)

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        floatingActionButton = {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(fabSpacing)
            ) {
                fabRows.forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(fabSpacing)) {
                        row.forEach { fab ->
                            key(fab.key) {
                                // Box so a FAB's own menu (the filter FAB's) anchors to it.
                                Box {
                                    FloatingActionButton(
                                        onClick = fab.onClick,
                                        containerColor = if (fab.highlighted) {
                                            MaterialTheme.colorScheme.errorContainer
                                        } else {
                                            FloatingActionButtonDefaults.containerColor
                                        },
                                        modifier = Modifier.tvFocusIndicator()
                                    ) {
                                        Icon(fab.icon, contentDescription = fab.contentDescription)
                                    }
                                    fab.menu?.invoke()
                                }
                            }
                        }
                    }
                }
            }
        },
        // Swipe-to-dismiss, not just the "Undo" action button - the undo snackbar's own timeout
        // is user-configurable (undoToastSeconds) and can be set quite long, so without this the
        // only way to clear it early (confirming "no, leave it as-is, I'm done watching this") was
        // to wait the whole thing out. SwipeToDismissBoxValue.Settled is excluded from the dismiss
        // check below since that's just the box's own resting state, not a swipe gesture.
        //
        // Watches dismissState.currentValue via LaunchedEffect rather than the simpler
        // confirmValueChange callback - that parameter is deprecated (as of this project's current
        // Compose BOM) with no direct replacement, in favour of reacting to the state's settled
        // value instead of vetoing/observing each transition attempt.
        snackbarHost = {
            SnackbarHost(snackbarHostState) { data ->
                val dismissState = rememberSwipeToDismissBoxState()
                LaunchedEffect(dismissState.currentValue) {
                    if (dismissState.currentValue != SwipeToDismissBoxValue.Settled) {
                        data.dismiss()
                    }
                }
                SwipeToDismissBox(state = dismissState, backgroundContent = {}) {
                    Snackbar(snackbarData = data)
                }
            }
        }
    ) { padding ->
        // Same flattening the dialog uses (see PermitJoinDialog's own allTopics), kept in
        // lockstep by construction (both iterate config.brokers, then PermitJoin.parseBaseTopics
        // per broker, in the same order) so permitJoinTopicIndex means the same pair in both
        // places. Hoisted up here (rather than inside the LazyColumn's own content, as before) so
        // whether join is on can decide, below, whether this renders as a normal scrolling list
        // item or a banner fixed above the whole list - a LazyColumn stickyHeader only stays
        // pinned until the NEXT stickyHeader (the first group's own) reaches the top and takes
        // over that slot, which isn't "always at the top" once the user scrolls past the first
        // group; a real always-on-top banner has to live outside the scrollable list entirely.
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
        // Which router (if any) permit join was last enabled through for this topic - the same
        // value onToggle below already publishes, just surfaced here too so it's visible while
        // join is open, not only inside the dialog that set it.
        val activeRouterText = activeTopic?.let { (brokerId, baseTopic) ->
            config.brokers.find { it.id == brokerId }?.permitJoinDevices?.get(baseTopic)
        }?.takeIf { it.isNotBlank() }
        val permitJoinIsOn = activeStatus?.isOn == true
        val statusText = when {
            activeTopic == null -> stringResource(R.string.permit_join_no_brokers)
            permitJoinIsOn -> {
                val remaining = PermitJoin.formatRemaining(activeStatus!!.remainingSeconds)
                activeRouterText?.let { stringResource(R.string.permit_join_open_via, remaining, it) }
                    ?: stringResource(R.string.permit_join_open, remaining)
            }
            else -> stringResource(R.string.permit_join_off)
        }
        val permitJoinBannerContent: @Composable () -> Unit = {
            PermitJoinBanner(
                title = stringResource(R.string.permit_join_title),
                subtitle = if (allTopics.size > 1 && activeTopic != null) {
                    stringResource(R.string.permit_join_subtitle, activeTopic.second, statusText)
                } else {
                    statusText
                },
                isOn = permitJoinIsOn,
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
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (config.brokers.isNotEmpty() && permitJoinIsOn) {
                permitJoinBannerContent()
            }
            Box(Modifier.weight(1f)) {
        if (config.groups.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                if (isConfigLoaded) {
                    Text(stringResource(R.string.home_no_groups), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.home_no_groups_detail))
                } else {
                    // config.groups briefly looks empty while ConfigRepository loads from disk -
                    // without this check, a user with real groups could see "add your first
                    // group" flash up on a slow cold start.
                    Text(stringResource(R.string.home_loading), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.home_loading_detail))
                }
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize()
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
                .pointerInput(gestureDetectorGeneration) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { startLocalPos ->
                            val coords = listCoordinates ?: return@detectDragGesturesAfterLongPress
                            val windowPos = coords.localToWindow(startLocalPos)
                            // Group headers are hit-tested first: the sticky header is drawn on top of
                            // whatever content has scrolled underneath it, so a press on it must never
                            // fall through to a tile/caption beneath. Only attached (currently laid
                            // out) coordinates count, and only tiles in an expanded group - a tile in
                            // a collapsed group isn't on screen, so it can't be what was pressed.
                            val liveGroups = app.configRepository.config.value.groups
                            val hitGroupId = groupHeaderCoordinates.entries
                                .firstOrNull { (_, c) -> c.isAttached && c.boundsInWindow().contains(windowPos) }
                                ?.key
                            val hitClusterKey = if (hitGroupId == null) {
                                clusterCaptionCoordinates.entries
                                    .firstOrNull { (_, c) -> c.isAttached && c.boundsInWindow().contains(windowPos) }
                                    ?.key
                            } else null
                            val expandedPanelIds = liveGroups.asSequence().filter { !it.collapsed }
                                .flatMap { it.panels }.map { it.id }.toSet()
                            val hitPanelId = if (hitGroupId == null && hitClusterKey == null) {
                                panelBounds.entries
                                    .firstOrNull { (id, b) -> id in expandedPanelIds && b.contains(windowPos) }
                                    ?.key
                            } else null
                            when {
                                hitPanelId != null -> {
                                    val fromGroupId = liveGroups
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
                                    val fromClusterId = fromKey.substringAfter("::")
                                    val fromClusterName = app.configRepository.config.value.groups
                                        .find { it.id == fromGroupId }?.clusterName(fromClusterId).orEmpty()
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
                                            val panelsByClusterKey = currentGroup.panels.groupBy { it.clusterKey }
                                            val currentOrder = panelsByClusterKey.entries
                                                .sortedBy { (_, ps) -> ps.minOf { it.displayOrder } }
                                                .map { (key, _) -> key }
                                            val fromIndex = currentOrder.indexOf(fromClusterId)
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
                                                reordered.add(toIndex, fromClusterId)
                                                app.configRepository.reorderClustersInGroup(fromGroupId, reordered)
                                                pushGroupOrderUpdatesForClusters(app, reordered, currentGroup.panels)
                                                publishAppTopicForClusterIfMissing(app, fromGroupId, fromClusterId)
                                                // Names the group in the confirmation and scrolls straight to it -
                                                // otherwise a cluster reordered off the bottom of a tall group (or
                                                // past whatever's currently on screen) just seems to vanish on
                                                // release, with no indication of where it actually landed.
                                                showUndoSnackbar(
                                                    app.getString(R.string.home_moved_within, fromClusterName, currentGroup.name),
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
                                            ?.panels?.filter { it.clusterId == fromClusterId }
                                            ?.map { it.id } ?: emptyList()
                                        val oldGroupName = liveGroups.find { it.id == fromGroupId }?.name
                                        val newGroupName = liveGroups.find { it.id == toGroupId }?.name
                                        app.configRepository.moveClusterToGroup(
                                            fromGroupId, toGroupId, fromClusterId,
                                            insertBeforeClusterKey = toClusterKey
                                        )
                                        if (newGroupName != null) {
                                            pushGroupMoveForAutoConfiguredDevices(app, movedPanelIds, toGroupId, newGroupName)
                                        }
                                        publishAppTopicForClusterIfMissing(app, toGroupId, fromClusterId)
                                        // Names the destination group in the confirmation, expands it if it
                                        // was collapsed, and scrolls straight to it - a cross-group move is
                                        // otherwise invisible: the cluster disappears from where it was
                                        // dragged from with nothing on screen showing where it went.
                                        showUndoSnackbar(
                                            app.getString(R.string.home_moved_to, fromClusterName, newGroupName ?: app.getString(R.string.home_another_group)),
                                            liveGroups
                                        ) {
                                            if (oldGroupName != null) {
                                                pushGroupMoveForAutoConfiguredDevices(app, movedPanelIds, fromGroupId, oldGroupName)
                                            }
                                        }
                                        app.configRepository.setGroupCollapsed(toGroupId, false)
                                        backStackEntry.savedStateHandle["scrollToGroupId"] = toGroupId
                                        pendingScrollToClusterKey = "$toGroupId::$fromClusterId"
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
                              // Same try/catch/finally shape as the cluster branch above (and the panel
                              // branch below) - without it, any exception here (e.g. a transient MQTT
                              // publish failure inside pushDashboardGroupOrderUpdates) propagates straight
                              // out of this onDragEnd callback and kills the shared pointerInput(Unit)
                              // coroutine that hosts detectDragGesturesAfterLongPress for every group,
                              // cluster AND panel drag on this screen - since that coroutine never restarts
                              // (its key never changes), the entire screen would stop starting new drags of
                              // any kind from then on, recoverable only by leaving and returning to Home.
                              // Confirmed by a user report of drags simply no longer starting after a handful
                              // of successful ones - this was the one drop-commit branch missing the guard
                              // its siblings already had.
                              try {
                                val fromId = draggedGroupId
                                // Recomputed fresh here rather than trusting onDrag's last value -
                                // a quick drag-and-release might not produce enough callbacks for
                                // a still-settling position to have self-corrected by release time.
                                val toId = fromId?.let { computeNearestGroupKey(it, dragTouchWindowPos) }
                                if (fromId != null && toId != null && fromId != toId) {
                                    val previousGroups = app.configRepository.config.value.groups
                                    val toIndex = previousGroups.indexOfFirst { it.id == toId }
                                    if (toIndex >= 0) {
                                        val movedGroupName = previousGroups.find { it.id == fromId }?.name ?: app.getString(R.string.home_group_fallback)
                                        app.configRepository.moveGroupToIndex(fromId, toIndex + 1)
                                        val reorderedGroups = app.configRepository.config.value.groups
                                        pushDashboardGroupOrderUpdates(app, reorderedGroups)
                                        showUndoSnackbar(app.getString(R.string.home_moved, movedGroupName), previousGroups) {
                                            pushDashboardGroupOrderUpdates(app, previousGroups)
                                        }
                                    }
                                }
                              } catch (c: CancellationException) {
                                draggedGroupId = null
                                draggedToGroupId = null
                                throw c
                              } catch (e: Exception) {
                                Log.e("Z2mDash", "Group drag drop failed", e)
                              } finally {
                                draggedGroupId = null
                                draggedToGroupId = null
                              }
                            } else if (draggedPanelId != null) {
                              try {
                                updatePanelDragTargets()
                                val panelId = draggedPanelId
                                val fromGroupId = draggedPanelFromGroupId
                                val mergeTargetKey = draggedToClusterKey
                                val toPanelIndex = draggedToPanelIndex
                                val willPopOut = draggedPanelWillPopOut
                                val popOutDropPos = dragTouchWindowPos
                                val panel = if (panelId != null && fromGroupId != null) {
                                    app.configRepository.config.value.groups
                                        .find { it.id == fromGroupId }?.panels?.find { it.id == panelId }
                                } else null
                                if (panel != null && fromGroupId != null) {
                                    when {
                                        mergeTargetKey != null -> {
                                            val toGroupId = mergeTargetKey.substringBefore("::")
                                            val toClusterId = mergeTargetKey.substringAfter("::")
                                            if (!(toGroupId == fromGroupId && toClusterId == panel.clusterId)) {
                                                val previousGroups = app.configRepository.config.value.groups
                                                val toClusterName = previousGroups.find { it.id == toGroupId }
                                                    ?.clusterName(toClusterId).orEmpty()
                                                val oldClusterName = previousGroups.find { it.id == fromGroupId }
                                                    ?.clusterName(panel.clusterId).orEmpty()
                                                app.configRepository.movePanelIntoCluster(
                                                    fromGroupId, panel.id, toGroupId, toClusterId
                                                )
                                                pushPanelClusterOverrideIfAutoConfigured(app, panel, toClusterName, toClusterId)
                                                publishAppTopicForClusterIfMissing(app, toGroupId, toClusterId)
                                                showUndoSnackbar(app.getString(R.string.home_moved_into, panel.label, toClusterName), previousGroups) {
                                                    pushPanelClusterOverrideIfAutoConfigured(app, panel, oldClusterName, panel.clusterId)
                                                }
                                                app.configRepository.setGroupCollapsed(toGroupId, false)
                                                backStackEntry.savedStateHandle["scrollToGroupId"] = toGroupId
                                                pendingScrollToClusterKey = "$toGroupId::$toClusterId"
                                            }
                                        }
                                        willPopOut && panel.clusterId.isNotBlank() && panel.label.isNotBlank() -> {
                                            val siblingCount = app.configRepository.config.value.groups
                                                .find { it.id == fromGroupId }?.panels
                                                ?.count { it.clusterId == panel.clusterId } ?: 0
                                            if (siblingCount > 1) {
                                                val previousGroups = app.configRepository.config.value.groups
                                                val oldClusterName = previousGroups.find { it.id == fromGroupId }
                                                    ?.clusterName(panel.clusterId).orEmpty()
                                                val newClusterId = app.configRepository
                                                    .movePanelToOwnCluster(fromGroupId, panel.id, panel.label)
                                                // movePanelToOwnCluster always appends the new cluster past every
                                                // other one's displayOrder - immediately re-slotted here into the
                                                // gap it was actually dropped in, rather than leaving it stuck at
                                                // the end of the group regardless of where that was. The target
                                                // index is every OTHER cluster (in this same group) whose last-
                                                // known bounds read as "before" popOutDropPos in reading order -
                                                // a whole row above it, or the same row and to its left - counted
                                                // up; that count IS the correct 0-based insert position. A single
                                                // "nearest cluster, insert before it" was tried first and was
                                                // wrong whenever the nearest cluster was the one *above* the drop
                                                // point (it needs inserting after that one, not before) - counting
                                                // every earlier cluster handles both sides of the gap correctly.
                                                val currentGroup = app.configRepository.config.value.groups
                                                    .find { it.id == fromGroupId }
                                                if (currentGroup != null && newClusterId != null) {
                                                    val currentOrder = currentGroup.panels
                                                        .groupBy { it.clusterKey }
                                                        .entries.sortedBy { (_, ps) -> ps.minOf { it.displayOrder } }
                                                        .map { (key, _) -> key }
                                                    val fromIndex = currentOrder.indexOf(newClusterId)
                                                    if (fromIndex >= 0) {
                                                        val targetIndex = currentOrder.withIndex().count { (i, clusterKey) ->
                                                            if (i == fromIndex) return@count false
                                                            val rect = clusterBounds["$fromGroupId::$clusterKey"]
                                                            rect != null && isWithinTrustedBounds(rect.center) &&
                                                                isBeforeDropPoint(rect, popOutDropPos)
                                                        }
                                                        if (targetIndex != fromIndex) {
                                                            val reordered = currentOrder.toMutableList()
                                                            reordered.removeAt(fromIndex)
                                                            reordered.add(targetIndex, newClusterId)
                                                            app.configRepository.reorderClustersInGroup(fromGroupId, reordered)
                                                        }
                                                    }
                                                }
                                                if (newClusterId != null) {
                                                    pushPanelClusterOverrideIfAutoConfigured(app, panel, panel.label, newClusterId)
                                                    publishAppTopicForClusterIfMissing(app, fromGroupId, newClusterId)
                                                }
                                                showUndoSnackbar(app.getString(R.string.home_moved, panel.label), previousGroups) {
                                                    pushPanelClusterOverrideIfAutoConfigured(app, panel, oldClusterName, panel.clusterId)
                                                }
                                            }
                                        }
                                        toPanelIndex >= 0 && panel.clusterId.isNotBlank() -> {
                                            val currentPanels = app.configRepository.config.value.groups
                                                .find { it.id == fromGroupId }?.panels
                                                ?.filter { it.clusterId == panel.clusterId }
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
                                                    publishAppTopicForClusterIfMissing(app, fromGroupId, panel.clusterId)
                                                    showUndoSnackbar(app.getString(R.string.home_moved, panel.label), previousGroups) {
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
            // FABs, which float on top of content without reserving space for themselves - one
            // more FAB row's worth for each extra row they've wrapped onto. On TV the end edge is
            // also kept clear for the scrollbar drawn over it (see TvListScrollbar below).
            contentPadding = PaddingValues(end = if (isTv) TvScrollbarWidth else 0.dp, bottom = 96.dp + extraFabRowsPadding)
        ) {
            // Only rendered in the list when NOT active - while active it's drawn as a fixed
            // banner above this whole LazyColumn instead (see its own computation/doc further up,
            // right after the Scaffold's content lambda opens), so it isn't shown twice.
            if (config.brokers.isNotEmpty() && !permitJoinIsOn) {
                item(key = "permitJoinBar") { permitJoinBannerContent() }
            }
            items(config.pendingAutoConfigDevices, key = { "${it.brokerId}|${it.appConfigTopic}" }) { pending ->
                // Must match notifyNewDeviceFound's own notificationId exactly ("brokerId|topic",
                // not deviceName) - two pending devices can share a displayed name (see
                // notifyNewDeviceFound's own doc), so cancelling by name alone could dismiss the
                // wrong device's notification, or none at all if a differently-named device
                // happened to hash the same.
                val pendingNotificationId = "${pending.brokerId}|${pending.appConfigTopic}".hashCode()
                PendingDeviceBanner(
                    pending = pending,
                    onAdd = {
                        addPendingDevice(app, payloadsState.value, pending)
                        // Both actions mean the user handled this via the in-app banner, so the
                        // matching system notification shouldn't linger.
                        NotificationManagerCompat.from(context).cancel(pendingNotificationId)
                    },
                    onIgnore = {
                        app.configRepository.ignoreAppConfigTopic(pending.brokerId, pending.appConfigTopic)
                        NotificationManagerCompat.from(context).cancel(pendingNotificationId)
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
                // Same leak class as compoundKey's own DisposableEffect below (see its comment) -
                // group.id is normally stable, so this only matters when the group itself is
                // deleted, but with nothing to remove these a deleted group's header position/
                // bounds would stay in these maps forever, able to out-compete a real group/
                // header as a drag's "nearest" match.
                DisposableEffect(group.id) {
                    onDispose {
                        groupCenters.remove(group.id)
                        groupHeaderCoordinates.remove(group.id)
                        clusterBounds.remove(headerClusterKey)
                    }
                }
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
                                // Deliberately NOT .clickable on this whole Row any more. It used to be, and
                                // that was harmless while group dragging had its own per-item detector, but
                                // once drag detection was consolidated onto the LazyColumn's single shared
                                // detector (see that detector's own comment), Compose's clickable consuming
                                // the initial pointer-down for its own press/ripple handling started
                                // starving the parent detector of the unconsumed down it needs to ever
                                // recognise a long press here - confirmed by a user report that group
                                // drag-and-drop stopped working outright (no drag feedback at all, unlike
                                // the separate stuck-mid-drag watchdog issue). Cluster captions never had
                                // this problem, since they were never given their own clickable to begin
                                // with (see their own captionRowModifier). The collapse toggle now lives on
                                // just the chevron icon below instead - small enough that it's not where
                                // anyone would actually grab this row to drag it.
                                .onGloballyPositioned { groupHeaderCoordinates[group.id] = it },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Text(group.name, style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.width(4.dp))
                            IconButton(
                                onClick = {
                                    val collapsing = !group.collapsed
                                    app.configRepository.setGroupCollapsed(group.id, collapsing)
                                    // Collapsing removes this group's whole content item, so
                                    // everything below it jumps up to fill the gap - without this,
                                    // the group (and the scroll position generally) ends up
                                    // wherever that shift happens to land rather than staying
                                    // anchored where the user was just looking, which could leave
                                    // the just-collapsed group scrolled out of view entirely. Reuses
                                    // the same scrollToGroupId mechanism the post-drag scroll uses.
                                    if (collapsing) {
                                        backStackEntry.savedStateHandle["scrollToGroupId"] = group.id
                                    }
                                }
                            ) {
                                Icon(
                                    if (group.collapsed) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                                    contentDescription = stringResource(if (group.collapsed) R.string.home_expand_group else R.string.home_collapse_group, group.name)
                                )
                            }
                        }
                        IconButton(onClick = { renamingGroup = group; renameText = group.name }) {
                            Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.home_rename_group, group.name))
                        }
                        IconButton(onClick = { navController.navigate("group/${group.id}/panel/new") }) {
                            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.home_add_panel_to, group.name))
                        }
                        IconButton(onClick = { pendingGroupDelete = group.id }) {
                            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.home_delete_group_named, group.name))
                        }
                    }
                } // Column
                } // Surface
                } // stickyHeader

                if (!group.collapsed) {
                    item(key = "${group.id}_content") {
                        // Panels sharing a clusterId render together in one card; standalone
                        // (blank-clusterId) panels each get their own unique bucket.
                        val clusters = LinkedHashMap<String, MutableList<Panel>>()
                        group.panels.forEach { panel ->
                            clusters.getOrPut(panel.clusterKey) { mutableListOf() }.add(panel)
                        }
                        // Sort clusters by lowest displayOrder (falls back to insertion order at
                        // the Int.MAX_VALUE default), and sort each cluster's panels too, so panels
                        // can be reordered *within* a cluster, not just relative to other clusters.
                        val orderedClusters = clusters.values
                            .map { bucket -> bucket.sortedBy { it.displayOrder } }
                            .sortedBy { bucket -> bucket.minOf { it.displayOrder } }
                        // While a filter is active, clusters (and standalone tiles, each their own
                        // single-panel bucket above) that don't match it are left out of
                        // row-packing/rendering entirely, rather than shown dimmed or in a
                        // separate dialog. Deliberately
                        // NOT wrapped in remember/derivedStateOf per bucket - calling those inside
                        // a plain .filter{} loop whose iteration count varies is a known Compose
                        // slot-alignment hazard without an explicit key() per item, which isn't
                        // available here. Reading payloadsState/timestampsState directly instead
                        // means every expanded group's content recomposes on every MQTT message
                        // while a filter is on (unlike everywhere else on this screen), but only
                        // for as long as one is deliberately switched on.
                        val filter = activeFilter
                        val visibleClusters = if (filter == null) {
                            orderedClusters
                        } else {
                            val payloads = payloadsState.value
                            val timestamps = timestampsState.value
                            val nowMillis = nowMillisState.longValue
                            orderedClusters.filter { bucket ->
                                when (filter) {
                                    DashboardFilter.STALE -> isClusterStale(bucket, payloads, timestamps, nowMillis)
                                    DashboardFilter.LOW_BATTERY -> isClusterLowBattery(bucket, payloads)
                                    DashboardFilter.WEAK_SIGNAL -> isClusterWeakSignal(bucket, payloads)
                                    DashboardFilter.LOW_MOISTURE -> isClusterLowMoisture(bucket, payloads)
                                    DashboardFilter.HIGH_MOISTURE -> isClusterHighMoisture(bucket, payloads)
                                }
                            }
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
                        // Less the TV scrollbar's column, which the list reserves at its end edge.
                        val availableRowWidth = screenWidthDp - 24.dp - if (isTv) TvScrollbarWidth else 0.dp
                        val packedRows = remember(visibleClusters, standaloneTileWidth, availableRowWidth) {
                            val rows = mutableListOf<MutableList<List<Panel>>>()
                            var currentRow = mutableListOf<List<Panel>>()
                            var usedWidth = 0.dp
                            visibleClusters.forEach { panelsInCluster ->
                                val itemWidth = if (panelsInCluster.first().clusterId.isBlank()) {
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
                                        val clusterId = panelsInCluster.first().clusterId
                                        val name = group.clusterName(clusterId)
                                        // Stable per-cluster identity, independent of list position -
                                        // without this, Compose can reuse another cluster's remembered
                                        // state (like ageText) when the list reorders.
                                        key(panelsInCluster.first().id) {
                                            if (clusterId.isBlank()) {
                                                val standalonePanel = panelsInCluster.first()
                                                // Same leak as clusterBounds below - see that comment.
                                                DisposableEffect(standalonePanel.id) {
                                                    onDispose { panelBounds.remove(standalonePanel.id) }
                                                }
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
                                                val compoundKey = "${group.id}::$clusterId"
                                                val clusterBringIntoViewRequester = remember(compoundKey) { BringIntoViewRequester() }
                                                DisposableEffect(compoundKey) {
                                                    clusterBringIntoViewRequesters[compoundKey] = clusterBringIntoViewRequester
                                                    onDispose {
                                                        clusterBringIntoViewRequesters.remove(compoundKey)
                                                        clusterCaptionCoordinates.remove(compoundKey)
                                                        // clusterBounds was the one map NOT cleaned up here, unlike
                                                        // its two siblings above - every cross-group move
                                                        // changes compoundKey, leaving the OLD
                                                        // key's Rect permanently stuck in the map (scrolling the
                                                        // card off/on does the same, re-disposing and re-composing
                                                        // under the same key, but a rename/move disposes the OLD
                                                        // key for good with nothing ever removing it). Confirmed
                                                        // as the cause of cluster drags landing in random, wrong
                                                        // groups: computeNearestClusterKey's nearest-match search
                                                        // runs over every entry in this map, so a ghost rect left
                                                        // over from an earlier rename/move - frozen whereever that
                                                        // cluster last rendered - could out-compete the real
                                                        // cluster actually under the finger, with the outcome
                                                        // depending on this session's whole drag/rename history
                                                        // rather than anything visible on screen right now.
                                                        clusterBounds.remove(compoundKey)
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
                                                        // Disambiguated against this group's own existing
                                                        // cluster names ("X copy", then "X copy 2", "X copy
                                                        // 3", ...) rather than always suggesting a bare "X
                                                        // copy". The clone is always its own cluster (by id)
                                                        // either way - this just keeps the two cards
                                                        // distinguishable on screen.
                                                        val existingNames = group.clusters.map { it.name }.toSet()
                                                        var candidate = app.getString(R.string.home_cluster_copy, name)
                                                        var suffix = 2
                                                        while (candidate in existingNames) {
                                                            candidate = app.getString(R.string.home_cluster_copy_n, name, suffix)
                                                            suffix++
                                                        }
                                                        duplicateClusterNameText = candidate
                                                        duplicateTopicText = prefix
                                                    },
                                                    onRetopic = {
                                                        val prefix = SensorDiscovery.commonTopicPrefix(panelsInCluster)
                                                        pendingClusterRetopic = PendingClusterRetopic(
                                                            groupId = group.id,
                                                            clusterId = clusterId,
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
        // TV only: a D-pad-operable scrollbar down the right edge, for paging a screen at a time
        // through a long dashboard instead of stepping tile by tile. Stops above the FABs (the
        // Scaffold's 16dp margin plus each FAB row), which float over this same corner.
        if (isTv) {
            TvListScrollbar(
                state = listState,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .fillMaxHeight()
                    .padding(top = 8.dp, bottom = 16.dp + (56.dp + fabSpacing) * fabRows.size)
                    .width(TvScrollbarWidth)
            )
        }
            } // Box(weight)
        } // Column
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
        // (group, cluster) for every cluster across every group whose name matches
        // case-insensitively as a substring - recomputed only while the dialog is actually open
        // and the query changes, not on every recomposition of the screen itself.
        val clusterSearchResults = remember(config, clusterSearchQuery) {
            if (clusterSearchQuery.isBlank()) {
                emptyList()
            } else {
                config.groups.flatMap { group ->
                    group.clusters
                        .filter { it.name.contains(clusterSearchQuery, ignoreCase = true) }
                        .map { cluster -> group to cluster }
                }.sortedBy { (_, cluster) -> cluster.name.lowercase() }
            }
        }
        fun jumpToCluster(groupId: String, clusterId: String) {
            // Same scroll mechanism a cross-group cluster drag already uses to reveal where a
            // moved cluster landed - a coarse index-based scroll to get the group's content
            // composed, then a precise bringIntoView once the specific card has mounted.
            app.configRepository.setGroupCollapsed(groupId, false)
            backStackEntry.savedStateHandle["scrollToGroupId"] = groupId
            pendingScrollToClusterKey = "$groupId::$clusterId"
            showClusterSearch = false
            clusterSearchQuery = ""
        }
        AlertDialog(
            onDismissRequest = {
                showClusterSearch = false
                clusterSearchQuery = ""
            },
            title = { Text(stringResource(R.string.home_search_clusters)) },
            text = {
                Column {
                    OutlinedTextField(
                        value = clusterSearchQuery,
                        onValueChange = { clusterSearchQuery = it },
                        label = { Text(stringResource(R.string.home_cluster_name)) },
                        singleLine = true,
                        keyboardOptions = tvAwareKeyboardOptions(),
                        modifier = Modifier.fillMaxWidth().clearFocusOnBack()
                    )
                    Spacer(Modifier.height(8.dp))
                    if (clusterSearchQuery.isNotBlank() && clusterSearchResults.isEmpty()) {
                        Text(stringResource(R.string.home_no_matching_clusters), style = MaterialTheme.typography.bodySmall)
                    } else {
                        LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                            items(
                                clusterSearchResults,
                                key = { (group, cluster) -> "${group.id}::${cluster.id}" }
                            ) { (group, cluster) ->
                                ListItem(
                                    headlineContent = { Text(cluster.name) },
                                    supportingContent = { Text(group.name) },
                                    modifier = Modifier.clickable { jumpToCluster(group.id, cluster.id) }
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
                }) { Text(stringResource(R.string.common_close)) }
            }
        )
    }

    pendingGroupDelete?.let { groupId ->
        AlertDialog(
            onDismissRequest = { pendingGroupDelete = null },
            title = { Text(stringResource(R.string.home_delete_group_title)) },
            text = { Text(stringResource(R.string.home_delete_group_text)) },
            confirmButton = {
                TextButton(onClick = {
                    val devicesBefore = app.configRepository.config.value.autoConfiguredDevices
                    app.configRepository.deleteGroup(groupId)
                    clearRetainedAppTopicsForOrphanedDevices(app, devicesBefore)
                    pendingGroupDelete = null
                }) { Text(stringResource(R.string.common_delete)) }
            },
            dismissButton = { TextButton(onClick = { pendingGroupDelete = null }) { Text(stringResource(R.string.common_cancel)) } }
        )
    }

    renamingGroup?.let { group ->
        AlertDialog(
            onDismissRequest = { renamingGroup = null },
            title = { Text(stringResource(R.string.home_edit_group)) },
            text = {
                Column {
                    OutlinedTextField(
                        value = renameText,
                        onValueChange = { renameText = it },
                        label = { Text(stringResource(R.string.broker_name)) },
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
                    ) { Text(stringResource(R.string.home_force_upload_copy)) }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.home_force_upload_copy_detail),
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
                    ) { Text(stringResource(R.string.home_retopic_all)) }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.home_retopic_all_detail),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (renameText.isNotBlank() && renameText != group.name) {
                        app.configRepository.upsertGroup(group.copy(name = renameText))
                        pushGroupRenameForAutoConfiguredDevices(app, group.id, group.name, renameText)
                    }
                    renamingGroup = null
                }) { Text(stringResource(R.string.common_save)) }
            },
            dismissButton = {
                TextButton(onClick = { renamingGroup = null }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }

    pendingForceRepublishGroup?.let { group ->
        AlertDialog(
            onDismissRequest = { pendingForceRepublishGroup = null },
            title = { Text(stringResource(R.string.home_force_upload_title, group.name)) },
            text = {
                Text(stringResource(R.string.home_force_upload_text))
            },
            confirmButton = {
                TextButton(onClick = {
                    forceRepublishGroupAppTopics(app, group.id)
                    pendingForceRepublishGroup = null
                    undoCoroutineScope.launch {
                        snackbarHostState.showSnackbar(app.getString(R.string.home_republished, group.name))
                    }
                }) { Text(stringResource(R.string.force_upload)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingForceRepublishGroup = null }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }

    pendingGroupRetopic?.let { group ->
        AlertDialog(
            onDismissRequest = { pendingGroupRetopic = null },
            title = { Text(stringResource(R.string.home_retopic_all_title, group.name)) },
            text = {
                Column {
                    Text(
                        stringResource(R.string.home_retopic_all_text),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = groupRetopicOldTopicText,
                        onValueChange = { groupRetopicOldTopicText = it },
                        label = { Text(stringResource(R.string.home_old_topic)) },
                        singleLine = true,
                        keyboardOptions = tvAwareKeyboardOptions(),
                        modifier = Modifier.clearFocusOnBack()
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = groupRetopicNewTopicText,
                        onValueChange = { groupRetopicNewTopicText = it },
                        label = { Text(stringResource(R.string.home_new_topic)) },
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
                                    app.getString(R.string.home_retopic_none, group.name)
                                } else {
                                    app.resources.getQuantityString(
                                        R.plurals.home_retopic_moved, movedClusters.size, movedClusters.size, groupRetopicNewTopicText
                                    )
                                }
                            )
                        }
                        groupRetopicOldTopicText = ""
                        groupRetopicNewTopicText = ""
                    },
                    enabled = groupRetopicOldTopicText.isNotBlank() && groupRetopicNewTopicText.isNotBlank() &&
                        groupRetopicOldTopicText != groupRetopicNewTopicText
                ) { Text(stringResource(R.string.home_retopic)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingGroupRetopic = null }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }

    pendingClusterDelete?.let { pending ->
        AlertDialog(
            onDismissRequest = { pendingClusterDelete = null },
            title = { Text(stringResource(R.string.home_delete_cluster_title, pending.name)) },
            text = { Text(pluralStringResource(R.plurals.home_delete_cluster_text, pending.panelIds.size, pending.panelIds.size)) },
            confirmButton = {
                TextButton(onClick = {
                    val devicesBefore = app.configRepository.config.value.autoConfiguredDevices
                    app.configRepository.removePanels(pending.groupId, pending.panelIds)
                    clearRetainedAppTopicsForOrphanedDevices(app, devicesBefore)
                    pendingClusterDelete = null
                }) { Text(stringResource(R.string.common_delete)) }
            },
            dismissButton = { TextButton(onClick = { pendingClusterDelete = null }) { Text(stringResource(R.string.common_cancel)) } }
        )
    }

    pendingClusterDuplicate?.let { pending ->
        AlertDialog(
            onDismissRequest = { pendingClusterDuplicate = null },
            title = { Text(stringResource(R.string.home_duplicate_title, pending.name)) },
            text = {
                Column {
                    if (SensorDiscovery.isSuspiciouslyBroadTopicPrefix(pending.originalTopicPrefix)) {
                        Text(
                            stringResource(
                                R.string.home_duplicate_broad_warning,
                                pending.originalTopicPrefix.ifBlank { stringResource(R.string.home_nothing) }
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(Modifier.height(12.dp))
                    }
                    OutlinedTextField(
                        value = duplicateTopicText,
                        onValueChange = { duplicateTopicText = it },
                        label = { Text(stringResource(R.string.panel_topic)) },
                        singleLine = true
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = duplicateClusterNameText,
                        onValueChange = { duplicateClusterNameText = it },
                        label = { Text(stringResource(R.string.home_cluster_name)) },
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
                        val newClusterId = app.configRepository.duplicateCluster(
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
                            ?.filter { newClusterId != null && it.clusterId == newClusterId }
                            ?.sortedBy { it.displayOrder } ?: emptyList()
                        val seedValues = sourcePanels.zip(newPanels).mapNotNull { (source, new) ->
                            if (source !is Panel.Sensor) return@mapNotNull null
                            val raw = app.connectionManager.latestPayloads.value["${source.brokerId}|${source.topic}"]
                            val value = raw?.let { JsonPath.extract(it, source.jsonPath) } ?: return@mapNotNull null
                            new.id to value
                        }.toMap()
                        if (newClusterId != null) {
                            publishAppTopicForClusterIfMissing(app, pending.groupId, newClusterId, seedValues)
                        }
                        pendingClusterDuplicate = null
                    },
                    enabled = duplicateClusterNameText.isNotBlank()
                ) { Text(stringResource(R.string.home_duplicate)) }
            },
            dismissButton = { TextButton(onClick = { pendingClusterDuplicate = null }) { Text(stringResource(R.string.common_cancel)) } }
        )
    }

    pendingClusterRetopic?.let { pending ->
        AlertDialog(
            onDismissRequest = { pendingClusterRetopic = null },
            title = { Text(stringResource(R.string.home_change_topic_title, pending.clusterName)) },
            text = {
                Column {
                    Text(
                        stringResource(R.string.home_change_topic_text),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(R.string.home_current_topic, pending.currentTopicPrefix.ifBlank { stringResource(R.string.home_none) }),
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (SensorDiscovery.isSuspiciouslyBroadTopicPrefix(pending.currentTopicPrefix)) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.home_change_topic_broad_warning),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = retopicNewTopicText,
                        onValueChange = { retopicNewTopicText = it },
                        label = { Text(stringResource(R.string.home_new_topic)) },
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
                            app, pending.groupId, pending.clusterId, pending.currentTopicPrefix, retopicNewTopicText
                        )
                        pendingClusterRetopic = null
                        retopicNewTopicText = ""
                    },
                    enabled = retopicNewTopicText.isNotBlank() && retopicNewTopicText != pending.currentTopicPrefix
                ) { Text(stringResource(R.string.common_save)) }
            },
            dismissButton = { TextButton(onClick = { pendingClusterRetopic = null }) { Text(stringResource(R.string.common_cancel)) } }
        )
    }

    pendingValueEdit?.let { panel ->
        AlertDialog(
            onDismissRequest = { pendingValueEdit = null },
            title = { Text(stringResource(R.string.home_edit_value_title, panel.label)) },
            text = {
                OutlinedTextField(
                    value = valueEditText,
                    onValueChange = { valueEditText = it },
                    label = { Text(stringResource(R.string.home_value)) },
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
                        // An editable panel's own topic is often an auto-configured device's own
                        // "/app" topic itself (a moisture min/max threshold embedded in its config
                        // payload - see SensorDiscovery.buildAppConfigPayload's "seed value" doc).
                        // Unlike every AutoConfigPush mutation, this publish never went through
                        // publishAndMarkApplied, so this phone's own echo of it never matched
                        // device.lastAppliedPayload and always triggered a full, unnecessary
                        // reconcile of the whole device - mark it the same way here so the echo is
                        // recognised as already applied, same as every other field push.
                        val device = app.configRepository.config.value.autoConfiguredDevices
                            .find { it.brokerId == panel.brokerId && it.appConfigTopic == panel.topic }
                        if (device != null) {
                            app.configRepository.markAutoConfiguredDevicePayloadApplied(
                                device.brokerId, device.appConfigTopic, updatedPayload, device.lastKnownOrderVersion
                            )
                        }
                        pendingValueEdit = null
                    },
                    enabled = valueEditText.isNotBlank()
                ) { Text(stringResource(R.string.common_save)) }
            },
            dismissButton = { TextButton(onClick = { pendingValueEdit = null }) { Text(stringResource(R.string.common_cancel)) } }
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
    val clusterId: String,
    // Display only (the dialog title).
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
    val deviceConfig = SensorDiscovery.parseDeviceAppConfig(currentPayload) ?: return
    val orderedDevicePanels = orderedPanelsOf(app, device)

    // Indexed by each field/control's own position in "panels"/"controls" (sensorFieldIndex/
    // controlIndex), not by name/label - see updateOrderingInAppPayload's doc for why keying by
    // name collapsed duplicate field names/labels onto a single order value.
    val panelOrderByIndex = mutableMapOf<Int, Int>()
    val controlOrderByIndex = mutableMapOf<Int, Int>()
    orderedPanelIds.forEachIndexed { order, id ->
        val panel = clusterPanels.find { it.id == id } ?: return@forEachIndexed
        when (panel) {
            is Panel.Sensor -> {
                val index = SensorDiscovery.sensorFieldIndex(deviceConfig, orderedDevicePanels, panel.id, panel.jsonPath)
                    ?: return@forEachIndexed
                panelOrderByIndex[index] = order
            }
            is Panel.Toggle -> {
                val index = SensorDiscovery.controlIndex(deviceConfig, orderedDevicePanels, panel.id, panel.commandTopic)
                    ?: return@forEachIndexed
                controlOrderByIndex[index] = order
            }
            is Panel.Button -> {
                val index = SensorDiscovery.controlIndex(deviceConfig, orderedDevicePanels, panel.id, panel.commandTopic)
                    ?: return@forEachIndexed
                controlOrderByIndex[index] = order
            }
        }
    }

    val orderVersion = System.currentTimeMillis()
    val updatedPayload = SensorDiscovery.updateOrderingInAppPayload(
        currentPayload, panelOrderByIndex, controlOrderByIndex, orderVersion
    ) ?: return
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
    val panelsByCluster = groupPanels.groupBy { it.clusterKey }
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
 * "brokerId|topic" keys where [panel]'s device would report device-level fields like "battery"
 * and "linkquality": the panel's own reading topic, plus that topic with any "/app" suffix
 * stripped - an editable threshold tile reads from the device's "/app" config topic, while those
 * fields are on the device's main topic (same matching LowBatteryAlertManager.deviceNameFor
 * does). A Button has no reading topic.
 */
private fun deviceTopicKeysFor(panel: Panel): List<String> {
    val topic = when (panel) {
        is Panel.Sensor -> panel.topic
        is Panel.Toggle -> panel.stateTopic
        is Panel.Button -> ""
    }.takeIf { it.isNotBlank() } ?: return emptyList()
    return listOf(topic, topic.removeSuffix("/app")).distinct().map { "${panel.brokerId}|$it" }
}

/** Whether any device behind [panels] reports a battery level LowBatteryAlertManager counts as low. */
private fun isClusterLowBattery(panels: List<Panel>, payloads: Map<String, String>): Boolean =
    panels.any { panel ->
        deviceTopicKeysFor(panel).any { key ->
            LowBatteryAlertManager.isLowBattery(
                payloads[key]?.let { JsonPath.extract(it, "battery") }?.toDoubleOrNull()
            )
        }
    }

/** The dashboard filters the Home screen's filter FABs switch between - at most one at a time. */
private enum class DashboardFilter { STALE, LOW_BATTERY, WEAK_SIGNAL, LOW_MOISTURE, HIGH_MOISTURE }

/** One Home screen FAB; [highlighted] marks an active filter, [menu] is a dropdown anchored to it. */
private class FabSpec(
    val key: String,
    val icon: ImageVector,
    val contentDescription: String,
    val highlighted: Boolean = false,
    val onClick: () -> Unit,
    val menu: (@Composable () -> Unit)? = null
)

/** One entry in the filter FAB's menu; [available] when there's anything on the dashboard for it to find. */
private class FilterOption(
    val filter: DashboardFilter,
    val icon: ImageVector,
    val label: String,
    val available: Boolean
)

/**
 * A moisture sensor with an ideal range to be low against - the same test WateringAlertManager
 * uses to decide which panels it watches.
 */
private fun isMoistureSensor(panel: Panel): Boolean =
    panel is Panel.Sensor && panel.icon == TileIcon.MOISTURE && panel.idealRangeTopic.isNotBlank()

/** Whether any moisture sensor in [panels] currently reads below its ideal range's minimum. */
private fun isClusterLowMoisture(panels: List<Panel>, payloads: Map<String, String>): Boolean =
    panels.any { it is Panel.Sensor && isMoistureSensor(it) && idealRangeAlert(it, payloads) == SensorAlert.BELOW_MIN }

/** Whether any moisture sensor in [panels] currently reads above its ideal range's maximum. */
private fun isClusterHighMoisture(panels: List<Panel>, payloads: Map<String, String>): Boolean =
    panels.any { it is Panel.Sensor && isMoistureSensor(it) && idealRangeAlert(it, payloads) == SensorAlert.ABOVE_MAX }

/**
 * Where [panel]'s current reading sits against its ideal range - what colours its tile, and what
 * the low-moisture filter checks. Compares the raw extracted value, not the rounded display one -
 * comparing a rounded number could misclassify a borderline reading.
 */
private fun idealRangeAlert(panel: Panel.Sensor, payloads: Map<String, String>): SensorAlert {
    if (panel.idealRangeTopic.isBlank()) return SensorAlert.NONE
    val numericValue = payloads["${panel.brokerId}|${panel.topic}"]
        ?.let { JsonPath.extract(it, panel.jsonPath) }?.toDoubleOrNull()
        ?: return SensorAlert.NONE
    val idealRaw = payloads["${panel.brokerId}|${panel.idealRangeTopic}"]
    val min = idealRaw?.let { JsonPath.extract(it, panel.idealMinPath) }?.toDoubleOrNull()
    val max = idealRaw?.let { JsonPath.extract(it, panel.idealMaxPath) }?.toDoubleOrNull()
    return when {
        min != null && numericValue < min -> SensorAlert.BELOW_MIN
        max != null && numericValue > max -> SensorAlert.ABOVE_MAX
        min != null || max != null -> SensorAlert.IN_RANGE
        else -> SensorAlert.NONE
    }
}

// Zigbee link quality (0-255) below this counts as a weak link - the weak-signal FAB's filter and
// the dark yellow cluster outline.
private const val WEAK_SIGNAL_LQI = 50

/** Whether any device behind [panels] reports a "linkquality" below WEAK_SIGNAL_LQI. */
private fun isClusterWeakSignal(panels: List<Panel>, payloads: Map<String, String>): Boolean =
    panels.any { panel ->
        deviceTopicKeysFor(panel).any { key ->
            val lqi = payloads[key]?.let { JsonPath.extract(it, "linkquality") }?.toDoubleOrNull()
            lqi != null && lqi < WEAK_SIGNAL_LQI
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
    // Keyed/derived the same way as ageState, so it only recomposes when the result flips.
    val lowBatteryState = remember(panels) {
        derivedStateOf { isClusterLowBattery(panels, payloadsState.value) }
    }
    val weakSignalState = remember(panels) {
        derivedStateOf { isClusterWeakSignal(panels, payloadsState.value) }
    }
    // Only one outline at a time, most urgent first: stale (red) - a stale reading is old news
    // anyway - then low battery (blue), then weak signal (dark yellow).
    val showLowBattery = lowBatteryState.value && !isStale
    val showWeakSignal = weakSignalState.value && !isStale && !showLowBattery

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
                } else if (showLowBattery) {
                    // Same outline as the stale indicator, in the same blue a sensor tile uses
                    // for a reading above its ideal range.
                    Modifier.border(2.dp, AlertBlue, RoundedCornerShape(12.dp))
                } else if (showWeakSignal) {
                    Modifier.border(2.dp, AlertYellow, RoundedCornerShape(12.dp))
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

                        // Without this a tile's last rect stayed in panelBounds forever once it
                        // left composition (its group collapsed, it scrolled away, or a restore
                        // replaced it) - and since a long-press hit-tests panelBounds, that
                        // invisible ghost rect could be grabbed instead of whatever was really
                        // under the finger, e.g. a group header, dragging a hidden tile instead.
                        DisposableEffect(panel.id) {
                            onDispose { panelBounds.remove(panel.id) }
                        }
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
                        stringResource(R.string.home_age, ageText),
                        style = ageTextStyle,
                        color = staleIndicatorColor
                    )
                    if (isStale) {
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = stringResource(R.string.home_stale_description),
                            tint = staleIndicatorColor,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                if (showLowBattery) {
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        Icons.Default.BatteryAlert,
                        contentDescription = stringResource(R.string.home_low_battery_description),
                        tint = AlertBlue,
                        modifier = Modifier.size(20.dp)
                    )
                }
                if (showWeakSignal) {
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        Icons.Default.SignalWifi4Bar,
                        contentDescription = stringResource(R.string.home_weak_signal_description, WEAK_SIGNAL_LQI),
                        tint = AlertYellow,
                        modifier = Modifier.size(20.dp)
                    )
                }
                IconButton(onClick = onDuplicate, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = stringResource(R.string.home_duplicate_cluster, name),
                        modifier = Modifier.size(16.dp)
                    )
                }
                IconButton(onClick = onRetopic, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Default.SwapHoriz,
                        contentDescription = stringResource(R.string.home_change_topic_cluster, name),
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
                        handle?.set("presetClusterId", panels.first().clusterId)
                        panels.firstOrNull()?.brokerId?.let { handle?.set("presetBrokerId", it) }
                        SensorDiscovery.commonTopicPrefix(panels).takeIf { it.isNotBlank() }
                            ?.let { handle?.set("presetTopic", it) }
                        navController.navigate("group/$groupId/panel/new")
                    },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = stringResource(R.string.home_add_tile_to, name),
                        modifier = Modifier.size(16.dp)
                    )
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = stringResource(R.string.home_delete_cluster, name),
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
                        isPresenceField -> if (extracted != null) app.getString(if (isPresent) R.string.tile_presence_detected else R.string.tile_presence_clear) else "--"
                        extracted != null -> extracted.toDoubleOrNull()?.let { num -> "%.${panel.decimals}f".format(num) } ?: extracted
                        else -> "--"
                    }
                    val alert = idealRangeAlert(panel, payloads)
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

        val targetGroupId = app.configRepository.resolveOrCreateGroup(deviceConfig.groupId, deviceConfig.group)
        val liveConfig = app.configRepository.config.value
        val targetGroup = liveConfig.groups.find { it.id == targetGroupId } ?: return
        val built = SensorDiscovery.buildPanels(
            brokerId = pending.brokerId,
            sensorTopic = pending.sensorTopic,
            sensorFieldKeys = sensorFieldKeys,
            appConfigTopic = pending.appConfigTopic,
            appConfigPayload = appConfigPayload,
            deviceConfig = deviceConfig,
            groupName = targetGroup.name,
            groupClusters = targetGroup.clusters,
            reservedPanelIds = liveConfig.groups.flatMap { g -> g.panels.map { it.id } }.toSet()
        )
        val newPanels = built.panels
        if (newPanels.isEmpty()) {
            Log.w(tag, "buildPanels produced zero panels for ${pending.deviceName} - aborting add. " +
                "deviceConfig.panelFields=${deviceConfig.panelFields}, sensorFieldKeys=$sensorFieldKeys")
            return
        }

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
            newPanels = newPanels,
            newClusters = built.clusters,
            isLiveDevice = { app.connectionManager.isLive("${it.brokerId}|${it.appConfigTopic}") }
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
        title = { Text(stringResource(R.string.permit_join_title)) },
        text = {
            if (allTopics.isEmpty()) {
                Text(stringResource(R.string.permit_join_dialog_no_brokers))
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
                            label = { Text(stringResource(R.string.broker_base_topic)) },
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
                                label = { Text(stringResource(R.string.permit_join_via)) },
                                placeholder = { Text(stringResource(R.string.permit_join_via_hint)) },
                                keyboardOptions = tvAwareKeyboardOptions(),
                                trailingIcon = if (routerNames.isNotEmpty()) {
                                    {
                                        // Pressing the dropdown arrow clears whatever's typed, not
                                        // just toggling the menu open - filteredRouterNames filters
                                        // by routerText, so leftover text from an earlier selection
                                        // otherwise left the reopened list showing only whatever
                                        // still matched it instead of every router again.
                                        IconButton(onClick = {
                                            routerText = ""
                                            // Forced open, not toggled - the field already being
                                            // focused (e.g. right after typing or picking a name)
                                            // left routerExpanded already true, so toggling it here
                                            // closed the menu immediately instead of opening it with
                                            // the now-cleared, unfiltered list.
                                            routerExpanded = true
                                        }) {
                                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = routerExpanded)
                                        }
                                    }
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
                            stringResource(R.string.permit_join_via_help),
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
                                Text(stringResource(R.string.permit_join_title), style = MaterialTheme.typography.titleMedium)
                                Text(
                                    if (status.isOn) {
                                        stringResource(R.string.permit_join_open, PermitJoin.formatRemaining(status.remainingSeconds))
                                    } else {
                                        stringResource(R.string.permit_join_allow)
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
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) }
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
            Text(stringResource(R.string.new_device_title), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.pending_device_text, pending.deviceName, pending.appConfigTopic),
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onIgnore) { Text(stringResource(R.string.pending_ignore)) }
                TextButton(onClick = onAdd) { Text(stringResource(R.string.common_add)) }
            }
        }
    }
}
