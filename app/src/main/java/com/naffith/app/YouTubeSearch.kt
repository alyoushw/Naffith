package com.naffith.app

import com.naffith.app.core.YouTubeResults
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** يبحث في يوتيوب ويرجع رقم أول فيديو (بدون مفتاح API). يرجع null إذا ما قدر. */
object YouTubeSearch {

    suspend fun firstVideoId(query: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val url = URL(
                "https://www.youtube.com/results?search_query=" + URLEncoder.encode(query, "UTF-8") + "&hl=ar&gl=SA"
            )
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 6000
                readTimeout = 8000
                setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36"
                )
                setRequestProperty("Accept-Language", "ar-SA,ar;q=0.9")
                setRequestProperty("Cookie", "CONSENT=YES+1")
            }
            try {
                val html = conn.inputStream.bufferedReader().use { it.readText() }
                YouTubeResults.firstVideoId(html)
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
    }
}
