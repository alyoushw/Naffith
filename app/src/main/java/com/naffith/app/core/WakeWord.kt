package com.naffith.app.core

/**
 * كلمة التنبيه «يا نفّذ». محرك التعرف يكتبها بأشكال مختلفة («يا نفذ»، «يانفذ»، «نافذ»، «نفض»…)
 * فنقبل الأشكال القريبة في أول الجملة.
 */
object WakeWord {

    private val names = setOf("نفذ", "نافذ", "نفض", "نفاذ", "انفذ", "نفذي", "naffith", "nafith", "nafeth")
    private val glued = names.map { "يا$it" }.toSet() // «يانفذ»
    private const val MAX_POSITION = 3 // نسمح بكلمات قبلها مثل «طيب يا نفذ»

    /**
     * إذا الجملة فيها كلمة التنبيه، يرجع الأمر اللي بعدها (قد يكون فاضي = نادى بس).
     * وإذا ما فيها، يرجع null.
     */
    fun extractCommand(text: String): String? {
        val tokens = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        for (i in 0 until minOf(tokens.size, MAX_POSITION + 1)) {
            val t = ArabicText.normalize(tokens[i])
            if (t in names || t in glued) {
                return tokens.drop(i + 1).joinToString(" ").trim()
            }
        }
        return null
    }

    /** يدور في كل احتمالات التعرف ويرجع أول أمر بعد كلمة التنبيه، مع البقية كبدائل. */
    fun extractFromAlternatives(alternatives: List<String>): List<String>? {
        val commands = alternatives.mapNotNull { extractCommand(it) }
        return commands.ifEmpty { null }
    }
}
