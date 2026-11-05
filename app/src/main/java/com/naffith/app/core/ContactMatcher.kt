package com.naffith.app.core

import kotlin.math.max
import kotlin.math.min

data class Contact(val id: String, val name: String, val number: String)

data class ContactMatch(val contact: Contact, val score: Double)

sealed interface MatchResult {
    data object None : MatchResult
    data class Single(val contact: Contact) : MatchResult
    data class Multiple(val matches: List<ContactMatch>) : MatchResult
}

/**
 * مطابقة تقريبية لأسماء جهات الاتصال.
 * - تتجاهل التشكيل والفروقات مثل أ/ا و ة/ه و ى/ي، والمسافات («عبدالله» = «عبد الله»).
 * - تتجاهل «ال» التعريف («الجنوبي» = «جنوبي»).
 * - تطابق بين العربي والإنجليزي («محمد» = Mohammed، «اتش اس ان» = HSN).
 * - تتسامح مع الأخطاء البسيطة في النطق.
 */
object ContactMatcher {

    const val THRESHOLD = 0.72
    private const val SUGGESTION_THRESHOLD = 0.45
    private const val EXACT = 1.0
    private const val MAX_RESULTS = 10

    fun match(queries: List<String>, contacts: List<Contact>): MatchResult {
        val scored = scoreAll(queries, contacts).filter { it.score >= THRESHOLD }
        return when {
            scored.isEmpty() -> MatchResult.None
            scored.size == 1 -> MatchResult.Single(scored[0].contact)
            // تطابق تام وحيد يغلب التطابقات الجزئية.
            scored[0].score >= EXACT && scored[1].score < EXACT -> MatchResult.Single(scored[0].contact)
            else -> MatchResult.Multiple(scored.take(MAX_RESULTS))
        }
    }

    /** أقرب الأسماء حتى لو ما وصلت حد التطابق، نعرضها كاقتراحات لما ما نلقى الاسم. */
    fun suggestions(queries: List<String>, contacts: List<Contact>, limit: Int = 6): List<Contact> =
        scoreAll(queries, contacts)
            .filter { it.score >= SUGGESTION_THRESHOLD }
            .take(limit)
            .map { it.contact }

    private fun scoreAll(queries: List<String>, contacts: List<Contact>): List<ContactMatch> {
        val usable = expandQueries(queries)
        if (usable.isEmpty() || contacts.isEmpty()) return emptyList()
        return dedupe(contacts)
            .map { c -> ContactMatch(c, usable.maxOf { q -> score(q, c.name) }) }
            .sortedByDescending { it.score }
    }

    /** نضيف للطلب صيغته بالحروف المتهجّاة: «اتش اس ان» ← hsn. */
    private fun expandQueries(queries: List<String>): List<String> =
        queries.map { ArabicText.normalize(it) }
            .filter { it.isNotEmpty() }
            .flatMap { listOf(it) + Phonetics.spelledLetterVariants(it) }
            .distinct()

    /** درجة التشابه بين ما قاله المستخدم واسم جهة الاتصال (0..1). */
    fun score(query: String, name: String): Double {
        val q = ArabicText.normalize(query)
        val n = ArabicText.normalize(name)
        if (q.isEmpty() || n.isEmpty()) return 0.0

        val qc = q.replace(" ", "")
        val nc = n.replace(" ", "")
        if (qc == nc) return EXACT

        val qTokens = q.split(" ")
        val nTokens = n.split(" ")
        var best = 0.0

        // نفس الاسم بعد تجاهل «ال»: «الجنوبي» = «جنوبي».
        if (qTokens.map(::stripArticle) == nTokens.map(::stripArticle)) best = max(best, 0.97)

        // كل كلمات الطلب موجودة في الاسم: «أحمد» ← «أحمد العتيبي»، «الجنوبي» ← «الجنوبي HSN».
        val perToken = qTokens.map { qt -> nTokens.maxOf { tokenSimilarity(qt, it) } }
        if (perToken.all { it >= 0.97 }) best = max(best, 0.92)

        // بداية الاسم: «أبو فه» ← «أبو فهد».
        if (qc.length >= 3 && nc.startsWith(qc)) best = max(best, 0.85)

        // تشابه الاسم كاملاً (أخطاء بسيطة في النطق أو الكتابة).
        best = max(best, similarity(qc, nc) * 0.95)

        // تشابه كلمة بكلمة: كل كلمة في الطلب تشبه كلمة في الاسم.
        if (perToken.all { it >= 0.75 }) best = max(best, perToken.average() * 0.88)

        return best
    }

    /** تشابه كلمتين: نفس اللغة ← حروف، لغتين مختلفتين ← الهيكل الصوتي. */
    internal fun tokenSimilarity(a: String, b: String): Double {
        if (a == b) return 1.0
        if (Phonetics.isLatin(a) == Phonetics.isLatin(b)) {
            if (stripArticle(a) == stripArticle(b)) return 1.0
            return max(similarity(a, b), similarity(stripArticle(a), stripArticle(b)))
        }
        var best = 0.0
        for (x in listOf(a, stripArticle(a)).distinct()) for (y in listOf(b, stripArticle(b)).distinct()) {
            val sx = Phonetics.skeleton(x)
            val sy = Phonetics.skeleton(y)
            if (sx.isEmpty() || sy.isEmpty()) continue
            val s = when {
                sx == sy && sx.length >= 3 -> 0.97
                sx == sy && sx.length == 2 -> 0.9
                sx == sy -> 0.8
                min(sx.length, sy.length) >= 3 -> similarity(sx, sy) * 0.9
                else -> 0.0
            }
            best = max(best, s)
        }
        return best
    }

    /** يحذف «ال» من بداية الكلمة (عربي) أو al/el (إنجليزي). */
    private fun stripArticle(token: String): String = when {
        token.length > 3 && token.startsWith("ال") -> token.substring(2)
        token.length > 4 && (token.startsWith("al") || token.startsWith("el")) -> token.substring(2)
        else -> token
    }

    /** 1 - (مسافة ليفنشتاين / طول أطول نص). */
    fun similarity(a: String, b: String): Double {
        if (a == b) return 1.0
        val longest = max(a.length, b.length)
        if (longest == 0) return 1.0
        return 1.0 - levenshtein(a, b).toDouble() / longest
    }

    private fun levenshtein(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = min(min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }

    /** نفس الاسم ونفس الرقم مكرر أحياناً من أكثر من حساب. */
    private fun dedupe(contacts: List<Contact>): List<Contact> =
        contacts.distinctBy { ArabicText.normalize(it.name) + "|" + PhoneNumbers.digitsOnly(it.number).takeLast(9) }
}
