package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.model.AppLanguage
import com.example.data.model.BookmarkItem
import com.example.data.model.BookmarkType
import com.example.data.model.Hadith
import com.example.data.repository.HadithBookInfo
import com.example.data.repository.HadithCatalog
import com.example.data.repository.HadithCollectionProgress
import com.example.data.repository.HadithData
import com.example.data.repository.HadithEditionManifest
import com.example.data.repository.HadithRepository
import com.example.data.repository.UserPreferencesRepository
import com.example.localization.AppLocalization
import com.example.ui.components.NoorTopBar
import com.example.ui.theme.QuranNaskhFontFamily
import kotlinx.coroutines.launch

@Composable
fun HadithScreen(
    language: AppLanguage,
    onBack: () -> Unit,
    initialHadithId: String? = null
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()

    val hadithRepo = remember(context) { HadithRepository.getInstance(context) }
    val prefsRepo = remember(context) { UserPreferencesRepository(context) }

    val downloadedBookKeys by hadithRepo.downloadedBookKeys.collectAsStateWithLifecycle()
    val bookByteSizes by hadithRepo.bookByteSizes.collectAsStateWithLifecycle()
    val downloadedBookCounts by hadithRepo.downloadedBookCounts.collectAsStateWithLifecycle()
    val activeDownloadKey by hadithRepo.activeDownloadKey.collectAsStateWithLifecycle()
    val downloadStatusMessage by hadithRepo.downloadStatusMessage.collectAsStateWithLifecycle()
    val batchProgress by hadithRepo.batchProgress.collectAsStateWithLifecycle()
    val lastReadHadith by prefsRepo.lastReadHadith.collectAsStateWithLifecycle()
    val bookmarks by prefsRepo.bookmarks.collectAsStateWithLifecycle()

    var selectedCollection by remember { mutableStateOf("All") }
    var selectedBukhariVolume by remember { mutableIntStateOf(0) } // 0 = All 7 volumes
    var searchQuery by remember { mutableStateOf("") }
    var selectedBook by remember { mutableStateOf<HadithBookInfo?>(null) }
    var highlightHadithId by remember { mutableStateOf<String?>(initialHadithId) }
    var manifestDialogCollection by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        hadithRepo.refreshDownloadIndex()
        prefsRepo.reloadBookmarksFromPrefs()
    }

    // If navigated from a Hadith Bookmark, open its book automatically when possible
    LaunchedEffect(initialHadithId) {
        if (!initialHadithId.isNullOrBlank()) {
            val match = HadithData.hadiths.firstOrNull { it.id == initialHadithId }
            if (match != null) {
                val book = HadithCatalog.booksFor(match.collection)
                    .firstOrNull { it.bookNumber == match.bookNumber }
                if (book != null) {
                    selectedCollection = match.collection
                    selectedBook = book
                    highlightHadithId = match.id
                }
            }
        }
    }

    // Sub-screen back handling to return to Books Browser before leaving HadithScreen
    BackHandler(enabled = selectedBook != null) {
        selectedBook = null
        highlightHadithId = null
    }

    val bukhariProgress = remember(downloadedBookKeys, bookByteSizes, downloadedBookCounts) {
        hadithRepo.getCollectionProgress(HadithCatalog.COLLECTION_BUKHARI)
    }
    val muslimProgress = remember(downloadedBookKeys, bookByteSizes, downloadedBookCounts) {
        hadithRepo.getCollectionProgress(HadithCatalog.COLLECTION_MUSLIM)
    }

    val searchResults = remember(searchQuery, selectedCollection, downloadedBookKeys) {
        if (searchQuery.isBlank()) emptyList()
        else hadithRepo.searchAvailableHadiths(selectedCollection, searchQuery)
    }

    val bookmarkedIds = remember(bookmarks) {
        bookmarks.mapNotNull { it.hadithId }.toSet()
    }

    val isArabicOrUrdu = language == AppLanguage.URDU || language == AppLanguage.ARABIC

    Scaffold(
        topBar = {
            NoorTopBar(
                title = selectedBook?.let { "Book ${it.bookNumber}: ${it.localizedTitle(language)}" }
                    ?: AppLocalization.get("hadith", language),
                language = language,
                onBackClick = {
                    if (selectedBook != null) {
                        selectedBook = null
                        highlightHadithId = null
                    } else {
                        onBack()
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            manifestDialogCollection = selectedBook?.collection
                                ?: if (selectedCollection == HadithCatalog.COLLECTION_MUSLIM) {
                                    HadithCatalog.COLLECTION_MUSLIM
                                } else {
                                    HadithCatalog.COLLECTION_BUKHARI
                                }
                        },
                        modifier = Modifier.testTag("hadith_manifest_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = AppLocalization.get("hadith_manifest_btn", language),
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
            val activeBook = selectedBook
            if (activeBook != null) {
                HadithBookReaderView(
                    book = activeBook,
                    language = language,
                    hadithRepo = hadithRepo,
                    prefsRepo = prefsRepo,
                    bookmarkedIds = bookmarkedIds,
                    highlightHadithId = highlightHadithId,
                    isDownloading = activeDownloadKey == hadithRepo.keyFor(activeBook.collection, activeBook.bookNumber),
                    isDownloaded = downloadedBookKeys.contains(hadithRepo.keyFor(activeBook.collection, activeBook.bookNumber)),
                    byteSize = bookByteSizes[hadithRepo.keyFor(activeBook.collection, activeBook.bookNumber)] ?: 0L,
                    onBackToBooks = {
                        selectedBook = null
                        highlightHadithId = null
                    },
                    onDownloadBook = {
                        scope.launch {
                            val res = hadithRepo.downloadBook(activeBook.collection, activeBook)
                            res.onSuccess { count ->
                                Toast.makeText(
                                    context,
                                    "Downloaded Book ${activeBook.bookNumber} ($count narrations offline)",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }.onFailure { err ->
                                Toast.makeText(
                                    context,
                                    "Download failed: ${err.message ?: "network error"}",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    },
                    onDeleteBook = {
                        hadithRepo.deleteDownloadedBook(activeBook.collection, activeBook.bookNumber)
                        Toast.makeText(context, "Removed Book ${activeBook.bookNumber} offline cache", Toast.LENGTH_SHORT).show()
                    },
                    onOpenUrl = { url -> runCatching { uriHandler.openUri(url) } },
                    onShowManifest = { manifestDialogCollection = activeBook.collection }
                )
            } else {
                // Search Bar
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = {
                        Text(
                            text = AppLocalization.get("hadith_search_hint", language),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (searchQuery.isNotBlank()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear")
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
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .testTag("hadith_search_input")
                )

                // Independent Collection Switcher
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    item {
                        FilterChip(
                            selected = selectedCollection == "All",
                            onClick = { selectedCollection = "All" },
                            label = { Text("All (97 + 57)") },
                            modifier = Modifier.testTag("hadith_filter_all")
                        )
                    }
                    item {
                        FilterChip(
                            selected = selectedCollection == HadithCatalog.COLLECTION_BUKHARI,
                            onClick = { selectedCollection = HadithCatalog.COLLECTION_BUKHARI },
                            label = { Text("${AppLocalization.get("bukhari", language)} (97)") },
                            modifier = Modifier.testTag("hadith_filter_bukhari")
                        )
                    }
                    item {
                        FilterChip(
                            selected = selectedCollection == HadithCatalog.COLLECTION_MUSLIM,
                            onClick = { selectedCollection = HadithCatalog.COLLECTION_MUSLIM },
                            label = { Text("${AppLocalization.get("muslim", language)} (57)") },
                            modifier = Modifier.testTag("hadith_filter_muslim")
                        )
                    }
                }

                // Active Batch Download Bar
                if (activeDownloadKey != null || batchProgress > 0f) {
                    LinearProgressIndicator(
                        progress = { if (batchProgress > 0f) batchProgress else 0.5f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                    downloadStatusMessage?.let { status ->
                        Text(
                            text = "Downloading: $status",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                    }
                }

                if (searchQuery.isNotBlank()) {
                    LazyColumn(
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        item {
                            Text(
                                text = "Found ${searchResults.size} matching narrations in offline & downloaded books",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        if (searchResults.isEmpty()) {
                            item {
                                Card(
                                    shape = RoundedCornerShape(14.dp),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = "No matching narrations found in currently cached/curated books. Download specific Books to search all narrations offline.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(16.dp)
                                    )
                                }
                            }
                        } else {
                            items(searchResults, key = { it.id }) { hadith ->
                                HadithCard(
                                    hadith = hadith,
                                    language = language,
                                    isBookmarked = bookmarkedIds.contains(hadith.id),
                                    onToggleBookmark = {
                                        val added = prefsRepo.toggleBookmark(
                                            BookmarkItem(
                                                id = "bm_hadith_${hadith.id}",
                                                type = BookmarkType.HADITH,
                                                title = "${hadith.collection} #${hadith.hadithNumber}",
                                                subtitle = hadith.textArabic.take(90),
                                                hadithId = hadith.id
                                            )
                                        )
                                        Toast.makeText(
                                            context,
                                            AppLocalization.get(if (added) "bookmark_saved" else "bookmark_removed", language),
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    },
                                    onOpenUrl = { url -> runCatching { uriHandler.openUri(url) } },
                                    onInteract = {
                                        prefsRepo.saveLastReadHadith(
                                            collection = hadith.collection,
                                            bookNumber = hadith.bookNumber,
                                            hadithId = hadith.id,
                                            hadithNumber = hadith.hadithNumber,
                                            bookTitle = hadith.bookName
                                        )
                                    }
                                )
                            }
                        }
                    }
                } else {
                    // Canonical Browser: Progress, Continue Reading, 7-Volume Index, 97 Books + 57 Muslim Sections
                    val bukhariBooks = remember(selectedBukhariVolume) {
                        if (selectedBukhariVolume == 0) HadithCatalog.bukhari97Books
                        else HadithCatalog.booksInBukhariVolume(selectedBukhariVolume)
                    }
                    val muslimBooks = remember { HadithCatalog.muslim57Books }

                    LazyColumn(
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("hadith_books_list")
                    ) {
                        // Continue Reading Card
                        lastReadHadith?.let { last ->
                            item(key = "continue_reading_card") {
                                Card(
                                    onClick = {
                                        val targetBook = HadithCatalog.booksFor(last.collection)
                                            .firstOrNull { it.bookNumber == last.bookNumber }
                                        if (targetBook != null) {
                                            highlightHadithId = last.hadithId
                                            selectedBook = targetBook
                                        }
                                    },
                                    shape = RoundedCornerShape(16.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f)
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("hadith_continue_reading")
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(14.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.AutoStories,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = AppLocalization.get("hadith_continue_reading", language),
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                            Text(
                                                text = "${last.collection} • #${last.hadithNumber}",
                                                style = MaterialTheme.typography.titleSmall,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Text(
                                                text = last.bookTitle,
                                                style = MaterialTheme.typography.bodySmall,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        Icon(Icons.Default.ChevronRight, contentDescription = null)
                                    }
                                }
                            }
                        }

                        // Real Collection Progress Summary
                        if (selectedCollection != HadithCatalog.COLLECTION_MUSLIM) {
                            item(key = "progress_bukhari") {
                                CollectionProgressCard(
                                    progress = bukhariProgress,
                                    language = language,
                                    onOpenManifest = { manifestDialogCollection = HadithCatalog.COLLECTION_BUKHARI }
                                )
                            }
                        }
                        if (selectedCollection != HadithCatalog.COLLECTION_BUKHARI) {
                            item(key = "progress_muslim") {
                                CollectionProgressCard(
                                    progress = muslimProgress,
                                    language = language,
                                    onOpenManifest = { manifestDialogCollection = HadithCatalog.COLLECTION_MUSLIM }
                                )
                            }
                        }

                        // SAHIH AL-BUKHARI SECTION (97 Books across 7 Volumes)
                        if (selectedCollection == "All" || selectedCollection == HadithCatalog.COLLECTION_BUKHARI) {
                            item(key = "bukhari_section_header") {
                                Column {
                                    Text(
                                        text = "${AppLocalization.get("bukhari", language)} — 97 Books (7 Volumes)",
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    LazyRow(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        item {
                                            FilterChip(
                                                selected = selectedBukhariVolume == 0,
                                                onClick = { selectedBukhariVolume = 0 },
                                                label = { Text(AppLocalization.get("hadith_all_volumes", language)) },
                                                modifier = Modifier.testTag("bukhari_vol_chip_0")
                                            )
                                        }
                                        items(HadithCatalog.bukhari7Volumes, key = { it.volumeNumber }) { vol ->
                                            FilterChip(
                                                selected = selectedBukhariVolume == vol.volumeNumber,
                                                onClick = { selectedBukhariVolume = vol.volumeNumber },
                                                label = { Text("Vol ${vol.volumeNumber} (B${vol.bookStart}-${vol.bookEnd})") },
                                                modifier = Modifier.testTag("bukhari_vol_chip_${vol.volumeNumber}")
                                            )
                                        }
                                    }

                                    // Volume Details + Download Whole Volume Button when a volume is chosen
                                    if (selectedBukhariVolume in 1..7) {
                                        val volInfo = HadithCatalog.bukhari7Volumes.first { it.volumeNumber == selectedBukhariVolume }
                                        val volBooks = HadithCatalog.booksInBukhariVolume(selectedBukhariVolume)
                                        val downloadedInVol = volBooks.count {
                                            downloadedBookKeys.contains(hadithRepo.keyFor(HadithCatalog.COLLECTION_BUKHARI, it.bookNumber))
                                        }
                                        Card(
                                            shape = RoundedCornerShape(14.dp),
                                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(top = 8.dp)
                                        ) {
                                            Column(modifier = Modifier.padding(12.dp)) {
                                                Text(
                                                    text = volInfo.localizedTitle(language),
                                                    style = MaterialTheme.typography.titleSmall.copy(
                                                        fontWeight = FontWeight.Bold,
                                                        textDirection = if (isArabicOrUrdu) TextDirection.Rtl else TextDirection.Ltr
                                                    ),
                                                    color = MaterialTheme.colorScheme.onSurface
                                                )
                                                Text(
                                                    text = "${volInfo.editionReference} • Hadith ${volInfo.hadithRange} • Offline: $downloadedInVol/${volBooks.size} books",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                                if (downloadedInVol < volBooks.size) {
                                                    Spacer(modifier = Modifier.height(8.dp))
                                                    OutlinedButton(
                                                        enabled = activeDownloadKey == null,
                                                        onClick = {
                                                            scope.launch {
                                                                val res = hadithRepo.downloadBukhariVolume(selectedBukhariVolume)
                                                                res.onSuccess { count ->
                                                                    Toast.makeText(
                                                                        context,
                                                                        "Volume $selectedBukhariVolume ready offline ($count narrations)",
                                                                        Toast.LENGTH_SHORT
                                                                    ).show()
                                                                }.onFailure { err ->
                                                                    Toast.makeText(
                                                                        context,
                                                                        "Volume download stopped: ${err.message}",
                                                                        Toast.LENGTH_LONG
                                                                    ).show()
                                                                }
                                                            }
                                                        }
                                                    ) {
                                                        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                                                        Spacer(modifier = Modifier.width(6.dp))
                                                        Text(AppLocalization.get("hadith_download_volume", language))
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            items(bukhariBooks, key = { "bukhari_${it.bookNumber}" }) { book ->
                                val key = hadithRepo.keyFor(book.collection, book.bookNumber)
                                val isDl = downloadedBookKeys.contains(key)
                                val isLoading = activeDownloadKey == key
                                val offlineCount = if (isDl) {
                                    downloadedBookCounts[key] ?: book.expectedNarrationCount
                                } else {
                                    HadithData.hadiths.count { it.collection == book.collection && it.bookNumber == book.bookNumber }
                                }
                                HadithBookRowCard(
                                    book = book,
                                    language = language,
                                    isDownloaded = isDl,
                                    isDownloading = isLoading,
                                    offlineCount = offlineCount,
                                    byteSize = bookByteSizes[key] ?: 0L,
                                    onOpenBook = { selectedBook = book },
                                    onDownloadClick = {
                                        scope.launch {
                                            hadithRepo.downloadBook(book.collection, book).onFailure {
                                                Toast.makeText(context, "Download failed: ${it.message}", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }
                                )
                            }
                        }

                        // SAHIH MUSLIM SECTION (57 Independent Sections)
                        if (selectedCollection == "All" || selectedCollection == HadithCatalog.COLLECTION_MUSLIM) {
                            item(key = "muslim_section_header") {
                                Column(modifier = Modifier.padding(top = 8.dp)) {
                                    Text(
                                        text = "${AppLocalization.get("muslim", language)} — 57 Independent Sections",
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        text = AppLocalization.get("hadith_independent_muslim_note", language),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            items(muslimBooks, key = { "muslim_${it.bookNumber}" }) { book ->
                                val key = hadithRepo.keyFor(book.collection, book.bookNumber)
                                val isDl = downloadedBookKeys.contains(key)
                                val isLoading = activeDownloadKey == key
                                val offlineCount = if (isDl) {
                                    downloadedBookCounts[key] ?: book.expectedNarrationCount
                                } else {
                                    HadithData.hadiths.count { it.collection == book.collection && it.bookNumber == book.bookNumber }
                                }
                                HadithBookRowCard(
                                    book = book,
                                    language = language,
                                    isDownloaded = isDl,
                                    isDownloading = isLoading,
                                    offlineCount = offlineCount,
                                    byteSize = bookByteSizes[key] ?: 0L,
                                    onOpenBook = { selectedBook = book },
                                    onDownloadClick = {
                                        scope.launch {
                                            hadithRepo.downloadBook(book.collection, book).onFailure {
                                                Toast.makeText(context, "Download failed: ${it.message}", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    manifestDialogCollection?.let { col ->
        val manifest = remember(col) { HadithCatalog.getManifest(col) }
        EditionManifestDialog(
            manifest = manifest,
            onDismiss = { manifestDialogCollection = null },
            onOpenUrl = { url -> runCatching { uriHandler.openUri(url) } }
        )
    }
}

@Composable
private fun CollectionProgressCard(
    progress: HadithCollectionProgress,
    language: AppLanguage,
    onOpenManifest: () -> Unit
) {
    val sizeText = if (progress.totalDownloadedBytes > 0L) {
        String.format("%.2f MB", progress.totalDownloadedBytes / (1024.0 * 1024.0))
    } else "Bundled Core"

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("hadith_progress_${progress.collection}")
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = progress.collection,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "${AppLocalization.get("hadith_collection_progress", language)}: ${progress.downloadedFullBooksCount}/${progress.totalBooks} Books Full Offline • $sizeText",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Offline narrations loaded: ${progress.totalNarrationsAvailableOffline} / ${progress.canonicalTotalNarrations} canonical",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = onOpenManifest) {
                    Text(AppLocalization.get("hadith_manifest_btn", language))
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = {
                    (progress.totalNarrationsAvailableOffline.toFloat() /
                        progress.canonicalTotalNarrations.toFloat().coerceAtLeast(1f)).coerceIn(0f, 1f)
                },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun HadithBookRowCard(
    book: HadithBookInfo,
    language: AppLanguage,
    isDownloaded: Boolean,
    isDownloading: Boolean,
    offlineCount: Int,
    byteSize: Long,
    onOpenBook: () -> Unit,
    onDownloadClick: () -> Unit
) {
    val isRtl = language == AppLanguage.URDU || language == AppLanguage.ARABIC
    val tagPrefix = if (book.collection == HadithCatalog.COLLECTION_MUSLIM) "muslim" else "bukhari"

    Card(
        onClick = onOpenBook,
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("hadith_book_card_${tagPrefix}_${book.bookNumber}")
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(12.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                modifier = Modifier.size(44.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = "${book.bookNumber}",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = book.localizedTitle(language),
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.Bold,
                        textDirection = if (isRtl) TextDirection.Rtl else TextDirection.Ltr
                    ),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "${book.titleAr} • Hadith ${book.hadithRange} (~${book.expectedNarrationCount})",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val statusLabel = if (isDownloaded) {
                    val kb = (byteSize / 1024L).coerceAtLeast(1L)
                    "${AppLocalization.get("hadith_downloaded_badge", language)} ($offlineCount • ${kb} KB)"
                } else if (offlineCount > 0) {
                    "$offlineCount curated offline • Full: ${book.expectedNarrationCount}"
                } else {
                    "Tap to download (${book.expectedNarrationCount} narrations)"
                }
                Text(
                    text = statusLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isDownloaded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
                )
            }

            if (isDownloading) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.5.dp)
            } else {
                IconButton(
                    onClick = onDownloadClick,
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                ) {
                    Icon(
                        imageVector = if (isDownloaded) Icons.Default.FileDownloadDone else Icons.Default.CloudDownload,
                        contentDescription = if (isDownloaded) AppLocalization.get("hadith_downloaded_badge", language)
                        else AppLocalization.get("hadith_download_book", language),
                        tint = if (isDownloaded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun HadithBookReaderView(
    book: HadithBookInfo,
    language: AppLanguage,
    hadithRepo: HadithRepository,
    prefsRepo: UserPreferencesRepository,
    bookmarkedIds: Set<String>,
    highlightHadithId: String?,
    isDownloading: Boolean,
    isDownloaded: Boolean,
    byteSize: Long,
    onBackToBooks: () -> Unit,
    onDownloadBook: () -> Unit,
    onDeleteBook: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onShowManifest: () -> Unit
) {
    val context = LocalContext.current
    val listState = rememberLazyListState()

    val hadiths = remember(book, isDownloaded) {
        hadithRepo.getHadithsForBook(book.collection, book.bookNumber)
    }

    LaunchedEffect(book.bookNumber, isDownloaded, highlightHadithId) {
        val target = if (!highlightHadithId.isNullOrBlank()) {
            hadiths.firstOrNull { it.id == highlightHadithId }
        } else null

        val firstOrTarget = target ?: hadiths.firstOrNull()
        if (firstOrTarget != null) {
            prefsRepo.saveLastReadHadith(
                collection = book.collection,
                bookNumber = book.bookNumber,
                hadithId = firstOrTarget.id,
                hadithNumber = firstOrTarget.hadithNumber,
                bookTitle = book.titleEn
            )
        }
        if (target != null) {
            val idx = hadiths.indexOf(target)
            if (idx >= 0) {
                listState.scrollToItem(idx + 1)
            }
        }
    }

    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxSize()
            .testTag("hadith_reader_list")
    ) {
        item(key = "book_reader_header") {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        TextButton(onClick = onBackToBooks) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("All Books")
                        }
                        TextButton(onClick = onShowManifest) {
                            Text(AppLocalization.get("hadith_manifest_btn", language))
                        }
                    }

                    Text(
                        text = "${book.collection} • Book ${book.bookNumber}",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "${book.localizedTitle(language)} — ${book.titleAr}",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    if (book.collection == HadithCatalog.COLLECTION_BUKHARI) {
                        Text(
                            text = "Volume ${book.volumeNumber} of 7 • Canonical Hadith ${book.hadithRange}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text(
                            text = "Independent Sahih Muslim Section • Hadith ${book.hadithRange}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (isDownloading) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Downloading Arabic, English & Urdu...", style = MaterialTheme.typography.labelSmall)
                        } else if (isDownloaded) {
                            val kb = (byteSize / 1024L).coerceAtLeast(1L)
                            OutlinedButton(
                                onClick = onDeleteBook,
                                modifier = Modifier.testTag("hadith_book_delete_btn")
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("${AppLocalization.get("hadith_delete_offline", language)} ($kb KB)")
                            }
                        } else {
                            Button(
                                onClick = onDownloadBook,
                                modifier = Modifier.testTag("hadith_book_download_btn")
                            ) {
                                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(AppLocalization.get("hadith_download_book", language))
                            }
                        }

                        OutlinedButton(onClick = { onOpenUrl(book.sourceUrl) }) {
                            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Sunnah.com")
                        }
                    }

                    if (!isDownloaded) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = AppLocalization.get("hadith_curated_only_badge", language),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }

                    if (language == AppLanguage.HINDI && hadiths.any { !it.isHindiAvailable }) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.6f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = AppLocalization.get("hadith_hindi_unavailable_notice", language),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    }
                }
            }
        }

        if (hadiths.isEmpty()) {
            item(key = "empty_book_prompt") {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CloudDownload,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(44.dp)
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "Download Book ${book.bookNumber} (${book.expectedNarrationCount} narrations)",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "This canonical book is fetched directly on demand from the open-licensed fawazahmed0/hadith-api CDN (Arabic, English & Urdu) for permanent offline reading.",
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        Button(onClick = onDownloadBook, enabled = !isDownloading) {
                            Text(AppLocalization.get("hadith_download_book", language))
                        }
                    }
                }
            }
        } else {
            items(hadiths, key = { it.id }) { hadith ->
                HadithCard(
                    hadith = hadith,
                    language = language,
                    isBookmarked = bookmarkedIds.contains(hadith.id),
                    isHighlighted = hadith.id == highlightHadithId,
                    onToggleBookmark = {
                        val added = prefsRepo.toggleBookmark(
                            BookmarkItem(
                                id = "bm_hadith_${hadith.id}",
                                type = BookmarkType.HADITH,
                                title = "${hadith.collection} #${hadith.hadithNumber}",
                                subtitle = hadith.textArabic.take(90),
                                hadithId = hadith.id
                            )
                        )
                        Toast.makeText(
                            context,
                            AppLocalization.get(if (added) "bookmark_saved" else "bookmark_removed", language),
                            Toast.LENGTH_SHORT
                        ).show()
                    },
                    onOpenUrl = onOpenUrl,
                    onInteract = {
                        prefsRepo.saveLastReadHadith(
                            collection = hadith.collection,
                            bookNumber = hadith.bookNumber,
                            hadithId = hadith.id,
                            hadithNumber = hadith.hadithNumber,
                            bookTitle = book.titleEn
                        )
                    }
                )
            }
        }
    }
}

@Composable
fun HadithCard(
    hadith: Hadith,
    language: AppLanguage,
    isBookmarked: Boolean = false,
    isHighlighted: Boolean = false,
    onToggleBookmark: (() -> Unit)? = null,
    onOpenUrl: ((String) -> Unit)? = null,
    onInteract: (() -> Unit)? = null
) {
    val context = LocalContext.current
    var showAllLanguages by remember { mutableStateOf(false) }

    val primaryTranslation = when (language) {
        AppLanguage.URDU -> hadith.textUrdu
        AppLanguage.ARABIC -> hadith.textArabic
        AppLanguage.HINDI -> if (hadith.isHindiAvailable && hadith.textHindi.isNotBlank()) {
            hadith.textHindi
        } else {
            "${hadith.textEnglish}\n\n[Urdu / اردو]: ${hadith.textUrdu}"
        }
        AppLanguage.ENGLISH -> hadith.textEnglish
    }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isHighlighted) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("hadith_card_${hadith.id}")
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "${hadith.collection} #${hadith.hadithNumber}",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = hadith.bookName,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                ) {
                    Text(
                        text = hadith.grade,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))

            val appSettings = com.example.ui.theme.LocalAppSettings.current
            val resolvedArabicFamily = com.example.ui.theme.resolveArabicFontFamily(appSettings.arabicFontOption)
            val resolvedArabicSize = (appSettings.arabicFontSize - 2f).coerceIn(17f, 34f)
            val resolvedTransSize = appSettings.translationFontSize.coerceIn(12f, 22f)

            // Original Arabic Hadith Text
            Text(
                text = hadith.textArabic,
                fontFamily = resolvedArabicFamily,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontSize = resolvedArabicSize.sp,
                    lineHeight = (resolvedArabicSize * 1.55f).sp,
                    textDirection = TextDirection.Rtl
                ),
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.End,
                modifier = Modifier.fillMaxWidth()
            )

            if (language != AppLanguage.ARABIC && appSettings.showInlineTranslation) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = primaryTranslation,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = resolvedTransSize.sp,
                        lineHeight = (resolvedTransSize * 1.45f).sp,
                        textDirection = if (language == AppLanguage.URDU) TextDirection.Rtl else TextDirection.Ltr
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (language == AppLanguage.HINDI && !hadith.isHindiAvailable) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = AppLocalization.get("hadith_hindi_unavailable_notice", language),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }

            AnimatedVisibility(visible = showAllLanguages) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp)
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                            RoundedCornerShape(10.dp)
                        )
                        .padding(10.dp)
                ) {
                    if (hadith.textEnglish.isNotBlank()) {
                        Text(
                            text = "English: ${hadith.textEnglish}",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                    }
                    if (hadith.textUrdu.isNotBlank()) {
                        Text(
                            text = "اردو: ${hadith.textUrdu}",
                            style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Rtl)
                        )
                    }
                    if (hadith.isHindiAvailable && hadith.textHindi.isNotBlank()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "हिन्दी: ${hadith.textHindi}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = "${AppLocalization.get("hadith_narrator_label", language)}: ${hadith.narrator}",
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.primary
            )
            if (hadith.editionName.isNotBlank()) {
                Text(
                    text = hadith.editionName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
                )
            }
            Text(
                text = hadith.sourceAttribution,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = AppLocalization.get(
                            if (showAllLanguages) "dua_hide_translations" else "dua_all_4_languages",
                            language
                        ),
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier
                            .clickable {
                                showAllLanguages = !showAllLanguages
                                onInteract?.invoke()
                            }
                            .padding(vertical = 8.dp, horizontal = 4.dp)
                    )
                    if (hadith.sourceUrl.isNotBlank() && onOpenUrl != null) {
                        Text(
                            text = AppLocalization.get("hadith_open_source_link", language),
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clickable { onOpenUrl(hadith.sourceUrl) }
                                .padding(vertical = 8.dp, horizontal = 4.dp)
                        )
                    }
                }

                Row {
                    IconButton(
                        onClick = {
                            onInteract?.invoke()
                            onToggleBookmark?.invoke()
                        },
                        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                    ) {
                        Icon(
                            imageVector = if (isBookmarked) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                            contentDescription = "Bookmark Hadith",
                            tint = if (isBookmarked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    IconButton(
                        onClick = {
                            onInteract?.invoke()
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = ClipData.newPlainText(
                                "Hadith",
                                "${hadith.textArabic}\n\n$primaryTranslation\n(${hadith.collection} #${hadith.hadithNumber})"
                            )
                            clipboard.setPrimaryClip(clip)
                            Toast.makeText(context, "Hadith copied to clipboard", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy Hadith")
                    }

                    IconButton(
                        onClick = {
                            onInteract?.invoke()
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(
                                    Intent.EXTRA_TEXT,
                                    "${hadith.textArabic}\n\n$primaryTranslation\n\n(${hadith.collection} #${hadith.hadithNumber} - Noor-e-Hidayat)"
                                )
                            }
                            context.startActivity(Intent.createChooser(intent, "Share Hadith"))
                        },
                        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                    ) {
                        Icon(Icons.Default.Share, contentDescription = "Share Hadith")
                    }
                }
            }
        }
    }
}

@Composable
private fun EditionManifestDialog(
    manifest: HadithEditionManifest,
    onDismiss: () -> Unit,
    onOpenUrl: (String) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = manifest.collection,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = manifest.titleArabic,
                    fontFamily = QuranNaskhFontFamily,
                    style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Rtl),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth()
                )
                Text("Compiler: ${manifest.compiler}", style = MaterialTheme.typography.bodySmall)
                Text("Structure: ${manifest.totalBooks} Books • ${manifest.canonicalTotalNarrations} canonical narrations", style = MaterialTheme.typography.bodySmall)
                Text("Edition: ${manifest.volumeEditionName}", style = MaterialTheme.typography.bodySmall)
                Text("English: ${manifest.englishTranslator}", style = MaterialTheme.typography.bodySmall)
                Text("Urdu: ${manifest.urduTranslator}", style = MaterialTheme.typography.bodySmall)
                Text("Hindi Status: ${manifest.hindiStatusNote}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                Text("Licence: ${manifest.openDatasetLicense}", style = MaterialTheme.typography.bodySmall)
                Text(manifest.attributionNotice, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onOpenUrl(manifest.primarySourceUrl) }) {
                        Text("Sunnah.com")
                    }
                    OutlinedButton(onClick = { onOpenUrl(manifest.openDatasetRepo) }) {
                        Text("Hadith API")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("OK")
            }
        }
    )
}
