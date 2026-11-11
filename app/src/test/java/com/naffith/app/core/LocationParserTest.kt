package com.naffith.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationParserTest {
    private fun p(s: String) = CommandParser.parseMulti(s)

    @Test fun copyLocation() {
        assertEquals(Command.ShareLocation(emptyList(), copyOnly = true), p("انسخ موقعي"))
        assertEquals(Command.ShareLocation(emptyList(), copyOnly = true), p("انسخ لي اللوكيشن"))
    }

    @Test fun sendLocationToContact() {
        val c = p("ارسل موقعي الى روحي") as Command.ShareLocation
        assertEquals("روحي", c.contactNames.first())
        val d = p("ارسل موقعي لروحي") as Command.ShareLocation
        assertTrue(d.contactNames.contains("روحي"))
        val e = p("ارسل لوكيشني واتساب لأبو فهد") as Command.ShareLocation
        assertTrue(e.contactNames.contains("ابو فهد"))
    }

    @Test fun sendLocationToLastCaller() {
        assertEquals(Command.ShareLocation(emptyList(), toLastCaller = true), p("ارسل موقعي لآخر رقم اتصل علي"))
        assertEquals(Command.ShareLocation(emptyList(), toLastCaller = true), p("ارسل موقعي للي اتصل علي"))
    }

    @Test fun saveLastCaller() {
        assertEquals(Command.LastCaller(LastCallerAction.SAVE, name = "المندوب"), p("احفظ آخر رقم باسم المندوب"))
        assertEquals(Command.LastCaller(LastCallerAction.SAVE, name = "سواق أوبر"), p("اضف آخر رقم في سجل المكالمات الى جهات الاتصال باسم سواق أوبر"))
        assertEquals(Command.LastCaller(LastCallerAction.SAVE), p("ضيف آخر رقم لجهات الاتصال"))
    }

    @Test fun whatsappAndCallLastCaller() {
        assertEquals(LastCallerAction.WHATSAPP, (p("ارسل رسالة واتساب الى آخر رقم في سجل الاتصال") as Command.LastCaller).action)
        val m = p("ارسل واتساب لآخر رقم انا عند الباب") as Command.LastCaller
        assertEquals("انا عند الباب", m.message)
        assertEquals(Command.LastCaller(LastCallerAction.CALL), p("اتصل على آخر رقم"))
        assertEquals(Command.LastCaller(LastCallerAction.SHOW), p("مين آخر واحد اتصل علي"))
    }

    @Test fun normalCommandsUntouched() {
        assertTrue(p("اتصل على خالد") is Command.Call)
        assertTrue(p("وين اقرب مطعم") is Command.OpenMaps)
    }
}
