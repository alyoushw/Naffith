package com.naffith.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** صيغ كثيرة لـ«ذكرني أتصل على فلان»: لازم كلها تكون اتصال مجهّز، والاسم نظيف. */
class CallReminderPhrasingTest {

    private fun p(s: String) = CommandParser.parseMulti(s, nowMinutes = 10 * 60)

    private fun check(text: String, name: String, at: Int? = null, delay: Int? = null) {
        val c = p(text)
        assertTrue("«$text» => $c", c is Command.CallLater)
        c as Command.CallLater
        assertTrue("«$text» => ${c.contactNames}", c.contactNames.any { ArabicText.normalize(it) == ArabicText.normalize(name) })
        if (at != null) assertEquals(text, at, c.atMinutes)
        if (delay != null) assertEquals(text, delay, c.delaySeconds)
    }

    private val sevenFifteen = 19 * 60 + 15
    private val seven = 19 * 60

    @Test fun mom() {
        check("ذكرني أكلم أمي الساعة 7:15 واتصل عليها", "امي", sevenFifteen)
        check("ذكرني اكلم امي الساعه 7:15 و اتصل عليها", "امي", sevenFifteen)
        check("ذكرني اكلم امي الساعة ٧:١٥ واتصل عليها", "امي", sevenFifteen)
        check("ذكرني أكلم أمي الساعة سبعة وربع واتصل عليها", "امي", sevenFifteen)
        check("ذكرني اكلم امي واتصل عليها الساعة 7:15", "امي", sevenFifteen)
        check("ذكرني الساعة 7:15 بالليل اكلم امي واتصل عليها", "امي", sevenFifteen)
        check("ذكرني الساعة 7 اتصل على امي واكلمها", "امي", seven)
        check("ذكرني اتصل واكلم امي الساعة 7", "امي", seven)
        check("ذكرني أتصل على أمي وأكلمها الساعة 7", "امي", seven)
        check("الساعة 7 ذكرني أتصل على أمي", "امي", seven)
        check("نبهني الساعة 7 اكلم امي", "امي", seven)
        check("ابي تذكرني اتصل على امي الساعة 7", "امي", seven)
        check("ذكرني اتصل لامي الساعه 7", "امي", seven)
        check("ذكرني اتصل على امي 7 المسا", "امي", seven)
        check("لا ذكرني أتصل على أمي الساعة 7", "امي", seven)
    }

    @Test fun others() {
        check("لا ذكرني أتصل على حسن الجنوبي الساعة 7", "حسن الجنوبي", seven)
        check("ذكرني بعد ساعة اتصل بأمي", "امي", delay = 3600)
        check("ذكرني الساعه ٧ ونص ادق على خالد", "خالد", 19 * 60 + 30)
        check("ذكرني أكلم خالد بخصوص السيارة الساعة 8", "خالد", 20 * 60)
        check("بعد عشر دقايق ذكرني اتصل على ابوي", "ابوي", delay = 600)
        check("ذكرني بعد نص ساعة ادق على ابو فهد", "ابو فهد", delay = 1800)
    }

    @Test fun notes() {
        assertEquals("عشان العشا", (p("ذكرني اتصل على امي بعد نص ساعة عشان العشا") as Command.CallLater).note)
        assertEquals("بخصوص السيارة", (p("ذكرني أكلم خالد بخصوص السيارة الساعة 8") as Command.CallLater).note)
    }

    @Test fun plainRemindersStayReminders() {
        assertTrue(p("ذكرني الساعة 5 اشتري خبز") is Command.Device)
        assertTrue(p("ذكرني بعد ساعة اطفي الفرن") is Command.Device)
    }
}
