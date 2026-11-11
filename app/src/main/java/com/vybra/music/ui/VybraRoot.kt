package com.vybra.music.ui

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.vybra.music.AppContainer
import com.vybra.music.ui.common.LocalIsPlaying
import com.vybra.music.ui.common.LocalNowPlayingId
import com.vybra.music.ui.components.AddToPlaylistSheet
import com.vybra.music.ui.components.SongDetailsDialog
import com.vybra.music.ui.components.SongOptionsSheet
import com.vybra.music.ui.player.LyricsEditorScreen
import com.vybra.music.ui.player.LyricsScreen
import com.vybra.music.ui.player.MiniPlayer
import com.vybra.music.ui.player.NowPlayingScreen
import com.vybra.music.ui.player.QueueSheet
import com.vybra.music.ui.player.SleepTimerSheet
import com.vybra.music.ui.screens.AddSongsScreen
import com.vybra.music.ui.screens.AlbumScreen
import com.vybra.music.ui.screens.ArtistScreen
import com.vybra.music.ui.screens.EqualizerScreen
import com.vybra.music.ui.screens.FoldersScreen
import com.vybra.music.ui.screens.HistoryScreen
import com.vybra.music.ui.screens.HomeScreen
import com.vybra.music.ui.screens.LibraryScreen
import com.vybra.music.ui.screens.PlaylistDetailScreen
import com.vybra.music.ui.screens.PlaylistsScreen
import com.vybra.music.ui.screens.SearchScreen
import com.vybra.music.ui.screens.SettingsScreen
import com.vybra.music.ui.screens.SongListScreen
import com.vybra.music.ui.screens.StatsScreen

private data class Tab(val route: String, val label: String, val icon: ImageVector, val selectedIcon: ImageVector)

private val tabs = listOf(
    Tab(Routes.HOME, "Inicio", Icons.Outlined.Home, Icons.Rounded.Home),
    Tab(Routes.SEARCH, "Buscar", Icons.Rounded.Search, Icons.Rounded.Search),
    Tab(Routes.LIBRARY, "Biblioteca", Icons.Outlined.LibraryMusic, Icons.Rounded.LibraryMusic),
    Tab(Routes.PLAYLISTS, "Playlists", Icons.AutoMirrored.Outlined.QueueMusic, Icons.AutoMirrored.Rounded.QueueMusic),
    Tab(Routes.SETTINGS, "Ajustes", Icons.Outlined.Settings, Icons.Rounded.Settings),
)

@Composable
fun VybraRoot(container: AppContainer) {
    val navController = rememberNavController()
    val scope = rememberCoroutineScope()
    val ui = remember { UiController(scope) }
    ui.navigate = { route -> navController.navigate(route) { launchSingleTop = true } }
    ui.navigateToTab = { route -> navigateToTab(navController, route) }
    ui.back = { if (!navController.popBackStack()) Unit }

    val playerState by container.player.state.collectAsStateWithLifecycle()
    // Pestaña resaltada: la actual si es una pestaña; si es una pantalla de detalle, la última pestaña visitada.
    val currentEntry by navController.currentBackStackEntryAsState()
    var lastTab by rememberSaveable { mutableStateOf(Routes.HOME) }
    val currentRoute = currentEntry?.destination?.route
    if (currentRoute != null && currentRoute in Routes.tabs && currentRoute != lastTab) lastTab = currentRoute
    val currentTab = lastTab

    CompositionLocalProvider(
        LocalUi provides ui,
        LocalNowPlayingId provides (playerState.nowPlaying?.songId ?: -1L),
        LocalIsPlaying provides playerState.isPlaying,
    ) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                snackbarHost = { SnackbarHost(ui.snackbar) },
                bottomBar = {
                    // El editor de transiciones ocupa toda la pantalla.
                    if (currentRoute == Routes.TRANSITION_EDITOR) return@Scaffold
                    Column(Modifier.background(MaterialTheme.colorScheme.background.copy(alpha = 0.0f))) {
                        AnimatedVisibility(
                            visible = playerState.hasMedia,
                            enter = expandVertically() + fadeIn(),
                            exit = shrinkVertically() + fadeOut(),
                        ) {
                            MiniPlayer(playerState)
                        }
                        NavigationBar(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                            tonalElevation = 0.dp,
                        ) {
                            tabs.forEach { tab ->
                                val selected = currentTab == tab.route
                                NavigationBarItem(
                                    selected = selected,
                                    onClick = { navigateToTab(navController, tab.route) },
                                    icon = { Icon(if (selected) tab.selectedIcon else tab.icon, tab.label) },
                                    label = { Text(tab.label, style = MaterialTheme.typography.labelSmall) },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = MaterialTheme.colorScheme.onSurface,
                                        selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                        indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
                                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    ),
                                )
                            }
                        }
                    }
                },
            ) { padding ->
                VybraNavHost(navController, Modifier.padding(bottom = padding.calculateBottomPadding()))
            }

            AnimatedVisibility(
                visible = ui.showNowPlaying && playerState.hasMedia,
                enter = slideInVertically(tween(320)) { it } + fadeIn(tween(200)),
                exit = slideOutVertically(tween(280)) { it } + fadeOut(tween(200)),
            ) {
                NowPlayingScreen(onClose = { ui.showNowPlaying = false })
            }

            AnimatedVisibility(
                visible = ui.showLyrics && playerState.hasMedia,
                enter = slideInVertically(tween(320)) { it } + fadeIn(tween(200)),
                exit = slideOutVertically(tween(280)) { it } + fadeOut(tween(200)),
            ) {
                LyricsScreen(onClose = { ui.showLyrics = false })
            }

            ui.lyricsEditor?.let { request ->
                LyricsEditorScreen(request, onClose = { ui.lyricsEditor = null })
            }

            SongOptionsSheet()
            AddToPlaylistSheet()
            SongDetailsDialog()
            QueueSheet()
            SleepTimerSheet()
            com.vybra.music.ui.player.VisualsSheet()
        }
    }
}

private fun navigateToTab(navController: NavHostController, route: String) {
    val current = navController.currentBackStackEntry?.destination?.route
    if (current == route) return
    navController.navigate(route) {
        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
private fun VybraNavHost(navController: NavHostController, modifier: Modifier) {
    val enter = fadeIn(tween(220)) + slideInHorizontally(tween(260)) { it / 12 }
    val exit = fadeOut(tween(180))
    val popEnter = fadeIn(tween(220))
    val popExit = fadeOut(tween(180)) + slideOutHorizontally(tween(260)) { it / 12 }

    NavHost(
        navController = navController,
        startDestination = Routes.HOME,
        modifier = modifier,
        enterTransition = { enter },
        exitTransition = { exit },
        popEnterTransition = { popEnter },
        popExitTransition = { popExit },
    ) {
        composable(Routes.HOME, enterTransition = { fadeIn(tween(200)) }, exitTransition = { fadeOut(tween(150)) }) { HomeScreen() }
        composable(Routes.SEARCH, enterTransition = { fadeIn(tween(200)) }, exitTransition = { fadeOut(tween(150)) }) { SearchScreen() }
        composable(Routes.LIBRARY, enterTransition = { fadeIn(tween(200)) }, exitTransition = { fadeOut(tween(150)) }) { LibraryScreen() }
        composable(Routes.PLAYLISTS, enterTransition = { fadeIn(tween(200)) }, exitTransition = { fadeOut(tween(150)) }) { PlaylistsScreen() }
        composable(Routes.SETTINGS, enterTransition = { fadeIn(tween(200)) }, exitTransition = { fadeOut(tween(150)) }) { SettingsScreen() }

        composable(Routes.ALBUM, arguments = listOf(navArgument("id") { type = NavType.LongType })) {
            AlbumScreen(it.arguments?.getLong("id") ?: -1L)
        }
        composable(Routes.ARTIST, arguments = listOf(navArgument("name") { type = NavType.StringType })) {
            ArtistScreen(Uri.decode(it.arguments?.getString("name") ?: ""))
        }
        composable(Routes.PLAYLIST, arguments = listOf(navArgument("id") { type = NavType.LongType })) {
            PlaylistDetailScreen(it.arguments?.getLong("id") ?: -1L)
        }
        composable(Routes.ADD_SONGS, arguments = listOf(navArgument("id") { type = NavType.LongType })) {
            AddSongsScreen(it.arguments?.getLong("id") ?: -1L)
        }
        composable(Routes.SONG_LIST, arguments = listOf(navArgument("kind") { type = NavType.StringType })) {
            val kind = runCatching { SongListKind.valueOf(it.arguments?.getString("kind") ?: "") }.getOrDefault(SongListKind.FAVORITES)
            SongListScreen(kind)
        }
        composable(Routes.HISTORY) { HistoryScreen() }
        composable(Routes.STATS) { StatsScreen() }
        composable(Routes.EQUALIZER) { EqualizerScreen() }
        composable(Routes.FOLDERS) { FoldersScreen() }
        composable(Routes.VISUALS) { com.vybra.music.ui.player.VisualsScreen() }
        composable(Routes.MIX_SETTINGS) { com.vybra.music.ui.screens.MixSettingsScreen() }
        composable(
            Routes.TRANSITION_EDITOR,
            arguments = listOf(navArgument("from") { type = NavType.LongType }, navArgument("to") { type = NavType.LongType }),
        ) {
            com.vybra.music.ui.screens.TransitionEditorScreen(
                it.arguments?.getLong("from") ?: -1L,
                it.arguments?.getLong("to") ?: -1L,
            )
        }
        composable(Routes.PLAYLIST_MIX, arguments = listOf(navArgument("id") { type = NavType.LongType })) {
            com.vybra.music.ui.screens.PlaylistMixScreen(it.arguments?.getLong("id") ?: -1L)
        }
    }
}
