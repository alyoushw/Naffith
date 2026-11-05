package com.naffith.app

import android.content.Context
import com.naffith.app.core.MapsLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * البيت والدوام: المستخدم يلصق رابط الموقع من خرائط Google (أو يكتب العنوان)، ونحفظ الوجهة
 * في الجوال بس، عشان «وجهني للمنزل» يروح له على طول.
 */
object SavedPlaces {

    enum class Kind(val key: String) { HOME("place_home"), WORK("place_work") }

    private const val PREFS = "settings"

    fun destination(context: Context, kind: Kind): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(kind.key, "").orEmpty()

    fun clear(context: Context, kind: Kind) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(kind.key).apply()

    /** يحفظ الرابط/العنوان ويرجع الوجهة اللي استخرجناها (أو null إذا فاضي). */
    suspend fun save(context: Context, kind: Kind, input: String): String? {
        val text = input.trim()
        if (text.isEmpty()) return null
        val full = if (MapsLink.isShortLink(text)) expandShortLink(text) ?: text else text
        val dest = MapsLink.extractDestination(full)
        if (dest.isBlank()) return null
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(kind.key, dest).apply()
        return dest
    }

    /** روابط المشاركة القصيرة (maps.app.goo.gl/…) نفتحها عشان نوصل للرابط الكامل اللي فيه الإحداثيات. */
    private suspend fun expandShortLink(url: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            var current = Regex("https?://\\S+").find(url)?.value ?: url
            repeat(6) {
                val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = 8000
                    readTimeout = 8000
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/130.0 Mobile")
                }
                val location = try {
                    if (conn.responseCode in 300..399) conn.getHeaderField("Location") else null
                } finally {
                    conn.disconnect()
                }
                if (location.isNullOrBlank()) return@runCatching current
                current = URL(URL(current), location).toString()
            }
            current
        }.getOrNull()
    }
}
