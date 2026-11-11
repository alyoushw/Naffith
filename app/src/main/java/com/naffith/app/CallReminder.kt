package com.naffith.app

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationCompat
import java.util.Calendar

/**
 * «بعد دقيقة جهز لي اتصال على روحي»: في الوقت يطلع إشعار فيه «اتصل» و«إلغاء».
 * «اتصل» يتصل على طول، «إلغاء» يشيل الإشعار.
 */
object CallReminder {

    private const val CHANNEL = "call_reminders"
    private const val CHANNEL_COUNTDOWN = "countdown"
    /** أندرويد ١٦ (وشريط Now Bar في سامسونج): نطلب يطلع الإشعار كـ«تحديث مباشر» فوق في الشريط. */
    private const val EXTRA_PROMOTED = "android.requestPromotedOngoing"
    const val EXTRA_NAME = "name"
    const val EXTRA_NUMBER = "number"
    const val EXTRA_ID = "id"
    const val ACTION_FIRE = "com.naffith.app.CALL_REMINDER_FIRE"
    const val ACTION_CANCEL = "com.naffith.app.CALL_REMINDER_CANCEL"

    /** يرجع وقت التنفيذ بالمللي ثانية. */
    fun schedule(context: Context, name: String, number: String, delaySeconds: Int?, atMinutes: Int?): Long {
        val at = timeFor(delaySeconds, atMinutes)
        val id = idFor(at, number)
        val pi = firePending(context, id, name, number)
        val am = context.getSystemService(AlarmManager::class.java)
        val exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        countdown(context, id, context.getString(R.string.countdown_call, name), at, number)
        return at
    }

    private fun firePending(context: Context, id: Int, name: String, number: String): PendingIntent =
        PendingIntent.getBroadcast(
            context, id,
            Intent(context, CallReminderReceiver::class.java).setAction(ACTION_FIRE)
                .putExtra(EXTRA_NAME, name).putExtra(EXTRA_NUMBER, number).putExtra(EXTRA_ID, id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    fun idFor(at: Long, key: String) = (at / 1000 % 100000).toInt() + key.hashCode() % 1000

    /** وقت التنفيذ بالمللي ثانية: بعد كذا ثانية، أو الساعة كذا (اليوم أو بكرة). */
    fun timeFor(delaySeconds: Int?, atMinutes: Int?): Long =
        when {
            delaySeconds != null -> System.currentTimeMillis() + delaySeconds * 1000L
            else -> Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, (atMinutes ?: 0) / 60)
                set(Calendar.MINUTE, (atMinutes ?: 0) % 60)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
            }.timeInMillis
        }

    /** نفس اليوم (أو خلال ٣ ساعات حتى لو بعد نص الليل). */
    private fun isSoon(at: Long): Boolean {
        val now = Calendar.getInstance()
        val then = Calendar.getInstance().apply { timeInMillis = at }
        val sameDay = now.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
            now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
        return sameDay || at - System.currentTimeMillis() <= 3 * 60 * 60 * 1000L
    }

    private fun canNotify(context: Context) = Build.VERSION.SDK_INT < 33 ||
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /**
     * إشعار ثابت في صفحة الإشعارات فيه عد تنازلي لين وقت التذكير (زي الـDynamic Island).
     * للاتصال: لما يجي الوقت ينستبدل بإشعار «اتصل / إلغاء». للتذكير العادي: يختفي في وقته
     * (المنبه نفسه يرن من تطبيق الساعة).
     */
    fun countdown(context: Context, id: Int, title: String, at: Long, callNumber: String? = null) {
        if (!isSoon(at) || at <= System.currentTimeMillis() || !canNotify(context)) return
        val nm = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_COUNTDOWN, context.getString(R.string.countdown_channel), NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { setSound(null, null); enableVibration(false); setShowBadge(false) }
            )
        }
        val time = String.format(java.util.Locale("ar"), "%tl:%<tM %s", at, if (Calendar.getInstance().apply { timeInMillis = at }.get(Calendar.HOUR_OF_DAY) < 12) "الصبح" else "المسا")
        val cancel = PendingIntent.getBroadcast(
            context, id,
            Intent(context, CallReminderReceiver::class.java).setAction(ACTION_CANCEL).putExtra(EXTRA_ID, id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val timeText = context.getString(R.string.countdown_at, time)
        // «إشعار مباشر» مفعّل (أندرويد ١٦): النظام يرسمه بنفسه في Now Bar والشريط.
        // غير كذا: تصميمنا، العد كبير وواضح.
        val live = canPostLive(nm)
        val b = NotificationCompat.Builder(context, CHANNEL_COUNTDOWN)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(title)
            .setContentText(timeText)
            .setWhen(at)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setTimeoutAfter(at - System.currentTimeMillis() + 5_000)
            .addExtras(Bundle().apply { putBoolean(EXTRA_PROMOTED, true) })
        if (callNumber != null) {
            val call = PendingIntent.getActivity(
                context, id,
                Intent(context, CallNowActivity::class.java)
                    .putExtra(EXTRA_NUMBER, callNumber).putExtra(EXTRA_ID, id)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            b.addAction(0, context.getString(R.string.countdown_call_now), call)
            b.addAction(0, context.getString(R.string.call_later_cancel), cancel)
        } else {
            b.addAction(0, context.getString(R.string.countdown_hide), cancel)
        }
        if (!live) {
            val base = android.os.SystemClock.elapsedRealtime() + (at - System.currentTimeMillis())
            fun views(layout: Int) = android.widget.RemoteViews(context.packageName, layout).apply {
                setTextViewText(R.id.cd_title, title)
                setChronometer(R.id.cd_timer, base, null, true)
                setChronometerCountDown(R.id.cd_timer, true)
                if (layout == R.layout.notif_countdown_big) setTextViewText(R.id.cd_time, timeText)
            }
            b.setStyle(NotificationCompat.DecoratedCustomViewStyle())
                .setCustomContentView(views(R.layout.notif_countdown_small))
                .setCustomBigContentView(views(R.layout.notif_countdown_big))
                .setShowWhen(false)
        }
        // «إشعار مباشر» (فوق في الشريط وفي Now Bar): الطلب نفسه في [EXTRA_PROMOTED] فوق.
        if (!live && Build.VERSION.SDK_INT >= 36) askForLiveNotifications(context, nm)
        runCatching { nm.notify(id, b.build()) }
    }

    /** إذا «الإشعارات المباشرة» مقفلة لمرسال: إشعار يوديك لمكان تفعيلها (مرة كل يومين بالكثير). */
    private fun askForLiveNotifications(context: Context, nm: NotificationManager) {
        if (Build.VERSION.SDK_INT < 36 || canPostLive(nm)) return
        val prefs = context.getSharedPreferences("live_notifications", Context.MODE_PRIVATE)
        if (System.currentTimeMillis() - prefs.getLong("asked_at", 0) < 2 * 24 * 60 * 60 * 1000L) return
        prefs.edit().putLong("asked_at", System.currentTimeMillis()).apply()
        val open = PendingIntent.getActivity(
            context, 9001, liveSettingsIntent(context),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val ask = NotificationCompat.Builder(context, CHANNEL_COUNTDOWN)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(context.getString(R.string.live_ask_title))
            .setContentText(context.getString(R.string.live_ask_text))
            .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(R.string.live_ask_text)))
            .setContentIntent(open)
            .addAction(0, context.getString(R.string.live_ask_enable), open)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(9001, ask) }
    }

    /** NotificationManager.canPostPromotedNotifications() (أندرويد ١٦ وفوق)، بدون ما نحتاج SDK أحدث. */
    private fun canPostLive(nm: NotificationManager): Boolean = Build.VERSION.SDK_INT >= 36 && runCatching {
        nm.javaClass.getMethod("canPostPromotedNotifications").invoke(nm) as Boolean
    }.getOrDefault(false)

    /** صفحة «الإشعارات المباشرة» لمرسال (أو إعدادات إشعاراته إذا الجوال ما يدعمها). */
    fun liveSettingsIntent(context: Context): Intent {
        val promo = Intent("android.settings.APP_NOTIFICATION_PROMOTION_SETTINGS")
            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 36 && promo.resolveActivity(context.packageManager) != null) return promo
        return Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun show(context: Context, name: String, number: String, id: Int) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, context.getString(R.string.call_later_channel), NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val call = PendingIntent.getActivity(
            context, id,
            Intent(context, CallNowActivity::class.java)
                .putExtra(EXTRA_NUMBER, number).putExtra(EXTRA_ID, id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val cancel = PendingIntent.getBroadcast(
            context, id,
            Intent(context, CallReminderReceiver::class.java).setAction(ACTION_CANCEL).putExtra(EXTRA_ID, id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(context.getString(R.string.call_later_title, name))
            .setContentText(context.getString(R.string.call_later_text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setContentIntent(call)
            .addAction(0, context.getString(R.string.call_later_call), call)
            .addAction(0, context.getString(R.string.call_later_cancel), cancel)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(id, n) }
    }

    /** «بعد دقيقة» / «الساعة ٥:٠٠ المسا». */
    fun describe(delaySeconds: Int?, atMinutes: Int?): String {
        if (delaySeconds != null) {
            val m = delaySeconds / 60
            val h = m / 60
            return when {
                delaySeconds < 60 -> "بعد $delaySeconds ثانية"
                m == 1 -> "بعد دقيقة"
                m == 2 -> "بعد دقيقتين"
                m < 60 && m <= 10 -> "بعد $m دقايق"
                m < 60 -> "بعد $m دقيقة"
                m % 60 == 30 && h == 1 -> "بعد ساعة ونص"
                m % 60 != 0 -> "بعد $h:${"%02d".format(m % 60)} ساعة"
                h == 1 -> "بعد ساعة"
                h == 2 -> "بعد ساعتين"
                else -> "بعد $h ساعات"
            }
        }
        val t = atMinutes ?: 0
        val hour = t / 60
        return String.format(
            java.util.Locale("ar"), "الساعة %d:%02d %s",
            if (hour % 12 == 0) 12 else hour % 12, t % 60, if (hour < 12) "الصبح" else "المسا"
        )
    }
}

class CallReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(CallReminder.EXTRA_ID, 0)
        when (intent.action) {
            CallReminder.ACTION_FIRE -> CallReminder.show(
                context,
                intent.getStringExtra(CallReminder.EXTRA_NAME).orEmpty(),
                intent.getStringExtra(CallReminder.EXTRA_NUMBER).orEmpty(),
                id
            )
            CallReminder.ACTION_CANCEL -> {
                context.getSystemService(NotificationManager::class.java).cancel(id)
                // «إلغاء» قبل الوقت: نلغي الاتصال المجهّز نفسه بعد.
                PendingIntent.getBroadcast(
                    context, id,
                    Intent(context, CallReminderReceiver::class.java).setAction(CallReminder.ACTION_FIRE),
                    PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
                )?.let { context.getSystemService(AlarmManager::class.java).cancel(it); it.cancel() }
            }
        }
    }
}

/** شاشة شفافة: تشيل الإشعار وتتصل (أو تفتح لوحة الاتصال إذا ما فيه إذن). */
class CallNowActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        getSystemService(NotificationManager::class.java).cancel(intent.getIntExtra(CallReminder.EXTRA_ID, 0))
        val number = intent.getStringExtra(CallReminder.EXTRA_NUMBER).orEmpty()
        val canCall = checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)
        val uri = Uri.fromParts("tel", number, null)
        runCatching { startActivity(Intent(if (canCall) Intent.ACTION_CALL else Intent.ACTION_DIAL, uri)) }
            .onFailure { runCatching { startActivity(Intent(Intent.ACTION_DIAL, uri)) } }
        finish()
    }
}
