package com.example.data.repository

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.widget.Toast
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

enum class AudioPlaybackSource {
    NONE,
    BUNDLED_ASSET,
    OFFLINE_FILE,
    CACHE_FILE,
    NETWORK_DOWNLOADED
}

object QuranAudioManager {

    private val playerLock = Any()
    private val playbackSessionId = AtomicLong(0L)
    private var mediaPlayer: MediaPlayer? = null
    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private var isInitializingTts = false
    private var pendingSpeechText: String? = null
    private var pendingTransliteration: String? = null
    private var toneGenerator: ToneGenerator? = null

    private var activeJob: Job? = null
    private val audioScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Reactive Compose state for UI observers
    val isAudioPlaying = mutableStateOf(false)
    val currentAudioTitle = mutableStateOf("")
    val currentPlayingKey = mutableStateOf<String?>(null) // e.g. "surah_1_mishary", "ayah_1_2_mishary", "azan_makkah"
    val currentPlayingSurahNumber = mutableStateOf<Int?>(null)
    val currentPlayingAyahNumber = mutableStateOf<Int?>(null)
    val isBuffering = mutableStateOf(false)
    val playbackSpeed = mutableFloatStateOf(1.0f)
    val currentLoopCount = mutableIntStateOf(0)
    val targetLoopCount = mutableIntStateOf(1)
    val audioErrorMessage = mutableStateOf<String?>(null)
    val lastPlaybackSource = mutableStateOf(AudioPlaybackSource.NONE)

    internal val networkRequestCountForTest = AtomicLong(0L)

    fun clearAudioError() {
        audioErrorMessage.value = null
    }

    fun init(context: Context) {
        try {
            playbackSpeed.floatValue = UserPreferencesRepository.getSavedAudioSpeed(context)
            targetLoopCount.intValue = UserPreferencesRepository.getSavedAyahRepeatCount(context)
            QuranOfflineSyncManager.startAutoSync(context)
        } catch (_: Exception) {}

        if (tts == null && !isInitializingTts) {
            isInitializingTts = true
            try {
                tts = TextToSpeech(context.applicationContext) { status ->
                    isInitializingTts = false
                    if (status == TextToSpeech.SUCCESS) {
                        val arLocale = Locale("ar")
                        val result = tts?.setLanguage(arLocale)
                        isTtsReady = (result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED)
                        if (!isTtsReady) {
                            tts?.setLanguage(Locale.getDefault())
                            isTtsReady = true
                        }
                        pendingSpeechText?.let { text ->
                            val trans = pendingTransliteration ?: ""
                            pendingSpeechText = null
                            pendingTransliteration = null
                            speakDirectly(context, text, trans)
                        }
                    } else {
                        isTtsReady = false
                    }
                }
            } catch (_: Exception) {
                isInitializingTts = false
                isTtsReady = false
            }
        }
        if (toneGenerator == null) {
            try {
                toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 70)
            } catch (_: Exception) {}
        }
    }

    /**
     * Pronounce an isolated Arabic alphabet letter or Qaida word (NEVER used as a fallback for Quran recitation).
     */
    fun speakArabic(context: Context, text: String, transliteration: String = "") {
        stopMedia()
        currentPlayingKey.value = "letter_$text"

        if (tts == null || !isTtsReady) {
            pendingSpeechText = text
            pendingTransliteration = transliteration
            init(context)
            if (!isTtsReady) {
                playTone()
                Toast.makeText(context, "🔊 $text ($transliteration)", Toast.LENGTH_SHORT).show()
                return
            }
        }

        speakDirectly(context, text, transliteration)
    }

    private fun speakDirectly(context: Context, text: String, transliteration: String) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val params = Bundle()
                params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "quran_word_$text")
            } else {
                @Suppress("DEPRECATION")
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null)
            }
            playTone()
            Toast.makeText(context, "🔊 $text ($transliteration)", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            playTone()
            Toast.makeText(context, "🔊 Pronunciation: $transliteration", Toast.LENGTH_SHORT).show()
        }
    }

    private fun playTone() {
        try {
            toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 80)
        } catch (_: Exception) {}
    }

    /**
     * Play Melodious Azan Alarm Audio (Makkah, Madinah, Fajr).
     */
    fun playAzan(
        context: Context,
        azanType: String = "makkah",
        useAlarmUsage: Boolean = false,
        onCompletion: () -> Unit = {}
    ) {
        if (azanType.equals("silent", ignoreCase = true)) {
            stopMedia()
            onCompletion()
            return
        }
        stopMedia()
        audioErrorMessage.value = null
        val sessionId = playbackSessionId.incrementAndGet()
        isBuffering.value = true

        val azanTitle = when (azanType.lowercase()) {
            "madina", "madinah" -> "Azan Madinah Munawwarah (مسجد نبوی)"
            "fajr" -> "Fajr Azan (الصلاة خير من النوم)"
            else -> "Azan Makkah Mukarramah (حرم مکی)"
        }
        currentAudioTitle.value = azanTitle
        val key = "azan_${azanType.lowercase()}"
        currentPlayingKey.value = key

        fetchAndPlay(
            context = context.applicationContext,
            sessionId = sessionId,
            key = key,
            candidateUrls = QuranAudioSourceCatalog.getAzanCandidateUrls(azanType),
            minBytes = QuranAudioSourceCatalog.MIN_AZAN_BYTES,
            speed = 1.0f,
            repeatTarget = 1,
            useAlarmUsage = useAlarmUsage,
            offlineMissingMessage = "Azan audio is not available offline.",
            onCompletion = onCompletion,
            onErrorFallback = {
                try {
                    val alertUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                        ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                    val r = RingtoneManager.getRingtone(context.applicationContext, alertUri)
                    r?.play()
                } catch (_: Exception) {}
                onCompletion()
            }
        )
    }

    /**
     * Play full Surah recitation (Surahs 1..114, exact Surah + reciter mapping).
     * Strict offline-first priority:
     * 1. Valid bundled asset
     * 2. Valid downloaded offline MP3 in internal persistent storage
     * 3. Valid cached MP3
     * 4. Verified network download (only when online; never falls back to TTS or wrong reciter)
     */
    fun playSurah(
        context: Context,
        surahNumber: Int,
        surahName: String,
        reciterId: String = "mishary",
        onCompletion: () -> Unit = {}
    ) {
        stopMedia()
        audioErrorMessage.value = null
        lastPlaybackSource.value = AudioPlaybackSource.NONE

        if (surahNumber !in 1..114) {
            audioErrorMessage.value = "Invalid Surah number: $surahNumber"
            onCompletion()
            return
        }

        val normReciter = QuranAudioSourceCatalog.normalizeReciterId(reciterId)
        val sessionId = playbackSessionId.incrementAndGet()
        isBuffering.value = true
        currentPlayingSurahNumber.value = surahNumber
        currentPlayingAyahNumber.value = null
        currentAudioTitle.value = "Surah $surahNumber: $surahName"
        val key = QuranOfflineSyncManager.surahKey(surahNumber, normReciter)
        currentPlayingKey.value = key

        val candidates = QuranOfflineSyncManager.candidateUrlsForSurah(surahNumber, normReciter)
        val minBytes = QuranOfflineSyncManager.requiredMinBytesForSurah(surahNumber)

        val savedSpeed = UserPreferencesRepository.getSavedAudioSpeed(context)
        playbackSpeed.floatValue = savedSpeed
        targetLoopCount.intValue = 1

        fetchAndPlay(
            context = context.applicationContext,
            sessionId = sessionId,
            key = key,
            candidateUrls = candidates,
            minBytes = minBytes,
            speed = savedSpeed,
            repeatTarget = 1,
            useAlarmUsage = false,
            offlineMissingMessage = "This Surah has not been downloaded for offline playback.",
            onCompletion = onCompletion
        )
    }

    /**
     * Play individual Ayah recitation with repeat loop and speed options (same-reciter-only mirrors).
     */
    fun playAyah(
        context: Context,
        surahNumber: Int,
        ayahNumber: Int,
        reciterId: String = "mishary",
        speed: Float = UserPreferencesRepository.getSavedAudioSpeed(context),
        repeatTarget: Int = UserPreferencesRepository.getSavedAyahRepeatCount(context),
        onCompletion: () -> Unit = {}
    ) {
        stopMedia()
        audioErrorMessage.value = null
        lastPlaybackSource.value = AudioPlaybackSource.NONE

        val normReciter = QuranAudioSourceCatalog.normalizeReciterId(reciterId)
        val sessionId = playbackSessionId.incrementAndGet()
        isBuffering.value = true
        currentPlayingSurahNumber.value = surahNumber
        currentPlayingAyahNumber.value = ayahNumber
        playbackSpeed.floatValue = speed
        targetLoopCount.intValue = repeatTarget
        currentLoopCount.intValue = 0
        val key = "ayah_${surahNumber}_${ayahNumber}_$normReciter"
        currentPlayingKey.value = key
        currentAudioTitle.value = "Ayah $surahNumber:$ayahNumber"

        val candidates = QuranAudioSourceCatalog.getAyahCandidateUrls(surahNumber, ayahNumber, normReciter)

        fetchAndPlay(
            context = context.applicationContext,
            sessionId = sessionId,
            key = key,
            candidateUrls = candidates,
            minBytes = QuranAudioSourceCatalog.MIN_AYAH_BYTES,
            speed = speed,
            repeatTarget = repeatTarget,
            useAlarmUsage = false,
            offlineMissingMessage = "This Ayah has not been downloaded for offline playback.",
            onCompletion = onCompletion
        )
    }

    private fun fetchAndPlay(
        context: Context,
        sessionId: Long,
        key: String,
        candidateUrls: List<String>,
        minBytes: Long,
        speed: Float,
        repeatTarget: Int,
        useAlarmUsage: Boolean,
        offlineMissingMessage: String,
        onCompletion: () -> Unit,
        onErrorFallback: (() -> Unit)? = null
    ) {
        // Priority 1: Valid Bundled Asset Audio (100% Offline, Zero Network needed)
        val bundledAfd = tryOpenValidBundledAsset(context, key)
        if (bundledAfd != null) {
            lastPlaybackSource.value = AudioPlaybackSource.BUNDLED_ASSET
            playAssetFile(sessionId, bundledAfd, key, speed, repeatTarget, useAlarmUsage, onCompletion)
            return
        }

        // Priority 2: Valid Persistent Offline Storage MP3
        val offlineDir = QuranOfflineSyncManager.getOfflineStorageDir(context)
        val offlineFile = File(offlineDir, "$key.mp3")
        if (QuranAudioSourceCatalog.isValidMp3File(offlineFile, minBytes)) {
            lastPlaybackSource.value = AudioPlaybackSource.OFFLINE_FILE
            playLocalFile(sessionId, offlineFile, key, speed, repeatTarget, useAlarmUsage, onCompletion)
            return
        } else if (offlineFile.exists()) {
            // Corrupt/incomplete file must never be played
            runCatching { offlineFile.delete() }
        }

        // Priority 3: Valid Cache Directory MP3
        val cacheDir = File(context.cacheDir, "quran_audio").apply { if (!exists()) mkdirs() }
        val cacheFile = File(cacheDir, "$key.mp3")
        if (QuranAudioSourceCatalog.isValidMp3File(cacheFile, minBytes)) {
            lastPlaybackSource.value = AudioPlaybackSource.CACHE_FILE
            playLocalFile(sessionId, cacheFile, key, speed, repeatTarget, useAlarmUsage, onCompletion)
            return
        } else if (cacheFile.exists()) {
            runCatching { cacheFile.delete() }
        }

        // Offline Check BEFORE attempting any network connection
        if (!QuranAudioSourceCatalog.isNetworkAvailable(context)) {
            isBuffering.value = false
            isAudioPlaying.value = false
            currentPlayingKey.value = null
            currentPlayingSurahNumber.value = null
            currentPlayingAyahNumber.value = null
            audioErrorMessage.value = offlineMissingMessage
            lastPlaybackSource.value = AudioPlaybackSource.NONE
            onErrorFallback?.invoke() ?: onCompletion()
            return
        }

        if (candidateUrls.isEmpty()) {
            isBuffering.value = false
            isAudioPlaying.value = false
            currentPlayingKey.value = null
            currentPlayingSurahNumber.value = null
            currentPlayingAyahNumber.value = null
            audioErrorMessage.value = "Audio source unavailable for this reciter."
            onErrorFallback?.invoke() ?: onCompletion()
            return
        }

        // Priority 4: Verified Network Download -> Persistent Offline File
        activeJob = audioScope.launch {
            networkRequestCountForTest.incrementAndGet()
            val downloadResult = withContext(Dispatchers.IO) {
                QuranOfflineSyncManager.downloadFromCandidatesSync(
                    callKey = "play_$key",
                    candidates = candidateUrls,
                    targetFile = offlineFile,
                    minBytes = minBytes,
                    resumeExistingTmp = false,
                    deleteTmpOnFail = true
                )
            }

            if (playbackSessionId.get() != sessionId || currentPlayingKey.value != key) {
                return@launch
            }

            downloadResult.fold(
                onSuccess = { downloadedFile ->
                    QuranOfflineSyncManager.refreshOfflineIndex(context)
                    lastPlaybackSource.value = AudioPlaybackSource.NETWORK_DOWNLOADED
                    playLocalFile(sessionId, downloadedFile, key, speed, repeatTarget, useAlarmUsage, onCompletion)
                },
                onFailure = { error ->
                    isBuffering.value = false
                    isAudioPlaying.value = false
                    currentPlayingKey.value = null
                    currentPlayingSurahNumber.value = null
                    currentPlayingAyahNumber.value = null
                    audioErrorMessage.value = error.message ?: "Unable to stream or download recitation audio."
                    onErrorFallback?.invoke() ?: onCompletion()
                }
            )
        }
    }

    private fun tryOpenValidBundledAsset(context: Context, key: String): AssetFileDescriptor? {
        return try {
            val afd = context.assets.openFd("audio/$key.mp3")
            if (afd.length > 0 || afd.declaredLength > 0) {
                afd
            } else {
                runCatching { afd.close() }
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun playLocalFile(
        sessionId: Long,
        file: File,
        key: String,
        speed: Float,
        repeatTarget: Int,
        useAlarmUsage: Boolean,
        onCompletion: () -> Unit
    ) {
        synchronized(playerLock) {
            if (playbackSessionId.get() != sessionId) return
            try {
                releasePlayerLocked()
                mediaPlayer = MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setContentType(if (useAlarmUsage) AudioAttributes.CONTENT_TYPE_SONIFICATION else AudioAttributes.CONTENT_TYPE_MUSIC)
                            .setUsage(if (useAlarmUsage) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_MEDIA)
                            .build()
                    )
                    setDataSource(file.absolutePath)
                    configurePlayerCallbacks(this, sessionId, key, speed, repeatTarget, onCompletion)
                    prepareAsync()
                }
            } catch (e: Exception) {
                resetUiPlaybackStateIfSessionMatches(sessionId, error = "Audio playback failed: ${e.message}")
                onCompletion()
            }
        }
    }

    private fun playAssetFile(
        sessionId: Long,
        afd: AssetFileDescriptor,
        key: String,
        speed: Float,
        repeatTarget: Int,
        useAlarmUsage: Boolean,
        onCompletion: () -> Unit
    ) {
        synchronized(playerLock) {
            if (playbackSessionId.get() != sessionId) {
                runCatching { afd.close() }
                return
            }
            try {
                releasePlayerLocked()
                mediaPlayer = MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setContentType(if (useAlarmUsage) AudioAttributes.CONTENT_TYPE_SONIFICATION else AudioAttributes.CONTENT_TYPE_MUSIC)
                            .setUsage(if (useAlarmUsage) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_MEDIA)
                            .build()
                    )
                    try {
                        setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                    } finally {
                        runCatching { afd.close() }
                    }
                    configurePlayerCallbacks(this, sessionId, key, speed, repeatTarget, onCompletion)
                    prepareAsync()
                }
            } catch (e: Exception) {
                runCatching { afd.close() }
                resetUiPlaybackStateIfSessionMatches(sessionId, error = "Asset playback failed: ${e.message}")
                onCompletion()
            }
        }
    }

    private fun configurePlayerCallbacks(
        player: MediaPlayer,
        sessionId: Long,
        key: String,
        speed: Float,
        repeatTarget: Int,
        onCompletion: () -> Unit
    ) {
        player.setOnPreparedListener { mp ->
            synchronized(playerLock) {
                if (playbackSessionId.get() != sessionId || mediaPlayer !== mp) {
                    runCatching { mp.release() }
                    return@setOnPreparedListener
                }
                isBuffering.value = false
                isAudioPlaying.value = true
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && speed != 1.0f) {
                    try {
                        mp.playbackParams = mp.playbackParams.setSpeed(speed)
                    } catch (_: Exception) {}
                }
                mp.start()
            }
        }

        player.setOnCompletionListener { mp ->
            synchronized(playerLock) {
                if (playbackSessionId.get() != sessionId || mediaPlayer !== mp) {
                    return@setOnCompletionListener
                }
                currentLoopCount.intValue++
                if (currentLoopCount.intValue < repeatTarget) {
                    try {
                        mp.seekTo(0)
                        mp.start()
                    } catch (_: Exception) {
                        releasePlayerLocked()
                        resetUiPlaybackStateIfSessionMatches(sessionId, error = null)
                        onCompletion()
                    }
                } else {
                    releasePlayerLocked()
                    resetUiPlaybackStateIfSessionMatches(sessionId, error = null)
                    onCompletion()
                }
            }
        }

        player.setOnErrorListener { _, what, extra ->
            synchronized(playerLock) {
                if (playbackSessionId.get() == sessionId) {
                    releasePlayerLocked()
                    resetUiPlaybackStateIfSessionMatches(
                        sessionId,
                        error = "Audio playback error ($what/$extra) for $key"
                    )
                    onCompletion()
                }
            }
            true
        }
    }

    private fun releasePlayerLocked() {
        mediaPlayer?.let { mp ->
            runCatching {
                if (mp.isPlaying) mp.stop()
            }
            runCatching { mp.release() }
        }
        mediaPlayer = null
    }

    private fun resetUiPlaybackStateIfSessionMatches(sessionId: Long, error: String?) {
        if (playbackSessionId.get() == sessionId) {
            isBuffering.value = false
            isAudioPlaying.value = false
            currentPlayingKey.value = null
            currentPlayingSurahNumber.value = null
            currentPlayingAyahNumber.value = null
            currentLoopCount.intValue = 0
            if (error != null) {
                audioErrorMessage.value = error
            }
        }
    }

    fun togglePlayPause() {
        synchronized(playerLock) {
            mediaPlayer?.let { mp ->
                try {
                    if (mp.isPlaying) {
                        mp.pause()
                        isAudioPlaying.value = false
                    } else {
                        mp.start()
                        isAudioPlaying.value = true
                    }
                } catch (_: Exception) {}
            }
        }
    }

    fun stopIfPlayingKey(key: String) {
        if (currentPlayingKey.value == key) {
            stopMedia()
        }
    }

    fun stopMedia() {
        playbackSessionId.incrementAndGet()
        try {
            activeJob?.cancel()
            activeJob = null
        } catch (_: Exception) {}

        synchronized(playerLock) {
            releasePlayerLocked()
        }
        isAudioPlaying.value = false
        isBuffering.value = false
        currentPlayingKey.value = null
        currentPlayingSurahNumber.value = null
        currentPlayingAyahNumber.value = null
        currentLoopCount.intValue = 0
    }
}
