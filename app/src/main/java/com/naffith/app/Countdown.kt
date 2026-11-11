package com.naffith.app

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.util.Calendar
import java.util.Locale

/**
 * إشعار العد التنازلي بحجم عادي: دائرة تقصر مع الوقت وداخلها التايمر.
 * الدائرة تتحدّث كل شوي لين الوقت (التايمر نفسه يعد لحاله).
 */
object Countdown {

    data class Spec(val id: Int, val title: String, val start: Long, val at: Long, val callNumber: String?)

    const val ACTION_TICK = "com.naffith.app.COUNTDOWN_TICK"
    private const val MARKER = "naffith.countdown"

    fun start(context: Context, spec: Spec, cancel: PendingIntent?) {
        val cancelPi = cancel ?: PendingIntent.getBroadcast(
            context, spec.id,
            Intent(context, CallReminderReceiver::class.java).setAction(CallReminder.ACTION_CANCEL)
                .putExtra(CallReminder.EXTRA_ID, spec.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        post(context, spec, cancelPi)
        scheduleTick(context, spec, cancelPi)
    }

    private fun timeText(context: Context, millis: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = millis }
        val h = c.get(Calendar.HOUR_OF_DAY)
        return String.format(
            Locale("ar"), "%d:%02d %s", if (h % 12 == 0) 12 else h % 12, c.get(Calendar.MINUTE),
            if (h < 12) "الصبح" else "المسا"
        )
    }

    fun post(context: Context, spec: Spec, cancel: PendingIntent) {
        val nm = context.getSystemService(NotificationManager::class.java)
        val channel = CallReminder.channelCountdown(context)
        val now = System.currentTimeMillis()
        val total = (spec.at - spec.start).coerceAtLeast(1)
        val progress = ((now - spec.start) * 1000 / total).toInt().coerceIn(0, 1000)
        val atText = context.getString(R.string.countdown_at, timeText(context, spec.at))
        val brand = ContextCompat.getColor(context, R.color.brand)
        val call = spec.callNumber?.let { number ->
            PendingIntent.getActivity(
                context, spec.id,
                Intent(context, CallNowActivity::class.java)
                    .putExtra(CallReminder.EXTRA_NUMBER, number).putExtra(CallReminder.EXTRA_ID, spec.id)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
        val cancelLabel = context.getString(if (call != null) R.string.call_later_cancel else R.string.rem_cancel)
        val extras = Bundle().apply {
            putBoolean(CallReminder.EXTRA_PROMOTED_KEY, true)
            putBoolean(MARKER, true)
        }
        // الدائرة تبين الباقي: تبدأ مليانة وتقصر لين الصفر.
        val remaining = 1000 - progress
        val base = SystemClock.elapsedRealtime() + (spec.at - now)
        val views = RemoteViews(context.packageName, R.layout.notif_countdown_small).apply {
            setTextViewText(R.id.cd_title, spec.title)
            setTextViewText(R.id.cd_time, atText)
            setChronometer(R.id.cd_timer, base, null, true)
            setChronometerCountDown(R.id.cd_timer, true)
            setProgressBar(R.id.cd_progress, 1000, remaining, false)
        }
        val b = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(spec.title)
            .setContentText(atText)
            .setColor(brand)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(views)
            .setCustomBigContentView(views)
            .setShowWhen(false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setTimeoutAfter(spec.at - now + 5_000)
            .addExtras(extras)
        if (call != null) b.addAction(0, context.getString(R.string.countdown_call_now), call)
        b.addAction(0, cancelLabel, cancel)
        val n = b.build()
        runCatching { nm.notify(spec.id, n) }
    }

    /** نحدّث الشريط: كل ١٥ ثانية للقصيرة، ودقيقة للطويلة. */
    private fun scheduleTick(context: Context, spec: Spec, cancel: PendingIntent) {
        val total = spec.at - spec.start
        val step = when {
            total <= 10 * 60_000L -> 10_000L
            total <= 60 * 60_000L -> 30_000L
            else -> 60_000L
        }
        val next = System.currentTimeMillis() + step
        if (next >= spec.at - 2_000) return
        val pi = PendingIntent.getBroadcast(
            context, spec.id,
            Intent(context, CountdownReceiver::class.java).setAction(ACTION_TICK)
                .putExtra("id", spec.id).putExtra("title", spec.title).putExtra("start", spec.start)
                .putExtra("at", spec.at).putExtra("number", spec.callNumber).putExtra("cancel", cancel),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val am = context.getSystemService(AlarmManager::class.java)
        if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) am.setExact(AlarmManager.RTC, next, pi)
        else am.set(AlarmManager.RTC, next, pi)
    }

    /** الإشعار لسا موجود وهو العد التنازلي (مو إشعار «اتصل» أو مسحه المستخدم)؟ */
    private fun stillShowing(context: Context, id: Int): Boolean =
        context.getSystemService(NotificationManager::class.java).activeNotifications
            .any { it.id == id && it.notification.extras.getBoolean(MARKER, false) }

    fun tick(context: Context, intent: Intent) {
        val spec = Spec(
            intent.getIntExtra("id", 0), intent.getStringExtra("title").orEmpty(),
            intent.getLongExtra("start", 0), intent.getLongExtra("at", 0), intent.getStringExtra("number")
        )
        @Suppress("DEPRECATION")
        val cancel = (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra("cancel", PendingIntent::class.java)
        else intent.getParcelableExtra("cancel")) ?: return
        if (System.currentTimeMillis() >= spec.at - 1_000 || !stillShowing(context, spec.id)) return
        post(context, spec, cancel)
        scheduleTick(context, spec, cancel)
    }
}

class CountdownReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Countdown.ACTION_TICK) Countdown.tick(context, intent)
    }
}
