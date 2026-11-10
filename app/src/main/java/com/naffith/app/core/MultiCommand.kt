package com.naffith.app.core

/**
 * أكثر من أمر في جملة وحدة (لين ٣): «حط الجوال صامت وصحني الساعة ٦»،
 * «صور الشاشة وارسلها لريحانة»، «ارسل لروحي اني جاي وبعدين وديني البيت».
 *
 * نفصل عند «وبعدين/بعدين/ثم/بعدها/وبعد كذا»، أو عند «و» ملتصقة بفعل أمر جديد («وارسل»، «واتصل»…).
 * ونقبل الفصل بس إذا كل جزء أمر مفهوم، عشان «هلا وينك» داخل رسالة ما تنقسم.
 */
object MultiCommand {

    private const val MAX_STEPS = 3

    private fun n(s: String) = ArabicText.normalize(s)

    private val separators = setOf("وبعدين", "بعدين", "ثم", "وثم", "بعدها", "وبعدها", "وبعده").map { n(it) }.toSet()

    /** أفعال تبدأ أمر جديد لما تجي بعد «و». */
    private val commandVerbs = setOf(
        "ارسل", "ارسلها", "ارسله", "ابعث", "ابعثها", "دز", "دزها", "اتصل", "دق", "كلم", "شغل", "افتح", "ودني", "وديني",
        "وجهني", "خذني", "صحني", "صحيني", "ذكرني", "ارفع", "نزل", "وطي", "قلل", "خفض", "زيد", "علي", "طف", "طفي",
        "ولع", "اكتم", "حط", "خل", "صور", "اقفل", "ابحث", "دور", "شف", "اضبط", "وقف", "كمل", "اكتب", "راسل",
    ).map { n(it) }.toSet()

    /** يقسّم الجملة لأجزاء (بدون التحقق إنها أوامر). */
    fun split(text: String): List<String> {
        val words = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val parts = mutableListOf(mutableListOf<String>())
        for ((i, w) in words.withIndex()) {
            val nw = n(w)
            val isSep = nw in separators ||
                (nw == "و" && words.getOrNull(i + 1)?.let { n(it) } in commandVerbs) ||
                (nw.startsWith("و") && nw.length > 2 && nw.drop(1) in commandVerbs && i > 0)
            if (isSep && parts.last().isNotEmpty() && parts.size < MAX_STEPS) {
                parts += mutableListOf<String>()
                // «وارسل» ← نبدأ الجزء الجديد بـ«ارسل».
                if (nw != "و" && nw !in separators) parts.last() += w.drop(1)
            } else if (!(nw in separators && parts.last().isEmpty())) {
                parts.last() += w
            }
        }
        return parts.map { it.joinToString(" ") }.filter { it.isNotBlank() }
    }

    /**
     * يحلل الجملة كأوامر متعددة. يرجع null إذا هي أمر واحد (أو أحد الأجزاء ما انفهم).
     * «صور الشاشة وارسلها لفلان» تصير أمر واحد: [Command.SendScreenshot].
     */
    fun parse(text: String, parseOne: (String) -> Command): Command? {
        val parts = split(text)
        if (parts.size < 2) return null
        val steps = parts.map(parseOne)
        if (steps.any { it is Command.Unknown }) return null

        val merged = mutableListOf<Command>()
        var i = 0
        while (i < steps.size) {
            val cur = steps[i]
            val next = steps.getOrNull(i + 1)
            if (cur is Command.Device && cur.action == DeviceAction.SCREENSHOT && next is Command.WhatsApp &&
                next.message.isBlank() && refersToIt(parts[i + 1])
            ) {
                val names = next.contactNames.ifEmpty { listOfNotNull(next.nameAndMessage.ifBlank { null }) }
                merged += Command.SendScreenshot(names)
                i += 2
                continue
            }
            merged += cur
            i++
        }
        return if (merged.size == 1) merged[0] else Command.Sequence(merged)
    }

    /** «ارسلها»، «ارسل الصورة»، «ابعثها». */
    private fun refersToIt(part: String): Boolean {
        val words = n(part).split(" ")
        return words.any { it.endsWith("ها") || it == "الصوره" || it == "الصورة" || it == "اياها" || it == "اللقطه" }
    }
}
