package com.naffith.app.core

import com.naffith.app.core.ArabicText.Token

/**
 * يحلل الجملة المنطوقة (بالسعودي أو الفصحى) ويحوّلها إلى [Command].
 * كل الكلمات هنا مكتوبة بصيغتها الموحّدة (انظر [ArabicText.normalize]).
 */
object CommandParser {

    /** سوابق تلتصق بالكلمة: «باليوتيوب»، «عالواتس»، «والخرايط»… */
    private val prefixes = listOf("وبال", "بال", "وال", "فال", "عال", "كال", "لل", "ال", "وب", "ب", "و", "ف", "ل", "ع")

    private val whatsappWords = setOf("واتساب", "واتس", "وتساب", "وتس", "واتسب", "whatsapp", "watsap", "واتسابي")
    private val youtubeWords = setOf("يوتيوب", "يوتوب", "يتيوب", "يوتيوبي", "youtube")
    private val mapsWords = setOf("خرايط", "خريطه", "ماب", "مابس", "maps", "map", "خرايطي")
    private val navigationWords = setOf("ودني", "وديني", "وصلني", "خذني", "دلني", "وين", "مكان", "موقع", "طريق", "اتجاهات", "navigate")
    private val callWords = setOf("اتصل", "اتصلي", "اتصال", "دق", "دقي", "كلم", "كلمي", "اضرب", "call", "تتصل", "اتصلوا")
    private val playWords = setOf("شغل", "شغلي", "شغللي")

    /** كلمات تُحذف من بداية أو نهاية المعامل (البحث أو الاسم). */
    private val commonEdgeWords = setOf(
        "افتح", "افتحي", "افتحلي", "فتح", "شغل", "شغلي", "ابحث", "ابحثي", "بحث", "دور", "دوري", "دورلي",
        "سرش", "جيب", "جيبلي", "اعرض", "ورني", "وريني", "طلع", "طلعلي", "اظهر", "روح", "رح",
        "في", "على", "عن", "من", "مع", "الى", "لي", "ل", "عند",
        "تطبيق", "برنامج", "قوقل", "جوجل", "google", "الحين", "بسرعه", "الان", "حق", "اللي", "يعني",
        "ابي", "ابغى", "ابغي", "ابا", "بغيت", "ودي", "اريد", "ممكن", "تكفى", "تكفين", "لو", "سمحت",
        "please", "open", "search", "for", "on", "in"
    )
    private val contactEdgeWords = setOf(
        "راسل", "راسلي", "ارسل", "رسل", "ابعث", "ارسال", "محادثه", "شات", "دردشه", "رساله", "مسج", "مسجات",
        "كلم", "كلمي", "اتصل", "دق", "رقم", "جوال", "بجوال", "تلفون", "اسمه", "اسمها", "يا"
    )

    /** عبارات مجاملة تُحذف قبل التحليل. */
    private val fillerPhrases = listOf(
        "لو سمحت", "لو سمحتي", "من فضلك", "الله يعافيك", "الله يخليك", "الله يسعدك", "يا نفذ", "يا نافذ", "نفذ"
    )

    /** يحلل كل البدائل التي رجعها محرك التعرف ويختار أول بديل مفهوم. */
    fun parseBest(alternatives: List<String>): Pair<String, Command> {
        val cleaned = alternatives.map { it.trim() }.filter { it.isNotEmpty() }
        for (alt in cleaned) {
            val cmd = parse(alt)
            if (cmd !is Command.Unknown) return alt to cmd
        }
        val first = cleaned.firstOrNull().orEmpty()
        return first to Command.Unknown(first)
    }

    /**
     * يستخرج اسم جهة الاتصال من جواب المستخدم لما نسأله «على مين؟» أو يعيد المحاولة.
     * لو أعاد الأمر كامل («افتح الواتساب محادثة الجنوبي») نأخذ الاسم منه بس، مو الجملة كلها.
     */
    fun parseName(text: String): List<String> {
        when (val cmd = parse(text)) {
            is Command.Call -> if (cmd.contactNames.isNotEmpty()) return cmd.contactNames
            is Command.WhatsApp -> if (cmd.contactNames.isNotEmpty()) return cmd.contactNames
            else -> {}
        }
        val tokens = stripFillers(ArabicText.tokenize(text))
        return contactNames(tokens, whatsappWords).ifEmpty { listOf(text.trim()).filter { it.isNotEmpty() } }
    }

    fun parse(text: String): Command {
        val tokens = stripFillers(ArabicText.tokenize(text))
        if (tokens.isEmpty()) return Command.Unknown(text)

        fun has(words: Set<String>) = tokens.any { isWord(it.normalized, words) }

        return when {
            has(whatsappWords) -> Command.WhatsApp(contactNames(tokens, whatsappWords))
            has(youtubeWords) -> Command.SearchYouTube(query(tokens, youtubeWords))
            has(mapsWords) -> Command.OpenMaps(query(tokens, mapsWords))
            has(navigationWords) -> Command.OpenMaps(query(tokens, navigationWords))
            has(callWords) -> Command.Call(contactNames(tokens, callWords))
            // «شغل لي أغنية…» بدون ذكر التطبيق ← نفترض يوتيوب، بشرط وجود شيء نبحث عنه.
            has(playWords) ->
                query(tokens, playWords).takeIf { it.isNotEmpty() }
                    ?.let { Command.SearchYouTube(it) } ?: Command.Unknown(text)
            else -> Command.Unknown(text)
        }
    }

    /** هل الكلمة (أو الكلمة بعد حذف سابقة ملتصقة بها) ضمن المجموعة؟ */
    internal fun isWord(token: String, words: Set<String>): Boolean {
        if (token in words) return true
        return prefixes.any { p -> token.length > p.length + 1 && token.startsWith(p) && token.substring(p.length) in words }
    }

    private fun stripFillers(tokens: List<Token>): List<Token> {
        var result = tokens
        for (phrase in fillerPhrases) {
            val parts = phrase.split(" ")
            var i = 0
            val out = ArrayList<Token>(result.size)
            while (i < result.size) {
                val matches = i + parts.size <= result.size &&
                    parts.indices.all { result[i + it].normalized == parts[it] }
                if (matches) i += parts.size else out.add(result[i++])
            }
            result = out
        }
        return result
    }

    /** يحذف كلمات التطبيق من أي مكان، ثم كلمات الربط والأفعال من الأطراف فقط. */
    private fun argumentTokens(tokens: List<Token>, appWords: Set<String>, edgeWords: Set<String>): List<Token> {
        val rest = tokens.filterNot { isWord(it.normalized, appWords) }.toMutableList()
        val edges = edgeWords.mapTo(HashSet()) { ArabicText.normalize(it) }
        fun isEdge(t: Token): Boolean {
            // «على» تتوحّد إلى «علي» وهو اسم شائع، فلا نحذفها إذا كانت آخر كلمة باقية.
            if (t.normalized == "علي") return rest.size > 1 && t.original.endsWith("ى")
            return t.normalized in edges || (t.normalized.startsWith("و") && t.normalized.drop(1) in edges)
        }
        while (rest.isNotEmpty() && isEdge(rest.first())) rest.removeAt(0)
        while (rest.isNotEmpty() && isEdge(rest.last())) rest.removeAt(rest.size - 1)
        return rest
    }

    private fun query(tokens: List<Token>, appWords: Set<String>): String =
        argumentTokens(tokens, appWords, commonEdgeWords + playWords)
            .joinToString(" ") { it.original }

    private fun contactNames(tokens: List<Token>, appWords: Set<String>): List<String> {
        val rest = argumentTokens(tokens, appWords + callWords, commonEdgeWords + contactEdgeWords)
        if (rest.isEmpty()) return emptyList()
        val name = rest.joinToString(" ") { it.original }
        val result = mutableListOf(name)
        // «اتصل بخالد» / «راسل لسارة»: الباء أو اللام قد تكون ملتصقة بالاسم.
        val first = rest.first().original
        val firstNorm = rest.first().normalized
        if (firstNorm.length > 3 && (firstNorm.startsWith("ب") || firstNorm.startsWith("ل"))) {
            val stripped = (listOf(first.drop(1)) + rest.drop(1).map { it.original }).joinToString(" ")
            result.add(stripped)
        }
        return result
    }
}
