package com.naffith.app.core

/**
 * مقارنة الأسماء بين العربي والإنجليزي.
 *
 * كل اسم يتحول إلى «هيكل صوتي» من الحروف الساكنة فقط، بحيث
 * «محمد» و «Mohammed» ← mhmd، و «خالد» و «Khalid» ← kld، و «الجنوبي» و «Aljanoubi» ← ljnb.
 * وأسماء الحروف المنطوقة بالعربي («اتش اس ان») تتحول إلى حروف إنجليزية (hsn).
 */
object Phonetics {

    private val arabicMap = mapOf(
        'ب' to "b", 'ت' to "t", 'ث' to "s", 'ج' to "j", 'ح' to "h", 'خ' to "k", 'د' to "d",
        'ذ' to "z", 'ر' to "r", 'ز' to "z", 'س' to "s", 'ش' to "s", 'ص' to "s", 'ض' to "d",
        'ط' to "t", 'ظ' to "z", 'غ' to "j", 'ف' to "f", 'ق' to "k", 'ك' to "k", 'ل' to "l",
        'م' to "m", 'ن' to "n", 'ه' to "h",
        // ا و ي ع ء تُعتبر أحرف مد/حلقية وتُحذف مثل الحروف المتحركة بالإنجليزي.
    )

    private val latinDigraphs = listOf("sh" to "s", "th" to "t", "kh" to "k", "gh" to "j", "dh" to "d", "ph" to "f", "ch" to "s")
    private val latinMap = mapOf(
        'b' to "b", 'p' to "b", 't' to "t", 'd' to "d", 'k' to "k", 'q' to "k", 'c' to "k",
        'g' to "j", 'j' to "j", 'f' to "f", 'v' to "f", 's' to "s", 'z' to "z", 'x' to "ks",
        'h' to "h", 'l' to "l", 'm' to "m", 'n' to "n", 'r' to "r",
    )

    /** أسماء الحروف الإنجليزية كما ينطقها/يكتبها محرك التعرف العربي (بعد التوحيد). */
    private val letterNames: Map<String, List<Char>> = mapOf(
        "ايه" to listOf('a'), "اي" to listOf('a', 'i', 'e'), "بي" to listOf('b', 'p'), "سي" to listOf('c'),
        "دي" to listOf('d'), "يي" to listOf('e'), "اف" to listOf('f'), "جي" to listOf('g', 'j'),
        "اتش" to listOf('h'), "ايتش" to listOf('h'), "اتشي" to listOf('h'), "جاي" to listOf('j'), "جيه" to listOf('j'),
        "كي" to listOf('k'), "كاي" to listOf('k'), "ال" to listOf('l'), "ام" to listOf('m'), "ان" to listOf('n'),
        "او" to listOf('o'), "كيو" to listOf('q'), "ار" to listOf('r'), "اس" to listOf('s'), "تي" to listOf('t'),
        "يو" to listOf('u'), "في" to listOf('v'), "دبليو" to listOf('w'), "اكس" to listOf('x'),
        "واي" to listOf('y'), "زد" to listOf('z'), "زي" to listOf('z'),
    )

    fun isLatin(token: String): Boolean = token.any { it in 'a'..'z' }

    /** الهيكل الصوتي لكلمة واحدة (موحّدة مسبقاً). */
    fun skeleton(token: String): String {
        val raw = if (isLatin(token)) latinSkeleton(token) else arabicSkeleton(token)
        val collapsed = StringBuilder()
        for (c in raw) if (collapsed.isEmpty() || collapsed.last() != c) collapsed.append(c)
        // الهاء في آخر الاسم غالباً ما تُكتب بالإنجليزي: «عبدالله»/Abdulla، «فاطمه»/Fatima.
        if (collapsed.length > 2 && collapsed.last() == 'h') collapsed.setLength(collapsed.length - 1)
        return collapsed.toString()
    }

    private fun arabicSkeleton(token: String): String =
        token.map { arabicMap[it] ?: "" }.joinToString("")

    private fun latinSkeleton(token: String): String {
        var t = token.lowercase()
        for ((from, to) in latinDigraphs) t = t.replace(from, to)
        return t.map { latinMap[it] ?: "" }.joinToString("")
    }

    /**
     * يحوّل الحروف المتهجّاة إلى اختصار إنجليزي: «الجنوبي اتش اس ان» ← «الجنوبي hsn».
     * يحتاج حرفين متتاليين على الأقل (عشان «ام محمد» تبقى كما هي).
     * يرجع كل الاحتمالات (بحد أقصى 8) أو قائمة فاضية إذا ما فيه حروف متهجّاة.
     */
    fun spelledLetterVariants(normalizedText: String): List<String> {
        val tokens = normalizedText.split(" ").filter { it.isNotEmpty() }
        fun letterOptions(t: String): List<Char>? =
            letterNames[t] ?: if (t.length == 1 && t[0] in 'a'..'z') listOf(t[0]) else null

        var variants = listOf(emptyList<String>())
        var changed = false
        var i = 0
        while (i < tokens.size) {
            var j = i
            while (j < tokens.size && letterOptions(tokens[j]) != null) j++
            if (j - i >= 2) {
                var acronyms = listOf("")
                for (k in i until j) {
                    acronyms = acronyms.flatMap { prefix -> letterOptions(tokens[k])!!.map { prefix + it } }.take(8)
                }
                variants = variants.flatMap { v -> acronyms.map { v + it } }.take(8)
                changed = true
                i = j
            } else {
                variants = variants.map { it + tokens[i] }
                i++
            }
        }
        return if (changed) variants.map { it.joinToString(" ") } else emptyList()
    }
}
