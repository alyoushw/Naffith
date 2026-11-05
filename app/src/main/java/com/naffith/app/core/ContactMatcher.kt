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
 * مطابقة تقريبية لأسماء جهات الاتصال العربية.
 * تتجاهل التشكيل والفروقات مثل أ/ا و ة/ه و ى/ي، والمسافات («عبدالله» = «عبد الله»)،
 * وتتسامح مع الأخطاء الإملائية البسيطة.
 */
object ContactMatcher {

    const val THRESHOLD = 0.72
    private const val EXACT = 1.0
    private const val MAX_RESULTS = 10

    fun match(queries: List<String>, contacts: List<Contact>): MatchResult {
        val usable = queries.map { ArabicText.normalize(it) }.filter { it.isNotEmpty() }
        if (usable.isEmpty() || contacts.isEmpty()) return MatchResult.None

        val scored = dedupe(contacts)
            .map { c -> ContactMatch(c, usable.maxOf { q -> score(q, c.name) }) }
            .filter { it.score >= THRESHOLD }
            .sortedByDescending { it.score }

        return when {
            scored.isEmpty() -> MatchResult.None
            scored.size == 1 -> MatchResult.Single(scored[0].contact)
            // تطابق تام وحيد يغلب التطابقات الجزئية.
            scored[0].score >= EXACT && scored[1].score < EXACT -> MatchResult.Single(scored[0].contact)
            else -> MatchResult.Multiple(scored.take(MAX_RESULTS))
        }
    }

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

        // كل كلمات الطلب موجودة في الاسم: «أحمد» ← «أحمد العتيبي».
        if (qTokens.all { it in nTokens }) best = max(best, 0.92)

        // بداية الاسم: «أبو فه» ← «أبو فهد».
        if (qc.length >= 3 && nc.startsWith(qc)) best = max(best, 0.85)

        // تشابه الاسم كاملاً (أخطاء بسيطة في النطق أو الكتابة).
        best = max(best, similarity(qc, nc) * 0.95)

        // تشابه كلمة بكلمة: كل كلمة في الطلب تشبه كلمة في الاسم.
        val perToken = qTokens.map { qt -> nTokens.maxOf { similarity(qt, it) } }
        if (perToken.all { it >= 0.75 }) best = max(best, perToken.average() * 0.88)

        return best
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
