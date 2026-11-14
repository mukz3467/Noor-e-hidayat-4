package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.model.AppLanguage
import com.example.data.model.BookmarkItem
import com.example.data.model.BookmarkType
import com.example.data.model.Dua
import com.example.data.repository.DuaAudioPlayer
import com.example.data.repository.DuaData
import com.example.data.repository.UserPreferencesRepository
import com.example.localization.AppLocalization
import com.example.ui.components.NoorTopBar
import com.example.ui.theme.QuranNaskhFontFamily
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DuasScreen(
    language: AppLanguage,
    onBack: () -> Unit,
    initialDuaId: String? = null
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val prefsRepo = remember(context) { UserPreferencesRepository(context) }

    val favoriteIds by prefsRepo.favoriteDuaIds.collectAsStateWithLifecycle()
    val recentViewedIds by prefsRepo.recentViewedDuaIds.collectAsStateWithLifecycle()
    val arabicFontSizePref by prefsRepo.arabicFontSize.collectAsStateWithLifecycle()

    var selectedCategory by remember { mutableStateOf("all") }
    var searchQuery by remember { mutableStateOf("") }
    var selectedDuaForDetails by remember { mutableStateOf<Dua?>(null) }
    var showFontSizeDialog by remember { mutableStateOf(false) }

    val currentlyPlayingId by DuaAudioPlayer.currentDuaId.collectAsStateWithLifecycle()
    val isPlayingAudio by DuaAudioPlayer.isPlaying.collectAsStateWithLifecycle()
    val isBufferingAudio by DuaAudioPlayer.isBuffering.collectAsStateWithLifecycle()
    val currentLoop by DuaAudioPlayer.currentLoop.collectAsStateWithLifecycle()
    val targetRepeat by DuaAudioPlayer.repeatCount.collectAsStateWithLifecycle()
    val audioError by DuaAudioPlayer.errorMessage.collectAsStateWithLifecycle()
    val isDownloadingPack by DuaAudioPlayer.isDownloadingPack.collectAsStateWithLifecycle()
    val offlinePackProgress by DuaAudioPlayer.offlinePackProgress.collectAsStateWithLifecycle()
    val cachedTrackCount by DuaAudioPlayer.cachedTrackCount.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        DuaAudioPlayer.init(context)
        prefsRepo.reloadBookmarksFromPrefs()
    }

    LaunchedEffect(initialDuaId) {
        if (!initialDuaId.isNullOrBlank()) {
            val found = DuaData.duas.firstOrNull { it.id == initialDuaId }
            if (found != null) {
                prefsRepo.recordViewedDua(found.id)
                selectedDuaForDetails = found
            }
        }
    }

    LaunchedEffect(audioError) {
        audioError?.let { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            DuaAudioPlayer.clearError()
        }
    }

    val dailyFeaturedDua = remember { DuaData.getDailyFeaturedDua() }

    val filteredDuas = remember(selectedCategory, searchQuery, favoriteIds, recentViewedIds) {
        DuaData.duas.filter { dua ->
            val matchCategory = when (selectedCategory) {
                "all" -> true
                "favorites" -> favoriteIds.contains(dua.id)
                "recent" -> recentViewedIds.contains(dua.id)
                else -> dua.categoryId == selectedCategory
            }

            val q = searchQuery.trim()
            val matchQuery = if (q.isBlank()) true else {
                dua.titleEn.contains(q, ignoreCase = true) ||
                    dua.titleUr.contains(q) ||
                    dua.titleAr.contains(q) ||
                    dua.titleHi.contains(q) ||
                    dua.arabic.contains(q) ||
                    dua.transliteration.contains(q, ignoreCase = true) ||
                    dua.translationEn.contains(q, ignoreCase = true) ||
                    dua.translationUr.contains(q) ||
                    dua.translationHi.contains(q, ignoreCase = true) ||
                    dua.narrator.contains(q, ignoreCase = true) ||
                    dua.occasionEn.contains(q, ignoreCase = true) ||
                    dua.occasionUr.contains(q) ||
                    dua.occasionHi.contains(q, ignoreCase = true) ||
                    dua.bookName.contains(q, ignoreCase = true) ||
                    dua.hadithOrAyahNumber.contains(q, ignoreCase = true) ||
                    dua.reference.contains(q, ignoreCase = true) ||
                    dua.grade.contains(q, ignoreCase = true)
            }

            matchCategory && matchQuery
        }
    }

    fun toggleFavoriteAndSyncBookmark(dua: Dua) {
        val added = prefsRepo.toggleFavoriteDua(dua.id)
        val currentlyBookmarked = prefsRepo.isDuaBookmarked(dua.id)
        if ((added && !currentlyBookmarked) || (!added && currentlyBookmarked)) {
            prefsRepo.toggleBookmark(
                BookmarkItem(
                    id = "bm_dua_${dua.id}",
                    type = BookmarkType.DUA,
                    title = dua.titleEn,
                    subtitle = dua.arabic.take(90),
                    duaId = dua.id
                )
            )
        }
        val msg = if (added) AppLocalization.get("dua_added_fav", language) else AppLocalization.get("dua_removed_fav", language)
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }

    Scaffold(
        topBar = {
            NoorTopBar(
                title = AppLocalization.get("duas", language),
                language = language,
                onBackClick = onBack,
                actions = {
                    IconButton(
                        onClick = { showFontSizeDialog = true },
                        modifier = Modifier.testTag("duas_font_size_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.FormatSize,
                            contentDescription = "Adjust Font Size",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            // Search Bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = {
                    Text(
                        text = AppLocalization.get("search_duas_hint", language),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Search",
                        tint = MaterialTheme.colorScheme.primary
                    )
                },
                trailingIcon = {
                    if (searchQuery.isNotBlank()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear Search")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .testTag("duas_search_input")
            )

            // 20 Category Filter Chips + All / Favorites / Recent
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                item {
                    FilterChip(
                        selected = selectedCategory == "all",
                        onClick = { selectedCategory = "all" },
                        leadingIcon = {
                            Icon(Icons.Default.MenuBook, contentDescription = null, modifier = Modifier.size(16.dp))
                        },
                        label = {
                            Text("${AppLocalization.get("all_duas", language)} (${DuaData.duas.size})")
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                            selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        modifier = Modifier.testTag("dua_chip_all")
                    )
                }

                item {
                    FilterChip(
                        selected = selectedCategory == "favorites",
                        onClick = { selectedCategory = "favorites" },
                        leadingIcon = {
                            Icon(
                                imageVector = if (selectedCategory == "favorites") Icons.Default.Favorite else Icons.Outlined.FavoriteBorder,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        label = {
                            Text("${AppLocalization.get("favorite_duas", language)} (${favoriteIds.size})")
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                            selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        modifier = Modifier.testTag("dua_chip_favorites")
                    )
                }

                item {
                    FilterChip(
                        selected = selectedCategory == "recent",
                        onClick = { selectedCategory = "recent" },
                        leadingIcon = {
                            Icon(Icons.Default.History, contentDescription = null, modifier = Modifier.size(16.dp))
                        },
                        label = {
                            Text(AppLocalization.get("recent_duas", language))
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                            selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        modifier = Modifier.testTag("dua_chip_recent")
                    )
                }

                // 20 Authentic Masnoon Dua Categories
                items(DuaData.categories, key = { it.id }) { cat ->
                    val catTitle = when (language) {
                        AppLanguage.URDU -> cat.titleUr
                        AppLanguage.ARABIC -> cat.titleAr
                        AppLanguage.HINDI -> cat.titleHi
                        else -> cat.titleEn
                    }
                    FilterChip(
                        selected = selectedCategory == cat.id,
                        onClick = { selectedCategory = cat.id },
                        label = { Text(catTitle) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        modifier = Modifier.testTag("dua_chip_${cat.id}")
                    )
                }
            }

            // Duas List
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                if (selectedCategory == "all" && searchQuery.isBlank()) {
                    item(key = "daily_featured_dua_banner") {
                        DailyFeaturedDuaCard(
                            dua = dailyFeaturedDua,
                            language = language,
                            arabicFontSize = arabicFontSizePref,
                            onOpenDetails = {
                                prefsRepo.recordViewedDua(dailyFeaturedDua.id)
                                selectedDuaForDetails = dailyFeaturedDua
                            },
                            isPlaying = currentlyPlayingId == dailyFeaturedDua.id && isPlayingAudio,
                            isBuffering = currentlyPlayingId == dailyFeaturedDua.id && isBufferingAudio,
                            onToggleAudio = {
                                prefsRepo.recordPlayedDua(dailyFeaturedDua.id)
                                DuaAudioPlayer.playDua(
                                    context = context,
                                    dua = dailyFeaturedDua,
                                    repeatTarget = 1
                                )
                            }
                        )
                    }

                if (filteredDuas.isEmpty()) {
                    item {
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 32.dp)
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.SearchOff,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(48.dp)
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = if (selectedCategory == "favorites") {
                                        AppLocalization.get("dua_no_favorites", language)
                                    } else {
                                        AppLocalization.get("dua_no_results", language)
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    textAlign = TextAlign.Center,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                } else {
                    items(filteredDuas, key = { it.id }) { dua ->
                        val isFav = favoriteIds.contains(dua.id)
                        val isThisPlaying = currentlyPlayingId == dua.id && isPlayingAudio
                        val isThisBuffering = currentlyPlayingId == dua.id && isBufferingAudio
                        val isOfflineReady = remember(dua.id, cachedTrackCount) {
                            DuaAudioPlayer.isDuaOfflineReady(context, dua)
                        }

                        DuaCard(
                            dua = dua,
                            language = language,
                            arabicFontSize = arabicFontSizePref,
                            isFavorite = isFav,
                            isPlaying = isThisPlaying,
                            isBuffering = isThisBuffering,
                            isOfflineAudioReady = isOfflineReady,
                            currentLoop = if (currentlyPlayingId == dua.id) currentLoop else 1,
                            targetRepeat = if (currentlyPlayingId == dua.id) targetRepeat else 1,
                            onToggleFavorite = { toggleFavoriteAndSyncBookmark(dua) },
                            onPlayAudio = { repeatCount ->
                                prefsRepo.recordPlayedDua(dua.id)
                                DuaAudioPlayer.playDua(
                                    context = context,
                                    dua = dua,
                                    repeatTarget = repeatCount
                                )
                            },
                            onPauseAudio = { DuaAudioPlayer.pause() },
                            onOpenDetails = {
                                prefsRepo.recordViewedDua(dua.id)
                                selectedDuaForDetails = dua
                            },
                            onOpenUrl = { url -> runCatching { uriHandler.openUri(url) } }
                        )
                    }
                }
            }
        }
    }

    selectedDuaForDetails?.let { detailedDua ->
        DuaDetailBottomSheet(
            dua = detailedDua,
            language = language,
            arabicFontSize = arabicFontSizePref,
            isFavorite = favoriteIds.contains(detailedDua.id),
            isPlaying = currentlyPlayingId == detailedDua.id && isPlayingAudio,
            isBuffering = currentlyPlayingId == detailedDua.id && isBufferingAudio,
            currentLoop = if (currentlyPlayingId == detailedDua.id) currentLoop else 1,
            targetRepeat = if (currentlyPlayingId == detailedDua.id) targetRepeat else 1,
            onDismiss = { selectedDuaForDetails = null },
            onToggleFavorite = { toggleFavoriteAndSyncBookmark(detailedDua) },
            onPlayAudio = { repeatTarget ->
                prefsRepo.recordPlayedDua(detailedDua.id)
                DuaAudioPlayer.playDua(
                    context = context,
                    dua = detailedDua,
                    repeatTarget = repeatTarget
                )
            },
            onPauseAudio = { DuaAudioPlayer.pause() },
            onOpenUrl = { url -> runCatching { uriHandler.openUri(url) } }
        )
    }

    if (showFontSizeDialog) {
        AlertDialog(
            onDismissRequest = { showFontSizeDialog = false },
            title = {
                Text(
                    text = AppLocalization.get("arabic_font_size", language),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column {
                    Text(
                        text = "بِسْمِ اللَّهِ الرَّحْمَٰنِ الرَّحِيمِ",
                        fontFamily = QuranNaskhFontFamily,
                        fontSize = arabicFontSizePref.sp,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp)
                    )

                    Row(
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        listOf(20f, 24f, 28f, 32f).forEach { size ->
                            val label = when (size) {
                                20f -> "Small"
                                24f -> "Medium"
                                28f -> "Large"
                                else -> "Extra"
                            }
                            FilterChip(
                                selected = arabicFontSizePref == size,
                                onClick = { prefsRepo.setArabicFontSize(size) },
                                label = { Text(label) }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showFontSizeDialog = false }) {
                    Text("OK")
                }
            }
        )
    }
}

@Composable
fun DailyFeaturedDuaCard(
    dua: Dua,
    language: AppLanguage,
    arabicFontSize: Float,
    onOpenDetails: () -> Unit,
    isPlaying: Boolean,
    isBuffering: Boolean,
    onToggleAudio: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
        ),
        border = CardDefaults.outlinedCardBorder().copy(
            brush = Brush.horizontalGradient(
                listOf(
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                    Color(0xFFE6A100)
                )
            )
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("daily_featured_dua_card")
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = Color(0xFFE6A100),
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = AppLocalization.get("daily_featured_dua", language),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                ) {
                    Text(
                        text = dua.grade,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = dua.arabic,
                fontFamily = QuranNaskhFontFamily,
                fontSize = (arabicFontSize - 2f).sp,
                lineHeight = (arabicFontSize + 12f).sp,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.End,
                style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Rtl),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            val transText = when (language) {
                AppLanguage.URDU -> dua.translationUr
                AppLanguage.HINDI -> dua.translationHi
                else -> dua.translationEn
            }
            Text(
                text = transText,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(14.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = dua.reference,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (dua.hasGenuineAudio) {
                        FilledTonalIconButton(
                            onClick = onToggleAudio,
                            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                        ) {
                            if (isBuffering) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(
                                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = "Play Recitation",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }

                    Button(
                        onClick = onOpenDetails,
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Text(AppLocalization.get("dua_recite_details", language), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

@Composable
fun DuaCard(
    dua: Dua,
    language: AppLanguage,
    arabicFontSize: Float,
    isFavorite: Boolean,
    isPlaying: Boolean,
    isBuffering: Boolean,
    isOfflineAudioReady: Boolean,
    currentLoop: Int,
    targetRepeat: Int,
    onToggleFavorite: () -> Unit,
    onPlayAudio: (Int) -> Unit,
    onPauseAudio: () -> Unit,
    onOpenDetails: () -> Unit,
    onOpenUrl: (String) -> Unit
) {
    val context = LocalContext.current
    var selectedRepeat by remember { mutableIntStateOf(dua.repeatCount.coerceAtLeast(1)) }
    var showAllLanguages by remember { mutableStateOf(false) }

    val title = when (language) {
        AppLanguage.URDU -> dua.titleUr
        AppLanguage.ARABIC -> dua.titleAr
        AppLanguage.HINDI -> dua.titleHi
        else -> dua.titleEn
    }

    val primaryTrans = when (language) {
        AppLanguage.URDU -> dua.translationUr
        AppLanguage.HINDI -> dua.translationHi
        else -> dua.translationEn
    }

    val occasionText = when (language) {
        AppLanguage.URDU -> dua.occasionUr
        AppLanguage.ARABIC -> dua.occasionAr
        AppLanguage.HINDI -> dua.occasionHi
        else -> dua.occasionEn
    }

    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("dua_card_${dua.id}")
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                    ) {
                        Text(
                            text = dua.grade,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }

                    if (dua.recommendedTime.isNotBlank()) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = Color(0xFFE6A100).copy(alpha = 0.12f)
                        ) {
                            Text(
                                text = dua.recommendedTime,
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFFB57E00),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }

                IconButton(
                    onClick = onToggleFavorite,
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                ) {
                    Icon(
                        imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Outlined.FavoriteBorder,
                        contentDescription = "Toggle Favorite",
                        tint = if (isFavorite) Color(0xFFE53935) else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    textDirection = if (language == AppLanguage.URDU || language == AppLanguage.ARABIC) TextDirection.Rtl else TextDirection.Ltr
                ),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth()
            )

            if (occasionText.isNotBlank()) {
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = occasionText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                modifier = Modifier.padding(vertical = 10.dp)
            )

            val appSettings = com.example.ui.theme.LocalAppSettings.current
            val resolvedArabicFamily = com.example.ui.theme.resolveArabicFontFamily(appSettings.arabicFontOption)
            val resolvedTransSize = appSettings.translationFontSize.coerceIn(12f, 22f)

            Text(
                text = dua.arabic,
                fontFamily = resolvedArabicFamily,
                fontSize = arabicFontSize.sp,
                lineHeight = (arabicFontSize + 14f).sp,
                textAlign = TextAlign.End,
                style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Rtl),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = dua.transliteration,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontStyle = FontStyle.Italic,
                    lineHeight = 18.sp
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
            )

            if (appSettings.showInlineTranslation) {
                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = primaryTrans,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = resolvedTransSize.sp,
                        lineHeight = (resolvedTransSize * 1.45f).sp,
                        textDirection = if (language == AppLanguage.URDU) TextDirection.Rtl else TextDirection.Ltr
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            AnimatedVisibility(visible = showAllLanguages, enter = fadeIn(), exit = fadeOut()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp)
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                            RoundedCornerShape(10.dp)
                        )
                        .padding(10.dp)
                ) {
                    Text(
                        text = "Urdu: ${dua.translationUr}",
                        style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Rtl),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "English: ${dua.translationEn}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Hindi: ${dua.translationHi}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (dua.narrator.isNotBlank()) {
                Text(
                    text = "${AppLocalization.get("hadith_narrator_label", language)}: ${dua.narrator}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = dua.reference,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .weight(1f)
                        .let { mod ->
                            if (dua.sourceUrl.isNotBlank()) {
                                mod.clickable { onOpenUrl(dua.sourceUrl) }
                            } else mod
                        }
                )

                Text(
                    text = AppLocalization.get(
                        if (showAllLanguages) "dua_hide_translations" else "dua_all_4_languages",
                        language
                    ),
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier
                        .clickable { showAllLanguages = !showAllLanguages }
                        .padding(6.dp)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Genuine Audio Bar OR Honest "Audio not available" Bar + Copy/Share/Details
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    if (dua.hasGenuineAudio) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            IconButton(
                                onClick = {
                                    if (isPlaying) onPauseAudio() else onPlayAudio(selectedRepeat)
                                },
                                modifier = Modifier
                                    .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                                    .testTag("dua_play_btn_${dua.id}")
                            ) {
                                if (isBuffering) {
                                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                                } else {
                                    Icon(
                                        imageVector = if (isPlaying) Icons.Default.PauseCircle else Icons.Default.PlayCircle,
                                        contentDescription = if (isPlaying) "Pause" else "Play Recitation",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(28.dp)
                                    )
                                }
                            }

                            Column {
                                if (isBuffering) {
                                    Text(
                                        text = AppLocalization.get("dua_buffering", language),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                } else if (isPlaying) {
                                    Text(
                                        text = String.format(
                                            AppLocalization.get("dua_reciting_loop", language),
                                            currentLoop,
                                            targetRepeat
                                        ),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.Bold
                                    )
                                } else {
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        listOf(1, 3, 7).forEach { times ->
                                            val isCur = selectedRepeat == times
                                            Box(
                                                contentAlignment = Alignment.Center,
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(6.dp))
                                                    .background(if (isCur) MaterialTheme.colorScheme.primary else Color.Transparent)
                                                    .border(
                                                        width = 1.dp,
                                                        color = if (isCur) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                                        shape = RoundedCornerShape(6.dp)
                                                    )
                                                    .clickable { selectedRepeat = times }
                                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                                            ) {
                                                Text(
                                                    text = "${times}x",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = if (isCur) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    }
                                    if (isOfflineAudioReady && dua.audioReciter.isNotBlank()) {
                                        Text(
                                            text = "${dua.audioReciter} • Offline",
                                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 6.dp, vertical = 4.dp)
                                .testTag("dua_audio_unavailable_${dua.id}")
                        ) {
                            Icon(
                                imageVector = Icons.Default.VolumeOff,
                                contentDescription = AppLocalization.get("dua_audio_not_available", language),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = AppLocalization.get("dua_audio_not_available", language),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2
                            )
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = ClipData.newPlainText(
                                    "Islamic Dua",
                                    "${dua.arabic}\n\n${dua.transliteration}\n\n$primaryTrans\n\n[${dua.reference} - Noor-e-Hidayat]"
                                )
                                clipboard.setPrimaryClip(clip)
                                Toast.makeText(context, AppLocalization.get("dua_copied", language), Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.sizeIn(minWidth = 42.dp, minHeight = 42.dp)
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy Dua", modifier = Modifier.size(18.dp))
                        }

                        IconButton(
                            onClick = {
                                val shareText = buildString {
                                    append("${dua.titleEn} / ${dua.titleUr}\n\n")
                                    append("${dua.arabic}\n\n")
                                    append("Transliteration:\n${dua.transliteration}\n\n")
                                    append("Translation:\n$primaryTrans\n\n")
                                    append("Reference: ${dua.reference} (${dua.grade})\n")
                                    append("Shared via Noor-e-Hidayat")
                                }
                                val intent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_SUBJECT, dua.titleEn)
                                    putExtra(Intent.EXTRA_TEXT, shareText)
                                }
                                context.startActivity(Intent.createChooser(intent, AppLocalization.get("share_dua", language)))
                            },
                            modifier = Modifier.sizeIn(minWidth = 42.dp, minHeight = 42.dp)
                        ) {
                            Icon(Icons.Default.Share, contentDescription = "Share Dua", modifier = Modifier.size(18.dp))
                        }

                        IconButton(
                            onClick = onOpenDetails,
                            modifier = Modifier.sizeIn(minWidth = 42.dp, minHeight = 42.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = "Dua Details",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DuaDetailBottomSheet(
    dua: Dua,
    language: AppLanguage,
    arabicFontSize: Float,
    isFavorite: Boolean,
    isPlaying: Boolean,
    isBuffering: Boolean,
    currentLoop: Int,
    targetRepeat: Int,
    onDismiss: () -> Unit,
    onToggleFavorite: () -> Unit,
    onPlayAudio: (Int) -> Unit,
    onPauseAudio: () -> Unit,
    onOpenUrl: (String) -> Unit
) {
    val context = LocalContext.current
    var sheetRepeatTarget by remember { mutableIntStateOf(dua.repeatCount.coerceAtLeast(1)) }

    val localizedMethod = when (language) {
        AppLanguage.URDU -> dua.methodUr
        AppLanguage.ARABIC -> dua.methodAr
        AppLanguage.HINDI -> dua.methodHi
        else -> dua.methodEn
    }
    val localizedOccasion = when (language) {
        AppLanguage.URDU -> dua.occasionUr
        AppLanguage.ARABIC -> dua.occasionAr
        AppLanguage.HINDI -> dua.occasionHi
        else -> dua.occasionEn
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 32.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                    ) {
                        Text(
                            text = dua.grade,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFFE6A100).copy(alpha = 0.15f)
                    ) {
                        Text(
                            text = dua.hadithOrAyahNumber.ifBlank { dua.reference },
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFFB57E00),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }

                IconButton(onClick = onToggleFavorite) {
                    Icon(
                        imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Outlined.FavoriteBorder,
                        contentDescription = "Favorite",
                        tint = if (isFavorite) Color(0xFFE53935) else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = dua.titleUr,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Bold,
                    textDirection = TextDirection.Rtl
                ),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth()
            )

            Text(
                text = dua.titleEn,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface
            )

            if (localizedOccasion.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = localizedOccasion,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = dua.arabic,
                    fontFamily = QuranNaskhFontFamily,
                    fontSize = (arabicFontSize + 4f).sp,
                    lineHeight = (arabicFontSize + 18f).sp,
                    textAlign = TextAlign.End,
                    style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Rtl),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(18.dp)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "Transliteration:",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = dua.transliteration,
                style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = AppLocalization.get("dua_all_4_languages", language),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(6.dp))

            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "اردو ترجمہ:",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.End
                    )
                    Text(
                        text = dua.translationUr,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            textDirection = TextDirection.Rtl,
                            lineHeight = 22.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "English Translation:",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = dua.translationEn,
                        style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 20.sp),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "हिन्दी अनुवाद:",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = dua.translationHi,
                        style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 20.sp),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            if (localizedMethod.isNotBlank()) {
                Spacer(modifier = Modifier.height(12.dp))
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = AppLocalization.get("dua_method_label", language),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = localizedMethod,
                            style = MaterialTheme.typography.bodySmall.copy(
                                textDirection = if (language == AppLanguage.URDU || language == AppLanguage.ARABIC) TextDirection.Rtl else TextDirection.Ltr
                            ),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            if (dua.virtuesBenefitsEn.isNotBlank() || dua.virtuesBenefitsUr.isNotBlank()) {
                Spacer(modifier = Modifier.height(12.dp))
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = Color(0xFFE6A100).copy(alpha = 0.1f),
                    border = CardDefaults.outlinedCardBorder().copy(
                        brush = Brush.horizontalGradient(listOf(Color(0xFFE6A100), Color(0xFFFFA000)))
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Verified, contentDescription = null, tint = Color(0xFFC88A00), modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = AppLocalization.get("virtues_benefits", language),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF8D6200)
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        if (dua.virtuesBenefitsUr.isNotBlank()) {
                            Text(
                                text = dua.virtuesBenefitsUr,
                                style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Rtl),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                        }
                        if (dua.virtuesBenefitsEn.isNotBlank()) {
                            Text(
                                text = dua.virtuesBenefitsEn,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }

            // Attribution, Narrator & Direct Verified URL
            Spacer(modifier = Modifier.height(12.dp))
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    if (dua.narrator.isNotBlank()) {
                        Text(
                            text = "${AppLocalization.get("hadith_narrator_label", language)}: ${dua.narrator}",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold)
                        )
                    }
                    Text(
                        text = "${dua.bookName} • ${dua.reference} (${dua.grade})",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (dua.sourceUrl.isNotBlank()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        OutlinedButton(onClick = { onOpenUrl(dua.sourceUrl) }) {
                            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(AppLocalization.get("hadith_open_source_link", language))
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Genuine Audio Player Controls OR Honest "Audio not available" Box
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    if (dua.hasGenuineAudio) {
                        Text(
                            text = "${AppLocalization.get("dua_genuine_audio_badge", language)} • ${dua.audioReciter}",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        if (dua.audioSourceName.isNotBlank()) {
                            Text(
                                text = "${dua.audioSourceName} (${dua.audioLicense})",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Button(
                                onClick = {
                                    if (isPlaying) onPauseAudio() else onPlayAudio(sheetRepeatTarget)
                                },
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                if (isBuffering) {
                                    CircularProgressIndicator(
                                        color = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Icon(
                                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                        contentDescription = null
                                    )
                                }
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    when {
                                        isBuffering -> AppLocalization.get("dua_buffering", language)
                                        isPlaying -> "Pause"
                                        else -> "Play Recitation"
                                    }
                                )
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf(1, 3, 7, 10).forEach { r ->
                                    FilterChip(
                                        selected = sheetRepeatTarget == r,
                                        onClick = { sheetRepeatTarget = r },
                                        label = { Text("${r}x") }
                                    )
                                }
                            }
                        }

                        if (isPlaying) {
                            Spacer(modifier = Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = { currentLoop.toFloat() / targetRepeat.toFloat().coerceAtLeast(1f) },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(
                                text = String.format(
                                    AppLocalization.get("dua_reciting_loop", language),
                                    currentLoop,
                                    targetRepeat
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.VolumeOff,
                                contentDescription = AppLocalization.get("dua_audio_not_available", language),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = AppLocalization.get("dua_audio_not_available", language),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText(
                            "Dua Details",
                            "${dua.titleEn}\n\n${dua.arabic}\n\n${dua.transliteration}\n\nUrdu: ${dua.translationUr}\n\nEnglish: ${dua.translationEn}\n\nReference: ${dua.reference} - Noor-e-Hidayat"
                        )
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(context, AppLocalization.get("dua_copied", language), Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(AppLocalization.get("copy_dua", language))
                }

                Button(
                    onClick = {
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, dua.titleEn)
                            putExtra(
                                Intent.EXTRA_TEXT,
                                "${dua.arabic}\n\n${dua.translationUr}\n\n${dua.translationEn}\n\n[${dua.reference} (${dua.grade}) - Noor-e-Hidayat]"
                            )
                        }
                        context.startActivity(Intent.createChooser(intent, AppLocalization.get("share_dua", language)))
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Share, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(AppLocalization.get("share_dua", language))
                }
            }
        }
    }
}
