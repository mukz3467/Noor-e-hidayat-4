package com.example

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.view.View
import android.webkit.WebChromeClient
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.AppLanguage
import com.example.data.repository.FeedLoadStatus
import com.example.data.repository.HarameynLocation
import com.example.data.repository.LectureCategory
import com.example.data.repository.LiveBroadcastState
import com.example.data.repository.YouTubeRepository
import com.example.localization.AppLocalization
import com.example.ui.components.FullscreenVideoController
import com.example.ui.components.YouTubeIFramePlayerConfig
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class YouTubeLecturesAndPlayerTest {

    private lateinit var context: Context
    private val originalClient = YouTubeRepository.httpClient

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("noor_youtube_feeds_v2", Context.MODE_PRIVATE).edit().clear().commit()
        YouTubeRepository.clearTestCaches()
        YouTubeRepository.networkChecker = { true }
    }

    @After
    fun tearDown() {
        YouTubeRepository.httpClient = originalClient
        YouTubeRepository.networkChecker = null
        YouTubeRepository.clearTestCaches()
    }

    private fun buildMockClient(handler: (okhttp3.Request) -> Response): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain -> handler(chain.request()) })
            .build()
    }

    private fun sampleAtomFeed(channelId: String, authorName: String, entries: List<Triple<String, String, String>>): String {
        val entriesXml = entries.joinToString("\n") { (vid, title, published) ->
            "<entry>\n" +
                "  <id>yt:video:$vid</id>\n" +
                "  <yt:videoId>$vid</yt:videoId>\n" +
                "  <yt:channelId>$channelId</yt:channelId>\n" +
                "  <title>$title</title>\n" +
                "  <link rel=\"alternate\" href=\"https://www.youtube.com/watch?v=$vid\"/>\n" +
                "  <author><name>$authorName</name><uri>https://www.youtube.com/channel/$channelId</uri></author>\n" +
                "  <published>$published</published>\n" +
                "  <media:group><media:thumbnail url=\"https://i.ytimg.com/vi/$vid/hqdefault.jpg\" width=\"480\" height=\"360\"/></media:group>\n" +
                "</entry>"
        }
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<feed xmlns:yt=\"http://www.youtube.com/xml/schemas/2015\" xmlns:media=\"http://search.yahoo.com/mrss/\" xmlns=\"http://www.w3.org/2005/Atom\">\n" +
            "  <title>$authorName</title>\n" +
            entriesXml + "\n" +
            "</feed>"
    }

    @Test
    fun testProhibitedScrapingSignInAndFakeCookieCodeRemovedFromSources() {
        val projectRoot = File(System.getProperty("user.dir") ?: ".")
        val srcDir = if (File(projectRoot, "app/src/main/java").exists()) {
            File(projectRoot, "app/src/main/java")
        } else {
            File(projectRoot, "src/main/java")
        }
        assertTrue("Source directory must exist", srcDir.exists())

        val sourceFiles = srcDir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        val combinedText = sourceFiles.joinToString("\n") { it.readText() }

        val forbiddenPatterns = listOf(
            "ytInitialData",
            "youtube.com/results",
            "PROXY_SESSION_HEADERS",
            "NON_AUTH_SESSION_COOKIES",
            "YouTubeSignInDialog",
            "YouTubeAuthManager",
            "accounts.google.com/ServiceLogin",
            "LOGIN_INFO",
            "SAPISID",
            "CURATED_SCHOLAR_VIDEOS",
            "24/7 LIVE"
        )
        forbiddenPatterns.forEach { forbidden ->
            assertFalse("Forbidden pattern '$forbidden' must not remain in app source", combinedText.contains(forbidden))
        }
    }

    @Test
    fun testVerifiedChannelsHaveValidUcIdsAndOfficialFeedUrls() {
        assertTrue(YouTubeRepository.VERIFIED_CHANNELS.size >= 6)
        YouTubeRepository.VERIFIED_CHANNELS.forEach { ch ->
            assertTrue("Channel ${ch.key} ID must match UC[22]", YouTubeRepository.isValidChannelId(ch.channelId))
            assertTrue("Handle must start with @", ch.handle.startsWith("@"))
            assertEquals("https://www.youtube.com/feeds/videos.xml?channel_id=${ch.channelId}", ch.feedUrl)
        }
        assertEquals("UCos52azQNBgW63_9uDJoPDA", YouTubeRepository.HARAMEYN_CHANNELS[HarameynLocation.MAKKAH]?.channelId)
        assertEquals("UCROKYPep-UuODNwyipe6JMw", YouTubeRepository.HARAMEYN_CHANNELS[HarameynLocation.MADINAH]?.channelId)
    }

    @Test
    fun testAtomFeedParserExtractsMetadataAndRejectsMalformedIds() {
        val xml = sampleAtomFeed(
            channelId = "UCO153Mjc-mCLmRriP24SkOA",
            authorName = "Nouman Ali Khan - Official - Bayyinah",
            entries = listOf(
                Triple("dQw4w9WgXcQ", "Surah Al-Kahf Tafseer &amp; Reflections", "2026-04-10T14:00:00+00:00"),
                Triple("bad_id", "Should be ignored because not 11 chars", "2026-04-09T10:00:00+00:00"),
                Triple("abcDEF12345", "Understanding Salah and Humility", "2026-04-11T09:15:00+00:00")
            )
        )
        val channel = YouTubeRepository.VERIFIED_CHANNELS.first { it.key == "bayyinah" }
        val parsed = YouTubeRepository.parseAtomFeedXml(xml, channel)

        assertEquals("Malformed video ID must be skipped", 2, parsed.size)
        // Newest sorted first
        assertEquals("abcDEF12345", parsed[0].id)
        assertEquals("2026-04-11", parsed[0].publishedDisplay)
        assertEquals("Nouman Ali Khan - Official - Bayyinah", parsed[0].channel)
        assertEquals("https://i.ytimg.com/vi/abcDEF12345/hqdefault.jpg", parsed[0].thumbnail)
        assertTrue(parsed[0].categories.contains(LectureCategory.SALAH_WORSHIP))

        assertEquals("dQw4w9WgXcQ", parsed[1].id)
        assertTrue(parsed[1].categories.contains(LectureCategory.QURAN_TAFSEER))
    }

    @Test
    fun testFeedFetchingCachesLocallyAndHandlesOfflineAndUnavailableChannels() = runBlocking {
        YouTubeRepository.httpClient = buildMockClient { req ->
            val url = req.url.toString()
            when {
                url.contains("UCO153Mjc-mCLmRriP24SkOA") -> {
                    Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(
                            sampleAtomFeed(
                                "UCO153Mjc-mCLmRriP24SkOA",
                                "Bayyinah",
                                listOf(Triple("vid12345678", "Quran Tafseer Part 1", "2026-04-12T10:00:00Z"))
                            ).toResponseBody("application/atom+xml".toMediaType())
                        )
                        .build()
                }
                url.contains("UCnVaVfGtwN2vQukYwWPAMWQ") -> {
                    // Simulate deleted/unavailable channel 404
                    Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(404)
                        .message("Not Found")
                        .body("".toResponseBody("text/plain".toMediaType()))
                        .build()
                }
                else -> {
                    Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(sampleAtomFeed("UC3vHW2h22WE-pNi5WJtRIjg", "Yaqeen", emptyList()).toResponseBody("application/atom+xml".toMediaType()))
                        .build()
                }
            }
        }

        val onlineResult = YouTubeRepository.fetchVerifiedChannelFeeds(context, forceRefresh = true)
        assertEquals(FeedLoadStatus.ONLINE_FRESH, onlineResult.status)
        assertEquals(1, onlineResult.videos.size)
        assertTrue("Unavailable 404 channel must be surfaced honestly", onlineResult.unavailableChannels.contains("@muftimenkofficial"))

        // Switch offline: must still return cached lecture metadata honestly without hitting network
        YouTubeRepository.clearTestCaches()
        YouTubeRepository.networkChecker = { false }
        val offlineResult = YouTubeRepository.fetchVerifiedChannelFeeds(context, forceRefresh = false)
        assertEquals(FeedLoadStatus.OFFLINE_CACHE, offlineResult.status)
        assertEquals(1, offlineResult.videos.size)
        assertTrue(offlineResult.videos[0].isFromCache)
    }

    @Test
    fun testHonestLocalSearchReturnsEmptyWhenNoMatchInsteadOfFakeFallback() {
        val sampleItems = YouTubeRepository.parseAtomFeedXml(
            sampleAtomFeed(
                "UCO153Mjc-mCLmRriP24SkOA",
                "Bayyinah",
                listOf(
                    Triple("vidAAA11111", "Seerah of the Prophet Muhammad", "2026-04-01T10:00:00Z"),
                    Triple("vidBBB22222", "Quran Tafseer Surah Yusuf", "2026-04-02T10:00:00Z")
                )
            ),
            YouTubeRepository.VERIFIED_CHANNELS.first { it.key == "bayyinah" }
        )

        val seerahHits = YouTubeRepository.searchLocalVerifiedLectures(sampleItems, query = "Seerah")
        assertEquals(1, seerahHits.size)
        assertEquals("vidAAA11111", seerahHits[0].id)

        val noHits = YouTubeRepository.searchLocalVerifiedLectures(sampleItems, query = "UnrelatedQuantumPhysicsQueryXYZ")
        assertTrue("Search with zero matches must return empty list (never substitute fake lectures)", noHits.isEmpty())
    }

    @Test
    fun testHarameynLiveVerificationDoesNotFabricateLiveWhenOfflineOrInactive() = runBlocking {
        // 1. Offline check
        YouTubeRepository.networkChecker = { false }
        val offlineMakkah = YouTubeRepository.checkHarameynLiveStatus(context, HarameynLocation.MAKKAH)
        assertEquals(LiveBroadcastState.OFFLINE, offlineMakkah.state)
        assertNull(offlineMakkah.verifiedVideoId)

        // 2. Online, channel returns HTML without active live metadata -> NO_ACTIVE_BROADCAST
        YouTubeRepository.networkChecker = { true }
        YouTubeRepository.httpClient = buildMockClient { req ->
            Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("<html><head><title>Channel</title></head><body></body></html>".toResponseBody("text/html".toMediaType()))
                .build()
        }
        val inactiveMadinah = YouTubeRepository.checkHarameynLiveStatus(context, HarameynLocation.MADINAH)
        assertEquals(LiveBroadcastState.NO_ACTIVE_BROADCAST, inactiveMadinah.state)
        assertNull("Inactive channel must not emit a fake live videoId", inactiveMadinah.verifiedVideoId)

        // 3. Online with confirmed isLiveBroadcast=True + canonical watch video + valid oEmbed -> LIVE_CONFIRMED
        YouTubeRepository.httpClient = buildMockClient { req ->
            val url = req.url.toString()
            if (url.contains("/live")) {
                val html = """
                    <html><head>
                    <link rel="canonical" href="https://www.youtube.com/watch?v=live1234567"/>
                    <meta itemprop="isLiveBroadcast" content="True"/>
                    </head></html>
                """.trimIndent()
                Response.Builder()
                    .request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(html.toResponseBody("text/html".toMediaType())).build()
            } else {
                Response.Builder()
                    .request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body("""{"title":"Makkah Live Official","provider_name":"YouTube"}""".toResponseBody("application/json".toMediaType())).build()
            }
        }
        val liveMakkah = YouTubeRepository.checkHarameynLiveStatus(context, HarameynLocation.MAKKAH)
        assertEquals(LiveBroadcastState.LIVE_CONFIRMED, liveMakkah.state)
        assertEquals("live1234567", liveMakkah.verifiedVideoId)
        assertTrue(liveMakkah.isEmbeddableConfirmed)
    }

    @Test
    fun testFavoritesAndRecentlyWatchedPersistence() {
        val sample = YouTubeRepository.parseAtomFeedXml(
            sampleAtomFeed(
                "UCO153Mjc-mCLmRriP24SkOA",
                "Bayyinah",
                listOf(Triple("favVid12345", "Patience in Trials", "2026-04-05T10:00:00Z"))
            ),
            null
        ).first()

        assertTrue(YouTubeRepository.toggleFavoriteLecture(context, sample))
        assertEquals(1, YouTubeRepository.getFavoriteLectures(context).size)
        assertFalse(YouTubeRepository.toggleFavoriteLecture(context, sample))
        assertTrue(YouTubeRepository.getFavoriteLectures(context).isEmpty())

        YouTubeRepository.recordWatchedLecture(context, sample)
        val recent = YouTubeRepository.getRecentlyWatchedLectures(context)
        assertEquals(1, recent.size)
        assertEquals("favVid12345", recent[0].id)
    }

    @Test
    fun testIFramePlayerHtmlFixesError153WithHttpsOriginAndReferrerPolicy() {
        val html = YouTubeIFramePlayerConfig.buildIFrameApiHtml("abc12345678", autoplay = true)
        assertTrue("Must load official YouTube IFrame API script", html.contains("https://www.youtube.com/iframe_api"))
        assertTrue("Must configure strict-origin-when-cross-origin", html.contains("strict-origin-when-cross-origin"))
        assertTrue("Must supply HTTPS origin matching loadDataWithBaseURL", html.contains("origin: '${YouTubeIFramePlayerConfig.APP_EMBED_ORIGIN}'"))
        assertTrue("Must preserve native YouTube controls", html.contains("controls: 1"))

        val err153 = YouTubeIFramePlayerConfig.formatErrorMessage(153, isNetworkError = false, isRendererCrash = false, AppLanguage.ENGLISH)
        assertTrue(err153.contains("153"))
        val err150 = YouTubeIFramePlayerConfig.formatErrorMessage(150, isNetworkError = false, isRendererCrash = false, AppLanguage.URDU)
        assertTrue(err150.contains("150"))
    }

    @Test
    fun testFullscreenControllerEntersLandscapeHidesViewAndRestoresOnExit() {
        val activity = Robolectric.buildActivity(Activity::class.java).create().start().resume().get()
        val host = FrameLayout(activity)
        val controller = FullscreenVideoController()
        controller.attachHost(activity, host)

        var callbackInvoked = false
        val fakeCustomView = View(activity)
        val callback = WebChromeClient.CustomViewCallback { callbackInvoked = true }

        controller.enterFullscreen(fakeCustomView, callback, activity)
        assertTrue(controller.isFullscreen)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, activity.requestedOrientation)
        assertEquals(View.VISIBLE, host.visibility)
        assertEquals(1, host.childCount)

        assertTrue(controller.exitFullscreen())
        assertFalse(controller.isFullscreen)
        assertTrue("CustomViewCallback.onCustomViewHidden must be called on exit", callbackInvoked)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, activity.requestedOrientation)
        assertEquals(View.GONE, host.visibility)
        assertEquals(0, host.childCount)
    }

    @Test
    fun testAll12CategoriesAndNewKeysLocalizedInAll4Languages() {
        val keys = LectureCategory.entries.map { it.labelKey } + listOf(
            "lectures_banner_tag",
            "lectures_header_title",
            "lectures_search_hint",
            "lectures_saved_favorites",
            "lectures_recently_watched",
            "lectures_offline_cache_banner",
            "lectures_empty_search_title",
            "live_tab_makkah",
            "live_tab_madinah",
            "live_status_no_active",
            "live_status_unavailable",
            "player_error_153"
        )
        assertEquals("Must cover all 12 categories", 12, LectureCategory.entries.size)

        keys.forEach { key ->
            val en = AppLocalization.get(key, AppLanguage.ENGLISH)
            val ur = AppLocalization.get(key, AppLanguage.URDU)
            val ar = AppLocalization.get(key, AppLanguage.ARABIC)
            val hi = AppLocalization.get(key, AppLanguage.HINDI)

            assertTrue("EN key '$key' missing", en != key && en.isNotBlank())
            assertTrue("UR key '$key' missing", ur != key && ur.isNotBlank())
            assertTrue("AR key '$key' missing", ar != key && ar.isNotBlank())
            assertTrue("HI key '$key' missing", hi != key && hi.isNotBlank())
        }
    }
}
