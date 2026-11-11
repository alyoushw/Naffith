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
    /** اختصار تعلّمه مرسال من تصحيحك: يشتغل بس إذا قلت نفس الجملة (عشان «١٠٪» ما تطابق «٢٠٪»). */
    val exact: Boolean = false,
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
            if (sc.exact) {
                if (tn.replace(" ", "") == qc) return sc
                continue
            }
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

/**
 * التصحيح: «لا، قصدي …»، «غلط»، «مو كذا …». يرجع الأمر الصحيح (أو "" إذا قال «غلط» بس)،
 * أو null إذا الكلام مو تصحيح.
 */
object Correction {

    private val strong = listOf("لا قصدي", "لا اقصد", "قصدي", "اقصد", "مو كذا", "مب كذا", "مو هذا", "مب هذا", "غلطان", "غلط", "خطا")
        .map { ArabicText.normalize(it) }
    private val weak = listOf("لا لا", "لا").map { ArabicText.normalize(it) }

    /** «قصدي»، «غلط»… أكيد تصحيح. «لا» لحالها ممكن تكون أمر («لا تزعجني»). */
    fun isStrong(text: String): Boolean = prefix(text, strong) != null

    fun parse(text: String): String? {
        val words = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val p = prefix(text, strong) ?: prefix(text, weak) ?: return null
        // نشيل كلمات التصحيح (وأي «لا» قبلها) من أول الجملة.
        var rest = words.drop(p)
        while (rest.isNotEmpty() && ArabicText.normalize(rest.first()).let { it == "لا" || it == "قصدي" || it == "اقصد" }) {
            rest = rest.drop(1)
        }
        return rest.joinToString(" ").trim()
    }

    /** كم كلمة في أول الجملة هي كلمة تصحيح (أو null). */
    private fun prefix(text: String, options: List<String>): Int? {
        val words = ArabicText.normalize(text).split(" ").filter { it.isNotEmpty() }
        for (o in options.sortedByDescending { it.length }) {
            val ow = o.split(" ")
            if (words.size >= ow.size && words.take(ow.size) == ow) return ow.size
        }
        return null
    }
}
