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
    /** كلمات التوجيه: تبدأ الملاحة على طول في خرائط Google. */
    private val navigateWords = setOf(
        "وجهني", "وجهيني", "ودني", "وديني", "وصلني", "خذني", "دلني", "اتجاهات", "navigate", "directions"
    )
    /** كلمات السؤال عن مكان: تعرض نتائج البحث في الخرائط. */
    private val placeWords = setOf("وين", "مكان", "موقع", "طريق")
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

    /** كلمات تبدأ بعدها الرسالة: «اكتب هلا»، «قل له وينك». */
    private val messageMarkers = setOf(
        "اكتب", "اكتبي", "اكتبله", "اكتبلها", "اكتبلهم", "قل", "قله", "قلها", "قول", "قوله", "قولها", "قوليه",
        "ارسل", "ارسله", "ارسلها", "ارسلي", "ابعث", "ابعثله", "ابعثلها", "رسل", "رسله", "بلغه", "بلغها",
        "تقول", "يقول", "مكتوب", "نصها", "write", "say", "send"
    )
    /** كلمات الإرسال: وجودها يعني نضغط إرسال، وفي آخر الجملة («… وارسلها») مو جزء من الرسالة. */
    private val sendWords = setOf(
        "ارسل", "ارسله", "ارسلها", "ارسلي", "ارسلهم", "رسل", "رسله", "رسلها", "ابعث", "ابعثه", "ابعثها", "ابعثله",
        "ابعثلها", "دز", "دزه", "دزها", "send"
    )

    /** كلمات تُحذف من بداية الرسالة: «اكتب له هلا» ← «هلا». */
    private val messageLeadWords = setOf("له", "لها", "لهم", "ليه", "فيها", "رساله", "مسج", "انه", "انها")
    private val messageTrailWords = setOf("في", "على", "ب", "عن", "من", "طريق")

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
            has(whatsappWords) -> whatsApp(tokens)
            has(youtubeWords) -> Command.SearchYouTube(query(tokens, youtubeWords), play = has(playWords))
            // «وجهني الى المنزل» / «افتح الخرائط ووديني العثيم مول»: ملاحة مباشرة.
            has(navigateWords) -> Command.OpenMaps(navigationQuery(tokens), navigate = true)
            has(mapsWords) -> Command.OpenMaps(query(tokens, mapsWords))
            has(placeWords) -> Command.OpenMaps(query(tokens, placeWords))
            // «ارسل لروحي هلا» بدون ذكر واتساب: الرسايل تروح واتساب.
            isMessageToSomeone(tokens) -> whatsApp(tokens)
            has(callWords) -> Command.Call(contactNames(tokens, callWords))
            // «شغل لي أغنية…» بدون ذكر التطبيق ← نفترض يوتيوب، بشرط وجود شيء نبحث عنه.
            has(playWords) ->
                query(tokens, playWords).takeIf { it.isNotEmpty() }
                    ?.let { Command.SearchYouTube(it, play = true) } ?: Command.Unknown(text)
            else -> Command.Unknown(text)
        }
    }

    private fun isMarker(t: Token) = t.normalized in messageMarkers ||
        (t.normalized.startsWith("و") && t.normalized.drop(1) in messageMarkers)

    /** كلمة لها معنى في جزء الاسم (مو اسم التطبيق ولا فعل ولا حرف جر). */
    private fun isMeaningful(t: Token) = !isWord(t.normalized, whatsappWords) && !isMarker(t) &&
        t.normalized !in commonEdgeWords && t.normalized !in contactEdgeWords &&
        !(t.normalized.startsWith("و") && t.normalized.drop(1) in commonEdgeWords)

    /**
     * واتساب مع رسالة اختيارية:
     * «افتح واتساب روحي اكتب هلا»، «راسل خالد بالواتس قل له وينك»، «قول لروحي بالواتساب هلا».
     */
    private fun isSendWord(t: Token) = t.normalized in sendWords ||
        (t.normalized.startsWith("و") && t.normalized.drop(1) in sendWords)

    private fun whatsApp(all: List<Token>): Command.WhatsApp {
        // «واتس اب» تنكتب أحياناً كلمتين.
        val withApp = all.filterIndexed { i, t ->
            !(t.normalized == "اب" && i > 0 && isWord(all[i - 1].normalized, whatsappWords))
        }.toMutableList()

        // «… وارسلها» / «… وارسل» في الآخر: أمر إرسال، مو جزء من الرسالة.
        val send = withApp.any(::isSendWord)
        while (withApp.size > 1 && (isSendWord(withApp.last()) || isWord(withApp.last().normalized, whatsappWords) ||
                withApp.last().normalized in messageTrailWords || withApp.last().normalized == "الحين")
        ) {
            withApp.removeAt(withApp.size - 1)
        }
        val tokens: List<Token> = withApp

        // 1) فيه اسم ثم كلمة رسالة: كل اللي بعدها رسالة.
        val split = tokens.indices.firstOrNull { i -> isMarker(tokens[i]) && tokens.take(i).any(::isMeaningful) }
        if (split != null) {
            return Command.WhatsApp(contactNames(tokens.take(split), whatsappWords), message(tokens.drop(split + 1)), send)
        }

        // 2) يبدأ بكلمة رسالة والاسم والرسالة ورا بعض:
        //    «ارسل واتساب روحي هلا»، «افتح الواتساب ارسل لمحمد الحفة»، «قول لروحي بالواتساب هلا».
        //    ما نعرف وين ينتهي الاسم إلا من جهات الاتصال، فنخليها للمطابقة
        //    (انظر ContactMatcher.splitNameAndMessage).
        val rest = tokens.filterNot { isWord(it.normalized, whatsappWords) }
            .dropWhile { it.normalized in commonEdgeWords && !isMarker(it) }
        if (rest.isNotEmpty() && isMarker(rest[0])) {
            val body = rest.drop(1).dropWhile {
                it.normalized in messageLeadWords || it.normalized in contactEdgeWords || it.normalized in commonEdgeWords
            }
            if (body.size >= 2) {
                return Command.WhatsApp(emptyList(), send = send, nameAndMessage = body.joinToString(" ") { it.original })
            }
            if (body.size == 1) return Command.WhatsApp(nameVariants(body), send = send)
        }

        return Command.WhatsApp(contactNames(tokens, whatsappWords), send = send)
    }

    private fun message(tokens: List<Token>): String {
        val msg = tokens.toMutableList()
        while (msg.isNotEmpty() && (msg.first().normalized in messageLeadWords || isMarker(msg.first()))) msg.removeAt(0)
        // «… بالواتس» / «… في الواتساب» في آخر الجملة مو من الرسالة.
        while (msg.isNotEmpty() && (isWord(msg.last().normalized, whatsappWords) || msg.last().normalized in messageTrailWords)) {
            msg.removeAt(msg.size - 1)
        }
        return msg.joinToString(" ") { it.original }
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

    /** «ارسل لروحي …» / «ابعث رسالة لخالد …» بدون ذكر التطبيق. */
    private fun isMessageToSomeone(tokens: List<Token>): Boolean {
        val rest = tokens.dropWhile { it.normalized in commonEdgeWords && !isMarker(it) }
        if (rest.isEmpty() || !isMarker(rest[0]) || rest[0].normalized in setOf("تقول", "يقول", "مكتوب", "say")) return false
        val target = rest.drop(1).dropWhile { it.normalized in messageLeadWords }.firstOrNull() ?: return false
        return target.normalized.startsWith("ل") && target.normalized.length > 2 && target.normalized !in messageLeadWords
    }

    /** وجهة الملاحة: «وجهني الى المنزل» ← «المنزل»، «وديني للعثيم مول» ← «العثيم مول». */
    private fun navigationQuery(tokens: List<Token>): String {
        val rest = argumentTokens(tokens, mapsWords + navigateWords, commonEdgeWords - setOf("شغل", "شغلي")).toMutableList()
        if (rest.isEmpty()) return ""
        val first = rest[0]
        val n = first.normalized
        rest[0] = when {
            n.startsWith("لل") && n.length > 3 -> Token("ال" + first.original.drop(2), "ال" + n.drop(2))
            n.startsWith("ل") && n.length > 3 && !n.startsWith("لا") -> Token(first.original.drop(1), n.drop(1))
            else -> first
        }
        return rest.joinToString(" ") { it.original }
    }

    private fun contactNames(tokens: List<Token>, appWords: Set<String>): List<String> {
        val rest = argumentTokens(tokens, appWords + callWords, commonEdgeWords + contactEdgeWords)
        return nameVariants(rest)
    }

    private fun nameVariants(rest: List<Token>): List<String> {
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
