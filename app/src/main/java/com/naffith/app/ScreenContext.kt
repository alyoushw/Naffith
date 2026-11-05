package com.naffith.app

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * لقطة الشاشة اللي يعطيها النظام للمساعد الافتراضي لما تفتحه (إذا فعّلت «استخدام لقطة الشاشة»
 * في إعدادات المساعد). نستخدمها لـ«اسأل عن الشاشة» ونمسحها بعد دقيقة.
 */
object ScreenContext {

    private const val MAX_SIDE = 1280
    private const val FRESH_MS = 60_000L

    @Volatile private var shot: Bitmap? = null
    @Volatile private var takenAt = 0L

    fun set(screenshot: Bitmap?) {
        shot = screenshot?.let { scaled(it) }
        takenAt = SystemClock.elapsedRealtime()
    }

    fun clear() {
        shot = null
    }

    /** لقطة حديثة (آخر دقيقة) أو null. */
    fun fresh(): Bitmap? = shot?.takeIf { SystemClock.elapsedRealtime() - takenAt < FRESH_MS }

    fun jpeg(bitmap: Bitmap): ByteArray =
        ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
            out.toByteArray()
        }

    /** نحفظها ملف مؤقت عشان نشاركها مع تطبيق Gemini. */
    fun shareUri(context: Context, bitmap: Bitmap): Uri {
        val dir = File(context.cacheDir, "screens").apply { mkdirs() }
        val file = File(dir, "screen.jpg")
        file.writeBytes(jpeg(bitmap))
        return FileProvider.getUriForFile(context, context.packageName + ".files", file)
    }

    private fun scaled(source: Bitmap): Bitmap {
        // لقطات النظام تكون أحياناً «HARDWARE» وما تنضغط؛ ننسخها لصورة عادية.
        val isHardware = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O &&
            source.config == Bitmap.Config.HARDWARE
        val soft = if (isHardware) source.copy(Bitmap.Config.ARGB_8888, false) else source
        val longest = maxOf(soft.width, soft.height)
        if (longest <= MAX_SIDE) return soft
        val ratio = MAX_SIDE.toFloat() / longest
        return Bitmap.createScaledBitmap(soft, (soft.width * ratio).toInt(), (soft.height * ratio).toInt(), true)
    }
}
