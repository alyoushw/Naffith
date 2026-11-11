package com.naffith.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CallLaterTest {

    private fun p(s: String) = CommandParser.parseMulti(s, nowMinutes = 10 * 60)

    @Test fun afterAMinute() {
        val c = p("بعد دقيقه ابغاك تجهز لي اتصال لروحي") as Command.CallLater
        assertEquals(60, c.delaySeconds)
        assertTrue(c.contactNames.contains("روحي"))
    }

    @Test fun remindMeToCall() {
        val c = p("ذكرني بعد عشر دقايق اتصل على ابوي") as Command.CallLater
        assertEquals(600, c.delaySeconds)
        assertEquals("ابوي", c.contactNames.first())
        val d = p("ذكرني بعد دقيقة واتصل على روحي") as Command.CallLater
        assertEquals(60, d.delaySeconds)
    }

    @Test fun atClock() {
        val c = p("ذكرني الساعة 5 العصر اتصل على خالد") as Command.CallLater
        assertEquals(17 * 60, c.atMinutes)
        assertEquals("خالد", c.contactNames.first())
    }

    @Test fun plainCallsStillCalls() {
        assertTrue(p("اتصل على خالد") is Command.Call)
        assertTrue(p("ذكرني الساعة 5 اشتري خبز") is Command.Device)
    }
}
