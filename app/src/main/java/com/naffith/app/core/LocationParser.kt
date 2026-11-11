package com.naffith.app.core

/** «انسخ موقعي»، «ارسل موقعي لروحي»، «احفظ آخر رقم باسم المندوب»، «ارسل واتساب لآخر رقم». */
object LocationParser {

    private fun n(s: String) = ArabicText.normalize(s)
    private fun set(vararg w: String) = w.map { n(it) }.toSet()

    private val locationWords = set("موقعي", "لوكيشني", "مكاني", "اللوكيشن", "لوكيشن", "الموقع", "موقع", "احداثياتي", "لوكيشنى")
    private val copyWords = set("انسخ", "انسخلي", "نسخ", "انسخه", "كوبي", "كوبي")
    private val sendWords = set(
        "ارسل", "ارسله", "ارسلي", "رسل", "ابعث", "ابعثه", "ابعثلي", "دز", "شارك", "شاركه", "ارسلها", "ابعثها", "راسل", "اكتب", "اكتبله"
    )
    private val whatsappWords = set("واتساب", "الواتساب", "وتساب", "واتس", "الواتس", "بالواتساب", "بالواتس", "واتسب")
    private val saveWords = set("اضف", "ضيف", "احفظ", "خزن", "حفظ", "اضيف", "ضيفه", "احفظه", "سجله")
    private val callWords = set("اتصل", "دق", "كلم", "اتصلي", "دقي")
    private val nameMarkers = set("باسم", "اسمه", "بأسم", "بإسم", "سمه", "سميه")

    /** عبارات «آخر رقم». */
    private val lastPhrases = listOf(
        "اخر رقم", "اخر متصل", "اخر واحد اتصل", "اخر واحد كلمني", "اخر شخص اتصل", "اخر اتصال", "اخر مكالمه",
        "اللي اتصل علي", "الي اتصل علي", "اللي اتصل عليي", "اللي دق علي", "الي دق علي", "اخر رقم اتصل", "اخر واحد دق",
        "اخر واحد", "اخر شخص",
    ).map { n(it) }.sortedByDescending { it.length }

    /** كلمات ما تكون من الاسم ولا الرسالة. */
    private val fillers = set(
        "على", "الى", "لي", "حق", "حقي", "في", "عبر", "رقم", "جوال", "سجل", "المكالمات", "الاتصال", "الاتصالات",
        "السجل", "بالسجل", "من", "جهات", "جهه", "جهات", "اتصالي", "اتصالاتي", "الاسماء", "كونتاكت", "رساله", "مسج",
        "يا", "مرسال", "له", "لها", "وقله", "قله", "وقول", "وقل", "قل", "عند", "اللي", "الي", "ابي", "ابغى", "ممكن",
    )

    fun parse(text: String): Command? {
        // «لآخر» ← «اخر»، «للي» ← «اللي» (حرف الجر ملتصق).
        val words = n(text).split(" ").filter { it.isNotEmpty() }.map { w ->
            when {
                (w.startsWith("لاخر") || w.startsWith("باخر")) && w.length > 4 -> w.drop(1)
                w == "لاخر" || w == "باخر" -> "اخر"
                w == "للي" || w == "لللي" -> "اللي"
                else -> w
            }
        }
        val norm = words.joinToString(" ")
        if (words.isEmpty()) return null
        fun has(s: Set<String>) = words.any { it in s }
        val lastPhrase = lastPhrases.firstOrNull { " $norm ".contains(" $it ") }
        val hasLoc = words.any { it in locationWords }

        if (hasLoc && (has(copyWords) || has(sendWords) || has(whatsappWords) || lastPhrase != null)) {
            if (has(copyWords) && !has(sendWords) && lastPhrase == null) return Command.ShareLocation(emptyList(), copyOnly = true)
            if (lastPhrase != null) return Command.ShareLocation(emptyList(), toLastCaller = true)
            val rest = words.filterNot { it in locationWords || it in sendWords || it in whatsappWords || it in fillers || it in copyWords }
            return Command.ShareLocation(nameVariants(rest))
        }
        if (lastPhrase == null) return null

        // اللي قبل وبعد «آخر رقم».
        val idx = norm.indexOf(lastPhrase)
        val before = norm.substring(0, idx).split(" ").filter { it.isNotEmpty() }
        val after = norm.substring(idx + lastPhrase.length).split(" ").filter { it.isNotEmpty() }
        val first = before.firstOrNull() ?: words.first()

        // «احفظ/ضيف آخر رقم … باسم أبو فهد» (أو «سجل آخر رقم باسم …»)
        val wantsSave = before.any { it in saveWords } || (first == n("سجل") && before.isNotEmpty()) ||
            words.any { it in nameMarkers }
        if (wantsSave) {
            val m = words.indexOfFirst { it in nameMarkers }
            val name = if (m >= 0) originalTail(text, words.size - m - 1) else ""
            return Command.LastCaller(LastCallerAction.SAVE, name = name)
        }
        if (before.any { it in callWords }) return Command.LastCaller(LastCallerAction.CALL)
        if (has(whatsappWords) || before.any { it in sendWords }) {
            // الرسالة: اللي بعد «آخر رقم» بعد ما نشيل «في سجل المكالمات»، «واتساب»، «وقله»…
            val msgWords = after.dropWhile { it in whatsappWords || it in fillers || it == n("اتصل") || it == n("علي") }
            val message = originalTail(text, msgWords.size)
            return Command.LastCaller(LastCallerAction.WHATSAPP, message = message, send = before.any { it in sendWords })
        }
        return Command.LastCaller(LastCallerAction.SHOW)
    }

    /** آخر [count] كلمات من النص الأصلي (عشان نحافظ على الكتابة). */
    private fun originalTail(text: String, count: Int): String {
        if (count <= 0) return ""
        val orig = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        return orig.takeLast(count).joinToString(" ").trim()
    }

    private fun nameVariants(rest: List<String>): List<String> {
        if (rest.isEmpty()) return emptyList()
        val name = rest.joinToString(" ")
        val first = rest.first()
        val out = mutableListOf(name)
        if (first.length > 3 && (first.startsWith("ل") || first.startsWith("ب"))) {
            out += (listOf(first.drop(1)) + rest.drop(1)).joinToString(" ")
        }
        return out
    }
}
