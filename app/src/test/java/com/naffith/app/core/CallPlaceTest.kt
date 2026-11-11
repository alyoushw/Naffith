package com.naffith.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** «ابحث عن محل… واتصل عليه» = اتصال بالمحل (مو جهة اتصال). */
class CallPlaceTest {
    private fun p(s: String) = CommandParser.parseMulti(s, 600)

    @Test fun searchThenCall() {
        assertEquals(Command.CallPlace("محل العبير"), p("ابحث عن محل العبير واتصل عليه"))
        assertEquals(Command.CallPlace("العبير للاتصالات"), p("ابحث في الخرائط عن العبير للاتصالات واتصل عليهم"))
        assertEquals(Command.CallPlace("مطعم البيك"), p("دور لي على مطعم البيك واتصل فيه"))
        assertEquals(Command.CallPlace("العبير الاتصالات"), p("افتح موقع العبير الاتصالات في خريطة جوجل واتصل عليه"))
    }

    @Test fun contactsStayContacts() {
        assertTrue(p("اتصل على خالد") is Command.Call)
        assertTrue(p("اتصل على محل العبير") is Command.Call) // «اتصل على» = جهات الاتصال أول
    }
}
