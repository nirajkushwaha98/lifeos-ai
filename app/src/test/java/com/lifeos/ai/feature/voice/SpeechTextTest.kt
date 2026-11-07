package com.lifeos.ai.feature.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechTextTest {

    @Test fun `markdown, bullets and links are stripped for captions and speech`() {
        val cleaned = SpeechText.clean("## Plan\n**Hello** _world_\n- item one\n2. item two\nSee [docs](https://example.com) or https://x.y/z")
        assertEquals("Plan\nHello world\nitem one\nitem two\nSee docs or", cleaned)
    }

    @Test fun `emoji and pictographs are dropped but Devanagari, rupee and degree signs stay`() {
        assertEquals("7:00 PM — Gym", SpeechText.clean("⏰ 7:00 PM — Gym ✅"))
        assertEquals("आज का खर्च ₹500 है", SpeechText.clean("आज का खर्च ₹500 है 🙂"))
        assertEquals("25°C, sunny", SpeechText.clean("25°C • sunny ☀️"))
    }

    @Test fun `rupee amounts are spoken the natural way round`() {
        val english = SpeechText.script("You spent ₹1,250 today.")
        assertEquals("You spent 1,250 rupees today.", english.spoken)
        assertFalse(english.hindi)

        val hindi = SpeechText.script("आज का खर्च ₹500 है")
        assertEquals("आज का खर्च 500 रुपये है", hindi.spoken)
        assertTrue(hindi.hindi)
    }

    @Test fun `line breaks become pauses`() {
        assertEquals("Line one. Line two", SpeechText.script("Line one\nLine two").spoken)
        assertEquals("Done. Next", SpeechText.script("Done.\nNext").spoken)
    }

    @Test fun `spoken offsets map back to the caption`() {
        val s = SpeechText.script("Hi ₹50 ok")
        assertEquals("Hi ₹50 ok", s.display)
        assertEquals("Hi 50 rupees ok", s.spoken)
        assertEquals(0, s.displayEnd(0))
        assertEquals(2, s.displayEnd(2)) // "Hi"
        assertEquals(6, s.displayEnd(5)) // "50" lights up "₹50"
        assertEquals(6, s.displayEnd(12)) // "rupees" belongs to the same amount
        assertEquals(s.display.length, s.displayEnd(s.spoken.length))
        assertEquals(s.display.length, s.displayEnd(s.spoken.length + 10))
    }
}
