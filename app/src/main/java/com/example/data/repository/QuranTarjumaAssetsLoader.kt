package com.example.data.repository

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream

data class BundledQuranEncVerse(
    val translation: String,
    val footnotes: String
)

data class BundledAsbabEntry(
    val wahidiEnglish: String?,
    val sahihArabic: String?
)

data class BundledSurahChronology(
    val revelationPlace: String,
    val revelationOrder: Int
)

/**
 * Loads pre-bundled, compressed (.json.gz) authentic Islamic Tarjuma datasets from assets/tarjuma/:
 * - QuranEnc verified editions: urdu_junagarhi, english_rwwad, hindi_omari,
 *   arabic_mokhtasar, english_mokhtasar, hindi_mokhtasar (all 114 surahs / 6,236 ayahs)
 * - Asbab al-Nuzul: Imam Al-Wahidi's Asbab al-Nuzul & Sahih Asbab al-Nuzul (Ibrahim Muhammad al-Ali)
 * - Surah Naming & Objectives (Al-Mukhtasar / Al-Muyassar headers across 114 Surahs)
 * - Traditional 114-Surah Revelation Order & Place metadata
 */
object QuranTarjumaAssetsLoader {

    private val quranEncCache = ConcurrentHashMap<String, JSONObject>()
    @Volatile
    private var asbabRootCache: JSONObject? = null
    @Volatile
    private var surahHeadersArCache: JSONObject? = null
    @Volatile
    private var surahRevOrderCache: JSONObject? = null

    private fun readGzipJson(context: Context?, assetRelativePath: String): JSONObject? {
        if (context == null) return null
        val candidates = listOf(assetRelativePath.removeSuffix(".gz"), assetRelativePath).distinct()
        for (candidate in candidates) {
            val parsed = try {
                context.assets.open(candidate).use { rawStream ->
                    val buffered = java.io.BufferedInputStream(rawStream, 8192)
                    buffered.mark(2)
                    val b0 = buffered.read()
                    val b1 = buffered.read()
                    buffered.reset()
                    val isGzipped = (b0 == 0x1f && b1 == 0x8b)
                    val inputStream = if (isGzipped) GZIPInputStream(buffered) else buffered
                    inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                        JSONObject(reader.readText())
                    }
                }
            } catch (_: Throwable) {
                null
            }
            if (parsed != null) return parsed
        }
        return null
    }

    private fun getQuranEncRoot(context: Context?, editionKey: String): JSONObject? {
        quranEncCache[editionKey]?.let { return it }
        val loaded = readGzipJson(context, "tarjuma/quranenc_${editionKey}.json.gz") ?: return null
        quranEncCache[editionKey] = loaded
        return loaded
    }

    fun getBundledQuranEncVerse(
        context: Context?,
        editionKey: String,
        surahNumber: Int,
        ayahNumber: Int
    ): BundledQuranEncVerse? {
        val root = getQuranEncRoot(context, editionKey) ?: return null
        val surahObj = root.optJSONObject(surahNumber.toString()) ?: return null
        val raw = surahObj.opt(ayahNumber.toString()) ?: return null
        return when (raw) {
            is JSONArray -> {
                val trans = raw.optString(0, "").trim()
                val fn = if (raw.length() > 1) raw.optString(1, "").trim() else ""
                if (trans.isBlank()) null else BundledQuranEncVerse(trans, fn)
            }
            is String -> {
                val trans = raw.trim()
                if (trans.isBlank()) null else BundledQuranEncVerse(trans, "")
            }
            else -> null
        }
    }

    fun hasBundledQuranEncEdition(editionKey: String): Boolean = editionKey in BUNDLED_QURANENC_KEYS

    fun getAsbabAlNuzul(
        context: Context?,
        surahNumber: Int,
        ayahNumber: Int
    ): BundledAsbabEntry? {
        val root = asbabRootCache ?: readGzipJson(context, "tarjuma/asbab_al_nuzul.json.gz")?.also {
            asbabRootCache = it
        } ?: return null

        val sKey = surahNumber.toString()
        val aKey = ayahNumber.toString()

        val wahidiText = root.optJSONObject("wahidi_en")
            ?.optJSONObject(sKey)
            ?.optString(aKey, "")
            ?.takeIf { it.isNotBlank() }

        val sahihArText = root.optJSONObject("sahih_ar")
            ?.optJSONObject(sKey)
            ?.optString(aKey, "")
            ?.takeIf { it.isNotBlank() }

        if (wahidiText == null && sahihArText == null) return null
        return BundledAsbabEntry(wahidiEnglish = wahidiText, sahihArabic = sahihArText)
    }

    fun getSurahHeaderArabic(context: Context?, surahNumber: Int): String? {
        val root = surahHeadersArCache ?: readGzipJson(context, "tarjuma/surah_headers_ar.json.gz")?.also {
            surahHeadersArCache = it
        } ?: return null
        return root.optString(surahNumber.toString(), "").takeIf { it.isNotBlank() }
    }

    fun getSurahRevelationChronology(context: Context?, surahNumber: Int): BundledSurahChronology? {
        val root = surahRevOrderCache ?: readGzipJson(context, "tarjuma/surah_revelation_order.json.gz")?.also {
            surahRevOrderCache = it
        } ?: return null
        val obj = root.optJSONObject(surahNumber.toString()) ?: return null
        val place = obj.optString("place", "")
        val order = obj.optInt("order", 0)
        if (order <= 0) return null
        return BundledSurahChronology(revelationPlace = place, revelationOrder = order)
    }

    val BUNDLED_QURANENC_KEYS = setOf(
        "urdu_junagarhi",
        "english_rwwad",
        "hindi_omari",
        "arabic_mokhtasar",
        "english_mokhtasar",
        "hindi_mokhtasar"
    )
}
