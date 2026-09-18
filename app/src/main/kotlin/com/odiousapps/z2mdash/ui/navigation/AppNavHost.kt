package com.odiousapps.z2mdash.ui.navigation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
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
import com.odiousapps.z2mdash.ui.tv.isTelevision
import com.odiousapps.z2mdash.ui.tv.onDpadSelect

private data class BottomTab(val route: String, val label: String, val icon: ImageVector)

private val bottomTabs = listOf(
    BottomTab("home", "Home", Icons.Default.Home),
    BottomTab("terminal", "Terminal", Icons.Default.Terminal),
    BottomTab("settings", "Settings", Icons.Default.Settings)
)

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
 * TV shell: a persistent side rail instead of a bottom bar - reaching the bottom edge of the
 * screen with a D-pad is a much worse fit for a 10-foot remote experience than a rail that's
 * already adjacent to wherever focus currently sits. Only shown on the same 3 main tab
 * destinations as the phone bottom bar; sub-screens (broker edit, add panel, etc.) render full
 * screen either way, exactly like the phone shell's bottomBar omission.
 */
@Composable
private fun TvNavShell(
    navController: NavHostController,
    currentRoute: String?,
    content: @Composable (Modifier) -> Unit
) {
    if (!bottomTabs.any { it.route == currentRoute }) {
        content(Modifier)
        return
    }
    // tv-material's own components (NavigationDrawerItem included) source their default colors
    // from androidx.tv.material3's OWN theme, a separate CompositionLocal from this app's usual
    // androidx.compose.material3.MaterialTheme - without this wrapper, unselected items rendered
    // with no visible color at all on real hardware (confirmed on-device: only the currently
    // selected tab's icon was showing; the other two were present but effectively invisible).
    androidx.tv.material3.MaterialTheme {
        // The first NavigationDrawerItem is given focus as soon as this shell appears, because
        // Compose does NOT automatically focus anything on its own when a D-pad key first
        // arrives with nothing focused yet (confirmed on-device: without this, every D-pad press
        // - including on HomeScreen's list - was simply inert, matching the user's original "the
        // remote does nothing" report). Every screen this shell wraps can then be reached by
        // moving focus right from here into the content area.
        val homeItemFocusRequester = remember { FocusRequester() }
        LaunchedEffect(Unit) { homeItemFocusRequester.requestFocus() }
        NavigationDrawer(
            drawerState = rememberDrawerState(DrawerValue.Closed),
            drawerContent = {
                // NavigationDrawerScope doesn't itself arrange children vertically - without this
                // explicit Column, all 3 items ended up rendering on top of each other (confirmed
                // on-device: only one was ever visible or reachable, no matter which).
                Column {
                    bottomTabs.forEachIndexed { index, tab ->
                        val onTabClick = { navigateToTab(navController, tab.route) }
                        NavigationDrawerItem(
                            selected = currentRoute == tab.route,
                            onClick = onTabClick,
                            leadingContent = { Icon(tab.icon, contentDescription = tab.label) },
                            modifier = Modifier
                                .onDpadSelect(onTabClick)
                                .then(if (index == 0) Modifier.focusRequester(homeItemFocusRequester) else Modifier)
                        ) {
                            Text(tab.label)
                        }
                    }
                }
            }
        ) {
            content(Modifier)
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
    // Same root cause as the nav rail's own initial-focus request above, but for every OTHER
    // screen (Brokers, Add/Edit Broker, Discover, etc.) that isn't one of the 3 rail
    // destinations: confirmed on-device that without this, landing on a fresh screen left
    // nothing focused at all, so D-pad input was completely inert there too - not just "won't
    // scroll," genuinely unusable. moveFocus(Next) from a "nothing focused" state falls back to
    // focusing the first focusable element in composition order (typically the screen's own
    // Back button), which is a sensible landing spot for any screen this general fix can't know
    // the specifics of. Skipped for the 3 rail destinations themselves, which already get a more
    // deliberate initial focus (the Home rail item) from TvNavShell above - this effect would
    // otherwise race that one and could steal focus back off the rail.
    val isTv = LocalIsTv.current
    if (isTv) {
        val focusManager = LocalFocusManager.current
        val backStackEntry by navController.currentBackStackEntryAsState()
        val currentRoute = backStackEntry?.destination?.route
        LaunchedEffect(backStackEntry?.id) {
            if (bottomTabs.none { it.route == currentRoute }) {
                focusManager.moveFocus(FocusDirection.Next)
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
        composable("broker/{brokerId}") { entry ->
            val id = entry.arguments?.getString("brokerId")
            AddEditBrokerScreen(navController, if (id == "new") null else id)
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
