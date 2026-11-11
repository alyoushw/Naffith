package com.naffith.app

import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * تذكيرات مرسال نفسه (مو منبه الساعة): «ذكرني الساعة ٥ اشتري خبز».
 * نوعين: إشعار فوق، أو شاشة كاملة تطلع على الجهاز مع رنة وتأجيل.
 */
object Reminders {

    private const val CHANNEL_NOTIF = "reminders"
    private const val CHANNEL_FULL = "reminders_full"
    const val ACTION_FIRE = "com.naffith.app.REMINDER_FIRE"
    const val ACTION_DONE = "com.naffith.app.REMINDER_DONE"
    const val ACTION_SNOOZE = "com.naffith.app.REMINDER_SNOOZE"
    const val EXTRA_LABEL = "label"
    const val EXTRA_FULL = "full"
    const val EXTRA_ID = "id"
    const val EXTRA_MINUTES = "minutes"
    const val NOTIF_SNOOZE_MINUTES = 10

    fun scheduleAt(context: Context, label: String, atMinutes: Int, full: Boolean): Long =
        scheduleMillis(context, label, CallReminder.timeFor(null, atMinutes), full)

    fun scheduleMillis(context: Context, label: String, at: Long, full: Boolean): Long {
        val id = CallReminder.idFor(at, label)
        val am = context.getSystemService(AlarmManager::class.java)
        val pi = firePending(context, id, label, full)
        val exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        CallReminder.countdown(context, id, "⏰ $label", at, cancel = actionPending(context, ACTION_DONE, id, label, full))
        return at
    }

    private fun firePending(context: Context, id: Int, label: String, full: Boolean): PendingIntent =
        PendingIntent.getBroadcast(
            context, id,
            Intent(context, ReminderReceiver::class.java).setAction(ACTION_FIRE)
                .putExtra(EXTRA_LABEL, label).putExtra(EXTRA_FULL, full).putExtra(EXTRA_ID, id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    fun actionPending(context: Context, action: String, id: Int, label: String, full: Boolean, minutes: Int = NOTIF_SNOOZE_MINUTES): PendingIntent =
        PendingIntent.getBroadcast(
            context, id * 4 + action.length % 4,
            Intent(context, ReminderReceiver::class.java).setAction(action)
                .putExtra(EXTRA_LABEL, label).putExtra(EXTRA_FULL, full).putExtra(EXTRA_ID, id).putExtra(EXTRA_MINUTES, minutes),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    /** نلغي التذكير قبل وقته (من «إلغاء» في العد التنازلي). */
    fun cancel(context: Context, id: Int, label: String, full: Boolean) {
        context.getSystemService(NotificationManager::class.java).cancel(id)
        val pi = firePending(context, id, label, full)
        context.getSystemService(AlarmManager::class.java).cancel(pi)
    }

    fun snooze(context: Context, label: String, full: Boolean, minutes: Int) {
        scheduleMillis(context, label, System.currentTimeMillis() + minutes * 60_000L, full)
    }

    /** يقدر يطلع الشاشة الكاملة؟ (أندرويد ١٤ يحتاج إذن، أو خدمة مرسال في «إمكانية الوصول»). */
    fun canShowFullScreen(context: Context): Boolean {
        if (WhatsAppSendService.instance != null) return true
        if (Build.VERSION.SDK_INT < 34) return true
        return context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
    }

    fun fullScreenSettings(context: Context): Intent =
        if (Build.VERSION.SDK_INT >= 34) {
            Intent(android.provider.Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, android.net.Uri.parse("package:" + context.packageName))
        } else {
            Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
        }

    /** وقت التذكير: إشعار، أو شاشة كاملة. */
    fun fire(context: Context, id: Int, label: String, full: Boolean) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_NOTIF, context.getString(R.string.rem_channel), NotificationManager.IMPORTANCE_HIGH)
            )
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_FULL, context.getString(R.string.rem_channel_full), NotificationManager.IMPORTANCE_HIGH)
                    .apply { setSound(null, null) } // الشاشة نفسها ترن
            )
        }
        val done = actionPending(context, ACTION_DONE, id, label, full)
        val snooze = actionPending(context, ACTION_SNOOZE, id, label, full)
        val b = NotificationCompat.Builder(context, if (full) CHANNEL_FULL else CHANNEL_NOTIF)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("⏰ $label")
            .setContentText(context.getString(R.string.rem_now))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(if (full) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(0, context.getString(R.string.rem_done), done)
            .addAction(0, context.getString(R.string.rem_snooze_n, NOTIF_SNOOZE_MINUTES), snooze)
        if (full) {
            val screen = Intent(context, ReminderAlertActivity::class.java)
                .putExtra(EXTRA_LABEL, label).putExtra(EXTRA_ID, id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val screenPi = PendingIntent.getActivity(
                context, id, screen, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            b.setFullScreenIntent(screenPi, true).setContentIntent(screenPi).setOngoing(true)
            nm.notifyCompat(id, b)
            // الجوال مفتوح وتستخدمه؟ النظام يطلّع الشاشة الكاملة كإشعار بس، فنفتحها بأنفسنا
            // (مسموح لأن خدمة مرسال في «إمكانية الوصول» شغالة).
            runCatching { context.startActivity(screen) }
        } else {
            b.setDefaults(NotificationCompat.DEFAULT_ALL).setAutoCancel(true)
            nm.notifyCompat(id, b)
        }
    }

    private fun NotificationManager.notifyCompat(id: Int, b: NotificationCompat.Builder) {
        runCatching { notify(id, b.build()) }
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(Reminders.EXTRA_ID, 0)
        val label = intent.getStringExtra(Reminders.EXTRA_LABEL).orEmpty()
        val full = intent.getBooleanExtra(Reminders.EXTRA_FULL, false)
        when (intent.action) {
            Reminders.ACTION_FIRE -> Reminders.fire(context, id, label, full)
            Reminders.ACTION_DONE -> {
                Reminders.cancel(context, id, label, full)
                ReminderAlertActivity.closeAll()
            }
            Reminders.ACTION_SNOOZE -> {
                context.getSystemService(NotificationManager::class.java).cancel(id)
                ReminderAlertActivity.closeAll()
                Reminders.snooze(context, label, full, intent.getIntExtra(Reminders.EXTRA_MINUTES, Reminders.NOTIF_SNOOZE_MINUTES))
            }
        }
    }
}

/** الشاشة الكاملة: اسم التذكير كبير، رنة واهتزاز، و«تم» أو «أجّل» كم دقيقة. */
class ReminderAlertActivity : AppCompatActivity() {

    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        current = this
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            getSystemService(KeyguardManager::class.java)?.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        render()
        startAlert()
        // ما نرن للأبد: دقيقة وبعدها نسكت (الإشعار يظل).
        handler.postDelayed({ stopAlert() }, 60_000)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        render()
    }

    private val label get() = intent.getStringExtra(Reminders.EXTRA_LABEL).orEmpty()
    private val reminderId get() = intent.getIntExtra(Reminders.EXTRA_ID, 0)

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun render() {
        val brand = ContextCompat.getColor(this, R.color.brand)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(48), dp(24), dp(32))
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(ContextCompat.getColor(context, R.color.bg_top), ContextCompat.getColor(context, R.color.bg))
            )
            layoutDirection = android.view.View.LAYOUT_DIRECTION_RTL
        }
        root.addView(TextView(this).apply {
            text = "⏰"
            textSize = 64f
            gravity = Gravity.CENTER
        })
        root.addView(TextView(this).apply {
            text = getString(R.string.rem_title)
            textSize = 18f
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, 0)
        })
        root.addView(TextView(this).apply {
            text = label
            textSize = 34f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(36))
        })
        root.addView(bigButton(getString(R.string.rem_done), brand) { done() })
        root.addView(TextView(this).apply {
            text = getString(R.string.rem_snooze_title)
            textSize = 16f
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            gravity = Gravity.CENTER
            setPadding(0, dp(28), 0, dp(10))
        })
        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        listOf(5, 10, 15).forEach { m -> row1.addView(snoozeChip(m)) }
        listOf(30, 60).forEach { m -> row2.addView(snoozeChip(m)) }
        root.addView(row1)
        root.addView(row2)
        setContentView(root)
    }

    private fun bigButton(text: String, color: Int, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        textSize = 20f
        isAllCaps = false
        setTextColor(0xFFFFFFFF.toInt())
        background = GradientDrawable().apply { cornerRadius = dp(32).toFloat(); setColor(color) }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64))
        setOnClickListener { onClick() }
    }

    private fun snoozeChip(minutes: Int) = Button(this).apply {
        text = if (minutes == 60) getString(R.string.rem_hour) else getString(R.string.rem_minutes, minutes)
        textSize = 16f
        isAllCaps = false
        setTextColor(ContextCompat.getColor(context, R.color.text_primary))
        background = GradientDrawable().apply {
            cornerRadius = dp(24).toFloat()
            setColor(ContextCompat.getColor(context, R.color.surface_card))
            setStroke(dp(1), ContextCompat.getColor(context, R.color.surface_stroke))
        }
        layoutParams = LinearLayout.LayoutParams(dp(96), dp(52)).apply { setMargins(dp(6), dp(6), dp(6), dp(6)) }
        setOnClickListener { snooze(minutes) }
    }

    private fun done() {
        getSystemService(NotificationManager::class.java).cancel(reminderId)
        finish()
    }

    private fun snooze(minutes: Int) {
        getSystemService(NotificationManager::class.java).cancel(reminderId)
        Reminders.snooze(applicationContext, label, true, minutes)
        android.widget.Toast.makeText(this, getString(R.string.rem_snoozed, minutes), android.widget.Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun startAlert() {
        runCatching {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            ringtone = RingtoneManager.getRingtone(this, uri)?.apply {
                audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
                if (Build.VERSION.SDK_INT >= 28) isLooping = true
                play()
            }
        }
        runCatching {
            @Suppress("DEPRECATION")
            vibrator = getSystemService(VIBRATOR_SERVICE) as? Vibrator
            val pattern = longArrayOf(0, 600, 400, 600, 1200)
            if (Build.VERSION.SDK_INT >= 26) {
                vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(pattern, 0)
            }
        }
    }

    private fun stopAlert() {
        runCatching { ringtone?.stop() }
        runCatching { vibrator?.cancel() }
    }

    override fun onDestroy() {
        stopAlert()
        handler.removeCallbacksAndMessages(null)
        if (current === this) current = null
        super.onDestroy()
    }

    companion object {
        private var current: ReminderAlertActivity? = null
        fun closeAll() { current?.finish() }
    }
}
