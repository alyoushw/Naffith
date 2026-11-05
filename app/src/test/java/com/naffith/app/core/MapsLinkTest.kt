package com.naffith.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapsLinkTest {

    @Test fun exactPlaceCoordinatesWin() {
        val url = "https://www.google.com/maps/place/My+Home/@24.7000000,46.6000000,17z/data=!3m1!4b1!4m6!3m5!1s0x0:0x0!8m2!3d24.7136123!4d46.6752957"
        assertEquals("24.7136123,46.6752957", MapsLink.extractDestination(url))
    }

    @Test fun queryParameter() {
        assertEquals("24.71,46.67", MapsLink.extractDestination("https://maps.google.com/?q=24.71,46.67"))
        assertEquals("حي النرجس الرياض", MapsLink.extractDestination("https://www.google.com/maps/search/?api=1&query=%D8%AD%D9%8A+%D8%A7%D9%84%D9%86%D8%B1%D8%AC%D8%B3+%D8%A7%D9%84%D8%B1%D9%8A%D8%A7%D8%B6"))
    }

    @Test fun mapCenterAsFallback() {
        assertEquals("21.5,39.2", MapsLink.extractDestination("https://www.google.com/maps/@21.5,39.2,15z"))
    }

    @Test fun plainCoordinatesAndAddress() {
        assertEquals("24.7136,46.6753", MapsLink.extractDestination(" 24.7136 , 46.6753 "))
        assertEquals("حي الملقا، الرياض", MapsLink.extractDestination("حي الملقا، الرياض"))
    }

    @Test fun shortLinks() {
        assertTrue(MapsLink.isShortLink("https://maps.app.goo.gl/AbCd123"))
        assertFalse(MapsLink.isShortLink("https://www.google.com/maps/@21.5,39.2,15z"))
    }
}
