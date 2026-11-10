package com.naffith.app

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.SystemClock
import android.provider.AlarmClock
import android.provider.Settings
import android.view.KeyEvent
import com.naffith.app.core.Command
import com.naffith.app.core.DeviceAction
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * ينفّذ أوامر التحكم في الجوال ويرجع جملة قصيرة نعرضها وننطقها.
 * بعض الأشياء أندرويد ما يسمح فيها للتطبيقات (الواي فاي، البلوتوث…)، فنفتح لوحتها.
 */
class DeviceController(private val context: Context) {

    /** النتيجة: [message] اللي نقوله، و[needsSetup] إذا لازم المستخدم يعطي إذن (فتحنا له الإعداد). */
    data class Result(val message: String, val needsSetup: Boolean = false)

    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private fun s(id: Int, vararg args: Any) = context.getString(id, *args)

    fun execute(cmd: Command.Device): Result = when (cmd.action) {
        DeviceAction.ALARM -> alarm(cmd.value, cmd.label)
        DeviceAction.TIMER -> timer(cmd.value)

        DeviceAction.VOLUME_UP -> volumeSteps(+1)
        DeviceAction.VOLUME_DOWN -> volumeSteps(-1)
        DeviceAction.VOLUME_MUTE -> {
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
            Result(s(R.string.dev_muted))
        }
        DeviceAction.VOLUME_MAX -> volumePercent(100)
        DeviceAction.VOLUME_SET -> volumePercent(cmd.value ?: 50)

        DeviceAction.RINGER_VIBRATE -> ringer(AudioManager.RINGER_MODE_VIBRATE, R.string.dev_vibrate)
        DeviceAction.RINGER_NORMAL -> ringer(AudioManager.RINGER_MODE_NORMAL, R.string.dev_ring_normal)
        DeviceAction.RINGER_SILENT -> ringer(AudioManager.RINGER_MODE_SILENT, R.string.dev_silent)

        DeviceAction.BRIGHTNESS_UP -> brightnessBy(+1)
        DeviceAction.BRIGHTNESS_DOWN -> brightnessBy(-1)
        DeviceAction.BRIGHTNESS_MAX -> brightnessTo(100)
        DeviceAction.BRIGHTNESS_SET -> brightnessTo(cmd.value ?: 50)
        DeviceAction.BRIGHTNESS_AUTO -> withWriteSettings {
            Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC)
            Result(s(R.string.dev_brightness_auto))
        }

        DeviceAction.FLASH_ON -> torch(true)
        DeviceAction.FLASH_OFF -> torch(false)

        DeviceAction.MEDIA_PAUSE -> media(KeyEvent.KEYCODE_MEDIA_PAUSE, R.string.dev_media_pause)
        DeviceAction.MEDIA_PLAY -> media(KeyEvent.KEYCODE_MEDIA_PLAY, R.string.dev_media_play)
        DeviceAction.MEDIA_NEXT -> media(KeyEvent.KEYCODE_MEDIA_NEXT, R.string.dev_media_next)
        DeviceAction.MEDIA_PREVIOUS -> media(KeyEvent.KEYCODE_MEDIA_PREVIOUS, R.string.dev_media_previous)

        DeviceAction.WIFI -> panel(if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_WIFI else Settings.ACTION_WIFI_SETTINGS, R.string.dev_wifi)
        DeviceAction.MOBILE_DATA -> panel(if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_INTERNET_CONNECTIVITY else Settings.ACTION_DATA_ROAMING_SETTINGS, R.string.dev_data)
        DeviceAction.BLUETOOTH -> panel(Settings.ACTION_BLUETOOTH_SETTINGS, R.string.dev_bluetooth)
        DeviceAction.AIRPLANE -> panel(Settings.ACTION_AIRPLANE_MODE_SETTINGS, R.string.dev_airplane)
        DeviceAction.LOCATION -> panel(Settings.ACTION_LOCATION_SOURCE_SETTINGS, R.string.dev_location)

        DeviceAction.DND_ON -> dnd(true)
        DeviceAction.DND_OFF -> dnd(false)

        DeviceAction.BATTERY -> battery()
        DeviceAction.TIME -> Result(s(R.string.dev_time, SimpleDateFormat("h:mm a", Locale("ar")).format(Calendar.getInstance().time)))
        DeviceAction.DATE -> Result(s(R.string.dev_date, SimpleDateFormat("EEEE d MMMM yyyy", Locale("ar")).format(Calendar.getInstance().time)))

        DeviceAction.LOCK_SCREEN -> globalAction(if (Build.VERSION.SDK_INT >= 28) 8 else -1, R.string.dev_locked)
        DeviceAction.SCREENSHOT -> globalAction(if (Build.VERSION.SDK_INT >= 28) 9 else -1, R.string.dev_screenshot)
    }

    // ---------- المنبه والمؤقت ----------

    private fun alarm(minutesOfDay: Int?, label: String): Result {
        if (minutesOfDay == null) {
            start(Intent(AlarmClock.ACTION_SHOW_ALARMS))
            return Result(s(R.string.dev_alarm_which))
        }
        val hour = minutesOfDay / 60
        val minute = minutesOfDay % 60
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        if (label.isNotBlank()) intent.putExtra(AlarmClock.EXTRA_MESSAGE, label)
        if (!start(intent)) return Result(s(R.string.cannot_open))
        val time = String.format(Locale("ar"), "%d:%02d %s", if (hour % 12 == 0) 12 else hour % 12, minute, if (hour < 12) "الصبح" else "المسا")
        return Result(if (label.isBlank()) s(R.string.dev_alarm_set, time) else s(R.string.dev_reminder_set, label, time))
    }

    private fun timer(seconds: Int?): Result {
        if (seconds == null) {
            start(Intent(AlarmClock.ACTION_SET_TIMER))
            return Result(s(R.string.dev_timer_open))
        }
        val ok = start(
            Intent(AlarmClock.ACTION_SET_TIMER)
                .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        )
        if (!ok) return Result(s(R.string.cannot_open))
        val text = when {
            seconds % 3600 == 0 -> s(R.string.dev_hours, seconds / 3600)
            seconds >= 3600 -> s(R.string.dev_hours_minutes, seconds / 3600, (seconds % 3600) / 60)
            seconds % 60 == 0 -> s(R.string.dev_minutes, seconds / 60)
            else -> s(R.string.dev_seconds, seconds)
        }
        return Result(s(R.string.dev_timer_set, text))
    }

    // ---------- الصوت ----------

    private fun volumeSteps(direction: Int): Result {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val step = maxOf(1, max / 5) // خمس خطوات بين الصامت والأعلى
        val now = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val target = (now + direction * step).coerceIn(0, max)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, AudioManager.FLAG_SHOW_UI)
        return Result(s(R.string.dev_volume_now, target * 100 / max))
    }

    private fun volumePercent(percent: Int): Result {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, (max * percent + 50) / 100, AudioManager.FLAG_SHOW_UI)
        return Result(s(R.string.dev_volume_now, percent))
    }

    private fun ringer(mode: Int, message: Int): Result {
        // «صامت» يحتاج إذن «عدم الإزعاج» في أندرويد 7+.
        val nm = context.getSystemService(NotificationManager::class.java)
        if (mode == AudioManager.RINGER_MODE_SILENT && !nm.isNotificationPolicyAccessGranted) {
            start(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            return Result(s(R.string.dev_need_dnd_access), needsSetup = true)
        }
        return try {
            audio.ringerMode = mode
            Result(s(message))
        } catch (_: SecurityException) {
            start(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            Result(s(R.string.dev_need_dnd_access), needsSetup = true)
        }
    }

    private fun dnd(on: Boolean): Result {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (!nm.isNotificationPolicyAccessGranted) {
            start(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            return Result(s(R.string.dev_need_dnd_access), needsSetup = true)
        }
        nm.setInterruptionFilter(if (on) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL)
        return Result(s(if (on) R.string.dev_dnd_on else R.string.dev_dnd_off))
    }

    private fun media(keyCode: Int, message: Int): Result {
        val now = SystemClock.uptimeMillis()
        audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
        audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
        return Result(s(message))
    }

    // ---------- الإضاءة ----------

    /** تغيير الإضاءة يحتاج إذن «تعديل إعدادات النظام» مرة وحدة. */
    private fun withWriteSettings(block: () -> Result): Result {
        if (!Settings.System.canWrite(context)) {
            start(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + context.packageName)))
            return Result(s(R.string.dev_need_write_settings), needsSetup = true)
        }
        return block()
    }

    /**
     * أندرويد ما يحسب الإضاءة بشكل مستقيم: شريط الإضاءة يمشي على منحنى، ونص الشريط تقريباً
     * ربع القيمة الخام. فنحوّل بين «النسبة اللي تشوفها» والقيمة الخام بنفس الشكل.
     */
    private fun rawToPercent(raw: Int): Int =
        (Math.pow(raw.coerceIn(0, 255) / 255.0, 1 / 2.2) * 100).toInt().coerceIn(0, 100)

    private fun percentToRaw(percent: Int): Int =
        (Math.pow(percent.coerceIn(1, 100) / 100.0, 2.2) * 255).toInt().coerceIn(1, 255)

    private fun isAutoBrightness(): Boolean = runCatching {
        Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE) ==
            Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
    }.getOrDefault(false)

    /** درجات واضحة للرفع والتنزيل. */
    private val brightnessSteps = listOf(5, 15, 30, 50, 70, 85, 100)

    private fun brightnessBy(direction: Int): Result {
        // مع الإضاءة التلقائية القيمة المحفوظة ما تمثل اللي تشوفه، فنروح لمستوى واضح.
        val target = if (isAutoBrightness()) {
            if (direction < 0) 15 else 85
        } else {
            val now = runCatching {
                rawToPercent(Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS))
            }.getOrDefault(50)
            if (direction < 0) brightnessSteps.lastOrNull { it < now - 2 } ?: 5
            else brightnessSteps.firstOrNull { it > now + 2 } ?: 100
        }
        return brightnessTo(target)
    }

    private fun brightnessTo(percent: Int): Result = withWriteSettings {
        val cr = context.contentResolver
        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, percentToRaw(percent))
        Result(s(R.string.dev_brightness_now, percent))
    }

    // ---------- الكشاف ----------

    private fun torch(on: Boolean): Result {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = runCatching {
            cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
        }.getOrNull() ?: return Result(s(R.string.dev_no_flash))
        return runCatching {
            cm.setTorchMode(id, on)
            Result(s(if (on) R.string.dev_flash_on else R.string.dev_flash_off))
        }.getOrElse { Result(s(R.string.dev_no_flash)) }
    }

    // ---------- البطارية ----------

    private fun battery(): Result {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return Result(s(if (bm.isCharging) R.string.dev_battery_charging else R.string.dev_battery, level))
    }

    // ---------- لوحات الإعدادات والإجراءات العامة ----------

    private fun panel(action: String, message: Int): Result {
        if (!start(Intent(action))) start(Intent(Settings.ACTION_SETTINGS))
        return Result(s(message))
    }

    /** قفل الشاشة ولقطة الشاشة عن طريق خدمة «إمكانية الوصول» حق مرسال. */
    private fun globalAction(action: Int, message: Int): Result {
        if (action < 0) return Result(s(R.string.dev_not_supported))
        val service = WhatsAppSendService.instance
        if (service == null) {
            start(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return Result(s(R.string.dev_need_a11y), needsSetup = true)
        }
        return if (service.performGlobalAction(action)) Result(s(message)) else Result(s(R.string.dev_not_supported))
    }

    private fun start(intent: Intent): Boolean = runCatching {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess
}
