package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.data.model.AppLanguage
import com.example.data.podcast.PodcastCategory
import com.example.data.podcast.PodcastDownloadProgress
import com.example.data.podcast.PodcastDownloadStatus
import com.example.data.podcast.PodcastEpisode
import com.example.data.podcast.PodcastFeedLoadResult
import com.example.data.podcast.PodcastPlayerManager
import com.example.data.podcast.PodcastRepository
import com.example.data.podcast.podcastLanguageDisplayName
import com.example.localization.AppLocalization
import com.example.ui.components.NoorTopBar
import java.util.Locale

private fun formatMillisDisplay(ms: Long): String {
    if (ms <= 0L) return "00:00"
    val totalSec = ms / 1000L
    val h = totalSec / 3600L
    val m = (totalSec % 3600L) / 60L
    val s = totalSec % 60L
    return if (h > 0) {
        String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    } else {
        String.format(Locale.US, "%02d:%02d", m, s)
    }
}

private fun formatBytesDisplay(bytes: Long): String {
    if (bytes <= 0L) return ""
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1.0) String.format(Locale.US, "%.1f MB", mb)
    else String.format(Locale.US, "%.0f KB", (bytes / 1024.0).coerceAtLeast(1.0))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PodcastsScreen(
    language: AppLanguage,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val playbackState by PodcastPlayerManager.playbackState.collectAsStateWithLifecycle()
    val downloadStates by PodcastRepository.downloadStates.collectAsStateWithLifecycle()

    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf<PodcastCategory?>(null) }
    var selectedShowId by remember { mutableStateOf<String?>(null) }
    var downloadedOnly by remember { mutableStateOf(false) }
    var refreshTrigger by remember { mutableIntStateOf(0) }
    var isLoading by remember { mutableStateOf(true) }
    var showQueueSheet by remember { mutableStateOf(false) }
    var feedResult by remember {
        mutableStateOf(
            PodcastFeedLoadResult(
                shows = PodcastRepository.VERIFIED_SHOWS,
                episodes = emptyList(),
                isOffline = false,
                isFromCache = false
            )
        )
    }

    LaunchedEffect(refreshTrigger) {
        isLoading = true
        feedResult = PodcastRepository.loadCatalogAndEpisodes(
            context = context,
            forceRefresh = refreshTrigger > 0
        )
        isLoading = false
    }

    DisposableEffect(Unit) {
        onDispose {
            PodcastPlayerManager.detachUiOnly()
        }
    }

    val downloadedGuids = remember(downloadStates) {
        downloadStates.filterValues { it.status == PodcastDownloadStatus.DOWNLOADED }.keys
    }

    val nonEmptyCategories = remember(feedResult.episodes) {
        PodcastRepository.availableCategoriesWithCounts(feedResult.episodes)
    }

    // Ensure selectedCategory never stays stuck on an empty category after refresh
    LaunchedEffect(nonEmptyCategories) {
        if (selectedCategory != null && nonEmptyCategories.none { it.first == selectedCategory }) {
            selectedCategory = null
        }
    }

    val visibleEpisodes = remember(
        feedResult.episodes,
        searchQuery,
        selectedCategory,
        selectedShowId,
        downloadedOnly,
        downloadedGuids
    ) {
        PodcastRepository.filterEpisodes(
            episodes = feedResult.episodes,
            query = searchQuery,
            selectedCategory = selectedCategory,
            selectedShowId = selectedShowId,
            downloadedOnly = downloadedOnly,
            downloadedGuids = downloadedGuids
        )
    }

    Scaffold(
        topBar = {
            NoorTopBar(
                title = AppLocalization.get("podcasts", language),
                language = language,
                onBackClick = onBack,
                actions = {
                    IconButton(
                        onClick = { refreshTrigger++ },
                        modifier = Modifier
                            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                            .testTag("podcast_refresh_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = AppLocalization.get("pod_refresh", language)
                        )
                    }
                }
            )
        },
        bottomBar = {
            val activeEp = playbackState.currentEpisode
            if (activeEp != null) {
                ActivePodcastMiniPlayer(
                    episode = activeEp,
                    language = language,
                    playbackState = playbackState,
                    onTogglePlayPause = { PodcastPlayerManager.togglePlayPause(context) },
                    onSkipPrev = { PodcastPlayerManager.skipToPrevious() },
                    onSkipNext = { PodcastPlayerManager.skipToNext() },
                    onSeekTo = { PodcastPlayerManager.seekTo(it) },
                    onCycleSpeed = { PodcastPlayerManager.cyclePlaybackSpeed() },
                    onOpenQueue = { showQueueSheet = true },
                    onStop = { PodcastPlayerManager.stopAndClearSession(context) }
                )
            }
        }
    ) { innerPadding ->
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background)
                .testTag("podcasts_lazy_column")
        ) {
            // Search Bar
            item {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text(AppLocalization.get("pod_search_hint", language), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(
                                onClick = { searchQuery = "" },
                                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                            ) {
                                Icon(Icons.Default.Close, contentDescription = AppLocalization.get("cancel", language))
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("podcast_search_input")
                )
            }

            // Verified Feed & Offline / Cache Banner
            item {
                if (feedResult.isOffline) {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("podcast_offline_banner")
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(12.dp)
                        ) {
                            Icon(Icons.Default.CloudOff, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = AppLocalization.get("pod_offline_banner", language),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(horizontal = 4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Verified,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "${AppLocalization.get("pod_verified_rss", language)} (${feedResult.shows.size})",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            // Show Filter Row
            item {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    item {
                        FilterChip(
                            selected = selectedShowId == null && !downloadedOnly,
                            onClick = {
                                selectedShowId = null
                                downloadedOnly = false
                            },
                            label = { Text(AppLocalization.get("pod_all_shows", language)) },
                            modifier = Modifier.testTag("pod_filter_all_shows")
                        )
                    }
                    if (downloadedGuids.isNotEmpty()) {
                        item {
                            FilterChip(
                                selected = downloadedOnly,
                                onClick = { downloadedOnly = !downloadedOnly },
                                leadingIcon = {
                                    Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                                },
                                label = { Text("${AppLocalization.get("pod_downloaded_only", language)} (${downloadedGuids.size})") },
                                modifier = Modifier.testTag("pod_filter_downloaded_only")
                            )
                        }
                    }
                    items(feedResult.shows, key = { it.id }) { show ->
                        val epCount = feedResult.episodes.count { it.showId == show.id }
                        if (epCount > 0) {
                            FilterChip(
                                selected = selectedShowId == show.id,
                                onClick = {
                                    selectedShowId = if (selectedShowId == show.id) null else show.id
                                    downloadedOnly = false
                                },
                                label = { Text("${show.showName} ($epCount)") },
                                modifier = Modifier.testTag("pod_show_chip_${show.id}")
                            )
                        }
                    }
                }
            }

            // Non-Empty Category Filter Chips (never shows empty categories as populated)
            if (nonEmptyCategories.isNotEmpty()) {
                item {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        item {
                            FilterChip(
                                selected = selectedCategory == null,
                                onClick = { selectedCategory = null },
                                label = { Text(AppLocalization.get("pod_all_categories", language)) },
                                modifier = Modifier.testTag("pod_cat_all")
                            )
                        }
                        items(nonEmptyCategories, key = { it.first.id }) { (category, count) ->
                            FilterChip(
                                selected = selectedCategory == category,
                                onClick = {
                                    selectedCategory = if (selectedCategory == category) null else category
                                },
                                label = { Text("${AppLocalization.get(category.labelKey, language)} ($count)") },
                                modifier = Modifier.testTag("pod_cat_chip_${category.id}")
                            )
                        }
                    }
                }
            }

            if (isLoading && feedResult.episodes.isEmpty()) {
                item {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 40.dp)
                    ) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = AppLocalization.get("pod_loading", language),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else if (visibleEpisodes.isEmpty()) {
                item {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("podcast_empty_card")
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ErrorOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(36.dp)
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = feedResult.errorMessage ?: AppLocalization.get("pod_empty_state", language),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            OutlinedButton(onClick = { refreshTrigger++ }) {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(AppLocalization.get("pod_refresh", language))
                            }
                        }
                    }
                }
            } else {
                itemsIndexed(visibleEpisodes, key = { _, ep -> "${ep.showId}_${ep.guid}" }) { index, episode ->
                    val isCurrent = playbackState.currentEpisode?.guid == episode.guid
                    val isPlaying = isCurrent && playbackState.isPlaying
                    val isBuffering = isCurrent && playbackState.isBuffering
                    val dlState = downloadStates[episode.guid]

                    PodcastEpisodeCard(
                        episode = episode,
                        language = language,
                        isCurrent = isCurrent,
                        isPlaying = isPlaying,
                        isBuffering = isBuffering,
                        downloadProgress = dlState,
                        onPlayToggle = {
                            if (isCurrent) {
                                PodcastPlayerManager.togglePlayPause(context)
                            } else {
                                PodcastPlayerManager.playEpisodeWithQueue(context, visibleEpisodes, index)
                            }
                        },
                        onAddToQueue = { PodcastPlayerManager.addToQueue(context, episode) },
                        onDownloadAction = {
                            when (dlState?.status) {
                                PodcastDownloadStatus.DOWNLOADING -> PodcastRepository.pauseEpisodeDownload(episode.guid)
                                PodcastDownloadStatus.DOWNLOADED -> PodcastRepository.deleteDownloadedEpisode(context, episode)
                                else -> PodcastRepository.startEpisodeDownload(context, episode)
                            }
                        },
                        onDeleteDownload = {
                            PodcastRepository.deleteDownloadedEpisode(context, episode)
                        }
                    )
                }
            }
        }
    }

    if (showQueueSheet) {
        ModalBottomSheet(onDismissRequest = { showQueueSheet = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(
                    text = "${AppLocalization.get("pod_queue", language)} (${playbackState.queue.size})",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
                Spacer(modifier = Modifier.height(10.dp))
                playbackState.queue.forEachIndexed { idx, ep ->
                    val active = idx == playbackState.currentIndex
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (active) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surface
                            )
                            .clickable {
                                PodcastPlayerManager.playEpisodeWithQueue(context, playbackState.queue, idx)
                                showQueueSheet = false
                            }
                            .padding(12.dp)
                    ) {
                        Icon(
                            imageVector = if (active) Icons.Default.PlayArrow else Icons.Default.Podcasts,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = ep.title,
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = ep.showName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun PodcastEpisodeCard(
    episode: PodcastEpisode,
    language: AppLanguage,
    isCurrent: Boolean,
    isPlaying: Boolean,
    isBuffering: Boolean,
    downloadProgress: PodcastDownloadProgress?,
    onPlayToggle: () -> Unit,
    onAddToQueue: () -> Unit,
    onDownloadAction: () -> Unit,
    onDeleteDownload: () -> Unit
) {
    val isDownloaded = downloadProgress?.status == PodcastDownloadStatus.DOWNLOADED
    val isDownloading = downloadProgress?.status == PodcastDownloadStatus.DOWNLOADING
    val isPaused = downloadProgress?.status == PodcastDownloadStatus.PAUSED

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isCurrent) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("podcast_card_${episode.guid}")
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer)
                ) {
                    if (episode.artworkUrl.startsWith("http")) {
                        AsyncImage(
                            model = episode.artworkUrl,
                            contentDescription = episode.showName,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Podcasts,
                            contentDescription = episode.showName,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = episode.title,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "${episode.showName} • ${episode.host}",
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val metaParts = buildList {
                        add(episode.publisher)
                        add(podcastLanguageDisplayName(episode.languageCode, language))
                        if (episode.pubDateDisplay.isNotBlank()) add(episode.pubDateDisplay)
                        if (episode.durationDisplay.isNotBlank()) add(episode.durationDisplay)
                    }
                    Text(
                        text = metaParts.joinToString(" • "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            if (episode.description.isNotBlank()) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = episode.description,
                    style = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (isDownloading) {
                Spacer(modifier = Modifier.height(10.dp))
                if ((downloadProgress?.totalBytes ?: 0L) > 0L) {
                    LinearProgressIndicator(
                        progress = { downloadProgress?.progressFraction ?: 0f },
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${AppLocalization.get("pod_downloading", language)} ${formatBytesDisplay(downloadProgress?.bytesDownloaded ?: 0L)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            } else if (isDownloaded) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(15.dp)
                    )
                    Text(
                        text = "${AppLocalization.get("pod_offline_ready", language)} (${formatBytesDisplay(downloadProgress?.bytesDownloaded ?: 0L)})",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            } else if (downloadProgress?.status == PodcastDownloadStatus.FAILED && !downloadProgress.errorMessage.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = downloadProgress.errorMessage,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Button(
                    onClick = onPlayToggle,
                    shape = CircleShape,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                    modifier = Modifier
                        .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                        .testTag("play_episode_${episode.guid}")
                ) {
                    if (isBuffering) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) AppLocalization.get("pod_pause", language) else AppLocalization.get("pod_play", language)
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isPlaying) AppLocalization.get("pod_pause", language) else AppLocalization.get("pod_play", language),
                        style = MaterialTheme.typography.labelLarge
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onAddToQueue,
                        modifier = Modifier
                            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                            .testTag("queue_episode_${episode.guid}")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                            contentDescription = AppLocalization.get("pod_add_queue", language),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (episode.downloadPermittedByPublisher) {
                        if (isDownloaded) {
                            IconButton(
                                onClick = onDeleteDownload,
                                modifier = Modifier
                                    .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                                    .testTag("delete_episode_${episode.guid}")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = AppLocalization.get("pod_delete_offline", language),
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        } else {
                            IconButton(
                                onClick = onDownloadAction,
                                modifier = Modifier
                                    .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                                    .testTag("download_episode_${episode.guid}")
                            ) {
                                Icon(
                                    imageVector = when {
                                        isDownloading -> Icons.Default.Pause
                                        isPaused -> Icons.Default.PlayArrow
                                        else -> Icons.Default.Download
                                    },
                                    contentDescription = when {
                                        isDownloading -> AppLocalization.get("pod_pause", language)
                                        isPaused -> AppLocalization.get("pod_resume", language)
                                        else -> AppLocalization.get("pod_download", language)
                                    },
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActivePodcastMiniPlayer(
    episode: PodcastEpisode,
    language: AppLanguage,
    playbackState: com.example.data.podcast.PodcastPlaybackUiState,
    onTogglePlayPause: () -> Unit,
    onSkipPrev: () -> Unit,
    onSkipNext: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onCycleSpeed: () -> Unit,
    onOpenQueue: () -> Unit,
    onStop: () -> Unit
) {
    Surface(
        tonalElevation = 8.dp,
        shadowElevation = 8.dp,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .testTag("active_podcast_player_bar")
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = episode.title,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val subtitle = if (playbackState.isBuffering) {
                        AppLocalization.get("pod_buffering", language)
                    } else if (playbackState.playingOfflineFile) {
                        "${episode.showName} • ${AppLocalization.get("pod_offline_ready", language)}"
                    } else {
                        episode.showName
                    }
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                TextButton(
                    onClick = onCycleSpeed,
                    modifier = Modifier
                        .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                        .testTag("podcast_speed_btn")
                ) {
                    Text(
                        text = "${playbackState.playbackSpeed}x",
                        fontWeight = FontWeight.Bold
                    )
                }

                IconButton(
                    onClick = onOpenQueue,
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                        contentDescription = AppLocalization.get("pod_queue", language)
                    )
                }

                IconButton(
                    onClick = onStop,
                    modifier = Modifier
                        .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                        .testTag("podcast_stop_btn")
                ) {
                    Icon(
                        imageVector = Icons.Default.Stop,
                        contentDescription = AppLocalization.get("pod_stop", language),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }

            if (!playbackState.errorMessage.isNullOrBlank()) {
                Text(
                    text = playbackState.errorMessage,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2
                )
            }

            val durationMs = playbackState.durationMs.coerceAtLeast(1L)
            val progress = (playbackState.positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
            Slider(
                value = progress,
                onValueChange = { frac ->
                    if (playbackState.durationMs > 0L) {
                        onSeekTo((frac * playbackState.durationMs).toLong())
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("podcast_seek_slider")
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = formatMillisDisplay(playbackState.positionMs),
                    style = MaterialTheme.typography.labelSmall
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onSkipPrev,
                        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                    ) {
                        Icon(Icons.Default.SkipPrevious, contentDescription = AppLocalization.get("pod_prev", language))
                    }
                    IconButton(
                        onClick = { PodcastPlayerManager.skipBackward() },
                        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                    ) {
                        Icon(Icons.Default.Replay10, contentDescription = "-15s")
                    }
                    IconButton(
                        onClick = onTogglePlayPause,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                            .testTag("mini_player_play_pause_btn")
                    ) {
                        if (playbackState.isBuffering) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        } else {
                            Icon(
                                imageVector = if (playbackState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = AppLocalization.get("pod_play", language),
                                tint = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    }
                    IconButton(
                        onClick = { PodcastPlayerManager.skipForward() },
                        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                    ) {
                        Icon(Icons.Default.Forward10, contentDescription = "+15s")
                    }
                    IconButton(
                        onClick = onSkipNext,
                        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                    ) {
                        Icon(Icons.Default.SkipNext, contentDescription = AppLocalization.get("pod_next", language))
                    }
                }
                Text(
                    text = formatMillisDisplay(playbackState.durationMs),
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}
