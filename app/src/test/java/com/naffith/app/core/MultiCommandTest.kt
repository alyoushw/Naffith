package com.naffith.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiCommandTest {

    private val now = 10 * 60
    private fun p(s: String) = CommandParser.parseMulti(s, now)

    @Test fun screenshotAndSend() {
        val cmd = p("صور الشاشة وارسلها لريحانة")
        assertTrue(cmd.toString(), cmd is Command.SendScreenshot)
        assertEquals("ريحانة", (cmd as Command.SendScreenshot).contactNames.last())
    }

    @Test fun twoDeviceCommands() {
        val cmd = p("حط الجوال صامت وصحني الساعة ٦ الصبح") as Command.Sequence
        assertEquals(
            listOf(Command.Device(DeviceAction.RINGER_SILENT), Command.Device(DeviceAction.ALARM, 6 * 60)),
            cmd.steps
        )
    }

    @Test fun messageThenNavigate() {
        val cmd = p("ارسل لروحي اني جاي وبعدين وديني البيت") as Command.Sequence
        assertEquals(2, cmd.steps.size)
        assertTrue(cmd.steps[0] is Command.WhatsApp)
        assertEquals(Command.OpenMaps("البيت", navigate = true), cmd.steps[1])
    }

    @Test fun threeCommandsMax() {
        val cmd = p("نزل الإضاءة واكتم الصوت وصحني الساعة ٦ الصبح") as Command.Sequence
        assertEquals(3, cmd.steps.size)
    }

    @Test fun messagesWithWawAreNotSplit() {
        val cmd = p("ارسل لروحي هلا وينك")
        assertTrue(cmd.toString(), cmd is Command.WhatsApp)
        val cmd2 = p("افتح واتساب روحي اكتب هلا وارسلها")
        assertTrue(cmd2.toString(), cmd2 is Command.WhatsApp)
        assertEquals(true, (cmd2 as Command.WhatsApp).send)
    }

    @Test fun singleCommandsUnchanged() {
        assertTrue(p("اتصل على خالد") is Command.Call)
        assertTrue(p("صور الشاشة") is Command.Device)
    }
}
