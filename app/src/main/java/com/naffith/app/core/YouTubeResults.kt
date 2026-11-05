package com.naffith.app.core

/** يطلع رقم أول فيديو من صفحة نتائج بحث يوتيوب، عشان نشغّله على طول. */
object YouTubeResults {

    private val videoRenderer = Regex("\"videoRenderer\":\\{\"videoId\":\"([A-Za-z0-9_-]{11})\"")
    private val anyVideo = Regex("\"videoId\":\"([A-Za-z0-9_-]{11})\"")

    fun firstVideoId(html: String): String? =
        videoRenderer.find(html)?.groupValues?.get(1) ?: anyVideo.find(html)?.groupValues?.get(1)
}
