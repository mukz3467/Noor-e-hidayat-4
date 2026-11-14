package com.example.data.notification

import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.data.repository.QuranAudioManager
import com.example.data.repository.UserPreferencesRepository
import com.example.localization.AppLocalization

class AzanPlaybackService : Service() {

    companion object {
        const val ACTION_START_AZAN = "com.example.ACTION_START_AZAN"
        const val ACTION_STOP_SERVICE = "com.example.ACTION_STOP_SERVICE"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SERVICE || intent?.action == PrayerAlarmReceiver.ACTION_STOP_AZAN) {
            stopAzanAndService()
            return START_NOT_STICKY
        }

        PrayerNotificationManager.init(applicationContext)
        val prayerTitle = intent?.getStringExtra(PrayerAlarmReceiver.EXTRA_PRAYER_TITLE) ?: "Prayer"
        val prayerTime = intent?.getStringExtra(PrayerAlarmReceiver.EXTRA_PRAYER_TIME) ?: ""
        val adhanSound = intent?.getStringExtra(PrayerAlarmReceiver.EXTRA_ADHAN_SOUND) ?: "makkah"

        val lang = UserPreferencesRepository(applicationContext).language.value
        val stopLabel = AppLocalization.get("azan_stop_notif_action", lang)

        val openIntent = Intent(applicationContext, MainActivity::class.java).apply {
            this.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPi = PendingIntent.getActivity(
            applicationContext,
            10,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(applicationContext, PrayerAlarmReceiver::class.java).apply {
            action = PrayerAlarmReceiver.ACTION_STOP_AZAN
        }
        val stopPi = PendingIntent.getBroadcast(
            applicationContext,
            990003,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notif = NotificationCompat.Builder(applicationContext, PrayerNotificationManager.CHANNEL_ID_NOVIB)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("🕌 Adhan: $prayerTitle ($prayerTime)")
            .setContentText("Playing $adhanSound Adhan")
            .setOngoing(true)
            .setSilent(true)
            .setSound(null)
            .setContentIntent(openPi)
            .addAction(android.R.drawable.ic_media_pause, stopLabel, stopPi)
            .build()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    PrayerNotificationManager.NOTIF_ID_SERVICE,
                    notif,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } else {
                startForeground(PrayerNotificationManager.NOTIF_ID_SERVICE, notif)
            }
        } catch (_: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (adhanSound == "silent") {
            stopAzanAndService()
            return START_NOT_STICKY
        }

        QuranAudioManager.playAzan(
            context = applicationContext,
            azanType = adhanSound,
            useAlarmUsage = true,
            onCompletion = {
                stopAzanAndService()
            }
        )

        return START_NOT_STICKY
    }

    private fun stopAzanAndService() {
        QuranAudioManager.stopMedia()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        } else {
            @Suppress("DEPRECATION")
            runCatching { stopForeground(true) }
        }
        stopSelf()
    }

    override fun onDestroy() {
        QuranAudioManager.stopMedia()
        super.onDestroy()
    }
}
