package com.example.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.core.common.CastConnectionState
import com.example.core.common.LibraryAccessState
import com.example.core.common.SortOption
import com.example.core.common.VideoItem
import com.example.core.common.ViewMode
import com.example.core.media.VideoThumbnailLoader
import com.example.ui.LibraryUiState
import com.example.ui.components.DeleteVideoConfirmDialog
import com.example.ui.components.RenameVideoDialog
import com.example.ui.components.VideoGridCard
import com.example.ui.components.VideoGridSkeleton
import com.example.ui.components.VideoListRow
import com.example.ui.components.VideoListSkeleton
import com.example.ui.components.VideoRowOverflowMenu
import com.example.ui.theme.AppThemeTokens
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    state: LibraryUiState,
    thumbnailLoader: VideoThumbnailLoader,
    connectionState: CastConnectionState,
    connectedDeviceName: String?,
    discoveredDeviceCount: Int,
    onOpenDrawer: () -> Unit,
    onOpenHelp: () -> Unit,
    onOpenCastDevices: () -> Unit,
    onSearchChange: (String) -> Unit,
    onSortChange: (SortOption) -> Unit,
    onViewModeChange: (ViewMode) -> Unit,
    onFolderFilterChange: (String?) -> Unit,
    onRefresh: () -> Unit,
    onDismissBanner: () -> Unit,
    onVideoClick: (VideoItem) -> Unit,
    onCastVideo: (VideoItem) -> Unit,
    onPlayLocally: (VideoItem) -> Unit,
    onShareVideo: (VideoItem) -> Unit,
    onOpenContainingFolder: (VideoItem) -> Unit,
    onRenameVideo: (VideoItem, String) -> Unit,
    onDeleteVideo: (VideoItem) -> Unit
) {
    var searchExpanded by rememberSaveable { mutableStateOf(false) }
    var sortMenuExpanded by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<VideoItem?>(null) }
    var deleteTarget by remember { mutableStateOf<VideoItem?>(null) }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    val coroutineScope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current

    val showScrollToTop by remember(state.viewMode) {
        derivedStateOf {
            if (state.viewMode == ViewMode.GRID) {
                gridState.firstVisibleItemIndex >= 6
            } else {
                listState.firstVisibleItemIndex >= 6
            }
        }
    }

    val isConnected = connectionState == CastConnectionState.CONNECTED
    val colors = MaterialTheme.colorScheme
    val tokens = AppThemeTokens.typography

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .nestedScroll(scrollBehavior.nestedScrollConnection)
            .testTag("library_screen")
    ) {
        // Clean Top Application Bar: Menu | "Video" | Search | Help | Chromecast
        TopAppBar(
            scrollBehavior = scrollBehavior,
            title = {
                Column {
                    Text(
                        text = stringResource(R.string.video_screen_title),
                        style = tokens.toolbarTitle,
                        color = colors.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (state.allVideos.isNotEmpty() || state.selectedFolderName != null) {
                        Text(
                            text = buildString {
                                append("${state.filteredVideos.size} video")
                                if (state.filteredVideos.size != 1) append("s")
                                if (state.selectedFolderName != null) {
                                    append(" · ${state.selectedFolderName}")
                                }
                            },
                            style = tokens.toolbarSubtitle,
                            color = colors.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            },
            navigationIcon = {
                IconButton(
                    onClick = onOpenDrawer,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("video_menu_drawer_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Menu,
                        contentDescription = stringResource(R.string.cd_nav_menu),
                        tint = colors.onSurface
                    )
                }
            },
            actions = {
                IconButton(
                    onClick = {
                        if (searchExpanded && state.searchQuery.isNotEmpty()) {
                            onSearchChange("")
                        }
                        searchExpanded = !searchExpanded
                    },
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("video_search_toggle")
                ) {
                    Icon(
                        imageVector = if (searchExpanded) Icons.Filled.Close else Icons.Filled.Search,
                        contentDescription = stringResource(R.string.cd_search_videos),
                        tint = if (searchExpanded || state.searchQuery.isNotEmpty()) colors.primary else colors.onSurface
                    )
                }
                IconButton(
                    onClick = onOpenHelp,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("video_help_button")
                ) {
                    Icon(
                        imageVector = Icons.Outlined.HelpOutline,
                        contentDescription = stringResource(R.string.cd_help),
                        tint = colors.onSurface
                    )
                }
                IconButton(
                    onClick = onOpenCastDevices,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("video_cast_button")
                ) {
                    Icon(
                        imageVector = if (isConnected) Icons.Filled.CastConnected else Icons.Filled.Cast,
                        contentDescription = if (isConnected) {
                            "Casting to ${connectedDeviceName ?: "Chromecast"}"
                        } else if (discoveredDeviceCount > 0) {
                            "$discoveredDeviceCount Chromecast TV available"
                        } else {
                            stringResource(R.string.cd_cast_button)
                        },
                        tint = when {
                            isConnected -> colors.secondary
                            discoveredDeviceCount > 0 -> colors.primary
                            else -> colors.onSurface
                        }
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = colors.surface,
                scrolledContainerColor = colors.surfaceContainer,
                titleContentColor = colors.onSurface
            )
        )

        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 960.dp)
                    .padding(horizontal = 14.dp)
            ) {
                // Smoothly expandable inline search field
                AnimatedVisibility(
                    visible = searchExpanded || state.searchQuery.isNotEmpty(),
                    enter = expandVertically(tween(200, easing = FastOutSlowInEasing)) + fadeIn(tween(180)),
                    exit = shrinkVertically(tween(180, easing = FastOutSlowInEasing)) + fadeOut(tween(140))
                ) {
                    OutlinedTextField(
                        value = state.searchQuery,
                        onValueChange = onSearchChange,
                        textStyle = MaterialTheme.typography.bodyMedium,
                        placeholder = {
                            Text(
                                text = stringResource(R.string.library_search_hint),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Filled.Search,
                                contentDescription = null,
                                tint = colors.onSurfaceVariant
                            )
                        },
                        trailingIcon = {
                            if (state.searchQuery.isNotEmpty()) {
                                IconButton(onClick = { onSearchChange("") }) {
                                    Icon(
                                        imageVector = Icons.Filled.Close,
                                        contentDescription = "Clear search"
                                    )
                                }
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = colors.primary,
                            unfocusedBorderColor = colors.outlineVariant,
                            focusedContainerColor = colors.surfaceContainer,
                            unfocusedContainerColor = colors.surfaceContainer
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp)
                            .testTag("library_search_input")
                    )
                }

                // Controls Strip: Sort menu | Refresh | List/Grid
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Box {
                        FilterChip(
                            selected = true,
                            onClick = { sortMenuExpanded = true },
                            label = {
                                Text(
                                    text = state.sortOption.label,
                                    style = MaterialTheme.typography.labelLarge,
                                    maxLines = 1
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Sort,
                                    contentDescription = "Sort videos",
                                    modifier = Modifier.size(16.dp)
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = colors.primaryContainer,
                                selectedLabelColor = colors.primary,
                                selectedLeadingIconColor = colors.primary
                            ),
                            modifier = Modifier.testTag("library_sort_dropdown_button")
                        )
                        DropdownMenu(
                            expanded = sortMenuExpanded,
                            onDismissRequest = { sortMenuExpanded = false },
                            containerColor = colors.surfaceContainerHigh
                        ) {
                            SortOption.entries.forEach { option ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = option.label,
                                            style = if (state.sortOption == option) {
                                                tokens.trackTitleSelected
                                            } else {
                                                MaterialTheme.typography.bodyMedium
                                            },
                                            color = if (state.sortOption == option) {
                                                colors.primary
                                            } else {
                                                colors.onSurface
                                            }
                                        )
                                    },
                                    trailingIcon = {
                                        if (state.sortOption == option) {
                                            Icon(
                                                imageVector = Icons.Filled.Check,
                                                contentDescription = "Selected",
                                                tint = colors.primary
                                            )
                                        }
                                    },
                                    onClick = {
                                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        sortMenuExpanded = false
                                        onSortChange(option)
                                    },
                                    modifier = Modifier.testTag("sort_option_${option.name.lowercase()}")
                                )
                            }
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onRefresh()
                            },
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("library_refresh_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Refresh,
                                contentDescription = stringResource(R.string.action_refresh),
                                tint = colors.onSurfaceVariant
                            )
                        }
                        IconButton(
                            onClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onViewModeChange(
                                    if (state.viewMode == ViewMode.LIST) ViewMode.GRID else ViewMode.LIST
                                )
                            },
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("library_view_mode_toggle")
                        ) {
                            Icon(
                                imageVector = if (state.viewMode == ViewMode.LIST) {
                                    Icons.Filled.GridView
                                } else {
                                    Icons.AutoMirrored.Filled.ViewList
                                },
                                contentDescription = if (state.viewMode == ViewMode.LIST) {
                                    stringResource(R.string.cd_grid_view)
                                } else {
                                    stringResource(R.string.cd_list_view)
                                },
                                tint = colors.primary
                            )
                        }
                    }
                }

                // Dynamic folder filter chips
                if (state.availableFolderNames.size > 1 || state.selectedFolderName != null) {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                    ) {
                        item(key = "folder_all") {
                            FilterChip(
                                selected = state.selectedFolderName == null,
                                onClick = { onFolderFilterChange(null) },
                                label = {
                                    Text(
                                        text = "All (${state.allVideos.size})",
                                        style = tokens.telemetryCompact
                                    )
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = colors.secondaryContainer,
                                    selectedLabelColor = colors.secondary
                                )
                            )
                        }
                        items(state.availableFolderNames, key = { "folder_$it" }) { folderName ->
                            FilterChip(
                                selected = state.selectedFolderName.equals(folderName, ignoreCase = true),
                                onClick = {
                                    onFolderFilterChange(
                                        if (state.selectedFolderName.equals(folderName, ignoreCase = true)) null else folderName
                                    )
                                },
                                label = {
                                    Text(
                                        text = folderName,
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = colors.secondaryContainer,
                                    selectedLabelColor = colors.secondary
                                )
                            )
                        }
                    }
                }

                // Info / Action feedback banner with smooth reveal/dismiss
                AnimatedVisibility(
                    visible = state.bannerMessage != null,
                    enter = expandVertically(tween(200)) + fadeIn(tween(160)),
                    exit = shrinkVertically(tween(180)) + fadeOut(tween(120))
                ) {
                    state.bannerMessage?.let { banner ->
                        Surface(
                            color = colors.primaryContainer,
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, colors.primary.copy(alpha = 0.4f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = banner,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.onPrimaryContainer,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(
                                    onClick = onDismissBanner,
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Close,
                                        contentDescription = "Dismiss",
                                        tint = colors.primary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                // Main Content Body: Shimmer Skeleton -> Empty State -> Animated List/Grid
                Box(modifier = Modifier.fillMaxSize()) {
                    when {
                        state.isLoading -> {
                            if (state.viewMode == ViewMode.GRID) {
                                VideoGridSkeleton()
                            } else {
                                VideoListSkeleton()
                            }
                        }

                        state.filteredVideos.isEmpty() -> {
                            LibraryEmptyOrPermissionState(
                                state = state,
                                onClearFilters = {
                                    onSearchChange("")
                                    onFolderFilterChange(null)
                                },
                                onRefresh = onRefresh
                            )
                        }

                        else -> {
                            AnimatedContent(
                                targetState = state.viewMode,
                                transitionSpec = {
                                    fadeIn(tween(180)) togetherWith fadeOut(tween(140))
                                },
                                label = "library_view_mode_transition"
                            ) { mode ->
                                if (mode == ViewMode.GRID) {
                                    LazyVerticalGrid(
                                        state = gridState,
                                        columns = GridCells.Adaptive(minSize = 180.dp),
                                        verticalArrangement = Arrangement.spacedBy(12.dp),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        contentPadding = PaddingValues(top = 4.dp, bottom = 28.dp),
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .testTag("video_library_grid")
                                    ) {
                                        items(
                                            items = state.filteredVideos,
                                            key = { it.id },
                                            contentType = { "video_grid_item" }
                                        ) { video ->
                                            VideoGridCard(
                                                video = video,
                                                thumbnailLoader = thumbnailLoader,
                                                onClick = { onVideoClick(video) },
                                                modifier = Modifier.animateItem(),
                                                overflowMenu = {
                                                    VideoRowOverflowMenu(
                                                        video = video,
                                                        onCast = { onCastVideo(video) },
                                                        onPlayLocally = { onPlayLocally(video) },
                                                        onDetails = { onVideoClick(video) },
                                                        onShare = { onShareVideo(video) },
                                                        onOpenFolder = { onOpenContainingFolder(video) },
                                                        onRequestRename = { renameTarget = video },
                                                        onRequestDelete = { deleteTarget = video }
                                                    )
                                                }
                                            )
                                        }
                                    }
                                } else {
                                    LazyColumn(
                                        state = listState,
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                        contentPadding = PaddingValues(top = 4.dp, bottom = 28.dp),
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .testTag("video_library_list")
                                    ) {
                                        items(
                                            items = state.filteredVideos,
                                            key = { it.id },
                                            contentType = { "video_list_row" }
                                        ) { video ->
                                            VideoListRow(
                                                video = video,
                                                thumbnailLoader = thumbnailLoader,
                                                onClick = { onVideoClick(video) },
                                                modifier = Modifier.animateItem(),
                                                overflowMenu = {
                                                    VideoRowOverflowMenu(
                                                        video = video,
                                                        onCast = { onCastVideo(video) },
                                                        onPlayLocally = { onPlayLocally(video) },
                                                        onDetails = { onVideoClick(video) },
                                                        onShare = { onShareVideo(video) },
                                                        onOpenFolder = { onOpenContainingFolder(video) },
                                                        onRequestRename = { renameTarget = video },
                                                        onRequestDelete = { deleteTarget = video }
                                                    )
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Smooth scroll-to-top FAB on deep library scrolls
                    androidx.compose.animation.AnimatedVisibility(
                        visible = showScrollToTop && !state.isLoading && state.filteredVideos.isNotEmpty(),
                        enter = fadeIn(tween(180)) + scaleIn(initialScale = 0.85f),
                        exit = fadeOut(tween(140)) + scaleOut(targetScale = 0.85f),
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(bottom = 16.dp, end = 4.dp)
                    ) {
                        SmallFloatingActionButton(
                            onClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                coroutineScope.launch {
                                    if (state.viewMode == ViewMode.GRID) {
                                        gridState.animateScrollToItem(0)
                                    } else {
                                        listState.animateScrollToItem(0)
                                    }
                                }
                            },
                            shape = CircleShape,
                            containerColor = colors.surfaceContainerHighest,
                            contentColor = colors.primary,
                            modifier = Modifier.testTag("library_scroll_to_top")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.KeyboardArrowUp,
                                contentDescription = "Scroll to top"
                            )
                        }
                    }
                }
            }
        }
    }

    renameTarget?.let { target ->
        RenameVideoDialog(
            video = target,
            onDismiss = { renameTarget = null },
            onConfirm = { newBase ->
                renameTarget = null
                onRenameVideo(target, newBase)
            }
        )
    }

    deleteTarget?.let { target ->
        DeleteVideoConfirmDialog(
            video = target,
            onDismiss = { deleteTarget = null },
            onConfirm = {
                deleteTarget = null
                onDeleteVideo(target)
            }
        )
    }
}

@Composable
private fun LibraryEmptyOrPermissionState(
    state: LibraryUiState,
    onClearFilters: () -> Unit,
    onRefresh: () -> Unit
) {
    val isFilterEmpty = state.allVideos.isNotEmpty()
    val isPermissionDenied = !isFilterEmpty && state.accessState == LibraryAccessState.MEDIA_STORE_UNAVAILABLE
    val colors = MaterialTheme.colorScheme

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Surface(
            color = colors.surfaceContainer,
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(1.dp, colors.outlineVariant),
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 460.dp)
                .padding(16.dp)
                .testTag("library_empty_state")
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(24.dp)
            ) {
                Icon(
                    imageVector = if (isPermissionDenied) Icons.Filled.FolderOff else Icons.Filled.VideoLibrary,
                    contentDescription = null,
                    tint = if (isPermissionDenied) colors.secondary else colors.primary,
                    modifier = Modifier.size(48.dp)
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    text = when {
                        isFilterEmpty -> "No matching videos"
                        isPermissionDenied -> "Media library unavailable"
                        else -> "No local videos found"
                    },
                    style = MaterialTheme.typography.titleLarge,
                    color = colors.onSurface,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = when {
                        isFilterEmpty -> "No videos match \"${state.searchQuery.ifBlank { state.selectedFolderName.orEmpty() }}\". Clear active filters to view all videos."
                        isPermissionDenied -> "Unable to read local videos from MediaStore right now. Refresh to scan again."
                        else -> "Videos stored on your device appear here automatically. Refresh to scan again."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(18.dp))

                if (isFilterEmpty) {
                    Button(
                        onClick = onClearFilters,
                        colors = ButtonDefaults.buttonColors(containerColor = colors.primary),
                        modifier = Modifier.testTag("clear_filters_button")
                    ) {
                        Text("Clear filters")
                    }
                } else {
                    Button(
                        onClick = onRefresh,
                        colors = ButtonDefaults.buttonColors(containerColor = colors.primary),
                        modifier = Modifier.testTag("empty_refresh_videos_button")
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.action_refresh))
                    }
                }
            }
        }
    }
}
