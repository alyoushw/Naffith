package com.naffith.app.core

/** نوع الاختصار: وش ينفّذ لما تقول كلمته. */
enum class ShortcutType { IMAGE, FILE, APP, LINK, CALL, PLACE, TEXT, COMMANDS, MACRO }

/**
 * اختصار يحطه المستخدم: «تقويم أرامكو» ← يفتح صورة التقويم.
 * [triggers] الكلام اللي يشغّله (أكثر من صيغة). [value] حسب النوع: رابط الصورة/الملف، اسم الحزمة،
 * الرابط، الرقم، الوجهة، النص، أو الأوامر (كل أمر في سطر). [title] اسم للعرض (اسم التطبيق/الجهة…).
 */
data class Shortcut(
    val id: String,
    val triggers: List<String>,
    val type: ShortcutType,
    val value: String,
    val title: String = "",
)

/** يلقى الاختصار اللي قاله المستخدم، حتى لو قال قبله «افتح» أو «ورني». */
object ShortcutMatcher {

    private val leadWords = setOf(
        "افتح", "افتحلي", "افتحي", "شغل", "شغلي", "وريني", "ورني", "طلع", "طلعلي", "اعرض", "اعرضلي", "جيب", "جيبلي",
        "ابي", "ابغى", "ابغي", "ابا", "لي", "يا", "مرسال", "ممكن", "لو", "سمحت", "نفذ", "سو", "سوي", "ابدا", "شف", "شوف"
    ).map { ArabicText.normalize(it) }.toSet()

    private const val MIN_SCORE = 0.86

    private fun strip(text: String): String =
        ArabicText.normalize(text).split(" ").filter { it.isNotEmpty() }
            .dropWhile { it in leadWords }
            .joinToString(" ")

    fun find(text: String, shortcuts: List<Shortcut>): Shortcut? {
        val q = strip(text)
        if (q.isEmpty()) return null
        val qc = q.replace(" ", "")
        var best: Shortcut? = null
        var bestScore = 0.0
        for (sc in shortcuts) for (t in sc.triggers) {
            val tn = strip(t)
            if (tn.isEmpty()) continue
            val score = when {
                tn.replace(" ", "") == qc -> 1.0
                // الجملة فيها الاختصار كامل («افتح لي تقويم ارامكو لو سمحت»)
                tn.length >= 4 && " $q ".contains(" $tn ") -> 0.95
                else -> ContactMatcher.score(q, tn)
            }
            if (score > bestScore) {
                bestScore = score
                best = sc
            }
        }
        return best.takeIf { bestScore >= MIN_SCORE }
    }

    /** «تقويم ارامكو، التقويم» ← ["تقويم ارامكو", "التقويم"]. */
    fun splitTriggers(text: String): List<String> =
        text.split(',', '،', '\n', '؛', ';').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
}
