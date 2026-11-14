package com.example.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.example.data.db.AsbabAlNuzulEntity
import com.example.data.db.NoorDatabase
import com.example.data.db.NoorDatabaseSeeder
import com.example.data.db.TafseerBenefitEntity
import com.example.data.db.TafseerDao
import com.example.data.db.TafseerPassageEntity
import com.example.data.db.TafseerSourceEntity
import com.example.data.db.TranslationCatalogEntity
import com.example.data.model.AppLanguage
import com.example.data.model.Ayah
import com.example.data.model.AyahRevelationContext
import com.example.data.model.Hadith
import com.example.data.model.RevelationType
import com.example.data.network.QuranCatalogApiService
import com.example.data.network.QuranFoundationChapterInfo
import com.example.data.network.QuranFoundationTafsirResource
import com.example.data.network.QuranFoundationTafsirResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

data class TranslationDownloadState(
    val translationId: String,
    val isDownloading: Boolean = false,
    val currentSurah: Int = 0,
    val totalSurahs: Int = 114,
    val downloadedBytes: Long = 0L,
    val errorMessage: String? = null
)

data class ResolvedAyahTranslation(
    val translationId: String,
    val translatorName: String,
    val publisher: String,
    val editionVersion: String,
    val licenseName: String,
    val sourceUrl: String,
    val translatedText: String,
    val footnotes: String,
    val isOfflineAvailable: Boolean,
    val isOnlineOnly: Boolean,
    val isUnavailableNotice: String? = null
)

data class ResolvedTafseerDetail(
    val source: TafseerSourceEntity,
    val surahNumber: Int,
    val ayahNumber: Int,
    val startAyah: Int,
    val endAyah: Int,
    val passageText: String?,
    val passageLanguageNotice: String?,
    val editionAttribution: String,
    val volumeAndPage: String?,
    val historicalBackground: String,
    val asbabAlNuzulList: List<AsbabAlNuzulEntity>,
    val unavailableAsbabMessage: String,
    val benefitsList: List<TafseerBenefitEntity>,
    val relatedHadiths: List<Hadith>,
    val isLoadingOnline: Boolean = false,
    val onlineError: String? = null
)

class QuranTranslationTafseerRepository private constructor(
    private val context: Context,
    private val dao: TafseerDao,
    private val prefs: SharedPreferences
) {
    private val repoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val downloadJobs = ConcurrentHashMap<String, Job>()

    // In-memory ONLY session cache for Quran Foundation online-only Tafseer & translations
    // (Never persisted to offline disk per Quran Foundation Terms)
    private val onlineTafsirSessionCache = ConcurrentHashMap<String, QuranFoundationTafsirResult>()
    private val onlineChapterInfoCache = ConcurrentHashMap<String, QuranFoundationChapterInfo>()
    private val onlineQfVerseCache = ConcurrentHashMap<String, String>()
    private val qfTafsirResourcesCache = MutableStateFlow<List<QuranFoundationTafsirResource>>(emptyList())

    private val _downloadStates = MutableStateFlow<Map<String, TranslationDownloadState>>(emptyMap())
    val downloadStates: StateFlow<Map<String, TranslationDownloadState>> = _downloadStates.asStateFlow()

    private val _selectedTranslatorIds = MutableStateFlow<Map<String, String>>(loadSavedTranslators())
    val selectedTranslatorIds: StateFlow<Map<String, String>> = _selectedTranslatorIds.asStateFlow()

    init {
        repoScope.launch {
            NoorDatabaseSeeder.seedIfEmpty(dao)
        }
    }

    fun observeTafseerSources(): Flow<List<TafseerSourceEntity>> = dao.observeTafseerSources()

    fun observeTranslationCatalog(): Flow<List<TranslationCatalogEntity>> = dao.observeTranslationCatalog()

    fun observeTranslationsByLanguage(language: AppLanguage): Flow<List<TranslationCatalogEntity>> =
        dao.observeTranslationsByLanguage(language.code)

    suspend fun ensureSeeded() {
        NoorDatabaseSeeder.seedIfEmpty(dao)
    }

    fun getSelectedTranslatorId(language: AppLanguage): String {
        return _selectedTranslatorIds.value[language.code] ?: defaultTranslatorIdFor(language)
    }

    fun selectTranslator(language: AppLanguage, translationId: String) {
        prefs.edit().putString("active_trans_${language.code}", translationId).apply()
        _selectedTranslatorIds.update { current ->
            current + (language.code to translationId)
        }
    }

    private fun loadSavedTranslators(): Map<String, String> {
        return AppLanguage.entries.associate { lang ->
            lang.code to (prefs.getString("active_trans_${lang.code}", null) ?: defaultTranslatorIdFor(lang))
        }
    }

    private fun defaultTranslatorIdFor(language: AppLanguage): String {
        return when (language) {
            AppLanguage.URDU -> "ur_jalandhry_bundled"
            AppLanguage.ENGLISH -> "en_sahih_bundled"
            AppLanguage.HINDI -> "hi_farooq_bundled"
            AppLanguage.ARABIC -> "tanzil_ar_uthmani"
        }
    }

    /**
     * Dynamically refreshes official QuranEnc and Quran Foundation translation catalogs
     * without overwriting user's local download states.
     */
    suspend fun refreshRemoteTranslationCatalog(language: AppLanguage): Result<Int> = withContext(Dispatchers.IO) {
        ensureSeeded()
        var totalAddedOrUpdated = 0

        val quranEncResult = QuranCatalogApiService.fetchQuranEncTranslationsList(language.code)
        val qfResult = QuranCatalogApiService.fetchQuranFoundationTranslationsList()

        val existingMap = dao.getTranslationCatalog().associateBy { it.translationId }
        val toUpsert = mutableListOf<TranslationCatalogEntity>()

        quranEncResult.getOrNull()?.forEach { remoteItem ->
            val existing = existingMap[remoteItem.translationId]
            toUpsert.add(
                if (existing != null) {
                    remoteItem.copy(
                        isDownloadedOffline = existing.isDownloadedOffline,
                        downloadedAyahCount = existing.downloadedAyahCount,
                        sizeBytes = existing.sizeBytes
                    )
                } else remoteItem
            )
        }

        qfResult.getOrNull()?.forEach { qfItem ->
            // Strictly keep Quran Foundation translations online-only (offlineAllowed = false)
            toUpsert.add(qfItem.copy(offlineAllowed = false, isDownloadedOffline = false))
        }

        if (toUpsert.isNotEmpty()) {
            dao.upsertTranslationCatalog(toUpsert)
            totalAddedOrUpdated = toUpsert.size
            Result.success(totalAddedOrUpdated)
        } else {
            val err = quranEncResult.exceptionOrNull() ?: qfResult.exceptionOrNull()
            if (err != null) Result.failure(err) else Result.success(0)
        }
    }

    /**
     * Resolves the complete Tarjuma for an Ayah using the selected verified translator.
     * Uses local Room DB if downloaded/bundled, or live authorized API if online-only.
     */
    suspend fun resolveAyahTranslation(
        ayah: Ayah,
        language: AppLanguage,
        requestedTranslationId: String? = null
    ): ResolvedAyahTranslation = withContext(Dispatchers.IO) {
        ensureSeeded()
        val transId = requestedTranslationId ?: getSelectedTranslatorId(language)
        val meta = dao.getTranslationCatalogById(transId)
            ?: dao.getTranslationCatalogById(defaultTranslatorIdFor(language))!!

        // 1. Check pre-bundled verified QuranEnc editions (urdu_junagarhi, english_rwwad, hindi_omari, arabic_mokhtasar, english_mokhtasar, hindi_mokhtasar)
        if (meta.sourceProvider == "QURANENC" && QuranTarjumaAssetsLoader.hasBundledQuranEncEdition(meta.remoteKey)) {
            val bundledEnc = QuranTarjumaAssetsLoader.getBundledQuranEncVerse(
                context = context,
                editionKey = meta.remoteKey,
                surahNumber = ayah.surahNumber,
                ayahNumber = ayah.ayahNumber
            )
            if (bundledEnc != null) {
                return@withContext ResolvedAyahTranslation(
                    translationId = meta.translationId,
                    translatorName = meta.translator,
                    publisher = meta.publisher,
                    editionVersion = meta.editionVersion,
                    licenseName = meta.licenseName,
                    sourceUrl = meta.sourceUrl,
                    translatedText = bundledEnc.translation,
                    footnotes = bundledEnc.footnotes,
                    isOfflineAvailable = true,
                    isOnlineOnly = false
                )
            }
        }

        // 2. Check bundled Tanzil-sourced assets (6,236 ayahs offline) and enrich footnotes from verified QuranEnc scholarly notes
        if (meta.isBundledOffline) {
            val bundledTrans = ayah.translations.firstOrNull { it.language == language }
            val text = if (language == AppLanguage.ARABIC) {
                ayah.textArabic
            } else {
                bundledTrans?.translationText ?: ayah.textArabic
            }
            val attributedFootnote = when (language) {
                AppLanguage.URDU -> QuranTarjumaAssetsLoader.getBundledQuranEncVerse(context, "urdu_junagarhi", ayah.surahNumber, ayah.ayahNumber)
                    ?.footnotes?.takeIf { it.isNotBlank() }
                    ?.let { "حاشیہ (مولانا محمد جوناگڑھی / QuranEnc):\n$it" } ?: ""
                AppLanguage.ENGLISH -> QuranTarjumaAssetsLoader.getBundledQuranEncVerse(context, "english_rwwad", ayah.surahNumber, ayah.ayahNumber)
                    ?.footnotes?.takeIf { it.isNotBlank() }
                    ?.let { "Explanatory Notes (Rowwad Translation Center / QuranEnc):\n$it" } ?: ""
                AppLanguage.HINDI -> QuranTarjumaAssetsLoader.getBundledQuranEncVerse(context, "hindi_omari", ayah.surahNumber, ayah.ayahNumber)
                    ?.footnotes?.takeIf { it.isNotBlank() }
                    ?.let { "टिप्पणी (मौलाना अज़ीज़ुल हक़ अल-उमरी / QuranEnc):\n$it" } ?: ""
                AppLanguage.ARABIC -> QuranTarjumaAssetsLoader.getBundledQuranEncVerse(context, "arabic_mokhtasar", ayah.surahNumber, ayah.ayahNumber)
                    ?.translation?.takeIf { it.isNotBlank() }
                    ?.let { "المختصر في تفسير القرآن الكريم (مركز تفسير):\n$it" } ?: ""
            }
            return@withContext ResolvedAyahTranslation(
                translationId = meta.translationId,
                translatorName = meta.translator,
                publisher = meta.publisher,
                editionVersion = meta.editionVersion,
                licenseName = meta.licenseName,
                sourceUrl = meta.sourceUrl,
                translatedText = text,
                footnotes = attributedFootnote,
                isOfflineAvailable = true,
                isOnlineOnly = false
            )
        }

        // 2. Check downloaded Room cache (authorized QuranEnc editions only)
        if (meta.offlineAllowed && meta.isDownloadedOffline) {
            val cached = dao.getDownloadedVerseTranslation(meta.translationId, ayah.surahNumber, ayah.ayahNumber)
            if (cached != null && cached.translatedText.isNotBlank()) {
                return@withContext ResolvedAyahTranslation(
                    translationId = meta.translationId,
                    translatorName = meta.translator,
                    publisher = meta.publisher,
                    editionVersion = meta.editionVersion,
                    licenseName = meta.licenseName,
                    sourceUrl = meta.sourceUrl,
                    translatedText = cached.translatedText,
                    footnotes = cached.footnotes,
                    isOfflineAvailable = true,
                    isOnlineOnly = false
                )
            }
        }

        // 3. Online fetch (QuranEnc live surah OR Quran Foundation online-only API)
        if (meta.sourceProvider == "QURANENC") {
            val surahRes = QuranCatalogApiService.fetchQuranEncSurah(
                translationId = meta.translationId,
                remoteKey = meta.remoteKey,
                surahNumber = ayah.surahNumber
            )
            val matched = surahRes.getOrNull()?.first?.firstOrNull { it.ayahNumber == ayah.ayahNumber }
            if (matched != null) {
                return@withContext ResolvedAyahTranslation(
                    translationId = meta.translationId,
                    translatorName = meta.translator,
                    publisher = meta.publisher,
                    editionVersion = meta.editionVersion,
                    licenseName = meta.licenseName,
                    sourceUrl = meta.sourceUrl,
                    translatedText = matched.translatedText,
                    footnotes = matched.footnotes,
                    isOfflineAvailable = false,
                    isOnlineOnly = false
                )
            }
        } else if (meta.sourceProvider == "QURAN_FOUNDATION") {
            val cacheKey = "${meta.remoteKey}:${ayah.surahNumber}:${ayah.ayahNumber}"
            val liveText = onlineQfVerseCache[cacheKey] ?: run {
                val res = QuranCatalogApiService.fetchQuranFoundationVerseTranslation(
                    translationNumericId = meta.remoteKey,
                    surahNumber = ayah.surahNumber,
                    ayahNumber = ayah.ayahNumber
                ).getOrNull()
                if (!res.isNullOrBlank()) {
                    onlineQfVerseCache[cacheKey] = res
                }
                res
            }
            if (!liveText.isNullOrBlank()) {
                return@withContext ResolvedAyahTranslation(
                    translationId = meta.translationId,
                    translatorName = meta.translator,
                    publisher = meta.publisher,
                    editionVersion = meta.editionVersion,
                    licenseName = meta.licenseName,
                    sourceUrl = meta.sourceUrl,
                    translatedText = liveText,
                    footnotes = "",
                    isOfflineAvailable = false,
                    isOnlineOnly = true
                )
            }
        }

        // 4. Honest fallback notice (no silent fabrication!)
        ResolvedAyahTranslation(
            translationId = meta.translationId,
            translatorName = meta.translator,
            publisher = meta.publisher,
            editionVersion = meta.editionVersion,
            licenseName = meta.licenseName,
            sourceUrl = meta.sourceUrl,
            translatedText = "",
            footnotes = "",
            isOfflineAvailable = false,
            isOnlineOnly = !meta.offlineAllowed,
            isUnavailableNotice = "Unable to fetch ${meta.translator} (${meta.editionVersion}) right now. Connect to the internet or select an offline downloaded translation."
        )
    }

    /**
     * Downloads all 114 Surahs of an authorized QuranEnc translation into Room.
     * - Strictly refuses non-offlineAllowed sources (e.g. Quran Foundation).
     * - Never marks incomplete downloads as completed.
     */
    fun startTranslationDownload(translationId: String) {
        if (downloadJobs[translationId]?.isActive == true) return

        val job = repoScope.launch {
            val meta = dao.getTranslationCatalogById(translationId)
            if (meta == null || !meta.offlineAllowed || meta.sourceProvider != "QURANENC") {
                _downloadStates.update {
                    it + (translationId to TranslationDownloadState(
                        translationId = translationId,
                        isDownloading = false,
                        errorMessage = "Offline downloading is not permitted for this source."
                    ))
                }
                return@launch
            }

            _downloadStates.update {
                it + (translationId to TranslationDownloadState(
                    translationId = translationId,
                    isDownloading = true,
                    currentSurah = 0,
                    totalSurahs = 114
                ))
            }

            // Ensure incomplete download is never marked completed
            dao.updateTranslationDownloadState(translationId, isDownloaded = false, ayahCount = 0, sizeBytes = 0L)

            var totalBytes = 0L
            var totalVerses = 0
            var failed = false
            var failMessage: String? = null

            for (surah in 1..114) {
                val res = QuranCatalogApiService.fetchQuranEncSurah(
                    translationId = translationId,
                    remoteKey = meta.remoteKey,
                    surahNumber = surah
                )
                val pair = res.getOrNull()
                if (pair == null || pair.first.isEmpty()) {
                    failed = true
                    failMessage = res.exceptionOrNull()?.message ?: "Download interrupted at Surah $surah"
                    break
                }
                dao.upsertTranslationVerses(pair.first)
                totalVerses += pair.first.size
                totalBytes += pair.second

                _downloadStates.update {
                    it + (translationId to TranslationDownloadState(
                        translationId = translationId,
                        isDownloading = true,
                        currentSurah = surah,
                        totalSurahs = 114,
                        downloadedBytes = totalBytes
                    ))
                }
            }

            if (!failed && totalVerses >= 6200) {
                dao.updateTranslationDownloadState(
                    translationId = translationId,
                    isDownloaded = true,
                    ayahCount = totalVerses,
                    sizeBytes = totalBytes
                )
                _downloadStates.update {
                    it + (translationId to TranslationDownloadState(
                        translationId = translationId,
                        isDownloading = false,
                        currentSurah = 114,
                        totalSurahs = 114,
                        downloadedBytes = totalBytes
                    ))
                }
            } else {
                // Clean up incomplete data or preserve false completion status
                dao.updateTranslationDownloadState(
                    translationId = translationId,
                    isDownloaded = false,
                    ayahCount = 0,
                    sizeBytes = 0L
                )
                dao.deleteTranslationVerses(translationId)
                _downloadStates.update {
                    it + (translationId to TranslationDownloadState(
                        translationId = translationId,
                        isDownloading = false,
                        currentSurah = 0,
                        totalSurahs = 114,
                        downloadedBytes = 0L,
                        errorMessage = failMessage ?: "Download incomplete — not marked complete."
                    ))
                }
            }
        }
        downloadJobs[translationId] = job
    }

    fun deleteDownloadedTranslation(translationId: String) {
        downloadJobs[translationId]?.cancel()
        repoScope.launch {
            val meta = dao.getTranslationCatalogById(translationId) ?: return@launch
            if (meta.isBundledOffline) return@launch // Do not delete bundled Tanzil core
            dao.deleteTranslationVerses(translationId)
            dao.updateTranslationDownloadState(translationId, isDownloaded = false, ayahCount = 0, sizeBytes = 0L)
            _downloadStates.update { it - translationId }

            // Revert active translator to bundled default if deleted one was selected
            AppLanguage.entries.forEach { lang ->
                if (getSelectedTranslatorId(lang) == translationId) {
                    selectTranslator(lang, defaultTranslatorIdFor(lang))
                }
            }
        }
    }

    /**
     * Builds the complete 7-section Tarjuma study context ([AyahRevelationContext]) using
     * verified Room Asbab al-Nuzul, Hadith-cited benefits, authentic Surah revelation periods,
     * and optional online-only Quran Foundation scholarly commentary in session memory.
     */
    suspend fun loadTarjumaStudyContext(
        ayah: Ayah,
        language: AppLanguage,
        activeTranslation: ResolvedAyahTranslation?,
        fetchOnlineWisdom: Boolean = false
    ): AyahRevelationContext = withContext(Dispatchers.IO) {
        ensureSeeded()
        val asbabList = dao.getAsbabAlNuzulForAyah(ayah.surahNumber, ayah.ayahNumber)
        val benefitsList = dao.getBenefitsForAyah(ayah.surahNumber, ayah.ayahNumber)
        val relatedHadiths = QuranTafseerRepository.findVerifiedHadithsForAyah(ayah.surahNumber, ayah.ayahNumber)

        val preferredSlug = when (language) {
            AppLanguage.URDU -> "160"
            AppLanguage.ARABIC -> "14"
            else -> "en-tafisr-ibn-kathir"
        }
        val editionLabel = when (language) {
            AppLanguage.URDU -> "Tafsir Ibn Kathir Urdu (تفسیر ابنِ کثیر) — Maulana Muhammad Junagarhi / Quran Foundation API v4"
            AppLanguage.ARABIC -> "Tafsir Ibn Kathir (Arabic Original) — Imam Ibn Kathir (Quran Foundation API v4)"
            else -> "Tafsir Ibn Kathir (Abridged English, Safiur-Rahman al-Mubarakpuri / Darussalam) — Quran Foundation API v4"
        }
        val cacheKey = "${preferredSlug}_${ayah.surahNumber}_${ayah.ayahNumber}"
        val chapterCacheKey = "${ayah.surahNumber}_en"

        var qfCommentary = onlineTafsirSessionCache[cacheKey]
        var chapterInfo = onlineChapterInfoCache[chapterCacheKey]

        if (fetchOnlineWisdom && (qfCommentary == null || chapterInfo == null)) {
            val tafsirDeferred = if (qfCommentary == null) {
                async {
                    QuranCatalogApiService.fetchQuranFoundationTafsirByAyah(
                        slugOrId = preferredSlug,
                        surahNumber = ayah.surahNumber,
                        ayahNumber = ayah.ayahNumber
                    ).getOrNull()?.also { fetched ->
                        onlineTafsirSessionCache[cacheKey] = fetched
                    }
                }
            } else null

            val chapterDeferred = if (chapterInfo == null) {
                async {
                    QuranCatalogApiService.fetchQuranFoundationChapterInfo(
                        surahNumber = ayah.surahNumber,
                        languageCode = "en"
                    ).getOrNull()?.also { fetched ->
                        onlineChapterInfoCache[chapterCacheKey] = fetched
                    }
                }
            } else null

            if (tafsirDeferred != null) qfCommentary = tafsirDeferred.await()
            if (chapterDeferred != null) chapterInfo = chapterDeferred.await()
        }

        QuranTafseerRepository.buildVerifiedAyahRevelationContext(
            surahNumber = ayah.surahNumber,
            ayahNumber = ayah.ayahNumber,
            language = language,
            asbabList = asbabList,
            benefitsList = benefitsList,
            relatedHadiths = relatedHadiths,
            onlineQfCommentary = qfCommentary,
            onlineQfEditionLabel = if (qfCommentary != null) editionLabel else null,
            activeTranslation = activeTranslation,
            context = context,
            onlineChapterInfo = chapterInfo
        )
    }

    /**
     * Loads strictly attributed Tafseer passage & source metadata for an Ayah & Scholar (1..5)
     * without mixing in Quran translations or Tarjuma study sections.
     */
    suspend fun loadTafseerDetail(
        surahNumber: Int,
        ayahNumber: Int,
        sourceId: String,
        language: AppLanguage
    ): ResolvedTafseerDetail = withContext(Dispatchers.IO) {
        ensureSeeded()
        val source = dao.getTafseerSourceById(sourceId)
            ?: dao.getTafseerSources().first()
        val asbabList = dao.getAsbabAlNuzulForAyah(surahNumber, ayahNumber)
        val benefitsList = dao.getBenefitsForAyah(surahNumber, ayahNumber)
        val relatedHadiths = QuranTafseerRepository.findVerifiedHadithsForAyah(surahNumber, ayahNumber)
        val historical = QuranTafseerRepository.buildVerifiedHistoricalPhase(surahNumber, ayahNumber, language, context)
        val unavailableAsbabMsg = QuranTafseerRepository.getUnavailableAsbabMessage(language)

        // 1. Check authorized local Room TafseerPassage (if offlineAllowed)
        if (source.offlineAllowed) {
            val localPassage = dao.getPassageForAyah(source.id, surahNumber, ayahNumber)
            if (localPassage != null && localPassage.verified) {
                val translations = dao.getTranslationsForPassage(localPassage.id)
                val matchingTrans = translations.firstOrNull { it.language == language.code }
                val text = matchingTrans?.translatedText ?: localPassage.originalText
                val notice = if (matchingTrans == null && language != AppLanguage.URDU) {
                    "No published ${language.displayName} edition is cached for this passage. Showing original verified text."
                } else null
                return@withContext ResolvedTafseerDetail(
                    source = source,
                    surahNumber = surahNumber,
                    ayahNumber = ayahNumber,
                    startAyah = localPassage.startAyah,
                    endAyah = localPassage.endAyah,
                    passageText = text,
                    passageLanguageNotice = notice,
                    editionAttribution = localPassage.sourceReference,
                    volumeAndPage = listOf(localPassage.volume, localPassage.page).filter { it.isNotBlank() }.joinToString(" • ").ifBlank { null },
                    historicalBackground = historical,
                    asbabAlNuzulList = asbabList,
                    unavailableAsbabMessage = unavailableAsbabMsg,
                    benefitsList = benefitsList,
                    relatedHadiths = relatedHadiths
                )
            }
        }

        // 2. If onlineAllowed (Quran Foundation API v4 for Ibn Kathir or English Maariful Quran), fetch live online-only!
        if (source.onlineAllowed) {
            // Ensure dynamic catalog list is loaded once per session to check if QF officially hosts Urdu Ibn Kathir
            if (qfTafsirResourcesCache.value.isEmpty()) {
                QuranCatalogApiService.fetchQuranFoundationTafsirResources().getOrNull()?.let {
                    qfTafsirResourcesCache.value = it
                }
            }

            val qfSlugs = selectAuthorizedQfSlug(source.id, language, qfTafsirResourcesCache.value)
            if (qfSlugs != null) {
                val (slugToFetch, editionLabel, langNotice) = qfSlugs
                val cacheKey = "${slugToFetch}_${surahNumber}_$ayahNumber"
                val cachedLive = onlineTafsirSessionCache[cacheKey]
                if (cachedLive != null) {
                    return@withContext ResolvedTafseerDetail(
                        source = source,
                        surahNumber = surahNumber,
                        ayahNumber = ayahNumber,
                        startAyah = cachedLive.startAyah,
                        endAyah = cachedLive.endAyah,
                        passageText = cachedLive.text,
                        passageLanguageNotice = langNotice,
                        editionAttribution = editionLabel,
                        volumeAndPage = "Online-Only API • Quran Foundation v4 (${cachedLive.versesMapText})",
                        historicalBackground = historical,
                        asbabAlNuzulList = asbabList,
                        unavailableAsbabMessage = unavailableAsbabMsg,
                        benefitsList = benefitsList,
                        relatedHadiths = relatedHadiths
                    )
                }

                val liveRes = QuranCatalogApiService.fetchQuranFoundationTafsirByAyah(
                    slugOrId = slugToFetch,
                    surahNumber = surahNumber,
                    ayahNumber = ayahNumber
                )
                val qfData = liveRes.getOrNull()
                if (qfData != null) {
                    onlineTafsirSessionCache[cacheKey] = qfData
                    return@withContext ResolvedTafseerDetail(
                        source = source,
                        surahNumber = surahNumber,
                        ayahNumber = ayahNumber,
                        startAyah = qfData.startAyah,
                        endAyah = qfData.endAyah,
                        passageText = qfData.text,
                        passageLanguageNotice = langNotice,
                        editionAttribution = editionLabel,
                        volumeAndPage = "Online-Only API • Quran Foundation v4 (${qfData.versesMapText})",
                        historicalBackground = historical,
                        asbabAlNuzulList = asbabList,
                        unavailableAsbabMessage = unavailableAsbabMsg,
                        benefitsList = benefitsList,
                        relatedHadiths = relatedHadiths
                    )
                } else {
                    return@withContext ResolvedTafseerDetail(
                        source = source,
                        surahNumber = surahNumber,
                        ayahNumber = ayahNumber,
                        startAyah = ayahNumber,
                        endAyah = ayahNumber,
                        passageText = null,
                        passageLanguageNotice = langNotice,
                        editionAttribution = editionLabel,
                        volumeAndPage = null,
                        historicalBackground = historical,
                        asbabAlNuzulList = asbabList,
                        unavailableAsbabMessage = unavailableAsbabMsg,
                        benefitsList = benefitsList,
                        relatedHadiths = relatedHadiths,
                        onlineError = liveRes.exceptionOrNull()?.message ?: "Offline or network unreachable for online-only Tafseer."
                    )
                }
            }
        }

        // 3. Reference-only / Pending Permission source — never fabricate or substitute another scholar!
        ResolvedTafseerDetail(
            source = source,
            surahNumber = surahNumber,
            ayahNumber = ayahNumber,
            startAyah = ayahNumber,
            endAyah = ayahNumber,
            passageText = null,
            passageLanguageNotice = null,
            editionAttribution = "${source.bookTitle} — ${source.scholarName}",
            volumeAndPage = null,
            historicalBackground = historical,
            asbabAlNuzulList = asbabList,
            unavailableAsbabMessage = unavailableAsbabMsg,
            benefitsList = benefitsList,
            relatedHadiths = relatedHadiths
        )
    }

    private fun selectAuthorizedQfSlug(
        sourceId: String,
        language: AppLanguage,
        qfCatalog: List<QuranFoundationTafsirResource>
    ): Triple<String, String, String?>? {
        return when (sourceId) {
            "tafseer_ibn_kathir" -> {
                when (language) {
                    AppLanguage.ARABIC -> Triple(
                        "14",
                        "Tafsir Ibn Kathir (Arabic Original) — Quran Foundation API (ID 14)",
                        null
                    )
                    AppLanguage.ENGLISH -> Triple(
                        "en-tafisr-ibn-kathir",
                        "Tafsir Ibn Kathir (Abridged English, Safiur-Rahman al-Mubarakpuri / Darussalam) — Quran Foundation API",
                        null
                    )
                    AppLanguage.URDU -> {
                        val urduIbnKathir = qfCatalog.firstOrNull {
                            it.languageName == "urdu" &&
                                (it.slug.contains("kathir", true) || it.slug.contains("kaseer", true) || it.name.contains("Kathir", true))
                        }
                        if (urduIbnKathir != null) {
                            Triple(
                                urduIbnKathir.id.toString(),
                                "${urduIbnKathir.name} (${urduIbnKathir.authorName}) — Quran Foundation API",
                                null
                            )
                        } else {
                            Triple(
                                "en-tafisr-ibn-kathir",
                                "Tafsir Ibn Kathir (Published English Abridged) — Quran Foundation API",
                                "Maulana Muhammad Joonagarhi's Urdu edition (${NoorDatabaseSeeder.buildOfficial5TafseerSources().first().sourceUrl}) is Reference-Only pending permission. Showing Quran Foundation API's verified published English Ibn Kathir edition verbatim (no silent machine translation)."
                            )
                        }
                    }
                    AppLanguage.HINDI -> Triple(
                        "en-tafisr-ibn-kathir",
                        "Tafsir Ibn Kathir (Published English Abridged) — Quran Foundation API",
                        "No published Hindi edition of Tafseer Ibn Kathir is available in the authorized catalog. Showing the verified published English edition verbatim (no silent machine translation)."
                    )
                }
            }
            "maariful_quran" -> {
                val notice = if (language != AppLanguage.ENGLISH) {
                    "The Urdu HTML export at islamicdatabase.org/book/export/html/133 is Reference-Only pending redistribution permission. Showing Quran Foundation API's officially licensed English Ma'ariful Quran edition (tr. Prof. Muhammad Shamim, rev. Mufti Taqi Usmani) verbatim."
                } else null
                Triple(
                    "en-tafsir-maarif-ul-quran",
                    "Ma'ariful Quran (Mufti Muhammad Shafi Usmani • English tr. Prof. Muhammad Shamim, rev. Mufti Taqi Usmani) — Quran Foundation API (ID 168)",
                    notice
                )
            }
            else -> null
        }
    }

    // Historical & Hadith verification helpers live in QuranTafseerRepository for single-source consistency.

    companion object {
        @Volatile
        private var INSTANCE: QuranTranslationTafseerRepository? = null

        fun getInstance(context: Context): QuranTranslationTafseerRepository {
            return INSTANCE ?: synchronized(this) {
                val appCtx = context.applicationContext
                val db = NoorDatabase.getInstance(appCtx)
                val prefs = appCtx.getSharedPreferences("noor_quran_catalog_prefs", Context.MODE_PRIVATE)
                val repo = QuranTranslationTafseerRepository(appCtx, db.tafseerDao(), prefs)
                INSTANCE = repo
                repo
            }
        }
    }
}
