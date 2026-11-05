package com.naffith.app.core

/**
 * توحيد النص العربي قبل المقارنة: يتجاهل التشكيل والتطويل ويوحّد
 * أشكال الهمزة (أ/إ/آ ← ا) والتاء المربوطة (ة ← ه) والألف المقصورة (ى ← ي).
 */
object ArabicText {

    private val diacritics = Regex("[\\u0610-\\u061A\\u064B-\\u065F\\u0670\\u06D6-\\u06ED]")
    private val nonWord = Regex("[^\\p{L}\\p{N}\\s]")
    private val spaces = Regex("\\s+")

    /** توحيد حرف واحد. */
    fun normalizeChar(c: Char): Char = when (c) {
        'أ', 'إ', 'آ', 'ٱ', 'ٲ', 'ٳ' -> 'ا'
        'ة' -> 'ه'
        'ى', 'ی', 'ئ' -> 'ي'
        'ؤ' -> 'و'
        'ک' -> 'ك'
        'گ' -> 'ق'
        in '٠'..'٩' -> '0' + (c - '٠')
        in '۰'..'۹' -> '0' + (c - '۰')
        else -> c.lowercaseChar()
    }

    /** يوحّد النص ويحذف علامات الترقيم ويختصر المسافات. */
    fun normalize(text: String): String {
        val noMarks = text.replace(diacritics, "").replace("ـ", "")
        val sb = StringBuilder(noMarks.length)
        for (c in noMarks) sb.append(normalizeChar(c))
        return sb.toString()
            .replace(nonWord, " ")
            .replace(spaces, " ")
            .trim()
    }

    /** يقسّم النص إلى كلمات مع الاحتفاظ بالكلمة الأصلية ونسختها الموحّدة. */
    fun tokenize(text: String): List<Token> =
        text.split(spaces)
            .map { Token(original = it.replace(nonWord, "").trim(), normalized = normalize(it)) }
            .filter { it.normalized.isNotEmpty() }

    data class Token(val original: String, val normalized: String)
}
