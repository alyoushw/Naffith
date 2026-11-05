package com.naffith.app.core

/** هل السؤال عن اللي في الشاشة؟ («وش هذا؟»، «ترجم اللي في الشاشة»، «مين هذا؟») */
object ScreenWords {

    private val screenWords = setOf("الشاشه", "شاشه", "شاشتي", "الصوره", "صوره", "screen")
    private val pointers = setOf("هذا", "هذي", "هذه", "ذا", "هاذا", "هاذي", "this")
    private val askWords = setOf("وش", "ايش", "شنو", "مين", "كم", "ترجم", "اشرح", "وضح", "what", "who")

    fun mentionsScreen(text: String): Boolean {
        val words = ArabicText.normalize(text).split(" ").filter { it.isNotEmpty() }
        if (words.any { w -> screenWords.any { w == it || w.endsWith(it) } }) return true
        // «وش هذا؟» / «ترجم هذي»: سؤال قصير يأشر على شي.
        return words.size <= 5 && words.any { it in askWords } && words.any { it in pointers }
    }
}
