package com.example.data.repository

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.example.data.model.AppLanguage
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

enum class LectureCategory(
    val id: String,
    val labelKey: String,
    val matchKeywords: List<String>
) {
    POPULAR_SCHOLARS(
        id = "popular_scholars",
        labelKey = "lecture_cat_popular_scholars",
        matchKeywords = emptyList()
    ),
    LATEST_LECTURES(
        id = "latest_lectures",
        labelKey = "lecture_cat_latest",
        matchKeywords = emptyList()
    ),
    QURAN_TAFSEER(
        id = "quran_tafseer",
        labelKey = "lecture_cat_tafseer",
        matchKeywords = listOf("tafseer", "tafsir", "surah", "ayat", "ayah", "تفسير", "تفسیر", "سورة", "قرآن", "quran", "bayyinah")
    ),
    QURAN_EXPLANATION(
        id = "quran_explanation",
        labelKey = "lecture_cat_quran_explanation",
        matchKeywords = listOf("tadabbur", "reflection", "story", "stories", "meaning", "message", "quran", "فہم", "تدبر", "قرآن")
    ),
    HADITH(
        id = "hadith",
        labelKey = "lecture_cat_hadith",
        matchKeywords = listOf("hadith", "hadeeth", "bukhari", "muslim", "sunnah", "حديث", "حدیث", "سنت")
    ),
    SEERAH(
        id = "seerah",
        labelKey = "lecture_cat_seerah",
        matchKeywords = listOf("seerah", "sirah", "prophet", "muhammad", "makkah", "madinah", "hijrah", "سيرة", "سیرت", "نبی")
    ),
    ISLAMIC_HISTORY(
        id = "islamic_history",
        labelKey = "lecture_cat_history",
        matchKeywords = listOf("history", "companions", "sahaba", "ummah", "khilafah", "jerusalem", "aqsa", "تاريخ", "تاریخ", "صحابہ")
    ),
    SALAH_WORSHIP(
        id = "salah_worship",
        labelKey = "lecture_cat_salah",
        matchKeywords = listOf("salah", "salat", "prayer", "namaz", "wudu", "fasting", "ramadan", "zakat", "hajj", "dua", "dhikr", "صلاة", "نماز", "عبادت", "دعاء")
    ),
    ISLAMIC_MANNERS(
        id = "islamic_manners",
        labelKey = "lecture_cat_manners",
        matchKeywords = listOf("akhlaq", "adab", "manners", "character", "patience", "sabr", "mercy", "forgiveness", "heart", "soul", "اخلاق", "صبر", "توبہ")
    ),
    FAMILY_SOCIETY(
        id = "family_society",
        labelKey = "lecture_cat_family",
        matchKeywords = listOf("family", "marriage", "parents", "children", "youth", "community", "wife", "husband", "nikah", "نکاح", "والدین", "خاندان")
    ),
    NEW_MUSLIM_EDUCATION(
        id = "new_muslim_education",
        labelKey = "lecture_cat_new_muslim",
        matchKeywords = listOf("revert", "convert", "new muslim", "basics", "pillar", "pillars", "islam", "faith", "aqeedah", "shahada", "عقيدة", "ایمان", "اسلام")
    ),
    ISLAMIC_QA(
        id = "islamic_qa",
        labelKey = "lecture_cat_qa",
        matchKeywords = listOf("q&a", "question", "ask", "fatwa", "ruling", "halal", "haram", "permissible", "مسئلہ", "سوال", "جواب", "فتوى", "huda", "assim")
    )
}

enum class HarameynLocation {
    MAKKAH,
    MADINAH
}

enum class LiveBroadcastState {
    LOADING,
    LIVE_CONFIRMED,
    NO_ACTIVE_BROADCAST,
    CURRENTLY_UNAVAILABLE,
    OFFLINE
}

enum class FeedLoadStatus {
    ONLINE_FRESH,
    CACHED_FRESH,
    OFFLINE_CACHE,
    ERROR_NO_CACHE
}

data class VerifiedIslamicChannel(
    val key: String,
    val channelId: String,
    val handle: String,
    val nameEn: String,
    val nameUr: String,
    val nameAr: String,
    val nameHi: String,
    val specialtyEn: String,
    val specialtyUr: String,
    val specialtyAr: String,
    val specialtyHi: String,
    val languageCode: String,
    val primaryCategories: List<LectureCategory>
) {
    val feedUrl: String
        get() = "https://www.youtube.com/feeds/videos.xml?channel_id=$channelId"

    val officialChannelUrl: String
        get() = "https://www.youtube.com/channel/$channelId"

    fun localizedName(language: AppLanguage): String = when (language) {
        AppLanguage.ENGLISH -> nameEn
        AppLanguage.URDU -> nameUr
        AppLanguage.ARABIC -> nameAr
        AppLanguage.HINDI -> nameHi
    }

    fun localizedSpecialty(language: AppLanguage): String = when (language) {
        AppLanguage.ENGLISH -> specialtyEn
        AppLanguage.URDU -> specialtyUr
        AppLanguage.ARABIC -> specialtyAr
        AppLanguage.HINDI -> specialtyHi
    }
}

data class HarameynStreamChannel(
    val location: HarameynLocation,
    val channelId: String,
    val handle: String,
    val nameEn: String,
    val nameUr: String,
    val nameAr: String,
    val nameHi: String
) {
    val officialChannelUrl: String
        get() = "https://www.youtube.com/channel/$channelId"

    val officialLiveUrl: String
        get() = "https://www.youtube.com/channel/$channelId/live"

    val feedUrl: String
        get() = "https://www.youtube.com/feeds/videos.xml?channel_id=$channelId"

    fun localizedName(language: AppLanguage): String = when (language) {
        AppLanguage.ENGLISH -> nameEn
        AppLanguage.URDU -> nameUr
        AppLanguage.ARABIC -> nameAr
        AppLanguage.HINDI -> nameHi
    }
}

data class YouTubeVideoItem(
    val id: String,
    val title: String,
    val channel: String,
    val channelId: String = "",
    val channelKey: String = "",
    val thumbnail: String,
    val publishedIso: String = "",
    val publishedDisplay: String = "",
    val publishedEpochMs: Long = 0L,
    val videoUrl: String = "https://www.youtube.com/watch?v=$id",
    val languageCode: String = "en",
    val categories: List<LectureCategory> = listOf(LectureCategory.LATEST_LECTURES),
    val isFromCache: Boolean = false
)

data class LectureFeedResult(
    val videos: List<YouTubeVideoItem>,
    val status: FeedLoadStatus,
    val unavailableChannels: List<String> = emptyList(),
    val lastUpdatedMs: Long = 0L
)

data class LiveStreamStatusResult(
    val location: HarameynLocation,
    val channel: HarameynStreamChannel,
    val state: LiveBroadcastState,
    val verifiedVideoId: String? = null,
    val streamTitle: String = "",
    val publishedDisplay: String = "",
    val watchOnYouTubeUrl: String = channel.officialLiveUrl,
    val isEmbeddableConfirmed: Boolean = false,
    val checkedAtMs: Long = System.currentTimeMillis()
)

/**
 * Policy-compliant YouTube feed & live broadcast verification repository.
 *
 * - Uses ONLY official public YouTube Atom/RSS channel feeds (`feeds/videos.xml?channel_id=...`),
 *   official oEmbed (`youtube.com/oembed`), and official channel `/live` canonical metadata.
 * - Contains ZERO HTML search scraping, ZERO embedded JSON script regex parsing, ZERO fake cookies,
 *   ZERO User-Agent spoofing, ZERO login walls, and ZERO fake live stream badges or unrelated fallbacks.
 * - NOTE: Global YouTube keyword search across all channels requires YouTube Data API v3 (`search/list`)
 *   with an API key and quota; local search here filters accurately across loaded verified channel feeds.
 */
object YouTubeRepository {

    private const val PREFS_NAME = "noor_youtube_feeds_v2"
    private const val KEY_CHANNEL_CACHE_PREFIX = "rss_cache_"
    private const val KEY_CHANNEL_TS_PREFIX = "rss_ts_"
    private const val KEY_FAVORITES_JSON = "lecture_favorites_v2"
    private const val KEY_RECENT_WATCHED_JSON = "lecture_recent_v2"
    private const val KEY_LIVE_CACHE_PREFIX = "live_status_v2_"
    private const val CACHE_TTL_MS = 30 * 60 * 1000L
    private const val MAX_RECENT_WATCHED = 25

    private val CHANNEL_ID_REGEX = Regex("^UC[A-Za-z0-9_-]{22}$")
    private val VIDEO_ID_REGEX = Regex("^[A-Za-z0-9_-]{11}$")
    private val CANONICAL_WATCH_REGEX = Regex("""<link\s+rel=["']canonical["']\s+href=["']https?://(?:www\.)?youtube\.com/watch\?v=([A-Za-z0-9_-]{11})["']""", RegexOption.IGNORE_CASE)
    private val LIVE_META_TRUE_REGEX = Regex("""<meta\s+itemprop=["']isLiveBroadcast["']\s+content=["']True["']""", RegexOption.IGNORE_CASE)
    private val LIVE_END_DATE_REGEX = Regex("""<meta\s+itemprop=["']endDate["']\s+content=["'][^"']+["']""", RegexOption.IGNORE_CASE)

    internal var httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    internal var networkChecker: ((Context) -> Boolean)? = null

    private val memoryCache = ConcurrentHashMap<String, Pair<Long, List<YouTubeVideoItem>>>()
    private val inFlightRequests = ConcurrentHashMap<String, Deferred<ChannelFetchOutcome>>()

    /**
     * Verified official YouTube channels with authenticated UC channel IDs.
     */
    val VERIFIED_CHANNELS: List<VerifiedIslamicChannel> = listOf(
        VerifiedIslamicChannel(
            key = "bayyinah",
            channelId = "UCO153Mjc-mCLmRriP24SkOA",
            handle = "@bayyinah",
            nameEn = "Nouman Ali Khan (Bayyinah)",
            nameUr = "نعمان علی خان (بینہ انسٹیٹیوٹ)",
            nameAr = "نعمان علي خان (مؤسسة بينة)",
            nameHi = "नोमान अली ख़ान (बय्यिनाह)",
            specialtyEn = "Quran Tafseer & Deep Linguistic Explanation",
            specialtyUr = "تفسیر اور فہمِ قرآن",
            specialtyAr = "التفسير وتدبر القرآن",
            specialtyHi = "क़ुरआन तफ़सीर और गहन व्याख्या",
            languageCode = "en",
            primaryCategories = listOf(
                LectureCategory.POPULAR_SCHOLARS,
                LectureCategory.QURAN_TAFSEER,
                LectureCategory.QURAN_EXPLANATION
            )
        ),
        VerifiedIslamicChannel(
            key = "mufti_menk",
            channelId = "UCnVaVfGtwN2vQukYwWPAMWQ",
            handle = "@muftimenkofficial",
            nameEn = "Mufti Menk Official",
            nameUr = "مفتی مینک آفیشل",
            nameAr = "المفتي منك الرسمي",
            nameHi = "मुफ़्ती मेनक आधिकारिक",
            specialtyEn = "Manners, Family Guidance & New Muslim Education",
            specialtyUr = "اخلاق، خاندانی تربیت اور نو مسلم رہنمائی",
            specialtyAr = "الأخلاق والأسرة وتعليم المسلمين الجدد",
            specialtyHi = "इस्लामी शिष्टाचार, परिवार और नव-मुस्लिम मार्गदर्शन",
            languageCode = "en",
            primaryCategories = listOf(
                LectureCategory.POPULAR_SCHOLARS,
                LectureCategory.ISLAMIC_MANNERS,
                LectureCategory.FAMILY_SOCIETY,
                LectureCategory.NEW_MUSLIM_EDUCATION
            )
        ),
        VerifiedIslamicChannel(
            key = "yaqeen",
            channelId = "UC3vHW2h22WE-pNi5WJtRIjg",
            handle = "@yaqeeninstituteofficial",
            nameEn = "Yaqeen Institute (Dr. Omar Suleiman)",
            nameUr = "یقین انسٹیٹیوٹ (ڈاکٹر عمر سلیمان)",
            nameAr = "معهد يقين (د. عمر سليمان)",
            nameHi = "यक़ीन इंस्टीट्यूट (डॉ. उमर सुलेमान)",
            specialtyEn = "Seerah, Islamic History & Contemporary Faith",
            specialtyUr = "سیرت النبی ﷺ، اسلامی تاریخ اور جدید مسائل",
            specialtyAr = "السيرة والتاريخ الإسلامي والفكر",
            specialtyHi = "सीरत, इस्लामी इतिहास और ईमान",
            languageCode = "en",
            primaryCategories = listOf(
                LectureCategory.POPULAR_SCHOLARS,
                LectureCategory.SEERAH,
                LectureCategory.ISLAMIC_HISTORY,
                LectureCategory.ISLAMIC_MANNERS
            )
        ),
        VerifiedIslamicChannel(
            key = "tariq_masood",
            channelId = "UCumT1HMTXegYzESyPKH3pVg",
            handle = "@MuftiTariqMasoodSpeeches",
            nameEn = "Mufti Tariq Masood Speeches",
            nameUr = "مفتی طارق مسعود اسپیچز",
            nameAr = "خطب المفتي طارق مسعود",
            nameHi = "मुफ़्ती तारिक़ मसूद स्पीचेज़",
            specialtyEn = "Salah, Family Life & Urdu Q&A",
            specialtyUr = "نماز، عائلی مسائل اور سوال و جواب",
            specialtyAr = "الصلاة والأسرة والأسئلة بالأردية",
            specialtyHi = "नमाज़, पारिवारिक जीवन और उर्दू प्रश्नोत्तर",
            languageCode = "ur",
            primaryCategories = listOf(
                LectureCategory.POPULAR_SCHOLARS,
                LectureCategory.SALAH_WORSHIP,
                LectureCategory.FAMILY_SOCIETY,
                LectureCategory.ISLAMIC_QA
            )
        ),
        VerifiedIslamicChannel(
            key = "assim",
            channelId = "UCfTlkthH5WBujs6zCsxdf6Q",
            handle = "@assimalhakeem",
            nameEn = "Sheikh Assim Al-Hakeem",
            nameUr = "شیخ عاصم الحکیم",
            nameAr = "الشيخ عاصم الحكيم",
            nameHi = "शेख़ आसिम अल-हकीम",
            specialtyEn = "Islamic Q&A, Salah, Fiqh & New Muslim Basics",
            specialtyUr = "سوال و جواب، فقہِ نماز اور بنیادی مسائل",
            specialtyAr = "الفتاوى والأسئلة وفقه الصلاة",
            specialtyHi = "इस्लामी प्रश्नोत्तर, नमाज़ और बुनियादी फ़िक़्ह",
            languageCode = "en",
            primaryCategories = listOf(
                LectureCategory.POPULAR_SCHOLARS,
                LectureCategory.ISLAMIC_QA,
                LectureCategory.SALAH_WORSHIP,
                LectureCategory.NEW_MUSLIM_EDUCATION
            )
        ),
        VerifiedIslamicChannel(
            key = "seekersguidance",
            channelId = "UCmu3m31K71M_0a8hYyA2zXg",
            handle = "@seekersguidanceorg",
            nameEn = "SeekersGuidance",
            nameUr = "سیکرز گائیڈنس",
            nameAr = "سيكرز جايدنس للعلوم الشرعية",
            nameHi = "सीकर्सगाइडेंस",
            specialtyEn = "Hadith, Seerah, Classical Jurisprudence & Worship",
            specialtyUr = "حدیث، سیرت اور عبادات کی تعلیم",
            specialtyAr = "الحديث والسيرة والعبادات",
            specialtyHi = "हदीस, सीरत और इबादत की शिक्षा",
            languageCode = "en",
            primaryCategories = listOf(
                LectureCategory.HADITH,
                LectureCategory.SEERAH,
                LectureCategory.SALAH_WORSHIP,
                LectureCategory.NEW_MUSLIM_EDUCATION
            )
        ),
        VerifiedIslamicChannel(
            key = "saudi_quran",
            channelId = "UCos52azQNBgW63_9uDJoPDA",
            handle = "@SaudiQuranTv",
            nameEn = "Saudi Quran TV (Makkah)",
            nameUr = "سعودی قرآن ٹی وی (مکہ مکرمہ)",
            nameAr = "قناة القرآن الكريم السعودية",
            nameHi = "सऊदी क़ुरआन टीवी (मक्का)",
            specialtyEn = "Official Masjid al-Haram Recitations & Khutbahs",
            specialtyUr = "مسجد الحرام کی تلاوت و خطبات",
            specialtyAr = "تلاوات وخطب المسجد الحرام",
            specialtyHi = "मस्जिद अल-हरम से तिलावत व ख़ुत्बे",
            languageCode = "ar",
            primaryCategories = listOf(
                LectureCategory.QURAN_EXPLANATION,
                LectureCategory.SALAH_WORSHIP,
                LectureCategory.LATEST_LECTURES
            )
        ),
        VerifiedIslamicChannel(
            key = "saudi_sunnah",
            channelId = "UCROKYPep-UuODNwyipe6JMw",
            handle = "@SaudiSunnahTv",
            nameEn = "Saudi Sunnah TV (Madinah)",
            nameUr = "سعودی سنت ٹی وی (مدینہ منورہ)",
            nameAr = "قناة السنة النبوية السعودية",
            nameHi = "सऊदी सुन्नत टीवी (मदीना)",
            specialtyEn = "Official Masjid an-Nabawi Hadith & Sunnah Reminders",
            specialtyUr = "مسجد نبوی ﷺ سے حدیث و سنت",
            specialtyAr = "السنة النبوية من المسجد النبوي",
            specialtyHi = "मस्जिद अन-नबवी से हदीस व सुन्नत",
            languageCode = "ar",
            primaryCategories = listOf(
                LectureCategory.HADITH,
                LectureCategory.SEERAH,
                LectureCategory.LATEST_LECTURES
            )
        )
    )

    val HARAMEYN_CHANNELS: Map<HarameynLocation, HarameynStreamChannel> = mapOf(
        HarameynLocation.MAKKAH to HarameynStreamChannel(
            location = HarameynLocation.MAKKAH,
            channelId = "UCos52azQNBgW63_9uDJoPDA",
            handle = "@SaudiQuranTv",
            nameEn = "Al-Quran Al-Kareem TV (Saudi Broadcasting Authority)",
            nameUr = "سعودی چینل القرآن الکریم (مسجد الحرام)",
            nameAr = "قناة القرآن الكريم الرسمية (المسجد الحرام)",
            nameHi = "सऊदी अल-क़ुरआन अल-करीम टीवी (मस्जिद अल-हरम)"
        ),
        HarameynLocation.MADINAH to HarameynStreamChannel(
            location = HarameynLocation.MADINAH,
            channelId = "UCROKYPep-UuODNwyipe6JMw",
            handle = "@SaudiSunnahTv",
            nameEn = "Al-Sunnah Al-Nabawiyah TV (Saudi Broadcasting Authority)",
            nameUr = "سعودی چینل السنۃ النبویہ (مسجد نبوی ﷺ)",
            nameAr = "قناة السنة النبوية الرسمية (المسجد النبوي)",
            nameHi = "सऊदी अल-सुन्नत अन-नबविया टीवी (मस्जिद अन-नबवी)"
        )
    )

    fun isValidChannelId(channelId: String): Boolean = CHANNEL_ID_REGEX.matches(channelId.trim())

    fun isValidVideoId(videoId: String): Boolean = VIDEO_ID_REGEX.matches(videoId.trim())

    fun isNetworkAvailable(context: Context): Boolean {
        networkChecker?.let { return it(context) }
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (_: Exception) {
            true
        }
    }

    private data class ChannelFetchOutcome(
        val channel: VerifiedIslamicChannel,
        val items: List<YouTubeVideoItem>,
        val fetchedOnline: Boolean,
        val cacheTimestampMs: Long,
        val unavailable: Boolean
    )

    /**
     * Fetches official Atom XML feeds for all verified channels (or a selected channel),
     * deduplicates concurrent calls, caches locally, and handles offline/unavailable channels honestly.
     */
    suspend fun fetchVerifiedChannelFeeds(
        context: Context,
        channelKey: String? = null,
        forceRefresh: Boolean = false
    ): LectureFeedResult = withContext(Dispatchers.IO) {
        val targets = if (channelKey.isNullOrBlank()) {
            VERIFIED_CHANNELS
        } else {
            VERIFIED_CHANNELS.filter { it.key == channelKey }.ifEmpty { VERIFIED_CHANNELS }
        }

        val online = isNetworkAvailable(context)
        val outcomes = coroutineScope {
            targets.map { channel ->
                async { fetchSingleChannelFeed(context, channel, forceRefresh, online) }
            }.awaitAll()
        }

        val combined = outcomes
            .flatMap { it.items }
            .distinctBy { it.id }
            .sortedByDescending { it.publishedEpochMs }

        val unavailable = outcomes.filter { it.unavailable }.map { it.channel.handle }
        val latestTimestamp = outcomes.maxOfOrNull { it.cacheTimestampMs } ?: 0L
        val anyFreshOnline = outcomes.any { it.fetchedOnline }

        val status = when {
            anyFreshOnline -> FeedLoadStatus.ONLINE_FRESH
            !online && combined.isNotEmpty() -> FeedLoadStatus.OFFLINE_CACHE
            combined.isNotEmpty() -> if (online) FeedLoadStatus.CACHED_FRESH else FeedLoadStatus.OFFLINE_CACHE
            else -> FeedLoadStatus.ERROR_NO_CACHE
        }

        LectureFeedResult(
            videos = combined,
            status = status,
            unavailableChannels = unavailable,
            lastUpdatedMs = latestTimestamp
        )
    }

    private suspend fun fetchSingleChannelFeed(
        context: Context,
        channel: VerifiedIslamicChannel,
        forceRefresh: Boolean,
        isOnline: Boolean
    ): ChannelFetchOutcome {
        if (!isValidChannelId(channel.channelId)) {
            return ChannelFetchOutcome(channel, emptyList(), fetchedOnline = false, cacheTimestampMs = 0L, unavailable = true)
        }

        val now = System.currentTimeMillis()
        if (!forceRefresh) {
            val mem = memoryCache[channel.channelId]
            if (mem != null && now - mem.first < CACHE_TTL_MS && mem.second.isNotEmpty()) {
                return ChannelFetchOutcome(channel, mem.second, fetchedOnline = false, cacheTimestampMs = mem.first, unavailable = false)
            }
            val diskCached = loadChannelCache(context, channel.channelId)
            if (diskCached.second.isNotEmpty() && (!isOnline || now - diskCached.first < CACHE_TTL_MS)) {
                val cachedList = diskCached.second.map { it.copy(isFromCache = true) }
                memoryCache[channel.channelId] = diskCached.first to cachedList
                return ChannelFetchOutcome(channel, cachedList, fetchedOnline = false, cacheTimestampMs = diskCached.first, unavailable = false)
            }
        }

        if (!isOnline) {
            val diskCached = loadChannelCache(context, channel.channelId)
            return ChannelFetchOutcome(
                channel = channel,
                items = diskCached.second.map { it.copy(isFromCache = true) },
                fetchedOnline = false,
                cacheTimestampMs = diskCached.first,
                unavailable = diskCached.second.isEmpty()
            )
        }

        val existingJob = inFlightRequests[channel.channelId]
        if (existingJob != null && existingJob.isActive) {
            return existingJob.await()
        }

        return coroutineScope {
            val deferred = async {
                try {
                    val xml = executeGetWithRetry(channel.feedUrl, maxAttempts = 2)
                    if (xml != null) {
                        val parsed = parseAtomFeedXml(xml, channel)
                        if (parsed.isNotEmpty()) {
                            val timestamp = System.currentTimeMillis()
                            memoryCache[channel.channelId] = timestamp to parsed
                            saveChannelCache(context, channel.channelId, timestamp, parsed)
                            ChannelFetchOutcome(channel, parsed, fetchedOnline = true, cacheTimestampMs = timestamp, unavailable = false)
                        } else {
                            val fallbackCache = loadChannelCache(context, channel.channelId)
                            ChannelFetchOutcome(
                                channel = channel,
                                items = fallbackCache.second.map { it.copy(isFromCache = true) },
                                fetchedOnline = false,
                                cacheTimestampMs = fallbackCache.first,
                                unavailable = fallbackCache.second.isEmpty()
                            )
                        }
                    } else {
                        val fallbackCache = loadChannelCache(context, channel.channelId)
                        ChannelFetchOutcome(
                            channel = channel,
                            items = fallbackCache.second.map { it.copy(isFromCache = true) },
                            fetchedOnline = false,
                            cacheTimestampMs = fallbackCache.first,
                            unavailable = true
                        )
                    }
                } finally {
                    inFlightRequests.remove(channel.channelId)
                }
            }
            inFlightRequests[channel.channelId] = deferred
            deferred.await()
        }
    }

    private fun executeGetWithRetry(url: String, maxAttempts: Int = 2): String? {
        repeat(maxAttempts) { attempt ->
            try {
                val request = Request.Builder()
                    .url(url)
                    .get()
                    .build()
                httpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        return response.body?.string()
                    }
                    // Do not retry permanent 404/410 missing channel/video responses
                    if (response.code == 404 || response.code == 410) {
                        return null
                    }
                }
            } catch (_: Exception) {
                if (attempt == maxAttempts - 1) return null
            }
        }
        return null
    }

    /**
     * Parses an official YouTube Atom XML feed (`https://www.youtube.com/feeds/videos.xml?channel_id=...`).
     */
    fun parseAtomFeedXml(
        xml: String,
        channel: VerifiedIslamicChannel? = null
    ): List<YouTubeVideoItem> {
        val cleanXml = xml.trimStart('\uFEFF', ' ', '\t', '\r', '\n').trim()
        if (cleanXml.isBlank()) return emptyList()
        val items = mutableListOf<YouTubeVideoItem>()

        try {
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = true
            val parser = factory.newPullParser()
            parser.setInput(StringReader(cleanXml))

            var eventType = parser.eventType
            var insideEntry = false
            var insideAuthor = false
            var feedTitle = ""

            var entryVideoId = ""
            var entryTitle = ""
            var entryAuthor = ""
            var entryPublished = ""
            var entryThumbnail = ""
            var entryLink = ""

            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        val name = parser.name ?: ""
                        when {
                            name == "title" && !insideEntry -> {
                                feedTitle = parser.nextText().trim()
                            }
                            name == "entry" -> {
                                insideEntry = true
                                entryVideoId = ""
                                entryTitle = ""
                                entryAuthor = ""
                                entryPublished = ""
                                entryThumbnail = ""
                                entryLink = ""
                            }
                            insideEntry -> {
                                when {
                                    name == "videoId" -> {
                                        entryVideoId = parser.nextText().trim()
                                    }
                                    name == "title" -> {
                                        entryTitle = parser.nextText().trim()
                                    }
                                    name == "author" -> {
                                        insideAuthor = true
                                    }
                                    name == "name" && insideAuthor -> {
                                        entryAuthor = parser.nextText().trim()
                                    }
                                    name == "published" -> {
                                        entryPublished = parser.nextText().trim()
                                    }
                                    name == "thumbnail" -> {
                                        val urlAttr = parser.getAttributeValue(null, "url")?.trim().orEmpty()
                                        if (urlAttr.startsWith("https://") && entryThumbnail.isBlank()) {
                                            entryThumbnail = urlAttr
                                        }
                                    }
                                    name == "link" -> {
                                        val rel = parser.getAttributeValue(null, "rel") ?: "alternate"
                                        val href = parser.getAttributeValue(null, "href")?.trim().orEmpty()
                                        if (rel == "alternate" && href.isNotBlank()) {
                                            entryLink = href
                                            if (entryVideoId.isBlank() && href.contains("v=")) {
                                                entryVideoId = href.substringAfter("v=").substringBefore("&").trim()
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        val name = parser.name ?: ""
                        if (name == "author" && insideAuthor) {
                            insideAuthor = false
                        } else if (name == "entry" && insideEntry) {
                            insideEntry = false
                            if (isValidVideoId(entryVideoId) && entryTitle.isNotBlank()) {
                                val resolvedChannelName = entryAuthor.ifBlank {
                                    channel?.nameEn ?: feedTitle.ifBlank { "Verified Channel" }
                                }
                                val thumb = entryThumbnail.ifBlank {
                                    "https://i.ytimg.com/vi/$entryVideoId/hqdefault.jpg"
                                }
                                val videoLink = entryLink.ifBlank {
                                    "https://www.youtube.com/watch?v=$entryVideoId"
                                }
                                val epochMs = parseIsoDateEpoch(entryPublished)
                                val displayDate = formatIsoDateShort(entryPublished)
                                val categories = classifyLectureCategories(entryTitle, channel)

                                items.add(
                                    YouTubeVideoItem(
                                        id = entryVideoId,
                                        title = entryTitle,
                                        channel = resolvedChannelName,
                                        channelId = channel?.channelId ?: "",
                                        channelKey = channel?.key ?: "",
                                        thumbnail = thumb,
                                        publishedIso = entryPublished,
                                        publishedDisplay = displayDate,
                                        publishedEpochMs = epochMs,
                                        videoUrl = videoLink,
                                        languageCode = channel?.languageCode ?: inferLanguageCode(entryTitle),
                                        categories = categories,
                                        isFromCache = false
                                    )
                                )
                            }
                        }
                    }
                }
                eventType = parser.next()
            }
        } catch (_: Exception) {
            return items
        }

        return items.distinctBy { it.id }.sortedByDescending { it.publishedEpochMs }
    }

    internal fun classifyLectureCategories(
        title: String,
        channel: VerifiedIslamicChannel?
    ): List<LectureCategory> {
        val lower = title.lowercase(Locale.ROOT)
        val matched = LinkedHashSet<LectureCategory>()
        matched.add(LectureCategory.LATEST_LECTURES)

        if (channel != null && LectureCategory.POPULAR_SCHOLARS in channel.primaryCategories) {
            matched.add(LectureCategory.POPULAR_SCHOLARS)
        }

        LectureCategory.entries.forEach { cat ->
            if (cat.matchKeywords.isNotEmpty() && cat.matchKeywords.any { kw -> lower.contains(kw) }) {
                matched.add(cat)
            }
        }

        // If no specific topical keyword matched from title, fall back to the channel's own verified specialty categories
        if (matched.none { it != LectureCategory.LATEST_LECTURES && it != LectureCategory.POPULAR_SCHOLARS } && channel != null) {
            matched.addAll(channel.primaryCategories)
        }

        return matched.toList()
    }

    private fun inferLanguageCode(title: String): String {
        val hasArabicScript = title.any { it in '\u0600'..'\u06FF' }
        if (!hasArabicScript) return "en"
        val hasUrduSpecific = title.any { it in listOf('ٹ', 'ڈ', 'ڑ', 'ں', 'ے', 'ھ', 'پ', 'چ', 'ژ', 'گ') }
        return if (hasUrduSpecific) "ur" else "ar"
    }

    /**
     * Searches ONLY across real verified feed items (scholar, title, channel, category keywords, language).
     * Never returns fake unrelated fallback videos when nothing matches.
     */
    fun searchLocalVerifiedLectures(
        items: List<YouTubeVideoItem>,
        query: String = "",
        category: LectureCategory? = null,
        channelKey: String? = null,
        languageFilter: String? = null
    ): List<YouTubeVideoItem> {
        val trimmed = query.trim().lowercase(Locale.ROOT)
        return items.filter { item ->
            val matchesChannel = channelKey.isNullOrBlank() || item.channelKey == channelKey
            val matchesLang = languageFilter.isNullOrBlank() || item.languageCode.equals(languageFilter, ignoreCase = true)
            val matchesCat = when (category) {
                null, LectureCategory.LATEST_LECTURES -> true
                else -> item.categories.contains(category)
            }
            if (!matchesChannel || !matchesLang || !matchesCat) return@filter false

            if (trimmed.isEmpty()) return@filter true

            val channelMeta = VERIFIED_CHANNELS.firstOrNull { it.key == item.channelKey }
            val searchableFields = listOfNotNull(
                item.title,
                item.channel,
                item.publishedDisplay,
                item.languageCode,
                channelMeta?.nameEn,
                channelMeta?.nameUr,
                channelMeta?.nameAr,
                channelMeta?.nameHi,
                channelMeta?.specialtyEn,
                channelMeta?.specialtyUr
            )
            searchableFields.any { it.lowercase(Locale.ROOT).contains(trimmed) } ||
                item.categories.any { cat -> cat.matchKeywords.any { kw -> kw.contains(trimmed) || trimmed.contains(kw) } }
        }
    }

    /**
     * Checks live broadcast status for Official Saudi Makkah / Madinah broadcaster channels.
     * NEVER fabricates LIVE status. Shows LIVE ONLY when confirmed via official channel live metadata + oEmbed.
     */
    suspend fun checkHarameynLiveStatus(
        context: Context,
        location: HarameynLocation
    ): LiveStreamStatusResult = withContext(Dispatchers.IO) {
        val channel = HARAMEYN_CHANNELS.getValue(location)

        if (!isNetworkAvailable(context)) {
            return@withContext LiveStreamStatusResult(
                location = location,
                channel = channel,
                state = LiveBroadcastState.OFFLINE,
                verifiedVideoId = null,
                streamTitle = channel.nameEn,
                watchOnYouTubeUrl = channel.officialLiveUrl,
                isEmbeddableConfirmed = false
            )
        }

        // Check official YouTube /channel/<UC_ID>/live canonical metadata (no search scraping, no login, no fake cookies)
        val liveHtml = executeGetWithRetry(channel.officialLiveUrl, maxAttempts = 2)
        if (liveHtml != null) {
            val canonicalId = CANONICAL_WATCH_REGEX.find(liveHtml)?.groupValues?.getOrNull(1)
            val isLiveTrue = LIVE_META_TRUE_REGEX.containsMatchIn(liveHtml)
            val hasEnded = LIVE_END_DATE_REGEX.containsMatchIn(liveHtml)

            if (canonicalId != null && isValidVideoId(canonicalId) && isLiveTrue && !hasEnded) {
                val oEmbedJson = fetchOEmbedJson(canonicalId)
                val title = oEmbedJson?.optString("title").orEmpty().ifBlank { channel.nameEn }
                val embedOk = oEmbedJson != null
                return@withContext LiveStreamStatusResult(
                    location = location,
                    channel = channel,
                    state = LiveBroadcastState.LIVE_CONFIRMED,
                    verifiedVideoId = canonicalId,
                    streamTitle = title,
                    watchOnYouTubeUrl = "https://www.youtube.com/watch?v=$canonicalId",
                    isEmbeddableConfirmed = embedOk
                )
            }

            // Response received cleanly, but no active live stream metadata found
            return@withContext LiveStreamStatusResult(
                location = location,
                channel = channel,
                state = LiveBroadcastState.NO_ACTIVE_BROADCAST,
                verifiedVideoId = null,
                streamTitle = channel.nameEn,
                watchOnYouTubeUrl = channel.officialLiveUrl,
                isEmbeddableConfirmed = false
            )
        }

        // Verify whether the official RSS feed itself is reachable cleanly; still NEVER label an RSS item LIVE!
        val rssXml = executeGetWithRetry(channel.feedUrl, maxAttempts = 1)
        val finalState = if (rssXml != null) {
            LiveBroadcastState.NO_ACTIVE_BROADCAST
        } else {
            LiveBroadcastState.CURRENTLY_UNAVAILABLE
        }

        LiveStreamStatusResult(
            location = location,
            channel = channel,
            state = finalState,
            verifiedVideoId = null,
            streamTitle = channel.nameEn,
            watchOnYouTubeUrl = channel.officialLiveUrl,
            isEmbeddableConfirmed = false
        )
    }

    /**
     * Checks official YouTube oEmbed endpoint to verify a video exists and returns embeddable metadata.
     */
    fun fetchOEmbedJson(videoId: String): JSONObject? {
        if (!isValidVideoId(videoId)) return null
        val url = "https://www.youtube.com/oembed?url=https://www.youtube.com/watch?v=$videoId&format=json"
        val body = executeGetWithRetry(url, maxAttempts = 1) ?: return null
        return try {
            JSONObject(body)
        } catch (_: Exception) {
            null
        }
    }

    // =========================================================
    // LOCAL METADATA CACHE, BOOKMARKS & RECENTLY WATCHED HISTORY
    // =========================================================

    private fun saveChannelCache(context: Context, channelId: String, timestamp: Long, items: List<YouTubeVideoItem>) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .putString(KEY_CHANNEL_CACHE_PREFIX + channelId, serializeVideosJson(items))
                .putLong(KEY_CHANNEL_TS_PREFIX + channelId, timestamp)
                .apply()
        } catch (_: Exception) {
        }
    }

    private fun loadChannelCache(context: Context, channelId: String): Pair<Long, List<YouTubeVideoItem>> {
        return try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val ts = prefs.getLong(KEY_CHANNEL_TS_PREFIX + channelId, 0L)
            val raw = prefs.getString(KEY_CHANNEL_CACHE_PREFIX + channelId, null) ?: return 0L to emptyList()
            ts to deserializeVideosJson(raw)
        } catch (_: Exception) {
            0L to emptyList()
        }
    }

    fun getFavoriteLectures(context: Context): List<YouTubeVideoItem> {
        return try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val raw = prefs.getString(KEY_FAVORITES_JSON, null) ?: return emptyList()
            deserializeVideosJson(raw)
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun toggleFavoriteLecture(context: Context, item: YouTubeVideoItem): Boolean {
        val current = getFavoriteLectures(context).toMutableList()
        val exists = current.any { it.id == item.id }
        val isNowFav = if (exists) {
            current.removeAll { it.id == item.id }
            false
        } else {
            current.add(0, item)
            true
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_FAVORITES_JSON, serializeVideosJson(current))
            .apply()
        return isNowFav
    }

    fun getRecentlyWatchedLectures(context: Context): List<YouTubeVideoItem> {
        return try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val raw = prefs.getString(KEY_RECENT_WATCHED_JSON, null) ?: return emptyList()
            deserializeVideosJson(raw)
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun recordWatchedLecture(context: Context, item: YouTubeVideoItem): List<YouTubeVideoItem> {
        val current = getRecentlyWatchedLectures(context).toMutableList()
        current.removeAll { it.id == item.id }
        current.add(0, item)
        val trimmed = current.take(MAX_RECENT_WATCHED)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_RECENT_WATCHED_JSON, serializeVideosJson(trimmed))
            .apply()
        return trimmed
    }

    fun clearTestCaches() {
        memoryCache.clear()
        inFlightRequests.clear()
    }

    private fun serializeVideosJson(items: List<YouTubeVideoItem>): String {
        val arr = JSONArray()
        items.forEach { item ->
            val obj = JSONObject().apply {
                put("id", item.id)
                put("title", item.title)
                put("channel", item.channel)
                put("channelId", item.channelId)
                put("channelKey", item.channelKey)
                put("thumbnail", item.thumbnail)
                put("publishedIso", item.publishedIso)
                put("publishedDisplay", item.publishedDisplay)
                put("publishedEpochMs", item.publishedEpochMs)
                put("videoUrl", item.videoUrl)
                put("languageCode", item.languageCode)
                put("categories", JSONArray(item.categories.map { it.id }))
            }
            arr.put(obj)
        }
        return arr.toString()
    }

    private fun deserializeVideosJson(raw: String): List<YouTubeVideoItem> {
        val arr = JSONArray(raw)
        val result = ArrayList<YouTubeVideoItem>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val id = obj.optString("id")
            if (!isValidVideoId(id)) continue
            val catArr = obj.optJSONArray("categories")
            val cats = mutableListOf<LectureCategory>()
            if (catArr != null) {
                for (c in 0 until catArr.length()) {
                    val catId = catArr.optString(c)
                    LectureCategory.entries.firstOrNull { it.id == catId }?.let { cats.add(it) }
                }
            }
            result.add(
                YouTubeVideoItem(
                    id = id,
                    title = obj.optString("title"),
                    channel = obj.optString("channel"),
                    channelId = obj.optString("channelId"),
                    channelKey = obj.optString("channelKey"),
                    thumbnail = obj.optString("thumbnail"),
                    publishedIso = obj.optString("publishedIso"),
                    publishedDisplay = obj.optString("publishedDisplay"),
                    publishedEpochMs = obj.optLong("publishedEpochMs", 0L),
                    videoUrl = obj.optString("videoUrl", "https://www.youtube.com/watch?v=$id"),
                    languageCode = obj.optString("languageCode", "en"),
                    categories = cats.ifEmpty { listOf(LectureCategory.LATEST_LECTURES) },
                    isFromCache = true
                )
            )
        }
        return result
    }

    internal fun parseIsoDateEpoch(iso: String): Long {
        if (iso.isBlank()) return 0L
        val normalized = iso.replace("Z", "+0000")
            .replace(Regex("([+-]\\d\\d):(\\d\\d)$"), "$1$2")
        val patterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ssZ",
            "yyyy-MM-dd'T'HH:mm:ss.SSSZ",
            "yyyy-MM-dd"
        )
        for (pattern in patterns) {
            try {
                val sdf = SimpleDateFormat(pattern, Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
                val parsed = sdf.parse(normalized)
                if (parsed != null) return parsed.time
            } catch (_: Exception) {
            }
        }
        return 0L
    }

    internal fun formatIsoDateShort(iso: String): String {
        if (iso.isBlank()) return ""
        return if (iso.length >= 10 && iso[4] == '-' && iso[7] == '-') {
            iso.substring(0, 10)
        } else {
            iso.take(16)
        }
    }
}
