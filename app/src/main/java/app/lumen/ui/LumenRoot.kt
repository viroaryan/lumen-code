// SPDX-FileCopyrightText: 2026 Nirvana Team
// SPDX-License-Identifier: GPL-3.0-or-later

package app.lumen.ui

import app.lumen.engine.SourceHealth

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import app.lumen.Graph
import app.lumen.ui.components.AddToPlaylistSheet
import app.lumen.ui.components.TrackMenuSheet
import app.lumen.ui.components.pressable
import app.lumen.ui.player.MiniPlayer
import app.lumen.ui.player.PlayerScreen
import app.lumen.ui.screens.CollectionScreen
import app.lumen.ui.screens.HomeScreen
import app.lumen.ui.screens.LibraryScreen
import app.lumen.ui.screens.SearchScreen
import app.lumen.ui.screens.SettingsScreen
import app.lumen.ui.screens.SpecialScreen
import app.lumen.ui.screens.UserPlaylistScreen
import app.lumen.ui.studio.StudioScreen
import app.lumen.ui.studio.StudioSessionScreen
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import app.lumen.ui.theme.LIcons
import app.lumen.ui.theme.Lumen

private data class Tab(val route: String, val label: String, val icon: ImageVector, val selectedIcon: ImageVector)

private val tabs = listOf(
    Tab(Routes.HOME, "Home", LIcons.Home, LIcons.HomeFilled),
    Tab(Routes.SEARCH, "Search", LIcons.Search, LIcons.SearchBold),
    Tab(Routes.STUDIO, "Studio", LIcons.Mic, LIcons.MicBold),
    Tab(Routes.LIBRARY, "Library", LIcons.Library, LIcons.LibraryBold),
)

@Composable
fun LumenRoot(app: AppState) {
    val c = Lumen.colors
    // Only "is anything loaded" and errors matter here; the rest of the player state would recompose the whole app.
    val hasPlayer by remember { Graph.player.state.map { it.current != null }.distinctUntilChanged() }.collectAsState(false)
    val error by remember { Graph.player.state.map { it.error?.let { _ -> it.current?.id } }.distinctUntilChanged() }.collectAsState(null)
    LaunchedEffect(error) { error?.let { app.toast("Couldn't play this one. Trying again…") } }
    // Messages from the engines: storage full, a failed download, a source that went down.
    LaunchedEffect(Unit) { Graph.downloads.events.collect { app.toast(it) } }
    LaunchedEffect(Unit) { SourceHealth.notices.collect { app.toast(it) } }

    val entry by app.nav.currentBackStackEntryAsState()
    // A recording session is a full-screen workspace: no tabs or mini player over it.
    val immersive = entry?.destination?.route == Routes.STUDIO_SESSION

    CompositionLocalProvider(LocalApp provides app) {
        Box(Modifier.fillMaxSize().background(c.bg)) {
            val bottomSpace = if (hasPlayer) 140.dp else 72.dp
            // The bottom bar sits above the system navigation bar, so lists must clear both.
            val navBar = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            val padding = PaddingValues(bottom = if (immersive) 0.dp else bottomSpace + 24.dp + navBar)
            NavHost(
                app.nav, startDestination = Routes.HOME, modifier = Modifier.fillMaxSize(),
                enterTransition = { fadeIn(tween(180)) }, exitTransition = { fadeOut(tween(120)) },
            ) {
                composable(Routes.HOME) { HomeScreen(padding) }
                composable(Routes.SEARCH) { SearchScreen(padding) }
                composable(Routes.LIBRARY) { LibraryScreen(padding) }
                composable(Routes.SETTINGS) { SettingsScreen(padding) }
                composable(Routes.COLLECTION) { CollectionScreen(it.arguments?.getString("id").orEmpty(), padding) }
                composable(Routes.PLAYLIST) { UserPlaylistScreen(it.arguments?.getString("id").orEmpty(), padding) }
                composable(Routes.SPECIAL) { SpecialScreen(it.arguments?.getString("kind").orEmpty(), padding) }
                composable(Routes.STUDIO) { StudioScreen(padding) }
                composable(Routes.STUDIO_SESSION) { StudioSessionScreen(it.arguments?.getString("id").orEmpty()) }
            }

            // A solid strip under the status bar, so scrolled content never runs into the clock and icons.
            Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars).background(c.bg))

            if (!immersive) Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                SnackbarHost(app.snackbar, Modifier.padding(horizontal = 8.dp)) { data ->
                    Snackbar(
                        containerColor = c.text, contentColor = c.bg, shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
                        action = data.visuals.actionLabel?.let { label ->
                            {
                                Text(
                                    label.uppercase(), style = Lumen.type.label, color = c.bg,
                                    modifier = Modifier.pressable({ data.performAction() }).padding(horizontal = 8.dp, vertical = 6.dp),
                                )
                            }
                        },
                    ) {
                        Text(data.visuals.message, style = Lumen.type.label)
                    }
                }
                Spacer(Modifier.fillMaxWidth().height(28.dp).background(Brush.verticalGradient(listOf(c.bg.copy(alpha = 0f), c.bg))))
                Column(Modifier.fillMaxWidth().background(c.bg)) {
                    if (hasPlayer) MiniPlayer(onOpen = { app.playerOpen = true })
                    BottomBar(app)
                }
            }

            AnimatedVisibility(
                visible = app.playerOpen && hasPlayer,
                enter = slideInVertically(tween(320)) { it } + fadeIn(tween(200)),
                exit = slideOutVertically(tween(280)) { it } + fadeOut(tween(200)),
            ) {
                PlayerScreen(onClose = { app.playerOpen = false })
            }

            TrackMenuSheet()
            AddToPlaylistSheet()
        }
    }
}

@Composable
private fun BottomBar(app: AppState) {
    val c = Lumen.colors
    val entry by app.nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    // Follow the back button: landing on a tab's root makes it the current tab.
    androidx.compose.runtime.LaunchedEffect(route) { tabs.firstOrNull { it.route == route }?.let { app.currentTab = it.route } }
    Row(
        Modifier.fillMaxWidth().navigationBarsPadding().height(60.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEach { tab ->
            val selected = app.currentTab == tab.route
            Column(
                Modifier.weight(1f).pressable({
                    app.playerOpen = false
                    if (selected) {
                        // Tapping the current tab again returns to its top.
                        if (route != tab.route) app.nav.popBackStack(tab.route, inclusive = false)
                        return@pressable
                    }
                    app.currentTab = tab.route
                    app.nav.navigate(tab.route) {
                        popUpTo(app.nav.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                }),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(if (selected) tab.selectedIcon else tab.icon, tab.label, Modifier.size(26.dp), tint = if (selected) c.text else c.textDim)
                Spacer(Modifier.height(3.dp))
                Text(tab.label, style = Lumen.type.micro.copy(letterSpacing = androidx.compose.ui.unit.TextUnit.Unspecified), color = if (selected) c.text else c.textDim)
            }
        }
    }
}
