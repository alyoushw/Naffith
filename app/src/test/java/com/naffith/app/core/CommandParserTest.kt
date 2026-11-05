package com.naffith.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandParserTest {

    private fun parse(s: String) = CommandParser.parse(s)

    // ---------- الخرائط ----------

    @Test fun mapsWithSearch() {
        assertEquals(Command.OpenMaps("مطعم في الرياض"), parse("افتح الخرائط وابحث عن مطعم في الرياض"))
    }

    @Test fun mapsKeepsOriginalSpelling() {
        assertEquals(Command.OpenMaps("أقرب صيدلية"), parse("افتح الخرائط على أقرب صيدلية"))
    }

    @Test fun mapsWithHamzaVariant() {
        assertEquals(Command.OpenMaps("البوليفارد"), parse("افتح خرائط قوقل ودورلي البوليفارد"))
    }

    @Test fun mapsGoogleMapsSaudiStyle() {
        assertEquals(Command.OpenMaps("كوفي"), parse("ابغى قوقل ماب ابحث عن كوفي"))
    }

    @Test fun mapsNavigationWords() {
        assertEquals(Command.OpenMaps("المطار"), parse("وديني المطار"))
        assertEquals(Command.OpenMaps("أقرب محطة بنزين"), parse("وين أقرب محطة بنزين"))
    }

    @Test fun mapsOnly() {
        assertEquals(Command.OpenMaps(""), parse("افتح الخرائط"))
    }

    // ---------- يوتيوب ----------

    @Test fun youtubeSearch() {
        assertEquals(Command.SearchYouTube("طبخ كبسة"), parse("ابحث في يوتيوب عن طبخ كبسة"))
    }

    @Test fun youtubeAttachedPrefix() {
        assertEquals(Command.SearchYouTube("مباراة الهلال"), parse("شغل لي مباراة الهلال باليوتيوب"))
        assertEquals(Command.SearchYouTube("محمد عبده"), parse("دور عاليوتيوب محمد عبده"))
    }

    @Test fun youtubeOpenOnly() {
        assertEquals(Command.SearchYouTube(""), parse("افتح اليوتيوب لو سمحت"))
    }

    @Test fun playWithoutAppMeansYoutube() {
        assertEquals(Command.SearchYouTube("أغنية طلال مداح"), parse("شغل أغنية طلال مداح"))
    }

    // ---------- واتساب ----------

    @Test fun whatsappChat() {
        val cmd = parse("افتح محادثة مع سارة في الواتساب") as Command.WhatsApp
        assertEquals("سارة", cmd.contactNames.first())
    }

    @Test fun whatsappSaudiPhrasing() {
        val cmd = parse("كلم أبو فهد واتس") as Command.WhatsApp
        assertEquals("أبو فهد", cmd.contactNames.first())
    }

    @Test fun whatsappAttachedPrefix() {
        val cmd = parse("راسل خالد عالواتساب") as Command.WhatsApp
        assertEquals("خالد", cmd.contactNames.first())
    }

    @Test fun whatsappWithoutName() {
        assertEquals(Command.WhatsApp(emptyList()), parse("افتح الواتساب"))
    }

    // ---------- الاتصال ----------

    @Test fun callSimple() {
        assertEquals("أبوي", (parse("اتصل على أبوي") as Command.Call).contactNames.first())
    }

    @Test fun callSaudiDialect() {
        assertEquals("خالد", (parse("دق على خالد الحين") as Command.Call).contactNames.first())
    }

    @Test fun callAttachedBa() {
        val cmd = parse("اتصل بمحمد") as Command.Call
        assertEquals(listOf("بمحمد", "محمد"), cmd.contactNames)
    }

    @Test fun callKeepsNameStartingWithBa() {
        // «بندر» يبقى أول احتمال، والمطابق يختار الصحيح.
        val cmd = parse("اتصل بندر") as Command.Call
        assertEquals("بندر", cmd.contactNames.first())
    }

    @Test fun callWithPoliteness() {
        val cmd = parse("الله يعافيك اتصل على أم محمد") as Command.Call
        assertEquals("أم محمد", cmd.contactNames.first())
    }

    @Test fun callPersonNamedAli() {
        assertEquals("علي", (parse("اتصل على علي") as Command.Call).contactNames.first())
        assertEquals("علي", (parse("اتصل علي") as Command.Call).contactNames.first())
    }

    @Test fun callWithoutName() {
        assertEquals(Command.Call(emptyList()), parse("اتصل"))
    }

    // ---------- غير مفهوم ----------

    @Test fun unknownCommand() {
        assertEquals(Command.Unknown("كم الساعة"), parse("كم الساعة"))
    }

    @Test fun emptyIsUnknown() {
        assertTrue(parse("   ") is Command.Unknown)
    }

    @Test fun parseBestPicksFirstUnderstoodAlternative() {
        val (text, cmd) = CommandParser.parseBest(listOf("انت صل على خالد", "اتصل على خالد"))
        assertEquals("اتصل على خالد", text)
        assertTrue(cmd is Command.Call)
    }

    @Test fun parseBestFallsBackToFirst() {
        val (text, cmd) = CommandParser.parseBest(listOf("كلام غريب", "شي ثاني"))
        assertEquals("كلام غريب", text)
        assertEquals(Command.Unknown("كلام غريب"), cmd)
    }

    // ---------- استخراج الاسم من الإعادة ----------

    @Test fun parseNameFromRepeatedFullCommand() {
        assertEquals("الجنوبي", CommandParser.parseName("افتح الواتساب محادثه الجنوبي").first())
        assertEquals("خالد", CommandParser.parseName("اتصل على خالد").first())
    }

    @Test fun parseNameFromBareName() {
        assertEquals("الجنوبي", CommandParser.parseName("الجنوبي").first())
        assertEquals("أبو فهد", CommandParser.parseName("أبو فهد لو سمحت").first())
    }

    // ---------- واتساب مع رسالة ----------

    @Test fun whatsappWithMessage() {
        assertEquals(Command.WhatsApp(listOf("روحي"), "هلا"), parse("افتح واتساب روحي اكتب هلا"))
    }

    @Test fun whatsappMessageWithLeadWord() {
        val cmd = parse("راسل خالد بالواتس قل له وينك تأخرت") as Command.WhatsApp
        assertEquals("خالد", cmd.contactNames.first())
        assertEquals("وينك تأخرت", cmd.message)
    }

    @Test fun whatsappMessageAttachedBa() {
        val cmd = parse("ارسل لروحي في الواتس اكتب أحبك") as Command.WhatsApp
        assertEquals(listOf("لروحي", "روحي"), cmd.contactNames)
        assertEquals("أحبك", cmd.message)
    }

    @Test fun whatsappMessageFirstThenName() {
        val cmd = parse("قول لروحي بالواتساب هلا والله") as Command.WhatsApp
        assertEquals(listOf("روحي"), cmd.contactNames)
        assertEquals("هلا والله", cmd.message)
    }

    @Test fun whatsappMessageTrailingAppWord() {
        val cmd = parse("كلم أبو فهد اكتب له السلام عليكم بالواتساب") as Command.WhatsApp
        assertEquals("أبو فهد", cmd.contactNames.first())
        assertEquals("السلام عليكم", cmd.message)
    }

    @Test fun whatsappSplitIntoTwoWords() {
        assertEquals(Command.WhatsApp(listOf("روحي")), parse("افتح واتس اب روحي"))
    }

    @Test fun whatsappWithoutMessage() {
        assertEquals("", (parse("افتح واتساب روحي") as Command.WhatsApp).message)
    }
}
