package com.naffith.app

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * يسجّل كلامك بنفسه (بدون محرك Google) لين تسكت، ويرجعه WAV نرسله لـ Gemini يفهمه.
 * هذا اللي يخلي «مرسال» يسمعك والجوال مقفول، لأن محرك Google يرفض يسمع فوق القفل.
 */
object CloudListener {

    private const val RATE = 16000
    private const val FRAME = RATE / 10 // 100ms
    private const val MAX_MS = 12_000
    private const val WAIT_FOR_SPEECH_MS = 5_000
    private const val END_SILENCE_MS = 1_300

    /**
     * يسجّل لين تسكت بعد ما تتكلم (أو تخلص المهلة). [onLevel] مستوى الصوت 0..1 للكرة.
     * يرجع null إذا ما تكلمت.
     */
    @SuppressLint("MissingPermission") // نتأكد من إذن المايك قبل ما ننادي
    suspend fun record(onLevel: (Float) -> Unit): ByteArray? = withContext(Dispatchers.IO) {
        val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, RATE)
        )
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            return@withContext null
        }
        val pcm = ByteArrayOutputStream()
        val frame = ShortArray(FRAME)
        var noiseFloor = 0.0
        var frames = 0
        var speechStarted = false
        var silentMs = 0
        try {
            recorder.startRecording()
            while (isActive) {
                val n = recorder.read(frame, 0, frame.size)
                if (n <= 0) continue
                frames++
                val bytes = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
                var sum = 0.0
                for (i in 0 until n) {
                    bytes.putShort(frame[i])
                    sum += frame[i].toDouble() * frame[i]
                }
                pcm.write(bytes.array())
                val rms = sqrt(sum / n)
                // أول 300ms نقيس ضجة المكان، وبعدها الكلام = أعلى منها بوضوح.
                if (frames <= 3) noiseFloor = maxOf(noiseFloor, rms)
                val threshold = maxOf(700.0, noiseFloor * 2.5)
                onLevel((rms / 6000.0).coerceIn(0.0, 1.0).toFloat())

                val elapsed = frames * 100
                if (rms > threshold) {
                    speechStarted = true
                    silentMs = 0
                } else if (speechStarted) {
                    silentMs += 100
                }
                if (speechStarted && silentMs >= END_SILENCE_MS) break
                if (!speechStarted && elapsed >= WAIT_FOR_SPEECH_MS) break
                if (elapsed >= MAX_MS) break
            }
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
        }
        if (!speechStarted) null else wav(pcm.toByteArray())
    }

    /** يلف بيانات PCM بترويسة WAV (16kHz، أحادي، 16-bit). */
    fun wav(pcm: ByteArray): ByteArray {
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + pcm.size); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1)
            putInt(RATE); putInt(RATE * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(pcm.size)
        }
        return header.array() + pcm
    }
}
