package com.naffith.app.core

import java.net.URLDecoder

/**
 * يطلع الوجهة من رابط خرائط Google (أو عنوان/إحداثيات مكتوبة) عشان نستخدمها في الملاحة.
 * يرجع «خط العرض,خط الطول» إذا لقى إحداثيات، وإلا اسم المكان أو النص نفسه.
 */
object MapsLink {

    private const val NUM = "-?\\d{1,3}\\.\\d+"
    /** الإحداثيات الدقيقة للمكان نفسه (مو مركز الخريطة). */
    private val exact = Regex("!3d($NUM)!4d($NUM)")
    private val atCenter = Regex("@($NUM),($NUM)")
    private val plainCoords = Regex("^\\s*($NUM)\\s*,\\s*($NUM)\\s*$")
    private val param = Regex("[?&](?:q|query|ll|destination|daddr|center)=([^&#]+)")
    private val place = Regex("/place/([^/@?]+)")

    fun isShortLink(text: String): Boolean =
        Regex("https?://(maps\\.app\\.goo\\.gl|goo\\.gl/maps|g\\.co/kgs)/", RegexOption.IGNORE_CASE).containsMatchIn(text)

    fun extractDestination(text: String): String {
        val t = text.trim()
        if (t.isEmpty()) return ""
        plainCoords.find(t)?.let { return "${it.groupValues[1]},${it.groupValues[2]}" }
        if (!t.startsWith("http", ignoreCase = true)) return t // عنوان مكتوب
        exact.find(t)?.let { return "${it.groupValues[1]},${it.groupValues[2]}" }
        param.find(t)?.let { m ->
            val value = decode(m.groupValues[1])
            plainCoords.find(value)?.let { return "${it.groupValues[1]},${it.groupValues[2]}" }
            if (value.isNotBlank()) return value
        }
        atCenter.find(t)?.let { return "${it.groupValues[1]},${it.groupValues[2]}" }
        place.find(t)?.let { return decode(it.groupValues[1]) }
        return t
    }

    private fun decode(s: String): String =
        runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s).replace('+', ' ').trim()
}
