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

    @Test fun callReminderWordings() {
        assertEquals(Command.CallLater(listOf("حسن الجنوبي"), atMinutes = 19 * 60), p("لا ذكرني أتصل على حسن الجنوبي الساعة 7 المسا"))
        assertEquals(Command.CallLater(listOf("أمي"), atMinutes = 19 * 60, note = "عشان الغداء"),
            p("ذكرني أكلم أمي الساعة 7 المسا عشان الغداء وأتصل عليها"))
        assertEquals(Command.CallLater(listOf("امي"), atMinutes = 19 * 60), p("ذكرني الساعة 7 المسا اكلم امي"))
        assertEquals(Command.CallLater(listOf("ابوي"), delaySeconds = 3600), p("ذكرني ادق على ابوي بعد ساعة"))
        assertTrue(p("ابي اكلم خالد") is Command.Call)
    }
}
