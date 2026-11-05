package com.naffith.app.core

/** أماكن محفوظة في خرائط Google: «المنزل» يروح للبيت المحفوظ، مو بحث عن كلمة «منزل». */
object Places {

    private val home = setOf(
        "المنزل", "منزل", "منزلي", "البيت", "بيت", "بيتي", "البيت حقي", "بيتنا", "home"
    )
    private val work = setOf(
        "العمل", "عمل", "عملي", "الدوام", "دوام", "دوامي", "الشغل", "شغلي", "المكتب", "مكتبي", "work"
    )

    /** يرجع «Home» أو «Work» (أسماء خرائط Google للأماكن المحفوظة)، وإلا الوجهة نفسها. */
    fun navigationTarget(query: String): String {
        val n = ArabicText.normalize(query)
        return when (n) {
            in home.map { ArabicText.normalize(it) } -> "Home"
            in work.map { ArabicText.normalize(it) } -> "Work"
            else -> query.trim()
        }
    }
}
