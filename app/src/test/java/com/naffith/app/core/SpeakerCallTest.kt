package com.naffith.app.core

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeakerCallTest {
    private fun p(s: String) = CommandParser.parseMulti(s, 600)

    @Test fun speakerPhrases() {
        val expected = Command.Call(listOf("روحي"), speaker = true)
        assertEquals(expected, p("اتصل على روحي على مكبر الصوت"))
        assertEquals(expected, p("اتصل على روحي وخله مكبر صوت"))
        assertEquals(expected, p("اتصل على روحي سبيكر"))
        assertEquals(expected, p("اتصل على روحي بالسبيكر"))
        assertEquals(expected, p("على المكبر اتصل على روحي"))
        assertEquals(Command.Call(listOf("خالد"), speaker = true), p("دق على خالد وحط المكبر"))
    }

    @Test fun normalCall() {
        assertEquals(Command.Call(listOf("روحي")), p("اتصل على روحي"))
    }
}
