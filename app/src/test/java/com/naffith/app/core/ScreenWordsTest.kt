package com.naffith.app.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenWordsTest {

    @Test fun screenQuestions() {
        assertTrue(ScreenWords.mentionsScreen("وش هذا"))
        assertTrue(ScreenWords.mentionsScreen("ترجم اللي في الشاشة"))
        assertTrue(ScreenWords.mentionsScreen("مين هذا الشخص"))
        assertTrue(ScreenWords.mentionsScreen("اشرح لي الصورة"))
    }

    @Test fun normalQuestions() {
        assertFalse(ScreenWords.mentionsScreen("كم عدد سكان الرياض"))
        assertFalse(ScreenWords.mentionsScreen("خويي تعبان اليوم ايش اسوي له"))
    }
}
