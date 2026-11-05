package com.naffith.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.naffith.app.core.WakeWord

/**
 * وضع «يا نفّذ»: خدمة في المقدمة (بإشعار ثابت) تسمع باستمرار، وإذا سمعت كلمة التنبيه
 * تفتح الشاشة الرئيسية وتعطيها الأمر عشان تنفّذه.
 *
 * - تتوقف مؤقتاً لما الشاشة مطفية (توفير بطارية) ولما التطبيق مفتوح (هو يسمع بنفسه).
 * - فتح التطبيقات من الخلفية يحتاج إذن «الظهور فوق التطبيقات»؛ بدونه نرسل إشعار تضغطه.
 */
class HandsFreeService : Service() {

    companion object {
        const val ACTION_STOP = "com.naffith.app.action.STOP_HANDS_FREE"
        private const val CHANNEL_ID = "hands_free"
        private const val ALERT_CHANNEL_ID = "hands_free_alerts"
        private const val NOTIFICATION_ID = 1
        private const val ALERT_NOTIFICATION_ID = 2
        private const val PREFS = "settings"
        private const val KEY_ENABLED = "hands_free_enabled"

        /** الخدمة الشغالة حالياً (نفس العملية)، عشان الشاشة الرئيسية توقفها مؤقتاً. */
        @Volatile
        var instance: HandsFreeService? = null
            private set

        fun isEnabled(context: Context) =
            context.getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

        fun setEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, enabled).apply()
        }

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, HandsFreeService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, HandsFreeService::class.java))
        }

        /** التطبيق ظاهر ويسمع بنفسه، فنوقف الاستماع في الخلفية عشان ما يتعارض المايك. */
        @Volatile
        private var appVisible = false

        fun setAppVisible(visible: Boolean) {
            appVisible = visible
            instance?.onAppVisibilityChanged(visible)
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private var consecutiveErrors = 0
    private var stopped = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> stopListening()
                Intent.ACTION_USER_PRESENT, Intent.ACTION_SCREEN_ON -> scheduleListen(500)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createChannels()
        ContextCompat.registerReceiver(
            this,
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            setEnabled(this, false)
            stopSelf()
            return START_NOT_STICKY
        }
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else 0
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
        } catch (e: Exception) {
            // أندرويد يرفض بدء خدمة المايك من الخلفية؛ تنطلق المرة الجاية لما يفتح التطبيق.
            stopSelf()
            return START_NOT_STICKY
        }
        scheduleListen(300)
        return START_STICKY
    }

    override fun onDestroy() {
        stopped = true
        instance = null
        handler.removeCallbacksAndMessages(null)
        runCatching { unregisterReceiver(screenReceiver) }
        recognizer?.destroy()
        recognizer = null
        super.onDestroy()
    }

    private fun onAppVisibilityChanged(visible: Boolean) {
        handler.post { if (visible) stopListening() else scheduleListen(800) }
    }

    // ---------- الاستماع المستمر ----------

    private fun canListen(): Boolean {
        if (stopped || appVisible) return false
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        return pm.isInteractive
    }

    private fun scheduleListen(delayMs: Long) {
        handler.removeCallbacks(listenRunnable)
        handler.postDelayed(listenRunnable, delayMs)
    }

    private val listenRunnable = Runnable { startListening() }

    private fun startListening() {
        if (!canListen() || listening) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            stopSelf()
            return
        }
        val sr = recognizer ?: SpeechRecognizer.createSpeechRecognizer(this).also {
            it.setRecognitionListener(listener)
            recognizer = it
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ar-SA")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ar-SA")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        }
        listening = true
        runCatching { sr.startListening(intent) }.onFailure {
            listening = false
            scheduleListen(3000)
        }
    }

    private fun stopListening() {
        handler.removeCallbacks(listenRunnable)
        if (listening) runCatching { recognizer?.cancel() }
        listening = false
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
        override fun onPartialResults(partialResults: Bundle?) {}

        override fun onResults(results: Bundle?) {
            listening = false
            consecutiveErrors = 0
            val alternatives = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
            val commands = WakeWord.extractFromAlternatives(alternatives)
            if (commands != null) {
                onWakeWord(commands)
                // نعطي التطبيق وقت يفتح؛ هو اللي يوقفنا ويرجّعنا.
                scheduleListen(2500)
            } else {
                scheduleListen(150)
            }
        }

        override fun onError(error: Int) {
            listening = false
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    consecutiveErrors = 0
                    scheduleListen(150)
                }
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    setEnabled(this@HandsFreeService, false)
                    stopSelf()
                }
                SpeechRecognizer.ERROR_CLIENT -> scheduleListen(500)
                else -> {
                    // مشغول/شبكة/مايك مستخدم في مكالمة: ننتظر أكثر كل ما تكررت.
                    consecutiveErrors++
                    recognizer?.destroy()
                    recognizer = null
                    scheduleListen(minOf(30_000L, 1000L * (1 shl minOf(consecutiveErrors, 5))))
                }
            }
        }
    }

    // ---------- تنفيذ الأمر ----------

    private fun onWakeWord(commands: List<String>) {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            if (commands.first().isBlank()) {
                putExtra(MainActivity.EXTRA_START_LISTENING, true)
            } else {
                putStringArrayListExtra(MainActivity.EXTRA_COMMANDS, ArrayList(commands.filter { it.isNotBlank() }))
            }
        }
        val canStartFromBackground = Settings.canDrawOverlays(this)
        val started = canStartFromBackground && runCatching { startActivity(intent) }.isSuccess
        if (!started) showTapToRunNotification(intent, commands.first())
    }

    /** بدون إذن «الظهور فوق التطبيقات» أندرويد يمنع الفتح من الخلفية، فنعرض إشعار تضغطه. */
    private fun showTapToRunNotification(intent: Intent, command: String) {
        val pi = PendingIntent.getActivity(
            this, 1, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val text = if (command.isBlank()) getString(R.string.hf_tap_to_talk) else getString(R.string.hf_tap_to_run, command)
        val n = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        runCatching { getSystemService(NotificationManager::class.java).notify(ALERT_NOTIFICATION_ID, n) }
    }

    // ---------- الإشعارات ----------

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.hf_channel), NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(ALERT_CHANNEL_ID, getString(R.string.hf_alert_channel), NotificationManager.IMPORTANCE_HIGH)
        )
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 0, Intent(this, HandsFreeService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(getString(R.string.hf_notification_title))
            .setContentText(getString(R.string.hf_notification_text))
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, getString(R.string.hf_stop), stop)
            .build()
    }
}
