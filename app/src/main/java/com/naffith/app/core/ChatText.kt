package com.naffith.app.core

/** تنظيف رد الذكاء الاصطناعي عشان ينقال بالصوت وينعرض بشكل نظيف. */
object ChatText {

    private val markdown = Regex("[*_#`>]+")
    private val bullets = Regex("(?m)^\\s*[-•]\\s+")
    private val links = Regex("\\[([^]]+)]\\([^)]+\\)")
    private val spaces = Regex("[ \\t]+")

    fun forSpeech(text: String): String =
        text.replace(links, "$1")
            .replace(bullets, "")
            .replace(markdown, "")
            .replace(spaces, " ")
            .lines().joinToString("\n") { it.trim() }
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
}
