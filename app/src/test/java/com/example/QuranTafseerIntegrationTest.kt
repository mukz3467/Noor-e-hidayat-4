package com.example

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.db.NoorDatabase
import com.example.data.db.NoorDatabaseSeeder
import com.example.data.db.TafseerDao
import com.example.data.db.TafseerPassageEntity
import com.example.data.db.TranslationVerseEntity
import com.example.data.model.AppLanguage
import com.example.data.repository.QuranData
import com.example.data.repository.QuranTafseerRepository
import com.example.data.repository.QuranTarjumaAssetsLoader
import com.example.data.repository.QuranTranslationTafseerRepository
import com.example.ui.screens.QuranReaderScreen
import com.example.ui.theme.NooreHidayatTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class QuranTafseerIntegrationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var db: NoorDatabase
    private lateinit var dao: TafseerDao

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, NoorDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.tafseerDao()
        runBlocking {
            NoorDatabaseSeeder.seedIfEmpty(dao)
        }
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun all114SurahsAnd6236AyahsLoadFromBundledAssetsCleanly() {
        var totalAyahs = 0
        for (surah in QuranData.surahs) {
            val ayahs = QuranData.getAyahsForSurah(context, surah.number)
            assertEquals(
                "Surah ${surah.number} (${surah.nameEnglish}) ayah count mismatch",
                surah.ayahCount,
                ayahs.size
            )
            ayahs.forEachIndexed { idx, ayah ->
                assertEquals(surah.number, ayah.surahNumber)
                assertEquals(idx + 1, ayah.ayahNumber)
                assertFalse("Arabic Uthmani must not contain UTF-8 BOM", ayah.textArabic.contains("\uFEFF"))
                assertTrue("Arabic text must not be blank at ${surah.number}:${ayah.ayahNumber}", ayah.textArabic.isNotBlank())
                assertEquals("Must carry all 4 language slots", 4, ayah.translations.size)
                assertTrue(ayah.translations.all { it.translationText.isNotBlank() })
            }
            totalAyahs += ayahs.size
        }
        assertEquals("Complete Quran must contain 6,236 ayahs", 6236, totalAyahs)
    }

    @Test
    fun roomSeedingPreservesExact5TafseerSourcesAndPermissions() = runBlocking {
        val sources = dao.getTafseerSources()
        assertEquals(5, sources.size)
        assertEquals(
            listOf(
                "tafseer_ibn_kathir",
                "tafheem_ul_quran",
                "maariful_quran",
                "tafseer_usmani",
                "ahsan_ul_bayan"
            ),
            sources.map { it.id }
        )

        // Verify exact English scholar label for #1 includes Ibn Kathir Urdu
        val englishScholars = QuranTafseerRepository.getTop5Scholars(AppLanguage.ENGLISH)
        assertEquals("Ibn Kathir Urdu", englishScholars[0].bookTitle)
        assertEquals("Tafheem-ul-Quran", englishScholars[1].bookTitle)
        assertEquals("Maariful Quran", englishScholars[2].bookTitle)
        assertEquals("Tafseer Usmani", englishScholars[3].bookTitle)
        assertEquals("Ahsan-ul-Bayan", englishScholars[4].bookTitle)

        // All 5 must currently prohibit offline bundling until redistribution permission is obtained
        sources.forEach { source ->
            assertFalse("${source.bookTitle} must have offlineAllowed=false", source.offlineAllowed)
        }

        // Quran Foundation online-only is permitted strictly for Ibn Kathir & Maariful Quran
        assertTrue(sources[0].onlineAllowed)
        assertFalse(sources[1].onlineAllowed)
        assertTrue(sources[2].onlineAllowed)
        assertFalse(sources[3].onlineAllowed)
        assertFalse(sources[4].onlineAllowed)
    }

    @Test
    fun multiAyahPassageRangeMatchesAllIncludedAyahsWithoutSplitting() = runBlocking {
        val passage = TafseerPassageEntity(
            id = "test_ibnkathir_2_1_5",
            sourceId = "tafseer_ibn_kathir",
            surahNumber = 2,
            startAyah = 1,
            endAyah = 5,
            originalText = "Authorized multi-ayah commentary spanning 2:1-5.",
            volume = "Vol. 1",
            page = "p. 42",
            sourceReference = "Quran Foundation API v4 • 2:1-5",
            verified = true
        )
        dao.upsertPassages(listOf(passage))

        for (ayahNum in 1..5) {
            val found = dao.getPassageForAyah("tafseer_ibn_kathir", 2, ayahNum)
            assertNotNull("Ayah $ayahNum must resolve multi-ayah passage 2:1-5", found)
            assertEquals(1, found!!.startAyah)
            assertEquals(5, found.endAyah)
        }

        val outside = dao.getPassageForAyah("tafseer_ibn_kathir", 2, 6)
        assertNull("Ayah 6 must not falsely match 2:1-5", outside)
    }

    @Test
    fun asbabAlNuzulReturnsVerifiedNarrationsOrExplicitUnavailableMessage() = runBlocking {
        // Verified report exists for Surah 2:144 (Qiblah change) and 2:158 (Safa-Marwah)
        val qiblahAsbab = dao.getAsbabAlNuzulForAyah(2, 144)
        assertTrue("2:144 must return verified Asbab al-Nuzul", qiblahAsbab.isNotEmpty())
        assertTrue(qiblahAsbab.first().hadithReference.contains("Sahih al-Bukhari"))

        val safaAsbab = dao.getAsbabAlNuzulForAyah(2, 158)
        assertTrue("2:158 must return verified Asbab al-Nuzul", safaAsbab.isNotEmpty())
        assertTrue(safaAsbab.first().hadithReference.contains("1643"))

        // Ayah without a specific verified Asbab report in local database returns empty -> explicit fallback string
        val unmappedAsbab = dao.getAsbabAlNuzulForAyah(12, 4)
        assertTrue("Unmapped ayah must not return invented Asbab al-Nuzul", unmappedAsbab.isEmpty())
        assertEquals(
            "No verified specific occasion of revelation is available in the selected source.",
            QuranTafseerRepository.getUnavailableAsbabMessage(AppLanguage.ENGLISH)
        )
    }

    @Test
    fun revelationContextAcrossMultipleSurahsAndAyahsPopulatesAllFieldsAccurately() = runBlocking {
        val testAyahRefs = listOf(
            1 to 1,
            1 to 5,
            2 to 144,
            2 to 158,
            2 to 255,
            4 to 95,
            9 to 40,
            12 to 4,
            48 to 1,
            112 to 1
        )

        val repo = QuranTranslationTafseerRepository.getInstance(context)
        repo.ensureSeeded()

        testAyahRefs.forEach { (surahNum, ayahNum) ->
            val ayahs = QuranData.getAyahsForSurah(context, surahNum)
            val ayah = ayahs.first { it.ayahNumber == ayahNum }
            val resolvedTrans = repo.resolveAyahTranslation(ayah, AppLanguage.ENGLISH)
            val studyCtx = repo.loadTarjumaStudyContext(
                ayah = ayah,
                language = AppLanguage.ENGLISH,
                activeTranslation = resolvedTrans,
                fetchOnlineWisdom = false
            )

            assertTrue("sababNuzool must not be blank for $surahNum:$ayahNum", studyCtx.sababNuzool.isNotBlank())
            assertTrue("zamanaNuzool must not be blank for $surahNum:$ayahNum", studyCtx.zamanaNuzool.isNotBlank())
            assertTrue("maqsadHikmat must not be blank for $surahNum:$ayahNum", studyCtx.maqsadHikmat.isNotBlank())
            assertTrue("fazaailBenefits must not be blank for $surahNum:$ayahNum", studyCtx.fazaailBenefits.isNotBlank())
            assertTrue("authenticReferences must not be blank for $surahNum:$ayahNum", studyCtx.authenticReferences.isNotBlank())

            // Verify official source links coverage
            val urls = studyCtx.referenceLinks.map { it.second }
            assertTrue(urls.contains("https://tanzil.net/"))
            assertTrue(urls.contains("https://quranenc.com/en/"))
            assertTrue(urls.contains("https://api-docs.quran.com/docs/content_apis_versioned/4.0.0/translations/"))
            assertTrue(urls.contains("https://api-docs.quran.foundation/docs/content_apis_versioned/4.0.0/tafsirs/"))
            assertTrue(urls.contains("https://archive.org/search?query=Asbab+al-Nuzul+Al-Wahidi"))
            assertTrue(urls.contains("https://sunnah.com/bukhari"))
            assertTrue(urls.contains("https://sunnah.com/muslim"))
            assertTrue(urls.contains("https://www.australianislamiclibrary.org/tafseer-ibn-katheer.html"))
            assertTrue(urls.contains("https://tafheemulquran.net/index.html"))
            assertTrue(urls.contains("https://islamicdatabase.org/book/export/html/133"))
            assertTrue(urls.contains("https://onlinemufti.org/tafseer-books/"))
            assertTrue(urls.contains("https://www.ahsanulbayan.co.in/"))
        }

        // Verify distinctness: 2:144 cites Qiblah change, 12:4 cites Al-Wahidi, and unmapped 12:5 explicitly notes absence of specific sabab yet provides revelation theme context
        val ctx2144 = QuranTafseerRepository.getRevelationContext(2, 144, AppLanguage.ENGLISH, context)
        val ctx124 = QuranTafseerRepository.getRevelationContext(12, 4, AppLanguage.ENGLISH, context)
        val ctx125 = QuranTafseerRepository.getRevelationContext(12, 5, AppLanguage.ENGLISH, context)
        assertTrue(ctx2144.sababNuzool.contains("Qiblah", ignoreCase = true))
        assertTrue(ctx124.sababNuzool.contains("Wahidi", ignoreCase = true))
        assertTrue(ctx125.sababNuzool.contains("No verified specific occasion of revelation"))

        // Verify all 4 languages produce rich non-blank AyahRevelationContext & scholarly commentary from assets
        val extraTestRefs = listOf(1 to 1, 2 to 255, 18 to 10, 36 to 1, 93 to 1, 112 to 1)
        extraTestRefs.forEach { (s, a) ->
            AppLanguage.entries.forEach { lang ->
                val ctx = QuranTafseerRepository.getRevelationContext(s, a, lang, context)
                assertTrue("sababNuzool blank for $s:$a ($lang)", ctx.sababNuzool.isNotBlank())
                assertTrue("zamanaNuzool blank for $s:$a ($lang)", ctx.zamanaNuzool.isNotBlank())
                assertTrue("maqsadHikmat blank for $s:$a ($lang)", ctx.maqsadHikmat.isNotBlank())
                assertTrue("fazaailBenefits blank for $s:$a ($lang)", ctx.fazaailBenefits.isNotBlank())
            }
        }

        // Verify all 6 pre-bundled QuranEnc editions resolve complete Quran verses across Surahs 1..114
        val bundledEditions = listOf(
            "urdu_junagarhi",
            "english_rwwad",
            "hindi_omari",
            "arabic_mokhtasar",
            "english_mokhtasar",
            "hindi_mokhtasar"
        )
        bundledEditions.forEach { key ->
            val v1 = QuranTarjumaAssetsLoader.getBundledQuranEncVerse(context, key, 1, 1)
            val v255 = QuranTarjumaAssetsLoader.getBundledQuranEncVerse(context, key, 2, 255)
            val v114 = QuranTarjumaAssetsLoader.getBundledQuranEncVerse(context, key, 114, 6)
            assertNotNull("$key missing 1:1", v1)
            assertNotNull("$key missing 2:255", v255)
            assertNotNull("$key missing 114:6", v114)
        }
        assertNotNull(QuranTarjumaAssetsLoader.getBundledQuranEncVerse(context, "urdu_junagarhi", 2, 255)?.footnotes?.takeIf { it.isNotBlank() })
        assertNotNull(QuranTarjumaAssetsLoader.getAsbabAlNuzul(context, 93, 1))
        assertNotNull(QuranTarjumaAssetsLoader.getSurahHeaderArabic(context, 5))
        assertEquals(5, QuranTarjumaAssetsLoader.getSurahRevelationChronology(context, 1)?.revelationOrder)

        // Verify resolved translations for bundled & QuranEnc catalog items include rich footnotes
        val ayah255 = QuranData.getAyahsForSurah(context, 2).first { it.ayahNumber == 255 }
        val resolvedUrJunagarhi = repo.resolveAyahTranslation(ayah255, AppLanguage.URDU, "quranenc_urdu_junagarhi")
        assertTrue(resolvedUrJunagarhi.isOfflineAvailable)
        assertTrue(resolvedUrJunagarhi.translatedText.isNotBlank())
        assertTrue(resolvedUrJunagarhi.footnotes.isNotBlank())
    }

    @Test
    fun translationCatalogPermissionRulesAndOfflineDeletionWorkProperly() = runBlocking {
        val catalog = dao.getTranslationCatalog()
        assertTrue(catalog.any { it.translationId == "tanzil_ar_uthmani" && it.isBundledOffline && it.offlineAllowed })
        assertTrue(catalog.any { it.translationId == "quranenc_urdu_junagarhi" && it.isBundledOffline && it.isDownloadedOffline && it.offlineAllowed })
        assertTrue(catalog.any { it.translationId == "quranenc_english_rwwad" && it.isBundledOffline && it.isDownloadedOffline && it.offlineAllowed })
        assertTrue(catalog.any { it.translationId == "quranenc_hindi_omari" && it.isBundledOffline && it.isDownloadedOffline && it.offlineAllowed })
        assertTrue(catalog.any { it.translationId == "quranenc_english_saheeh" && it.offlineAllowed && !it.isBundledOffline })

        val qfClearQuran = dao.getTranslationCatalogById("qf_en_clear_quran_131")
        assertNotNull(qfClearQuran)
        assertFalse("Quran Foundation translation must have offlineAllowed=false", qfClearQuran!!.offlineAllowed)
        assertTrue(qfClearQuran.onlineAllowed)

        // Simulate partial verse storage on non-bundled downloadable edition: download state must remain false unless marked complete
        dao.upsertTranslationVerses(
            listOf(
                TranslationVerseEntity(
                    id = "quranenc_english_saheeh_1_1",
                    translationId = "quranenc_english_saheeh",
                    surahNumber = 1,
                    ayahNumber = 1,
                    translatedText = "In the name of Allah, the Entirely Merciful, the Especially Merciful.",
                    footnotes = ""
                )
            )
        )
        val metaBefore = dao.getTranslationCatalogById("quranenc_english_saheeh")!!
        assertFalse("Incomplete verse storage must never auto-mark downloaded", metaBefore.isDownloadedOffline)

        // Clean deletion removes verses and resets state
        dao.deleteTranslationVerses("quranenc_english_saheeh")
        dao.updateTranslationDownloadState("quranenc_english_saheeh", isDownloaded = false, ayahCount = 0, sizeBytes = 0L)
        assertEquals(0, dao.countDownloadedVerses("quranenc_english_saheeh"))
    }

    @Test
    fun tarjumaAndTafseerBottomSheetsMaintainStrictContentSeparation() {
        composeTestRule.setContent {
            NooreHidayatTheme {
                QuranReaderScreen(
                    surahNumber = 1,
                    language = AppLanguage.ENGLISH,
                    onBack = {},
                    onNavigateToSurah = {}
                )
            }
        }
        composeTestRule.waitForIdle()

        // 1. Scroll to Ayah 1 card and open existing Tarjuma bottom sheet
        composeTestRule.waitUntil(timeoutMillis = 5_000L) {
            composeTestRule.onAllNodesWithTag("quran_reader_ayah_list").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("quran_reader_ayah_list").performScrollToNode(hasTestTag("ayah_card_1"))
        composeTestRule.waitUntil(timeoutMillis = 5_000L) {
            composeTestRule.onAllNodesWithTag("ayah_1_translation_btn").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("ayah_1_translation_btn").performClick()

        // Wait until sheet & async studyContext load (Section 3 appears once studyContext is ready)
        composeTestRule.waitUntil(timeoutMillis = 5_000L) {
            composeTestRule.onAllNodesWithTag("tarjuma_sheet_list").fetchSemanticsNodes().isNotEmpty() &&
                runCatching {
                    composeTestRule.onNodeWithTag("tarjuma_sheet_list").performScrollToNode(hasTestTag("tarjuma_section_3"))
                    composeTestRule.onAllNodesWithTag("tarjuma_section_3").fetchSemanticsNodes().isNotEmpty()
                }.getOrDefault(false)
        }

        // Scroll cleanly to each of the 7 sections in order
        composeTestRule.onNodeWithTag("tarjuma_sheet_list").performScrollToNode(hasText("1. Arabic Quranic Ayah"))
        composeTestRule.onNodeWithText("1. Arabic Quranic Ayah").assertIsDisplayed()
        composeTestRule.onNodeWithTag("tarjuma_sheet_list").performScrollToNode(hasText("2. Authentic Published Translation (English)"))
        composeTestRule.onNodeWithText("2. Authentic Published Translation (English)").assertIsDisplayed()
        composeTestRule.onNodeWithTag("tarjuma_sheet_list").performScrollToNode(hasText("3. WHY WAS THIS AYAH REVEALED?"))
        composeTestRule.onNodeWithText("3. WHY WAS THIS AYAH REVEALED?").assertIsDisplayed()
        composeTestRule.onNodeWithTag("tarjuma_sheet_list").performScrollToNode(hasText("4. WHEN WAS THIS AYAH REVEALED?"))
        composeTestRule.onNodeWithText("4. WHEN WAS THIS AYAH REVEALED?").assertIsDisplayed()
        composeTestRule.onNodeWithTag("tarjuma_sheet_list").performScrollToNode(hasText("5. HIDDEN WISDOM AND DEEP MEANING"))
        composeTestRule.onNodeWithText("5. HIDDEN WISDOM AND DEEP MEANING").assertIsDisplayed()
        composeTestRule.onNodeWithTag("tarjuma_sheet_list").performScrollToNode(hasText("6. BENEFITS AND PRACTICAL LESSONS"))
        composeTestRule.onNodeWithText("6. BENEFITS AND PRACTICAL LESSONS").assertIsDisplayed()
        composeTestRule.onNodeWithTag("tarjuma_sheet_list").performScrollToNode(hasText("7. AUTHENTIC REFERENCES"))
        composeTestRule.onNodeWithText("7. AUTHENTIC REFERENCES").assertIsDisplayed()
    }

    @Test
    fun tafseerBottomSheetExcludesTranslationAndStudySections() {
        composeTestRule.setContent {
            NooreHidayatTheme {
                QuranReaderScreen(
                    surahNumber = 1,
                    language = AppLanguage.ENGLISH,
                    onBack = {},
                    onNavigateToSurah = {}
                )
            }
        }
        composeTestRule.waitUntil(timeoutMillis = 5_000L) {
            composeTestRule.onAllNodesWithTag("quran_reader_ayah_list").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("quran_reader_ayah_list").performScrollToNode(hasTestTag("ayah_card_1"))
        composeTestRule.waitUntil(timeoutMillis = 5_000L) {
            composeTestRule.onAllNodesWithTag("ayah_1_tafseer_btn").fetchSemanticsNodes().isNotEmpty()
        }

        // Open existing Tafseer bottom sheet for Ayah 1
        composeTestRule.onNodeWithTag("ayah_1_tafseer_btn").performClick()
        composeTestRule.waitUntil(timeoutMillis = 5_000L) {
            composeTestRule.onAllNodesWithTag("tafseer_sheet_list").fetchSemanticsNodes().isNotEmpty()
        }

        // Verify 5 scholar chips exist in Tafseer sheet
        composeTestRule.onNodeWithTag("scholar_chip_tafseer_ibn_kathir").assertIsDisplayed()
        composeTestRule.onNodeWithTag("scholar_chip_tafheem_ul_quran").assertIsDisplayed()
        composeTestRule.onNodeWithTag("scholar_chip_maariful_quran").assertIsDisplayed()
        composeTestRule.onNodeWithTag("scholar_chip_tafseer_usmani").assertIsDisplayed()
        composeTestRule.onNodeWithTag("scholar_chip_ahsan_ul_bayan").assertIsDisplayed()

        // Switch to a reference-only scholar (e.g. Tafheem-ul-Quran) to ensure commentary-only card renders immediately without waiting for network
        composeTestRule.onNodeWithTag("scholar_chip_tafheem_ul_quran").performClick()
        composeTestRule.waitUntil(timeoutMillis = 5_000L) {
            runCatching {
                composeTestRule.onNodeWithTag("tafseer_sheet_list").performScrollToNode(hasTestTag("tafseer_only_passage_card"))
                composeTestRule.onAllNodesWithTag("tafseer_only_passage_card").fetchSemanticsNodes().isNotEmpty()
            }.getOrDefault(false)
        }
        composeTestRule.onNodeWithTag("tafseer_only_passage_card").assertIsDisplayed()

        // Strictly verify NO Tarjuma / translation / 4-study sections leaked into Tafseer sheet
        assertEquals(0, composeTestRule.onAllNodesWithText("WHY WAS THIS AYAH REVEALED?", substring = true).fetchSemanticsNodes().size)
        assertEquals(0, composeTestRule.onAllNodesWithText("WHEN WAS THIS AYAH REVEALED?", substring = true).fetchSemanticsNodes().size)
        assertEquals(0, composeTestRule.onAllNodesWithText("HIDDEN WISDOM AND DEEP MEANING", substring = true).fetchSemanticsNodes().size)
        assertEquals(0, composeTestRule.onAllNodesWithText("BENEFITS AND PRACTICAL LESSONS", substring = true).fetchSemanticsNodes().size)
        assertEquals(0, composeTestRule.onAllNodesWithText("2. Ayah Translation", substring = true).fetchSemanticsNodes().size)
    }
}

