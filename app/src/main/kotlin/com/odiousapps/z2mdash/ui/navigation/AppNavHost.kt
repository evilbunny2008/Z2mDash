package com.odiousapps.z2mdash.ui.navigation

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
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
 * already adjacent to wherever focus currently sits.
 *
 * Always wraps `content` in the exact same NavigationDrawer, on every route, rather than only
 * doing so for the 3 main tab destinations and calling `content(Modifier)` directly (bypassing
 * the drawer entirely) everywhere else. That conditional-bypass version was a real, confirmed
 * bug: `content` ultimately composes the whole NavHost, and calling it from two different
 * positions in the tree (sometimes as a direct child of this function, sometimes nested inside
 * NavigationDrawer's own content lambda) meant Compose treated it as a *different* composable
 * every time a route crossed between "is a main tab" and "isn't" - disposing and recreating the
 * entire NavHost, along with every destination's own rememberSaveable-backed state. The
 * user-visible symptom: opening a panel from Home and pressing Back landed back on Home with its
 * scroll position reset to the top, because HomeScreen's LazyListState never actually survived
 * the round trip. Keeping the wrapping structure identical on every route - the rail is simply
 * always present, including on sub-screens like broker/panel editors - fixes that by construction,
 * and doubles as a reasonably common TV pattern in its own right (a persistent nav rail everywhere,
 * not just top-level screens).
 */
@Composable
private fun TvNavShell(
    navController: NavHostController,
    currentRoute: String?,
    content: @Composable (Modifier) -> Unit
) {
    // tv-material's own components (NavigationDrawerItem included) source their default colors
    // from androidx.tv.material3's OWN theme, a separate CompositionLocal from this app's usual
    // androidx.compose.material3.MaterialTheme - without this wrapper, unselected items rendered
    // with no visible color at all on real hardware (confirmed on-device: only the currently
    // selected tab's icon was showing; the other two were present but effectively invisible).
    // Explicitly fed this app's OWN current color scheme (light/dark/dynamic - see
    // toTvColorScheme()'s own comment) rather than left on tv-material3's default tokens -
    // confirmed on-device (TV set to system dark mode) that leaving it on the default produced
    // near-illegible dark-on-dark rail text, since tv-material3's own default has no idea what
    // theme the rest of the app is actually in.
    androidx.tv.material3.MaterialTheme(colorScheme = MaterialTheme.colorScheme.toTvColorScheme()) {
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
                            // Deliberately tv-material3's own Icon/Text here, NOT the
                            // androidx.compose.material3 ones this file otherwise uses (see
                            // PhoneNavShell below) - NavigationDrawerItem only ever adjusts ITS
                            // OWN library's LocalContentColor for selected/focused/unselected
                            // contrast. The compose-material3 versions read a completely
                            // different, unrelated LocalContentColor, so they were silently
                            // falling back to the app's outer theme's plain onSurface color
                            // regardless of this item's actual state - the real cause of the
                            // confirmed on-device dark-on-dark illegibility, independent of (and
                            // in addition to) the ColorScheme mismatch fixed above.
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
            // currentRoute != null is not redundant with the bottomTabs check below - on the very
            // first composition, before NavHost has settled on its startDestination, this can
            // briefly be null, which also isn't "one of the 3 tabs." Without this guard, that
            // moment raced TvNavShell's own initial focus request (Home gets focus there first,
            // this effect then immediately bumps it one step forward) - confirmed on-device as
            // the cause of a real "Home is shown as selected, but the remote acts like Terminal
            // has focus" report right after a cold start.
            if (currentRoute != null && bottomTabs.none { it.route == currentRoute }) {
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
        composable(
            // "?focus={focus}" is an optional query segment - every existing "broker/$id"
            // navigation call (without it) still matches this route unchanged. Lets a caller
            // like HomeScreen's own Permit Join banner deep-link straight to that section of
            // this screen instead of just landing at the top of a long scrolling form.
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
