package com.example.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.outlined.Cast
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.BuildConfig
import com.example.R
import com.example.core.common.CastConnectionState
import com.example.core.common.VideoItem
import com.example.core.update.AppUpdateState
import com.example.ui.components.AppUpdateDialog
import com.example.ui.components.MiniCastControllerBar
import com.example.ui.screens.CastDevicesScreen
import com.example.ui.screens.CastingProgressScreen
import com.example.ui.screens.CompatibilityInfoScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.LibraryScreen
import com.example.ui.screens.RemotePlayerScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.SplashScreen
import com.example.ui.screens.TroubleshootingScreen
import com.example.ui.screens.VideoDetailsScreen
import com.example.ui.theme.AppThemeTokens
import kotlinx.coroutines.launch

object LumaRoutes {
    const val SPLASH = "splash"
    const val HOME = "home"
    const val LIBRARY = "library"
    const val DEVICES = "devices"
    const val SETTINGS = "settings"
    const val VIDEO_DETAILS = "video_details/{videoId}"
    const val COMPATIBILITY = "compatibility/{videoId}"
    const val CASTING_PROGRESS = "casting_progress"
    const val REMOTE_PLAYER = "remote_player"
    const val TROUBLESHOOTING = "troubleshooting"

    fun videoDetails(videoId: String): String = "video_details/${Uri.encode(videoId)}"
    fun compatibility(videoId: String): String = "compatibility/${Uri.encode(videoId)}"
}

private data class TopLevelTab(
    val route: String,
    val labelRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String
)

private val TOP_LEVEL_TABS = listOf(
    TopLevelTab(LumaRoutes.HOME, R.string.nav_home, Icons.Filled.Home, Icons.Outlined.Home, "nav_home"),
    TopLevelTab(LumaRoutes.LIBRARY, R.string.video_screen_title, Icons.Filled.VideoLibrary, Icons.Outlined.VideoLibrary, "nav_library"),
    TopLevelTab(LumaRoutes.DEVICES, R.string.nav_devices, Icons.Filled.Cast, Icons.Outlined.Cast, "nav_devices"),
    TopLevelTab(LumaRoutes.SETTINGS, R.string.nav_settings, Icons.Filled.Settings, Icons.Outlined.Settings, "nav_settings")
)

private val TOP_LEVEL_ROUTES = setOf(
    LumaRoutes.HOME,
    LumaRoutes.LIBRARY,
    LumaRoutes.DEVICES,
    LumaRoutes.SETTINGS
)

@Composable
fun LumaCastApp(viewModel: LumaCastViewModel) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val drawerScope = rememberCoroutineScope()

    // Collect ONLY low-frequency shell states at the root so 1s playback position ticks
    // or transcoding progress % never recompose the app chrome or unrelated screens.
    val castHeader by viewModel.castHeaderStatus.collectAsStateWithLifecycle()
    val drawerNetworkLabel by viewModel.drawerNetworkLabel.collectAsStateWithLifecycle()
    val pendingConsentRequest by viewModel.pendingConsentRequest.collectAsStateWithLifecycle()
    val appUpdateState by viewModel.appUpdateState.collectAsStateWithLifecycle()
    val showUpdateDialog by viewModel.showUpdateDialog.collectAsStateWithLifecycle()

    // Automatically refresh library when returning to the app after external file imports or storage changes
    var hasPassedInitialResume by remember { mutableStateOf(false) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.onInstallPermissionReturned()
                if (hasPassedInitialResume) {
                    viewModel.refreshLibrary()
                } else {
                    hasPassedInitialResume = true
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var hasNearbyWifiPermission by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.NEARBY_WIFI_DEVICES
                ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val nearbyWifiPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted ->
            hasNearbyWifiPermission = granted
            if (granted) viewModel.refreshCastDevices()
        }
    )

    // Android 11+ system consent launcher for MediaStore.createDeleteRequest / createWriteRequest
    val mediaConsentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult(),
        onResult = { result ->
            viewModel.onMediaConsentResult(result.resultCode == Activity.RESULT_OK)
        }
    )

    // System Package Installer result launcher (returns user consent, cancellation, or error result)
    val apkInstallLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
        onResult = { result ->
            viewModel.onApkInstallerResult(result.resultCode)
        }
    )

    // Android 8+ Unknown App Sources settings launcher
    val unknownSourcesLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
        onResult = {
            viewModel.onInstallPermissionReturned()
        }
    )

    LaunchedEffect(viewModel) {
        viewModel.installApkRequests.collect { apkFile ->
            runCatching {
                apkInstallLauncher.launch(viewModel.createInstallApkIntent(apkFile))
            }.onFailure {
                viewModel.onApkInstallerResult(Activity.RESULT_FIRST_USER)
            }
        }
    }

    LaunchedEffect(pendingConsentRequest) {
        val req = pendingConsentRequest ?: return@LaunchedEffect
        runCatching {
            mediaConsentLauncher.launch(IntentSenderRequest.Builder(req.intentSender).build())
        }.onFailure {
            viewModel.onMediaConsentResult(false)
        }
    }

    // Zero-permission Android Photo Picker for multi-video selection
    val videoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 50),
        onResult = { uris -> viewModel.onPickedVideosSelected(uris) }
    )

    // Storage Access Framework (SAF) folder picker for recursive movie & subtitle folder indexing
    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
        onResult = { treeUri -> treeUri?.let { viewModel.onSafFolderSelected(it) } }
    )

    // SAF OpenDocument picker for external subtitle files (.srt, .ass, .ssa, .vtt, .ttml, .idx, .sub, .sup)
    val externalSubtitlePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri ->
            val currentVideo = viewModel.playbackState.value.currentVideo
            if (uri != null && currentVideo != null) {
                viewModel.onExternalSubtitlePicked(currentVideo, uri, activateImmediately = true)
            }
        }
    )

    // Android 13+ notification permission for ongoing cast foreground service controls
    val notificationPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { /* Service still starts cleanly; notification displays if granted */ }
    )

    val launchVideoPicker: () -> Unit = remember(videoPickerLauncher) {
        {
            videoPickerLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
            )
        }
    }

    val launchFolderPicker: () -> Unit = remember(folderPickerLauncher) {
        { folderPickerLauncher.launch(null) }
    }

    val startCastingVideo: (VideoItem) -> Unit = remember(viewModel, navController, notificationPermLauncher) {
        { video ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notificationPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            viewModel.castVideo(video)
            navController.navigate(LumaRoutes.CASTING_PROGRESS)
        }
    }

    val showTopLevelNav = currentRoute in TOP_LEVEL_ROUTES

    val navigateTab: (String) -> Unit = remember(navController) {
        { route ->
            navController.navigate(route) {
                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
    }

    val colors = MaterialTheme.colorScheme
    val tokens = AppThemeTokens.typography

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = showTopLevelNav,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = colors.surface,
                drawerContentColor = colors.onSurface
            ) {
                Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 20.dp)) {
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.headlineMedium,
                        color = colors.primary
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = drawerNetworkLabel,
                        style = tokens.connectionStatus,
                        color = colors.secondary
                    )
                    if (castHeader.connectedDeviceName != null) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "Connected to ${castHeader.connectedDeviceName}",
                            style = tokens.toolbarSubtitle,
                            color = colors.onSurfaceVariant
                        )
                    }
                }
                HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.55f))
                Spacer(Modifier.height(8.dp))

                TOP_LEVEL_TABS.forEach { tab ->
                    val selected = currentRoute == tab.route
                    NavigationDrawerItem(
                        label = { Text(stringResource(tab.labelRes), style = MaterialTheme.typography.labelLarge) },
                        selected = selected,
                        onClick = {
                            drawerScope.launch { drawerState.close() }
                            navigateTab(tab.route)
                        },
                        icon = {
                            Icon(
                                imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                contentDescription = null
                            )
                        },
                        colors = NavigationDrawerItemDefaults.colors(
                            selectedContainerColor = colors.primaryContainer,
                            selectedIconColor = colors.primary,
                            selectedTextColor = colors.primary
                        ),
                        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                    )
                }

                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.55f))
                Spacer(Modifier.height(8.dp))

                NavigationDrawerItem(
                    label = { Text(stringResource(R.string.action_browse_folders), style = MaterialTheme.typography.labelLarge) },
                    selected = false,
                    onClick = {
                        drawerScope.launch { drawerState.close() }
                        launchFolderPicker()
                    },
                    icon = { Icon(Icons.Filled.CreateNewFolder, contentDescription = null, tint = colors.primary) },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )
                NavigationDrawerItem(
                    label = { Text(stringResource(R.string.action_add_videos), style = MaterialTheme.typography.labelLarge) },
                    selected = false,
                    onClick = {
                        drawerScope.launch { drawerState.close() }
                        launchVideoPicker()
                    },
                    icon = { Icon(Icons.Filled.AddPhotoAlternate, contentDescription = null, tint = colors.secondary) },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )
                NavigationDrawerItem(
                    label = { Text(stringResource(R.string.action_open_diagnostics), style = MaterialTheme.typography.labelLarge) },
                    selected = false,
                    onClick = {
                        drawerScope.launch { drawerState.close() }
                        navController.navigate(LumaRoutes.TROUBLESHOOTING)
                    },
                    icon = { Icon(Icons.Outlined.HelpOutline, contentDescription = null) },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )
                val drawerUpdateLabel = when (val u = appUpdateState) {
                    is AppUpdateState.UpdateAvailable -> "Update Available · v${u.release.versionName}"
                    is AppUpdateState.Downloading -> "Downloading Update · ${u.progressPercent}%"
                    is AppUpdateState.ReadyToInstall -> "Install Update · v${u.release.versionName}"
                    is AppUpdateState.NeedsInstallPermission -> "Install Update · v${u.release.versionName}"
                    else -> stringResource(R.string.update_check_action)
                }
                val hasPendingUpdate = appUpdateState is AppUpdateState.UpdateAvailable ||
                    appUpdateState is AppUpdateState.Downloading ||
                    appUpdateState is AppUpdateState.ReadyToInstall ||
                    appUpdateState is AppUpdateState.NeedsInstallPermission
                NavigationDrawerItem(
                    label = { Text(drawerUpdateLabel, style = MaterialTheme.typography.labelLarge) },
                    selected = hasPendingUpdate,
                    onClick = {
                        drawerScope.launch { drawerState.close() }
                        viewModel.openUpdateDialog()
                    },
                    icon = {
                        Icon(
                            imageVector = Icons.Filled.SystemUpdate,
                            contentDescription = null,
                            tint = if (hasPendingUpdate) colors.primary else colors.onSurfaceVariant
                        )
                    },
                    colors = NavigationDrawerItemDefaults.colors(
                        selectedContainerColor = colors.primaryContainer,
                        selectedIconColor = colors.primary,
                        selectedTextColor = colors.primary
                    ),
                    modifier = Modifier
                        .padding(NavigationDrawerItemDefaults.ItemPadding)
                        .testTag("drawer_app_update_item")
                )
            }
        }
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val useNavRail = maxWidth >= 700.dp

            Row(modifier = Modifier.fillMaxSize()) {
                if (showTopLevelNav && useNavRail) {
                    NavigationRail(
                        containerColor = colors.surface,
                        modifier = Modifier.fillMaxHeight()
                    ) {
                        TOP_LEVEL_TABS.forEach { tab ->
                            val selected = currentRoute == tab.route
                            NavigationRailItem(
                                selected = selected,
                                onClick = { navigateTab(tab.route) },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                        contentDescription = stringResource(tab.labelRes)
                                    )
                                },
                                label = { Text(stringResource(tab.labelRes), style = MaterialTheme.typography.labelMedium) },
                                colors = NavigationRailItemDefaults.colors(
                                    selectedIconColor = colors.primary,
                                    selectedTextColor = colors.primary,
                                    indicatorColor = colors.primaryContainer
                                ),
                                modifier = Modifier.testTag(tab.testTag)
                            )
                        }
                    }
                }

                Scaffold(
                    containerColor = colors.background,
                    contentWindowInsets = WindowInsets.safeDrawing,
                    bottomBar = {
                        if (showTopLevelNav) {
                            Column(
                                modifier = if (useNavRail) Modifier.navigationBarsPadding() else Modifier
                            ) {
                                LiveMiniCastControllerSlot(
                                    viewModel = viewModel,
                                    onOpenRemote = { navController.navigate(LumaRoutes.REMOTE_PLAYER) }
                                )
                                if (!useNavRail) {
                                    HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.45f))
                                    NavigationBar(
                                        containerColor = colors.surface,
                                        tonalElevation = 0.dp
                                    ) {
                                        TOP_LEVEL_TABS.forEach { tab ->
                                            val selected = currentRoute == tab.route
                                            NavigationBarItem(
                                                selected = selected,
                                                onClick = { navigateTab(tab.route) },
                                                icon = {
                                                    Icon(
                                                        imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                                        contentDescription = stringResource(tab.labelRes)
                                                    )
                                                },
                                                label = { Text(stringResource(tab.labelRes), style = MaterialTheme.typography.labelMedium) },
                                                colors = NavigationBarItemDefaults.colors(
                                                    selectedIconColor = colors.primary,
                                                    selectedTextColor = colors.primary,
                                                    indicatorColor = colors.primaryContainer
                                                ),
                                                modifier = Modifier.testTag(tab.testTag)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        val tabFadeIn = fadeIn(animationSpec = tween(180, easing = FastOutSlowInEasing))
                        val tabFadeOut = fadeOut(animationSpec = tween(140, easing = FastOutSlowInEasing))
                        val pushEnter = slideInHorizontally(
                            animationSpec = tween(240, easing = FastOutSlowInEasing),
                            initialOffsetX = { it / 8 }
                        ) + fadeIn(animationSpec = tween(200))
                        val pushExit = fadeOut(animationSpec = tween(150))
                        val popEnter = fadeIn(animationSpec = tween(190))
                        val popExit = slideOutHorizontally(
                            animationSpec = tween(210, easing = FastOutSlowInEasing),
                            targetOffsetX = { it / 8 }
                        ) + fadeOut(animationSpec = tween(170))

                        NavHost(
                            navController = navController,
                            startDestination = LumaRoutes.SPLASH,
                            enterTransition = { pushEnter },
                            exitTransition = { pushExit },
                            popEnterTransition = { popEnter },
                            popExitTransition = { popExit }
                        ) {
                            composable(
                                route = LumaRoutes.SPLASH,
                                enterTransition = { fadeIn(tween(120)) },
                                exitTransition = { fadeOut(tween(220)) }
                            ) {
                                SplashScreen(
                                    onFinished = {
                                        navController.navigate(LumaRoutes.LIBRARY) {
                                            popUpTo(LumaRoutes.SPLASH) { inclusive = true }
                                        }
                                    }
                                )
                            }

                            composable(
                                route = LumaRoutes.HOME,
                                enterTransition = { tabFadeIn },
                                exitTransition = { tabFadeOut },
                                popEnterTransition = { tabFadeIn },
                                popExitTransition = { tabFadeOut }
                            ) {
                                val libraryState by viewModel.libraryState.collectAsStateWithLifecycle()
                                val playbackState by viewModel.playbackState.collectAsStateWithLifecycle()
                                val networkHealth by viewModel.networkHealth.collectAsStateWithLifecycle()

                                HomeScreen(
                                    libraryState = libraryState,
                                    playbackState = playbackState,
                                    networkHealth = networkHealth,
                                    discoveredDeviceCount = castHeader.discoveredDeviceCount,
                                    thumbnailLoader = viewModel.thumbnailLoader,
                                    onPickVideos = launchVideoPicker,
                                    onPickFolder = launchFolderPicker,
                                    onOpenVideoDetails = { video ->
                                        navController.navigate(LumaRoutes.videoDetails(video.id))
                                    },
                                    onOpenLibrary = { folder ->
                                        viewModel.selectFolderFilter(folder)
                                        navigateTab(LumaRoutes.LIBRARY)
                                    },
                                    onOpenDevices = { navigateTab(LumaRoutes.DEVICES) },
                                    onOpenRemote = { navController.navigate(LumaRoutes.REMOTE_PLAYER) },
                                    onOpenTroubleshooting = { navController.navigate(LumaRoutes.TROUBLESHOOTING) }
                                )
                            }

                            composable(
                                route = LumaRoutes.LIBRARY,
                                enterTransition = { tabFadeIn },
                                exitTransition = { tabFadeOut },
                                popEnterTransition = { tabFadeIn },
                                popExitTransition = { tabFadeOut }
                            ) {
                                val libraryState by viewModel.libraryState.collectAsStateWithLifecycle()

                                LibraryScreen(
                                    state = libraryState,
                                    thumbnailLoader = viewModel.thumbnailLoader,
                                    connectionState = castHeader.connectionState,
                                    connectedDeviceName = castHeader.connectedDeviceName,
                                    discoveredDeviceCount = castHeader.discoveredDeviceCount,
                                    onOpenDrawer = { drawerScope.launch { drawerState.open() } },
                                    onOpenHelp = { navController.navigate(LumaRoutes.TROUBLESHOOTING) },
                                    onOpenCastDevices = {
                                        if (castHeader.isCastingActive) {
                                            navController.navigate(LumaRoutes.REMOTE_PLAYER)
                                        } else {
                                            navigateTab(LumaRoutes.DEVICES)
                                        }
                                    },
                                    onSearchChange = viewModel::updateSearchQuery,
                                    onSortChange = viewModel::updateSortOption,
                                    onViewModeChange = viewModel::updateViewMode,
                                    onFolderFilterChange = viewModel::selectFolderFilter,
                                    onRefresh = { viewModel.refreshLibrary() },
                                    onDismissBanner = viewModel::dismissBanner,
                                    onVideoClick = { video ->
                                        navController.navigate(LumaRoutes.videoDetails(video.id))
                                    },
                                    onCastVideo = startCastingVideo,
                                    onPlayLocally = viewModel::playVideoLocally,
                                    onShareVideo = viewModel::shareVideo,
                                    onOpenContainingFolder = viewModel::openContainingFolder,
                                    onRenameVideo = viewModel::renameVideo,
                                    onDeleteVideo = viewModel::deleteVideo
                                )
                            }

                            composable(
                                route = LumaRoutes.DEVICES,
                                enterTransition = { tabFadeIn },
                                exitTransition = { tabFadeOut },
                                popEnterTransition = { tabFadeIn },
                                popExitTransition = { tabFadeOut }
                            ) {
                                val isCastSdkAvailable by viewModel.isCastSdkAvailable.collectAsStateWithLifecycle()
                                val isScanningDevices by viewModel.isScanningDevices.collectAsStateWithLifecycle()
                                val discoveryTimedOut by viewModel.discoveryTimedOut.collectAsStateWithLifecycle()
                                val discoveredDevices by viewModel.discoveredDevices.collectAsStateWithLifecycle()
                                val playbackState by viewModel.playbackState.collectAsStateWithLifecycle()
                                val networkHealth by viewModel.networkHealth.collectAsStateWithLifecycle()

                                CastDevicesScreen(
                                    isSdkAvailable = isCastSdkAvailable,
                                    isScanning = isScanningDevices,
                                    discoveryTimedOut = discoveryTimedOut,
                                    devices = discoveredDevices,
                                    playbackState = playbackState,
                                    networkHealth = networkHealth,
                                    needsNearbyWifiPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNearbyWifiPermission,
                                    onRequestNearbyWifiPermission = {
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                            nearbyWifiPermLauncher.launch(Manifest.permission.NEARBY_WIFI_DEVICES)
                                        }
                                    },
                                    onRefreshDevices = viewModel::refreshCastDevices,
                                    onSelectDevice = viewModel::selectCastDevice,
                                    onDisconnect = viewModel::disconnectCast,
                                    onOpenRemote = { navController.navigate(LumaRoutes.REMOTE_PLAYER) },
                                    onOpenHotspotSettings = viewModel::openSystemHotspotSettings,
                                    onOpenTroubleshooting = { navController.navigate(LumaRoutes.TROUBLESHOOTING) }
                                )
                            }

                            composable(
                                route = LumaRoutes.SETTINGS,
                                enterTransition = { tabFadeIn },
                                exitTransition = { tabFadeOut },
                                popEnterTransition = { tabFadeIn },
                                popExitTransition = { tabFadeOut }
                            ) {
                                val settings by viewModel.settings.collectAsStateWithLifecycle()
                                val libraryState by viewModel.libraryState.collectAsStateWithLifecycle()
                                val cacheSizeBytes by viewModel.cacheSizeBytes.collectAsStateWithLifecycle()
                                val networkHealth by viewModel.networkHealth.collectAsStateWithLifecycle()
                                val isScanningDevices by viewModel.isScanningDevices.collectAsStateWithLifecycle()

                                SettingsScreen(
                                    settings = settings,
                                    folders = libraryState.folders,
                                    cacheSizeBytes = cacheSizeBytes,
                                    networkHealth = networkHealth,
                                    connectedDeviceName = castHeader.connectedDeviceName,
                                    discoveredDeviceCount = castHeader.discoveredDeviceCount,
                                    isScanningDevices = isScanningDevices,
                                    appUpdateState = appUpdateState,
                                    onSetReceiverProfile = viewModel::setReceiverProfile,
                                    onSetCustomReceiverAppId = viewModel::setCustomReceiverAppId,
                                    onSetAutoRemux = viewModel::setAutoRemuxEnabled,
                                    onSetAllowTranscode = viewModel::setAllowHardwareTranscode,
                                    onSetMaxTranscodeHeight = viewModel::setMaxTranscodeHeight,
                                    onSetPreferredAudioLanguage = viewModel::setPreferredAudioLanguage,
                                    onSetPreferAc3Passthrough = viewModel::setPreferAc3Passthrough,
                                    onSetPreferredSubtitleLanguage = viewModel::setPreferredSubtitleLanguage,
                                    onSetPreferredPort = viewModel::setPreferredServerPort,
                                    onSetKeepScreenOn = viewModel::setKeepScreenOnRemote,
                                    onRemoveFolder = viewModel::removeSafFolder,
                                    onClearCache = viewModel::clearPreparedMediaCache,
                                    onRefreshDevices = viewModel::refreshCastDevices,
                                    onOpenHotspotSettings = viewModel::openSystemHotspotSettings,
                                    onOpenTroubleshooting = { navController.navigate(LumaRoutes.TROUBLESHOOTING) },
                                    onSetGithubRepo = viewModel::setGithubUpdateRepo,
                                    onSetAutoCheckUpdates = viewModel::setAutoCheckUpdates,
                                    onCheckForUpdates = viewModel::checkForUpdatesManually,
                                    onOpenUpdateDialog = viewModel::openUpdateDialog
                                )
                            }

                            composable(
                                route = LumaRoutes.VIDEO_DETAILS,
                                arguments = listOf(navArgument("videoId") { type = NavType.StringType })
                            ) { entry ->
                                val videoId = Uri.decode(entry.arguments?.getString("videoId").orEmpty())
                                val compatibilityReports by viewModel.compatibilityReports.collectAsStateWithLifecycle()
                                val inspectingIds by viewModel.inspectingVideoIds.collectAsStateWithLifecycle()
                                val video = remember(videoId) { viewModel.findVideoById(videoId) }

                                VideoDetailsScreen(
                                    video = video,
                                    report = compatibilityReports[videoId],
                                    isInspecting = videoId in inspectingIds,
                                    connectionState = castHeader.connectionState,
                                    connectedDeviceName = castHeader.connectedDeviceName,
                                    thumbnailLoader = viewModel.thumbnailLoader,
                                    onBack = { navController.popBackStack() },
                                    onInspect = { viewModel.inspectVideoCompatibility(it) },
                                    onSelectAudioTrack = { v, trackIdx ->
                                        viewModel.selectAudioTrackForVideo(v, trackIdx)
                                    },
                                    onCastClick = startCastingVideo,
                                    onOpenDevices = { navigateTab(LumaRoutes.DEVICES) },
                                    onAddToQueue = viewModel::addVideoToQueue,
                                    onOpenCompatibilityInfo = { v ->
                                        navController.navigate(LumaRoutes.compatibility(v.id))
                                    }
                                )
                            }

                            composable(
                                route = LumaRoutes.COMPATIBILITY,
                                arguments = listOf(navArgument("videoId") { type = NavType.StringType })
                            ) { entry ->
                                val videoId = Uri.decode(entry.arguments?.getString("videoId").orEmpty())
                                val compatibilityReports by viewModel.compatibilityReports.collectAsStateWithLifecycle()
                                val inspectingIds by viewModel.inspectingVideoIds.collectAsStateWithLifecycle()
                                val settings by viewModel.settings.collectAsStateWithLifecycle()
                                val video = remember(videoId) { viewModel.findVideoById(videoId) }

                                CompatibilityInfoScreen(
                                    video = video,
                                    report = compatibilityReports[videoId],
                                    defaultProfile = settings.receiverProfile,
                                    isInspecting = videoId in inspectingIds,
                                    onInspectForProfile = { v, profile ->
                                        viewModel.inspectVideoCompatibility(v, profile)
                                    },
                                    onSelectAudioTrack = { v, trackIdx, profile ->
                                        viewModel.selectAudioTrackForVideo(v, trackIdx, profile)
                                    },
                                    onBack = { navController.popBackStack() }
                                )
                            }

                            composable(LumaRoutes.CASTING_PROGRESS) {
                                val pipelineState by viewModel.pipelineState.collectAsStateWithLifecycle()

                                CastingProgressScreen(
                                    pipelineState = pipelineState,
                                    onCancel = viewModel::cancelActiveCastPipeline,
                                    onRetry = startCastingVideo,
                                    onOpenRemote = {
                                        viewModel.resetPipelineState()
                                        navController.navigate(LumaRoutes.REMOTE_PLAYER) {
                                            popUpTo(LumaRoutes.CASTING_PROGRESS) { inclusive = true }
                                        }
                                    },
                                    onOpenDiagnostics = { navController.navigate(LumaRoutes.TROUBLESHOOTING) },
                                    onBack = { navController.popBackStack() }
                                )
                            }

                            composable(LumaRoutes.REMOTE_PLAYER) {
                                val playbackState by viewModel.playbackState.collectAsStateWithLifecycle()
                                val liveTrackConversion by viewModel.liveTrackConversion.collectAsStateWithLifecycle()
                                val settings by viewModel.settings.collectAsStateWithLifecycle()

                                RemotePlayerScreen(
                                    playbackState = playbackState,
                                    liveTrackConversion = liveTrackConversion,
                                    keepScreenOn = settings.keepScreenOnRemote,
                                    thumbnailLoader = viewModel.thumbnailLoader,
                                    onBack = { navController.popBackStack() },
                                    onTogglePlayPause = viewModel::togglePlayPause,
                                    onSeekTo = viewModel::seekTo,
                                    onSeekRelative = viewModel::seekRelative,
                                    onQueuePrevious = viewModel::queuePrevious,
                                    onQueueNext = viewModel::queueNext,
                                    onVolumeChange = viewModel::setVolume,
                                    onToggleMute = viewModel::toggleMute,
                                    onSwitchAudioTrack = viewModel::switchAudioTrackDuringPlayback,
                                    onSelectSubtitle = viewModel::selectSubtitleDuringPlayback,
                                    onPickExternalSubtitle = {
                                        externalSubtitlePickerLauncher.launch(arrayOf("*/*"))
                                    },
                                    onAuthorizeSubtitleFolder = launchFolderPicker,
                                    onAdjustSubtitleDelay = viewModel::adjustSubtitleDelay,
                                    onUpdateSubtitleAppearance = viewModel::updateSubtitleAppearance,
                                    onCancelLiveConversion = viewModel::cancelLiveTrackConversion,
                                    onDisconnect = viewModel::disconnectCast,
                                    onOpenDiagnostics = { navController.navigate(LumaRoutes.TROUBLESHOOTING) }
                                )
                            }

                            composable(LumaRoutes.TROUBLESHOOTING) {
                                val networkHealth by viewModel.networkHealth.collectAsStateWithLifecycle()
                                val resourceHealth by viewModel.resourceHealth.collectAsStateWithLifecycle()
                                val isCastSdkAvailable by viewModel.isCastSdkAvailable.collectAsStateWithLifecycle()
                                val isRunningNetworkDiagnostics by viewModel.isRunningNetworkDiagnostics.collectAsStateWithLifecycle()

                                TroubleshootingScreen(
                                    networkHealth = networkHealth,
                                    resourceHealth = resourceHealth,
                                    isCastSdkAvailable = isCastSdkAvailable,
                                    discoveredDeviceCount = castHeader.discoveredDeviceCount,
                                    connectedDeviceName = castHeader.connectedDeviceName,
                                    isRunningDiagnostics = isRunningNetworkDiagnostics,
                                    onRunHttpSelfTest = viewModel::runHttpServerDiagnostics,
                                    onRefreshDevices = viewModel::refreshCastDevices,
                                    onOpenHotspotSettings = viewModel::openSystemHotspotSettings,
                                    onBack = { navController.popBackStack() }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showUpdateDialog) {
        AppUpdateDialog(
            updateState = appUpdateState,
            currentVersion = BuildConfig.VERSION_NAME,
            onDownloadAndInstall = viewModel::downloadAndInstallUpdate,
            onInstallNow = { viewModel.requestInstallIfReady() },
            onOpenInstallPermission = {
                runCatching {
                    unknownSourcesLauncher.launch(viewModel.createManageUnknownAppsIntent())
                }
            },
            onCancelDownload = viewModel::cancelUpdateDownload,
            onRetryCheck = viewModel::checkForUpdatesManually,
            onSkipVersion = viewModel::skipUpdateVersion,
            onOpenReleasePage = viewModel::openUpdateReleaseInBrowser,
            onDismiss = viewModel::dismissUpdateDialog
        )
    }
}

/**
 * Isolated slot that collects high-frequency playbackState locally so 1-second position ticks
 * ONLY recompose this compact mini-bar when a Chromecast session is connected.
 */
@Composable
private fun LiveMiniCastControllerSlot(
    viewModel: LumaCastViewModel,
    onOpenRemote: () -> Unit
) {
    val playbackState by viewModel.playbackState.collectAsStateWithLifecycle()
    AnimatedVisibility(
        visible = playbackState.connectionState == CastConnectionState.CONNECTED,
        enter = slideInVertically(
            animationSpec = tween(220, easing = FastOutSlowInEasing),
            initialOffsetY = { it / 2 }
        ) + fadeIn(tween(180)),
        exit = slideOutVertically(
            animationSpec = tween(180, easing = FastOutSlowInEasing),
            targetOffsetY = { it / 2 }
        ) + fadeOut(tween(140))
    ) {
        MiniCastControllerBar(
            playbackState = playbackState,
            thumbnailLoader = viewModel.thumbnailLoader,
            onTogglePlayPause = viewModel::togglePlayPause,
            onOpenRemote = onOpenRemote
        )
    }
}
