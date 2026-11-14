package com.example.data.network

import androidx.core.text.HtmlCompat
import com.example.data.db.TranslationCatalogEntity
import com.example.data.db.TranslationVerseEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class QuranEncCatalogItem(
    val key: String,
    val title: String,
    val description: String,
    val languageIsoCode: String,
    val version: String,
    val lastUpdate: String,
    val direction: String
)

data class QuranFoundationTafsirResource(
    val id: Int,
    val name: String,
    val authorName: String,
    val slug: String,
    val languageName: String
)

data class QuranFoundationTafsirResult(
    val resourceId: Int,
    val resourceName: String,
    val languageName: String,
    val text: String,
    val startAyah: Int,
    val endAyah: Int,
    val versesMapText: String
)

data class QuranFoundationChapterInfo(
    val chapterId: Int,
    val languageName: String,
    val shortText: String,
    val source: String,
    val fullText: String
)

object QuranCatalogApiService {

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Retrieves official available translations list from QuranEnc API v1
     * for language code ("ur", "en", "hi", "ar").
     */
    suspend fun fetchQuranEncTranslationsList(languageCode: String): Result<List<TranslationCatalogEntity>> =
        withContext(Dispatchers.IO) {
            try {
                val url = "https://quranenc.com/api/v1/translations/list/$languageCode?localization=en"
                val jsonText = executeGet(url) ?: return@withContext Result.failure(Exception("Unable to reach QuranEnc catalog"))
                val root = JSONObject(jsonText)
                val arr = root.optJSONArray("translations") ?: return@withContext Result.success(emptyList())
                val list = mutableListOf<TranslationCatalogEntity>()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val key = obj.optString("key").trim()
                    if (key.isEmpty()) continue
                    val title = obj.optString("title").ifBlank { key }
                    val desc = obj.optString("description")
                    val lang = obj.optString("language_iso_code", languageCode).lowercase()
                    val version = obj.optString("version", "1.0")
                    val lastUpdate = obj.optString("last_update", "")

                    list.add(
                        TranslationCatalogEntity(
                            translationId = "quranenc_$key",
                            languageCode = lang,
                            translator = title,
                            publisher = if (desc.isNotBlank()) desc.take(120) else "QuranEnc / Rowwad",
                            editionVersion = "$key (v$version)",
                            sourceUrl = "https://quranenc.com/en/browse/$key",
                            licenseName = "QuranEnc Open Download & Attribution Terms",
                            licenseUrl = "https://quranenc.com/en/home/about/terms",
                            lastUpdatedDate = lastUpdate.ifBlank { "Official Catalog" },
                            sourceProvider = "QURANENC",
                            remoteKey = key,
                            isBundledOffline = false,
                            offlineAllowed = true,
                            onlineAllowed = true,
                            isDownloadedOffline = false,
                            downloadedAyahCount = 0,
                            sizeBytes = 0L,
                            isMachineTranslated = false
                        )
                    )
                }
                Result.success(list)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /**
     * Fetches a full Surah translation from QuranEnc API v1.
     */
    suspend fun fetchQuranEncSurah(
        translationId: String,
        remoteKey: String,
        surahNumber: Int
    ): Result<Pair<List<TranslationVerseEntity>, Long>> = withContext(Dispatchers.IO) {
        try {
            val url = "https://quranenc.com/api/v1/translation/sura/$remoteKey/$surahNumber"
            val jsonText = executeGet(url) ?: return@withContext Result.failure(Exception("Network error downloading Surah $surahNumber"))
            val root = JSONObject(jsonText)
            val resultArr = root.optJSONArray("result")
                ?: return@withContext Result.failure(Exception("Invalid QuranEnc surah payload"))

            val verses = mutableListOf<TranslationVerseEntity>()
            for (i in 0 until resultArr.length()) {
                val item = resultArr.getJSONObject(i)
                val sura = item.optString("sura").toIntOrNull() ?: surahNumber
                val aya = item.optString("aya").toIntOrNull() ?: (i + 1)
                val trans = cleanHtmlPreservingParagraphs(item.optString("translation"))
                val footnotes = cleanHtmlPreservingParagraphs(item.optString("footnotes"))
                if (trans.isNotBlank()) {
                    verses.add(
                        TranslationVerseEntity(
                            id = "${translationId}_${sura}_$aya",
                            translationId = translationId,
                            surahNumber = sura,
                            ayahNumber = aya,
                            translatedText = trans,
                            footnotes = footnotes
                        )
                    )
                }
            }
            Result.success(verses to jsonText.toByteArray(Charsets.UTF_8).size.toLong())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Fetches official translations catalog from Quran Foundation / Quran.com API v4.
     * Note: Per Quran Foundation Terms, QF translations are marked online-only (offlineAllowed = false).
     */
    suspend fun fetchQuranFoundationTranslationsList(): Result<List<TranslationCatalogEntity>> =
        withContext(Dispatchers.IO) {
            try {
                val url = "https://api.quran.com/api/v4/resources/translations"
                val jsonText = executeGet(url) ?: return@withContext Result.failure(Exception("Unable to reach Quran Foundation API"))
                val root = JSONObject(jsonText)
                val arr = root.optJSONArray("translations") ?: return@withContext Result.success(emptyList())
                val list = mutableListOf<TranslationCatalogEntity>()
                val targetLanguages = mapOf(
                    "urdu" to "ur",
                    "english" to "en",
                    "hindi" to "hi",
                    "arabic" to "ar"
                )
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val langName = obj.optString("language_name").lowercase()
                    val langCode = targetLanguages[langName] ?: continue
                    val id = obj.optInt("id", -1)
                    if (id <= 0) continue
                    val name = obj.optString("name")
                    val author = obj.optString("author_name").ifBlank { name }
                    val slug = obj.optString("slug").ifBlank { "qf_$id" }

                    list.add(
                        TranslationCatalogEntity(
                            translationId = "qf_${langCode}_$id",
                            languageCode = langCode,
                            translator = author,
                            publisher = "Quran Foundation API v4 (Online-Only)",
                            editionVersion = "$name ($slug)",
                            sourceUrl = "https://api-docs.quran.com/docs/content_apis_versioned/4.0.0/translations/",
                            licenseName = "Quran Foundation Online Display Only (No Offline Redistribution)",
                            licenseUrl = "https://api-docs.quran.com/docs/content_apis_versioned/4.0.0/translations/",
                            lastUpdatedDate = "Live API",
                            sourceProvider = "QURAN_FOUNDATION",
                            remoteKey = id.toString(),
                            isBundledOffline = false,
                            offlineAllowed = false,
                            onlineAllowed = true,
                            isDownloadedOffline = false,
                            downloadedAyahCount = 0,
                            sizeBytes = 0L,
                            isMachineTranslated = false
                        )
                    )
                }
                Result.success(list)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /**
     * Fetches single ayah translation online-only from Quran Foundation API v4 (not cached to disk).
     */
    suspend fun fetchQuranFoundationVerseTranslation(
        translationNumericId: String,
        surahNumber: Int,
        ayahNumber: Int
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val url = "https://api.quran.com/api/v4/verses/by_key/$surahNumber:$ayahNumber?translations=$translationNumericId"
            val jsonText = executeGet(url) ?: return@withContext Result.failure(Exception("Unable to fetch live translation"))
            val root = JSONObject(jsonText)
            val verseObj = root.optJSONObject("verse")
            val transArr = verseObj?.optJSONArray("translations")
            if (transArr != null && transArr.length() > 0) {
                val raw = transArr.getJSONObject(0).optString("text")
                Result.success(cleanHtmlPreservingParagraphs(raw))
            } else {
                Result.failure(Exception("Translation passage not returned by API"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Dynamically retrieves verified Tafsir resources catalog from Quran Foundation API v4.
     */
    suspend fun fetchQuranFoundationTafsirResources(): Result<List<QuranFoundationTafsirResource>> =
        withContext(Dispatchers.IO) {
            try {
                val url = "https://api.quran.com/api/v4/resources/tafsirs"
                val jsonText = executeGet(url) ?: return@withContext Result.failure(Exception("Unable to reach Quran Foundation tafsirs API"))
                val root = JSONObject(jsonText)
                val arr = root.optJSONArray("tafsirs") ?: return@withContext Result.success(emptyList())
                val list = mutableListOf<QuranFoundationTafsirResource>()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val id = obj.optInt("id", -1)
                    if (id <= 0) continue
                    list.add(
                        QuranFoundationTafsirResource(
                            id = id,
                            name = obj.optString("name"),
                            authorName = obj.optString("author_name"),
                            slug = obj.optString("slug"),
                            languageName = obj.optString("language_name").lowercase()
                        )
                    )
                }
                Result.success(list)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /**
     * Fetches live online-only Tafsir for a specific ayah from Quran Foundation API v4.
     * Supports multi-ayah verse ranges returned in the `verses` object (never misattributing multi-ayah passages).
     * Never writes QF content to offline database storage.
     */
    suspend fun fetchQuranFoundationTafsirByAyah(
        slugOrId: String,
        surahNumber: Int,
        ayahNumber: Int
    ): Result<QuranFoundationTafsirResult> = withContext(Dispatchers.IO) {
        try {
            val url = "https://api.quran.com/api/v4/tafsirs/$slugOrId/by_ayah/$surahNumber:$ayahNumber"
            val jsonText = executeGet(url) ?: return@withContext Result.failure(Exception("Network unavailable for live Tafseer"))
            val root = JSONObject(jsonText)
            val tafsirObj = root.optJSONObject("tafsir")
                ?: return@withContext Result.failure(Exception("No Tafseer entry found for $surahNumber:$ayahNumber"))

            val rawHtml = tafsirObj.optString("text")
            val cleanedText = cleanHtmlPreservingParagraphs(rawHtml)
            if (cleanedText.isBlank()) {
                return@withContext Result.failure(Exception("Tafseer passage is empty for $surahNumber:$ayahNumber"))
            }

            val resourceId = tafsirObj.optInt("resource_id", 0)
            val resourceName = tafsirObj.optString("resource_name")
            val langName = tafsirObj.optString("language_name")

            // Parse actual ayah span covered by this commentary passage
            var startAyah = ayahNumber
            var endAyah = ayahNumber
            val versesObj = tafsirObj.optJSONObject("verses")
            if (versesObj != null && versesObj.length() > 0) {
                val ayahNumbers = mutableListOf<Int>()
                val keys = versesObj.keys()
                while (keys.hasNext()) {
                    val key = keys.next() // format "surah:ayah"
                    val parts = key.split(":")
                    if (parts.size == 2 && parts[0].toIntOrNull() == surahNumber) {
                        parts[1].toIntOrNull()?.let { ayahNumbers.add(it) }
                    }
                }
                if (ayahNumbers.isNotEmpty()) {
                    startAyah = ayahNumbers.minOrNull() ?: ayahNumber
                    endAyah = ayahNumbers.maxOrNull() ?: ayahNumber
                }
            }

            Result.success(
                QuranFoundationTafsirResult(
                    resourceId = resourceId,
                    resourceName = resourceName,
                    languageName = langName,
                    text = cleanedText,
                    startAyah = startAyah,
                    endAyah = endAyah,
                    versesMapText = if (startAyah == endAyah) "$surahNumber:$startAyah" else "$surahNumber:$startAyah-$endAyah"
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Fetches verified Surah historical background & theme online-only from Quran Foundation API v4.
     * Cached only in session memory; never written to offline disk.
     */
    suspend fun fetchQuranFoundationChapterInfo(
        surahNumber: Int,
        languageCode: String = "en"
    ): Result<QuranFoundationChapterInfo> = withContext(Dispatchers.IO) {
        try {
            val url = "https://api.quran.com/api/v4/chapters/$surahNumber/info?language=$languageCode"
            val jsonText = executeGet(url) ?: return@withContext Result.failure(Exception("Chapter info unavailable"))
            val root = JSONObject(jsonText)
            val info = root.optJSONObject("chapter_info")
                ?: return@withContext Result.failure(Exception("No chapter_info for Surah $surahNumber ($languageCode)"))
            val shortText = cleanHtmlPreservingParagraphs(info.optString("short_text"))
            val fullText = cleanHtmlPreservingParagraphs(info.optString("text"))
            if (shortText.isBlank() && fullText.isBlank()) {
                return@withContext Result.failure(Exception("Empty chapter info"))
            }
            Result.success(
                QuranFoundationChapterInfo(
                    chapterId = surahNumber,
                    languageName = info.optString("language_name", languageCode),
                    shortText = shortText,
                    source = info.optString("source", "Quran Foundation API v4"),
                    fullText = fullText
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun executeGet(url: String): String? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Noor-e-Hidayat/3.0 (Android; AuthenticQuranClient)")
            .header("Accept", "application/json")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            return response.body?.string()
        }
    }

    fun cleanHtmlPreservingParagraphs(raw: String): String {
        if (raw.isBlank()) return ""
        val withParagraphMarkers = raw
            .replace(Regex("(?i)<br\\s*/?>"), "\n")
            .replace(Regex("(?i)</p>"), "\n\n")
            .replace(Regex("(?i)</h[1-6]>"), "\n\n")
            .replace(Regex("(?i)</div>"), "\n")
            .replace(Regex("(?i)<sup[^>]*>.*?</sup>"), "")
        val decoded = try {
            HtmlCompat.fromHtml(withParagraphMarkers, HtmlCompat.FROM_HTML_MODE_LEGACY).toString()
        } catch (_: Throwable) {
            withParagraphMarkers
                .replace(Regex("<[^>]+>"), "")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&nbsp;", " ")
        }
        return decoded
            .lines()
            .map { it.trimEnd() }
            .joinToString("\n")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }
}
