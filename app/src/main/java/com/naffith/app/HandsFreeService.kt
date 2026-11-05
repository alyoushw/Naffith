package com.naffith.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig

/**
 * وضع «يا نفّذ»: خدمة في المقدمة تسمع باستمرار بمحرك كلمة تنبيه يشتغل على الجوال نفسه
 * (sherpa-onnx، بدون إنترنت)، حتى والشاشة مطفية. لما يلتقط «يا نفّذ» يهز الجوال ويفتح
 * الشاشة الرئيسية (فوق قفل الشاشة) وهي تسمع الأمر.
 *
 * المايك يبقى مفتوح بشكل ثابت (ما يطفي ويشتغل)، ويتوقف بس لما التطبيق نفسه مفتوح.
 */
class HandsFreeService : Service() {

    companion object {
        private const val TAG = "HandsFree"
        const val ACTION_STOP = "com.naffith.app.action.STOP_HANDS_FREE"
        private const val CHANNEL_ID = "hands_free"
        private const val ALERT_CHANNEL_ID = "hands_free_alerts"
        private const val NOTIFICATION_ID = 1
        private const val ALERT_NOTIFICATION_ID = 2
        private const val PREFS = "settings"
        private const val KEY_ENABLED = "hands_free_enabled"
        private const val SAMPLE_RATE = 16000

        /** الخدمة الشغالة حالياً (نفس العملية). */
        @Volatile
        var instance: HandsFreeService? = null
            private set

        @Volatile
        private var appVisible = false

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

        /** التطبيق ظاهر ويسمع بنفسه، فنفك المايك عشان محرك Google يقدر يستخدمه. */
        fun setAppVisible(visible: Boolean) {
            appVisible = visible
            instance?.onAppVisibilityChanged(visible)
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var spotter: KeywordSpotter? = null
    private var worker: Thread? = null
    @Volatile private var running = false
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createChannels()
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
            // أندرويد يرفض تشغيل خدمة المايك من الخلفية؛ تنطلق لما يفتح التطبيق.
            Log.w(TAG, "startForeground refused", e)
            stopSelf()
            return START_NOT_STICKY
        }
        acquireWakeLock()
        if (!appVisible) startDetection()
        return START_STICKY
    }

    override fun onDestroy() {
        instance = null
        handler.removeCallbacksAndMessages(null)
        stopDetection()
        spotter?.release()
        spotter = null
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    private fun onAppVisibilityChanged(visible: Boolean) {
        handler.post {
            if (instance == null) return@post
            if (visible) stopDetection() else handler.postDelayed({ if (!appVisible) startDetection() }, 700)
        }
    }

    /** نخلي المعالج شغال والشاشة مطفية، وإلا يوقف تحليل الصوت. */
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Naffith:HandsFree").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    // ---------- الاستماع لكلمة التنبيه ----------

    private fun startDetection() {
        if (worker != null) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            setEnabled(this, false)
            stopSelf()
            return
        }
        running = true
        worker = Thread({ detectionLoop() }, "naffith-kws").also { it.start() }
    }

    private fun stopDetection() {
        running = false
        worker?.let { runCatching { it.join(1000) } }
        worker = null
    }

    private fun loadSpotter(): KeywordSpotter {
        spotter?.let { return it }
        val dir = "kws"
        val config = KeywordSpotterConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = "$dir/encoder.int8.onnx",
                    decoder = "$dir/decoder.int8.onnx",
                    joiner = "$dir/joiner.int8.onnx",
                ),
                tokens = "$dir/tokens.txt",
                modelType = "zipformer2",
                numThreads = 1,
            ),
            keywordsFile = "$dir/keywords.txt",
            keywordsScore = 1.5f,
            keywordsThreshold = 0.25f,
            numTrailingBlanks = 1,
        )
        return KeywordSpotter(assets, config).also { spotter = it }
    }

    @SuppressLint("MissingPermission") // تحققنا من الإذن في startDetection
    private fun detectionLoop() {
        var recorder: AudioRecord? = null
        try {
            val kws = loadSpotter()
            val stream = kws.createStream()
            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            recorder = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, SAMPLE_RATE)
            )
            if (recorder.state != AudioRecord.STATE_INITIALIZED) error("AudioRecord init failed")
            recorder.startRecording()

            val buffer = ShortArray(SAMPLE_RATE / 10) // 100ms
            val samples = FloatArray(buffer.size)
            var detected = false
            while (running && !detected) {
                val n = recorder.read(buffer, 0, buffer.size)
                if (n <= 0) continue
                for (i in 0 until n) samples[i] = buffer[i] / 32768f
                stream.acceptWaveform(if (n == samples.size) samples else samples.copyOf(n), SAMPLE_RATE)
                while (kws.isReady(stream)) {
                    kws.decode(stream)
                    if (kws.getResult(stream).keyword.isNotBlank()) {
                        kws.reset(stream)
                        detected = true
                        break
                    }
                }
            }
            stream.release()
            if (detected) handler.post { onWakeWord() }
        } catch (t: Throwable) {
            Log.e(TAG, "wake word engine failed", t)
            handler.post { showAlert(getString(R.string.hf_engine_failed), null) }
        } finally {
            recorder?.let { r ->
                runCatching { r.stop() }
                r.release()
            }
        }
    }

    // ---------- بعد ما نسمع «يا نفّذ» ----------

    private fun onWakeWord() {
        worker = null
        running = false
        vibrate()
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(MainActivity.EXTRA_START_LISTENING, true)
            putExtra(MainActivity.EXTRA_FROM_WAKE_WORD, true)
        }
        val started = Settings.canDrawOverlays(this) && runCatching { startActivity(intent) }.isSuccess
        if (!started) showAlert(getString(R.string.hf_tap_to_talk), intent)
        // إذا التطبيق ما انفتح (أو انقفل بسرعة) نرجع نسمع.
        handler.postDelayed({ if (!appVisible && instance != null) startDetection() }, 5000)
    }

    private fun vibrate() {
        @Suppress("DEPRECATION")
        val v = getSystemService(VIBRATOR_SERVICE) as? Vibrator ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            v.vibrate(VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(120)
        }
    }

    /** بدون إذن «الظهور فوق التطبيقات» أندرويد يمنع الفتح من الخلفية، فنعرض إشعار تضغطه. */
    private fun showAlert(text: String, intent: Intent?) {
        val pi = PendingIntent.getActivity(
            this, 1, intent ?: Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
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

    // ---------- الإشعار الثابت ----------

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
