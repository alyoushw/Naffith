package com.naffith.app.core

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatTextTest {

    @Test fun stripsMarkdown() {
        assertEquals("هلا والله! كيف حالك؟", ChatText.forSpeech("**هلا والله!** كيف حالك؟"))
        assertEquals("أول شي\nثاني شي", ChatText.forSpeech("- أول شي\n- ثاني شي"))
        assertEquals("شوف موقع جوجل", ChatText.forSpeech("شوف [موقع جوجل](https://google.com)"))
        assertEquals("عنوان", ChatText.forSpeech("## عنوان"))
    }
}
