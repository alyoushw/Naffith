package com.naffith.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShortcutMatcherTest {

    private val calendar = Shortcut("1", listOf("تقويم أرامكو", "التقويم"), ShortcutType.IMAGE, "content://x")
    private val sleep = Shortcut("2", listOf("وقت النوم"), ShortcutType.COMMANDS, "حط الجوال صامت\nصحني الساعة 6")
    private val iban = Shortcut("3", listOf("رقم الايبان"), ShortcutType.TEXT, "SA00")
    private val all = listOf(calendar, sleep, iban)

    @Test fun exactAndWithLeadWords() {
        assertEquals(calendar, ShortcutMatcher.find("تقويم ارامكو", all))
        assertEquals(calendar, ShortcutMatcher.find("افتح لي تقويم أرامكو", all))
        assertEquals(calendar, ShortcutMatcher.find("ورني التقويم", all))
        assertEquals(sleep, ShortcutMatcher.find("يا مرسال وقت النوم", all))
    }

    @Test fun containedInSentence() {
        assertEquals(iban, ShortcutMatcher.find("ابي رقم الآيبان لو سمحت", all))
    }

    @Test fun smallSpellingDifferences() {
        assertEquals(calendar, ShortcutMatcher.find("تقويم ارمكو", all))
    }

    @Test fun normalCommandsAreNotShortcuts() {
        assertNull(ShortcutMatcher.find("اتصل على خالد", all))
        assertNull(ShortcutMatcher.find("ارفع الصوت", all))
    }

    @Test fun splitTriggers() {
        assertEquals(listOf("تقويم أرامكو", "التقويم", "كالندر"), ShortcutMatcher.splitTriggers("تقويم أرامكو، التقويم, كالندر\n"))
    }
}
