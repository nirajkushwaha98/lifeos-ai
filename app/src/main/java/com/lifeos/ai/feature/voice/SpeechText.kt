package com.lifeos.ai.feature.voice

/**
 * A reply prepared for voice mode.
 *
 * [display] is what the captions show; [spoken] is what the text-to-speech engine reads (amounts
 * said the natural way round, line breaks turned into pauses). TTS progress callbacks report
 * offsets into [spoken]; [displayEnd] maps them back so captions can light up exactly what has
 * been said so far.
 */
class SpeechScript internal constructor(
    val display: String,
    val spoken: String,
    /** True when the reply is mostly Devanagari, so a Hindi voice should read it. */
    val hindi: Boolean,
    private val displayEndAfter: IntArray,
) {
    /** Display offset (exclusive) covered once [spoken] has been read up to [spokenEnd] (exclusive). */
    fun displayEnd(spokenEnd: Int): Int = when {
        spokenEnd <= 0 -> 0
        spokenEnd > displayEndAfter.size -> display.length
        else -> displayEndAfter[spokenEnd - 1]
    }
}

object SpeechText {
    private val codeFence = Regex("```[\\w-]*")
    private val markdownLink = Regex("\\[([^\\]]+)]\\([^)]*\\)")
    private val bareUrl = Regex("https?://\\S+")
    private val heading = Regex("(?m)^[ \\t]*#{1,6}[ \\t]*")
    private val quote = Regex("(?m)^[ \\t]*>[ \\t]?")
    private val bullet = Regex("(?m)^[ \\t]*(?:[-*+•]|\\d{1,2}[.)])[ \\t]+")
    private val emphasis = Regex("[*_`~]+")
    private val spaces = Regex("[ \\t\\u00A0]+")
    private val spaceBeforePunctuation = Regex(" +([,.!?।])(?=\\s|$)")
    private val rupees = Regex("₹\\s?([0-9][0-9,]*(?:\\.[0-9]+)?)")
    private val pauseMarks = setOf('.', '!', '?', '।', ':', ';', ',')

    /** Strips Markdown, links and emoji so a reply reads naturally as captions and as speech. */
    fun clean(raw: String): String {
        var t = raw.replace("\r", "")
        t = codeFence.replace(t, "")
        t = markdownLink.replace(t) { it.groupValues[1] }
        t = bareUrl.replace(t, "")
        t = heading.replace(t, "")
        t = quote.replace(t, "")
        t = bullet.replace(t, "")
        t = emphasis.replace(t, "")
        t = stripSymbols(t)
        return t.lines()
            .map { spaceBeforePunctuation.replace(spaces.replace(it, " "), "$1").trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
    }

    fun script(raw: String): SpeechScript {
        val display = clean(raw)
        val hindi = isMostlyDevanagari(display)
        val spoken = StringBuilder(display.length + 16)
        val ends = IntList(display.length + 16)

        fun emit(text: CharSequence, displayEnd: Int) {
            for (c in text) {
                spoken.append(c)
                ends.add(displayEnd)
            }
        }

        fun emitPlain(from: Int, to: Int) {
            for (i in from until to) {
                when (val c = display[i]) {
                    '\n' -> {
                        val previous = spoken.lastOrNull { !it.isWhitespace() }
                        emit(if (previous != null && previous in pauseMarks) " " else ". ", i + 1)
                    }
                    '₹' -> emit(if (hindi) "रुपये " else "rupees ", i + 1)
                    else -> emit(c.toString(), i + 1)
                }
            }
        }

        // "₹1,250" is read as "1,250 rupees" — the way people say it.
        var cursor = 0
        for (match in rupees.findAll(display)) {
            emitPlain(cursor, match.range.first)
            val amount = match.groups[1]!!.range
            for (k in amount) emit(display[k].toString(), k + 1)
            emit(if (hindi) " रुपये" else " rupees", match.range.last + 1)
            cursor = match.range.last + 1
        }
        emitPlain(cursor, display.length)
        return SpeechScript(display, spoken.toString(), hindi, ends.toArray())
    }

    private fun isMostlyDevanagari(text: String): Boolean {
        var devanagari = 0
        var latin = 0
        for (c in text) {
            when {
                c in 'ऀ'..'ॿ' -> devanagari++
                c in 'a'..'z' || c in 'A'..'Z' -> latin++
            }
        }
        return devanagari > 0 && devanagari >= latin
    }

    /** Drops emoji and pictographs (keeping letters of every script, digits, ₹ and °). */
    private fun stripSymbols(s: String): String {
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            i += Character.charCount(cp)
            val type = Character.getType(cp)
            when {
                cp == 0x2022 || cp == 0x00B7 -> out.append(',') // • and · separators read as a pause
                cp == 0x00B0 -> out.appendCodePoint(cp)
                type == Character.OTHER_SYMBOL.toInt() || type == Character.MODIFIER_SYMBOL.toInt() -> Unit
                cp in 0x2190..0x21FF || cp == 0xFE0E || cp == 0xFE0F || cp == 0x200D || cp == 0x20E3 -> Unit
                else -> out.appendCodePoint(cp)
            }
        }
        return out.toString()
    }

    private class IntList(capacity: Int) {
        private var data = IntArray(capacity.coerceAtLeast(4))
        private var size = 0
        fun add(value: Int) {
            if (size == data.size) data = data.copyOf(size * 2)
            data[size++] = value
        }
        fun toArray(): IntArray = data.copyOf(size)
    }
}
