package com.odiousapps.z2mdash.ui.navigation

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.tv.material3.DrawerValue
import androidx.tv.material3.NavigationDrawer
import androidx.tv.material3.NavigationDrawerItem
import androidx.tv.material3.rememberDrawerState
import com.odiousapps.z2mdash.ui.screens.AddEditBrokerScreen
import com.odiousapps.z2mdash.ui.screens.AddGroupScreen
import com.odiousapps.z2mdash.ui.screens.AddPanelScreen
import com.odiousapps.z2mdash.ui.screens.AlertSettingsScreen
import com.odiousapps.z2mdash.ui.screens.BackupRestoreScreen
import com.odiousapps.z2mdash.ui.screens.BrokersScreen
import com.odiousapps.z2mdash.ui.screens.DiscoverScreen
import com.odiousapps.z2mdash.ui.screens.MqttBackupScreen
import com.odiousapps.z2mdash.ui.screens.HomeScreen
import com.odiousapps.z2mdash.ui.screens.SettingsScreen
import com.odiousapps.z2mdash.ui.screens.TerminalScreen
import com.odiousapps.z2mdash.ui.screens.WelcomeScreen
import com.odiousapps.z2mdash.ui.tv.LocalIsTv
import com.odiousapps.z2mdash.ui.tv.blockDirectionDown
import com.odiousapps.z2mdash.ui.tv.isTelevision
import com.odiousapps.z2mdash.ui.tv.onDpadSelect
import com.odiousapps.z2mdash.ui.tv.toTvColorScheme

private data class BottomTab(val route: String, val label: String, val icon: ImageVector)

private val bottomTabs = listOf(
    BottomTab("home", "Home", Icons.Default.Home),
    BottomTab("terminal", "Terminal", Icons.Default.Terminal),
    BottomTab("settings", "Settings", Icons.Default.Settings)
)

/**
 * Set by [TvNavShell] on its content area's FocusRequester, so [AppNavGraph] can focus directly
 * into the content area on navigating to a non-tab screen - moveFocus() alone can land back on the
 * rail's Home item instead (confirmed on-device), since the rail composes before the content.
 */
private val LocalTvContentFocusRequester = staticCompositionLocalOf<FocusRequester?> { null }

@Composable
fun AppNavHost() {
    val context = LocalContext.current
    val isTv = remember(context) { isTelevision(context) }
    CompositionLocalProvider(LocalIsTv provides isTv) {
        val navController = rememberNavController()
        val backStackEntry by navController.currentBackStackEntryAsState()
        val currentRoute = backStackEntry?.destination?.route

        if (isTv) {
            TvNavShell(navController, currentRoute) { modifier -> AppNavGraph(navController, modifier) }
        } else {
            PhoneNavShell(navController, currentRoute) { modifier -> AppNavGraph(navController, modifier) }
        }
    }
}

/** Today's touch-first shell: a bottom [NavigationBar], shown only on the 3 main tab destinations. */
@Composable
private fun PhoneNavShell(
    navController: NavHostController,
    currentRoute: String?,
    content: @Composable (Modifier) -> Unit
) {
    Scaffold(
        bottomBar = {
            if (bottomTabs.any { it.route == currentRoute }) {
                NavigationBar {
                    bottomTabs.forEach { tab ->
                        NavigationBarItem(
                            selected = currentRoute == tab.route,
                            onClick = { navigateToTab(navController, tab.route) },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) }
                        )
                    }
                }
            }
        }
    ) { padding -> content(Modifier.padding(padding)) }
}

/**
 * TV shell: a persistent side rail instead of a bottom bar - a D-pad reaching the screen edge is
 * worse than a rail already adjacent to focus.
 *
 * Always wraps `content` in the same NavigationDrawer on every route (never bypassing it for
 * non-tab screens) - calling `content` from two different tree positions made Compose treat it as
 * a different composable each time a route crossed the tab/non-tab boundary, disposing and
 * recreating the whole NavHost and every screen's rememberSaveable state (confirmed bug: Back from
 * a panel to Home reset Home's scroll position). Identical wrapping fixes this by construction.
 */
@Composable
private fun TvNavShell(
    navController: NavHostController,
    currentRoute: String?,
    content: @Composable (Modifier) -> Unit
) {
    // tv-material3 components source colours from their own separate MaterialTheme, not this app's
    // androidx.compose.material3 one - without this wrapper, unselected rail items rendered with
    // no visible colour (confirmed on-device), and tv-material3's default tokens produced
    // near-illegible dark-on-dark rail text in dark mode since they don't know the app's theme.
    androidx.tv.material3.MaterialTheme(colorScheme = MaterialTheme.colorScheme.toTvColorScheme()) {
        // Focus the first rail item as soon as this shell appears - Compose doesn't autofocus
        // anything on a D-pad's first press (confirmed on-device: without this, every D-pad press
        // was inert). Every other screen is then reachable by moving focus right from here.
        val homeItemFocusRequester = remember { FocusRequester() }
        LaunchedEffect(Unit) { homeItemFocusRequester.requestFocus() }
        // Exposed via LocalTvContentFocusRequester so AppNavGraph can focus directly into this
        // content area on a non-tab route, instead of searching from "nothing focused" - see that
        // CompositionLocal's own comment.
        val contentFocusRequester = remember { FocusRequester() }
        NavigationDrawer(
            drawerState = rememberDrawerState(DrawerValue.Closed),
            drawerContent = {
                // NavigationDrawerScope doesn't arrange children vertically on its own - without
                // this Column, all 3 items rendered on top of each other (confirmed on-device).
                Column {
                    bottomTabs.forEachIndexed { index, tab ->
                        val onTabClick = { navigateToTab(navController, tab.route) }
                        NavigationDrawerItem(
                            selected = currentRoute == tab.route,
                            onClick = onTabClick,
                            // Deliberately tv-material3's own Icon/Text, not compose-material3's
                            // (see PhoneNavShell) - NavigationDrawerItem only adjusts its own
                            // library's LocalContentColor for selected/focused state; the
                            // compose-material3 versions read a different LocalContentColor and
                            // silently fell back to a fixed colour, causing the dark-on-dark bug
                            // independent of the ColorScheme mismatch fixed above.
                            leadingContent = {
                                androidx.tv.material3.Icon(tab.icon, contentDescription = tab.label)
                            },
                            modifier = Modifier
                                .onDpadSelect(onTabClick)
                                .then(if (index == 0) Modifier.focusRequester(homeItemFocusRequester) else Modifier)
                                .then(if (index == bottomTabs.lastIndex) Modifier.blockDirectionDown() else Modifier)
                        ) {
                            androidx.tv.material3.Text(tab.label)
                        }
                    }
                }
            }
        ) {
            CompositionLocalProvider(LocalTvContentFocusRequester provides contentFocusRequester) {
                Box(Modifier.focusRequester(contentFocusRequester).focusGroup()) {
                    content(Modifier)
                }
            }
        }
    }
}

private fun navigateToTab(navController: NavHostController, route: String) {
    navController.navigate(route) {
        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
private fun AppNavGraph(navController: NavHostController, modifier: Modifier) {
    // Same issue as the rail's initial-focus fix above, for every non-tab screen: without this,
    // landing on a fresh screen left nothing focused, making D-pad input completely inert
    // (confirmed on-device). Focuses directly into the content area via LocalTvContentFocusRequester
    // rather than moveFocus(Next), which searched from "nothing focused" and could land back on the
    // rail's Home item instead (e.g. Settings -> Alarm/Alert landed focus back on Home).
    // Skipped for the 3 rail destinations, which already get initial focus from TvNavShell -
    // running this too would race that and could steal focus back off the rail.
    val isTv = LocalIsTv.current
    if (isTv) {
        val contentFocusRequester = LocalTvContentFocusRequester.current
        val backStackEntry by navController.currentBackStackEntryAsState()
        val currentRoute = backStackEntry?.destination?.route
        LaunchedEffect(backStackEntry?.id) {
            // currentRoute != null isn't redundant with the bottomTabs check below - on first
            // composition, before NavHost settles on startDestination, route can briefly be null.
            // Without this guard that raced TvNavShell's initial focus request, causing a
            // confirmed cold-start bug: Home shown selected but the remote acting like Terminal
            // had focus.
            if (currentRoute != null && bottomTabs.none { it.route == currentRoute }) {
                contentFocusRequester?.requestFocus()
            }
        }
    }
    NavHost(
        navController = navController,
        startDestination = "home",
        modifier = modifier
    ) {
        composable("home") { backStackEntry -> HomeScreen(navController, backStackEntry) }
        composable("welcome") { WelcomeScreen(navController) }
        composable("terminal") { TerminalScreen() }
        composable("settings") { SettingsScreen(navController) }
        composable("alertSettings") { AlertSettingsScreen(navController) }
        composable("backupRestore") { BackupRestoreScreen(navController) }
        composable("brokers") { BrokersScreen(navController) }
        composable("discover/{brokerId}") { entry ->
            DiscoverScreen(navController, initialBrokerId = entry.arguments?.getString("brokerId"))
        }
        composable("mqttBackup") { MqttBackupScreen(navController) }
        composable(
            // "?focus={focus}" is optional - existing "broker/$id" calls still match unchanged.
            // Lets a caller (e.g. HomeScreen's Permit Join banner) deep-link to a section of this
            // screen instead of landing at the top of a long form.
            route = "broker/{brokerId}?focus={focus}",
            arguments = listOf(
                navArgument("focus") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            )
        ) { entry ->
            val id = entry.arguments?.getString("brokerId")
            val focus = entry.arguments?.getString("focus")
            AddEditBrokerScreen(navController, if (id == "new") null else id, focus)
        }
        composable("addGroup") { AddGroupScreen(navController) }
        composable("group/{groupId}/panel/{panelId}") { entry ->
            val groupId = entry.arguments?.getString("groupId")
            val panelId = entry.arguments?.getString("panelId")
            if (groupId != null) {
                AddPanelScreen(navController, groupId, if (panelId == "new") null else panelId)
            }
        }
    }
}
