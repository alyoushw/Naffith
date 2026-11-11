package com.naffith.app.core

/** جواب «إشعار ولا شاشة كاملة؟»: true = شاشة كاملة، false = إشعار، null = ما فهمنا. */
object ReminderMode {
    private val full = listOf("شاشه", "كامل", "كامله", "صفحه", "اثنين", "ثنين", "الثاني", "الثانيه", "ثاني", "2", "٢", "منبه", "تنبيه")
    private val notif = listOf("اشعار", "الاشعار", "اشعارات", "واحد", "الاول", "الاولى", "اول", "1", "١", "فوق", "عادي")

    fun parse(text: String): Boolean? {
        val words = ArabicText.normalize(text).split(" ").filter { it.isNotEmpty() }
        val isFull = words.any { w -> full.any { w == it || w.endsWith(it) && it.length > 3 } }
        val isNotif = words.any { w -> notif.any { w == it || w == "ب$it" || w == "ك$it" } }
        return when {
            isFull && !isNotif -> true
            isNotif && !isFull -> false
            else -> null
        }
    }
}
