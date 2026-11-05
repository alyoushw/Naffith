package com.naffith.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WakeWordTest {

    @Test fun wakeWordWithCommand() {
        assertEquals("اتصل بروحي", WakeWord.extractCommand("يا نفذ اتصل بروحي"))
        assertEquals("اتصل على خالد", WakeWord.extractCommand("يا نفّذ اتصل على خالد"))
    }

    @Test fun spellingVariants() {
        assertEquals("افتح يوتيوب", WakeWord.extractCommand("يانفذ افتح يوتيوب"))
        assertEquals("افتح يوتيوب", WakeWord.extractCommand("يا نافذ افتح يوتيوب"))
        assertEquals("افتح يوتيوب", WakeWord.extractCommand("نفذ افتح يوتيوب"))
    }

    @Test fun wordsBeforeWakeWord() {
        assertEquals("وديني البيت", WakeWord.extractCommand("طيب يا نفذ وديني البيت"))
    }

    @Test fun onlyWakeWord() {
        assertEquals("", WakeWord.extractCommand("يا نفذ"))
    }

    @Test fun noWakeWord() {
        assertNull(WakeWord.extractCommand("اتصل على خالد"))
        assertNull(WakeWord.extractCommand("كلام عادي ما فيه شي ولا نفذ متأخرة"))
    }

    @Test fun alternatives() {
        assertEquals(listOf("اتصل بروحي"), WakeWord.extractFromAlternatives(listOf("انا اتصل بروحي", "يا نفذ اتصل بروحي")))
        assertNull(WakeWord.extractFromAlternatives(listOf("مرحبا")))
    }

    @Test fun callNicknameContact() {
        // «اتصل بروحي» ← جهة الاتصال «روحي».
        val cmd = CommandParser.parse("اتصل بروحي") as Command.Call
        val rouhi = Contact("1", "روحي ❤️", "0501234567")
        val result = ContactMatcher.match(cmd.contactNames, listOf(rouhi, Contact("2", "روان", "0550000000")))
        assertEquals(MatchResult.Single(rouhi), result)
    }
}
