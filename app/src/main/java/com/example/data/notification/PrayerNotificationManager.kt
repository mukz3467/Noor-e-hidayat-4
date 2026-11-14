package com.example.data.notification

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.data.location.AppLocationManager
import com.example.data.repository.PrayerCalculator
import com.example.data.repository.QuranAudioManager
import java.util.Calendar
import java.util.TimeZone
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PrayerNotificationSettings(
    val fajrEnabled: Boolean = true,
    val dhuhrEnabled: Boolean = true,
    val asrEnabled: Boolean = true,
    val maghribEnabled: Boolean = true,
    val ishaEnabled: Boolean = true,
    val adhanSound: String = "makkah",
    val vibrationEnabled: Boolean = true,
    val leadTimeMinutes: Int = 0
)

data class PrayerAlarmEntry(val id: String, val prayerName: String, val prayerTimeMillis: Long)

object PrayerNotificationManager {
    const val CHANNEL_ID = "noor_prayer_channel"
    const val CHANNEL_SILENT_ID = "noor_prayer_silent_channel"
    const val CHANNEL_ID_NOVIB = CHANNEL_SILENT_ID
    const val NOTIF_ID_SERVICE = 202
    private const val PREFS_NAME = "prayer_notif_prefs"
    private const val KEY_SCHEDULED = "scheduled_prayer_alarms"

    private val _settings = MutableStateFlow(PrayerNotificationSettings())
    val settings: StateFlow<PrayerNotificationSettings> = _settings.asStateFlow()
    private var isInitialized = false

    fun init(context: Context) {
        if (isInitialized) return
        isInitialized = true
        loadSettings(context)
        createNotificationChannels(context)
        restoreScheduledAlarms(context)
    }

    private fun createNotificationChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Prayer alarms", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Prayer-time alerts with device alarm sound"
                    setSound(alarmSound, attrs)
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 500, 250, 500)
                }
            )
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_SILENT_ID, "Silent prayer reminders", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Prayer-time alerts without sound"
                    setSound(null, null)
                    enableVibration(true)
                }
            )
        }
    }

    fun hasNotificationPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun canUseExactAlarms(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()

    fun stopAzanAudio(context: Context) {
        QuranAudioManager.stopMedia()
        runCatching {
            context.stopService(Intent(context, AzanPlaybackService::class.java))
            context.stopService(Intent(context, AdhanPlaybackService::class.java))
        }
    }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) true
        else (context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager)
            .isIgnoringBatteryOptimizations(context.packageName)

    fun openExactAlarmSettings(context: Context) = requestExactAlarmPermission(context)

    fun openBatteryOptimizationSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }
        }
    }

    fun requestExactAlarmPermission(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !canUseExactAlarms(context)) {
            try {
                context.startActivity(
                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + context.packageName))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: Exception) {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }

    private fun loadSettings(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _settings.value = PrayerNotificationSettings(
            fajrEnabled = prefs.getBoolean("fajr_enabled", true),
            dhuhrEnabled = prefs.getBoolean("dhuhr_enabled", true),
            asrEnabled = prefs.getBoolean("asr_enabled", true),
            maghribEnabled = prefs.getBoolean("maghrib_enabled", true),
            ishaEnabled = prefs.getBoolean("isha_enabled", true),
            adhanSound = prefs.getString("adhan_sound", "makkah") ?: "makkah",
            vibrationEnabled = prefs.getBoolean("vibration_enabled", true),
            leadTimeMinutes = prefs.getInt("lead_time_minutes", 0).coerceIn(0, 60)
        )
    }

    fun updateSettings(context: Context, newSettings: PrayerNotificationSettings) {
        _settings.value = newSettings.copy(leadTimeMinutes = newSettings.leadTimeMinutes.coerceIn(0, 60))
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            putBoolean("fajr_enabled", _settings.value.fajrEnabled)
            putBoolean("dhuhr_enabled", _settings.value.dhuhrEnabled)
            putBoolean("asr_enabled", _settings.value.asrEnabled)
            putBoolean("maghrib_enabled", _settings.value.maghribEnabled)
            putBoolean("isha_enabled", _settings.value.ishaEnabled)
            putString("adhan_sound", _settings.value.adhanSound)
            putBoolean("vibration_enabled", _settings.value.vibrationEnabled)
            putInt("lead_time_minutes", _settings.value.leadTimeMinutes)
            apply()
        }
        restoreScheduledAlarms(context)
    }

    fun scheduleAlarms(context: Context, entries: List<PrayerAlarmEntry>) {
        init(context)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        readEntries(prefs).forEach { cancelAlarm(context, it.id) }
        val future = entries.filter {
            it.prayerTimeMillis - _settings.value.leadTimeMinutes * 60_000L > System.currentTimeMillis()
        }
        saveEntries(prefs, future)
        scheduleEntries(context, future)
    }

    /** Compatibility entry point used by app startup and settings. */
    fun canScheduleExactAlarms(context: Context): Boolean = canUseExactAlarms(context)

    fun cancelAllAlarms(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        readEntries(prefs).forEach { cancelAlarm(context, it.id) }
        saveEntries(prefs, emptyList())
    }

    fun rescheduleAllAlarms(context: Context, reason: String = "settings_changed") {
        scheduleNextRollingWindow(context, reason)
    }

    /**
     * Keep a rolling 8-day set of local prayer alarms. Times are calculated from the
     * currently selected location and prayer calculation preferences, not hard-coded.
     */
    fun scheduleNextRollingWindow(context: Context, reason: String = "refresh") {
        init(context)
        AppLocationManager.init(context)
        PrayerCalculator.init(context)
        val location = AppLocationManager.currentLocation.value
        val config = PrayerCalculator.calcConfig.value
        val entries = mutableListOf<PrayerAlarmEntry>()
        val base = Calendar.getInstance(TimeZone.getTimeZone(location.timeZoneId))
        for (dayOffset in 0..7) {
            val day = (base.clone() as Calendar).apply {
                add(Calendar.DAY_OF_MONTH, dayOffset)
            }
            val schedule = PrayerCalculator.calculatePrayerTimes(
                latitude = location.latitude,
                longitude = location.longitude,
                calendar = day,
                method = config.method,
                asrJuristic = config.asrJuristic,
                adjustments = config.adjustments,
                highLatitudeRule = config.highLatitudeRule,
                cityName = location.cityName,
                timeZoneId = location.timeZoneId,
                customParams = config.customParams
            )
            val prayers = listOf(
                "fajr" to schedule.fajr,
                "dhuhr" to schedule.dhuhr,
                "asr" to schedule.asr,
                "maghrib" to schedule.maghrib,
                "isha" to schedule.isha
            )
            prayers.forEach { (key, formatted) ->
                val trigger = PrayerCalculator.computePrayerTriggerTimeMillis(formatted, day, key)
                if (trigger != null && trigger > System.currentTimeMillis()) {
                    val dateKey = "%04d%02d%02d".format(
                        day.get(Calendar.YEAR),
                        day.get(Calendar.MONTH) + 1,
                        day.get(Calendar.DAY_OF_MONTH)
                    )
                    entries += PrayerAlarmEntry("${dateKey}_${key}", key, trigger)
                }
            }
        }
        scheduleAlarms(context, entries)
    }

    fun restoreScheduledAlarms(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = readEntries(prefs)
        saved.forEach { cancelAlarm(context, it.id) }
        val future = saved.filter {
            it.prayerTimeMillis - _settings.value.leadTimeMinutes * 60_000L > System.currentTimeMillis()
        }
        saveEntries(prefs, future)
        scheduleEntries(context, future)
    }

    private fun readEntries(prefs: SharedPreferences): List<PrayerAlarmEntry> = try {
        val array = JSONArray(prefs.getString(KEY_SCHEDULED, "[]") ?: "[]")
        (0 until array.length()).mapNotNull { i ->
            val item = array.optJSONObject(i) ?: return@mapNotNull null
            PrayerAlarmEntry(item.optString("id"), item.optString("name"), item.optLong("time"))
        }
    } catch (_: Exception) { emptyList() }

    private fun saveEntries(prefs: SharedPreferences, entries: List<PrayerAlarmEntry>) {
        val json = JSONArray()
        entries.forEach { json.put(JSONObject().put("id", it.id).put("name", it.prayerName).put("time", it.prayerTimeMillis)) }
        prefs.edit().putString(KEY_SCHEDULED, json.toString()).apply()
    }

    private fun isEnabled(name: String): Boolean = when (name.lowercase()) {
        "fajr" -> _settings.value.fajrEnabled
        "dhuhr" -> _settings.value.dhuhrEnabled
        "asr" -> _settings.value.asrEnabled
        "maghrib" -> _settings.value.maghribEnabled
        "isha" -> _settings.value.ishaEnabled
        else -> false
    }

    private fun scheduleEntries(context: Context, entries: List<PrayerAlarmEntry>) {
        val manager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        entries.filter { isEnabled(it.prayerName) }.forEach { entry ->
            val fireAt = entry.prayerTimeMillis - _settings.value.leadTimeMinutes * 60_000L
            if (fireAt <= System.currentTimeMillis()) return@forEach
            val intent = Intent(context, PrayerAlarmReceiver::class.java).apply {
                action = "com.example.PRAYER_ALARM." + entry.id
                putExtra(PrayerAlarmReceiver.EXTRA_ID, entry.id)
                putExtra(PrayerAlarmReceiver.EXTRA_NAME, entry.prayerName)
                putExtra(PrayerAlarmReceiver.EXTRA_SOUND, _settings.value.adhanSound)
                putExtra(PrayerAlarmReceiver.EXTRA_VIBRATE, _settings.value.vibrationEnabled)
            }
            val pending = PendingIntent.getBroadcast(
                context, entry.id.hashCode(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    if (canUseExactAlarms(context)) manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAt, pending)
                    else manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAt, pending)
                } else {
                    @Suppress("DEPRECATION")
                    manager.setExact(AlarmManager.RTC_WAKEUP, fireAt, pending)
                }
            } catch (_: SecurityException) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAt, pending)
                else manager.set(AlarmManager.RTC_WAKEUP, fireAt, pending)
            }
        }
    }

    private fun cancelAlarm(context: Context, id: String) {
        val intent = Intent(context, PrayerAlarmReceiver::class.java).apply { action = "com.example.PRAYER_ALARM." + id }
        val pending = PendingIntent.getBroadcast(
            context, id.hashCode(), intent, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (pending != null) (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pending)
    }

    fun onAlarmDelivered(context: Context, id: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        saveEntries(prefs, readEntries(prefs).filterNot { it.id == id })
    }

    fun postPrayerNotification(context: Context, prayerName: String, soundType: String, vibration: Boolean, id: Int) {
        if (!hasNotificationPermission(context)) return
        val openApp = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val silent = soundType == "silent"
        val builder = NotificationCompat.Builder(context, if (silent) CHANNEL_SILENT_ID else CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("🕌 " + prayerName + " prayer time")
            .setContentText("It is time for " + prayerName + " prayer.")
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVibrate(if (vibration) longArrayOf(0, 500, 250, 500) else null)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O && !silent) {
            builder.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))
        }
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(id, builder.build())
    }

    fun sendTestNotification(context: Context, prayerName: String = "Asr", prayerTime: String = "04:15 PM") {
        init(context)
        postPrayerNotification(context, prayerName, _settings.value.adhanSound, _settings.value.vibrationEnabled, 101)
        if (_settings.value.adhanSound != "silent" && _settings.value.adhanSound != "system") AdhanPlaybackService.start(context, _settings.value.adhanSound)
    }
}
