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
    const val EXTRA_NAME = "name"
    const val EXTRA_NUMBER = "number"
    const val EXTRA_ID = "id"
    const val ACTION_FIRE = "com.naffith.app.CALL_REMINDER_FIRE"
    const val ACTION_CANCEL = "com.naffith.app.CALL_REMINDER_CANCEL"

    /** يرجع وقت التنفيذ بالمللي ثانية. */
    fun schedule(context: Context, name: String, number: String, delaySeconds: Int?, atMinutes: Int?): Long {
        val at = when {
            delaySeconds != null -> System.currentTimeMillis() + delaySeconds * 1000L
            else -> Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, (atMinutes ?: 0) / 60)
                set(Calendar.MINUTE, (atMinutes ?: 0) % 60)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
            }.timeInMillis
        }
        val id = (at / 1000 % 100000).toInt() + number.hashCode() % 1000
        val pi = PendingIntent.getBroadcast(
            context, id,
            Intent(context, CallReminderReceiver::class.java).setAction(ACTION_FIRE)
                .putExtra(EXTRA_NAME, name).putExtra(EXTRA_NUMBER, number).putExtra(EXTRA_ID, id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val am = context.getSystemService(AlarmManager::class.java)
        val exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        return at
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
            CallReminder.ACTION_CANCEL -> context.getSystemService(NotificationManager::class.java).cancel(id)
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
