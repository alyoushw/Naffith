package com.naffith.app.core

import org.junit.Assert.assertEquals
import org.junit.Test

class ReminderModeTest {
    @Test fun answers() {
        assertEquals(false, ReminderMode.parse("اشعار"))
        assertEquals(false, ReminderMode.parse("الأول"))
        assertEquals(false, ReminderMode.parse("بإشعار"))
        assertEquals(true, ReminderMode.parse("شاشة كاملة"))
        assertEquals(true, ReminderMode.parse("الثاني"))
        assertEquals(true, ReminderMode.parse("الشاشة"))
        assertEquals(null, ReminderMode.parse("ما ادري"))
    }
}
