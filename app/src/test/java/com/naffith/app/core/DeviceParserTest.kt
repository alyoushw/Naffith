package com.naffith.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceParserTest {

    /** الساعة ١٠ الصبح. */
    private val now = 10 * 60
    private fun p(s: String) = CommandParser.parse(s, now)
    private fun d(action: DeviceAction, value: Int? = null, label: String = "") = Command.Device(action, value, label)

    @Test fun alarms() {
        assertEquals(d(DeviceAction.ALARM, 6 * 60 + 30), p("صحني الساعة ٦ ونص الصبح"))
        assertEquals(d(DeviceAction.ALARM, 17 * 60), p("اضبط منبه الساعة خمس العصر"))
        assertEquals(d(DeviceAction.ALARM, 7 * 60 + 15), p("منبه 7:15 الصبح"))
        // بدون صبح/ليل والصبح فات: نختار المسا.
        assertEquals(d(DeviceAction.ALARM, 21 * 60), p("صحني الساعة ٩"))
        assertEquals(d(DeviceAction.ALARM, 11 * 60), p("صحني الساعة ١١"))
        assertEquals(d(DeviceAction.ALARM, 5 * 60 + 45), p("صحني الساعة ستة الا ربع الصبح"))
    }

    @Test fun relativeAlarmAndReminder() {
        assertEquals(d(DeviceAction.ALARM, 11 * 60), p("صحني بعد ساعة"))
        assertEquals(d(DeviceAction.ALARM, 10 * 60 + 30, ""), p("ذكرني بعد نص ساعة"))
        assertEquals(d(DeviceAction.ALARM, 17 * 60, "اتصل على ابوي"), p("ذكرني الساعة ٥ العصر اتصل على ابوي"))
    }

    @Test fun timer() {
        assertEquals(d(DeviceAction.TIMER, 600), p("مؤقت ١٠ دقايق"))
        assertEquals(d(DeviceAction.TIMER, 5400), p("شغل تايمر ساعة ونص"))
    }

    @Test fun volume() {
        assertEquals(d(DeviceAction.VOLUME_UP), p("ارفع الصوت"))
        assertEquals(d(DeviceAction.VOLUME_DOWN), p("نزل الصوت شوي"))
        assertEquals(d(DeviceAction.VOLUME_MUTE), p("اكتم الصوت"))
        assertEquals(d(DeviceAction.VOLUME_SET, 50), p("الصوت على ٥٠"))
        assertEquals(d(DeviceAction.VOLUME_SET, 70), p("خل الصوت سبعة"))
        assertEquals(d(DeviceAction.VOLUME_MAX), p("الصوت كامل"))
    }

    @Test fun ringer() {
        assertEquals(d(DeviceAction.RINGER_SILENT), p("حط الجوال صامت"))
        assertEquals(d(DeviceAction.RINGER_VIBRATE), p("خله هزاز"))
        assertEquals(d(DeviceAction.RINGER_NORMAL), p("طلع الجوال من الصامت"))
    }

    @Test fun brightnessAndFlash() {
        assertEquals(d(DeviceAction.BRIGHTNESS_UP), p("ارفع الإضاءة"))
        assertEquals(d(DeviceAction.BRIGHTNESS_DOWN), p("نزل السطوع"))
        assertEquals(d(DeviceAction.BRIGHTNESS_SET, 30), p("الاضاءة على ٣٠"))
        assertEquals(d(DeviceAction.BRIGHTNESS_AUTO), p("خل الإضاءة تلقائي"))
        assertEquals(d(DeviceAction.FLASH_ON), p("ولع الكشاف"))
        assertEquals(d(DeviceAction.FLASH_OFF), p("طف الاتريك"))
    }

    @Test fun mediaAndQuestions() {
        assertEquals(d(DeviceAction.MEDIA_PAUSE), p("وقف الأغنية"))
        assertEquals(d(DeviceAction.MEDIA_NEXT), p("اللي بعدها"))
        assertEquals(d(DeviceAction.BATTERY), p("كم البطارية"))
        assertEquals(d(DeviceAction.TIME), p("كم الساعة"))
        assertEquals(d(DeviceAction.DATE), p("وش التاريخ اليوم"))
    }

    @Test fun systemPanels() {
        assertEquals(d(DeviceAction.WIFI), p("شغل الواي فاي"))
        assertEquals(d(DeviceAction.BLUETOOTH), p("طف البلوتوث"))
        assertEquals(d(DeviceAction.DND_ON), p("لا تزعجني"))
        assertEquals(d(DeviceAction.LOCK_SCREEN), p("اقفل الشاشة"))
        assertEquals(d(DeviceAction.SCREENSHOT), p("صور الشاشة"))
    }

    @Test fun otherCommandsUnaffected() {
        assertTrue(p("اتصل على خالد") is Command.Call)
        assertTrue(p("شغل لي قصيدة وليا وحافظا") is Command.SearchYouTube)
        assertTrue(p("وديني البيت") is Command.OpenMaps)
        assertTrue(p("افتح التيك توك") is Command.OpenApp)
        assertTrue(p("وش تاريخ الدولة السعودية") is Command.Unknown)
    }

    @Test fun brightnessSynonyms() {
        for (t in listOf("قلل الاضاءه", "خفض الإضاءة", "خفضها الاضاءة", "نزل النور", "خفف السطوع", "وطي الاضاءة",
            "خل الشاشة اقل", "الإضاءة خافتة", "عتم الشاشة")) {
            assertEquals(t, d(DeviceAction.BRIGHTNESS_DOWN), p(t))
        }
        for (t in listOf("زيد الاضاءه", "ارفع الإضاءة شوي", "علي السطوع", "خل الشاشة اقوى", "نور الشاشة", "زيدها الاضاءة")) {
            assertEquals(t, d(DeviceAction.BRIGHTNESS_UP), p(t))
        }
    }

    @Test fun volumeSynonyms() {
        for (t in listOf("وطي الصوت", "قلل الصوت", "خفض الصوت", "هدي الصوت", "نزله الصوت", "الصوت اقل")) {
            assertEquals(t, d(DeviceAction.VOLUME_DOWN), p(t))
        }
        for (t in listOf("علي الصوت", "زيد الصوت", "ارفع الصوت شوي", "كثر الصوت", "الصوت اعلى")) {
            assertEquals(t, d(DeviceAction.VOLUME_UP), p(t))
        }
    }
}
