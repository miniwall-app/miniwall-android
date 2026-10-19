package com.miniwall.core.render

import com.miniwall.core.logic.TextLogic

/** A measurer with fixed metrics: each character is 0.55 x the font size wide, lines are 1.5 x tall. */
object FakeMeasurer : TextMeasurer {
    override fun measure(runs: List<TextLogic.Run>, font: FontSpec, maxWidth: Double?): Pair<Double, Double> {
        val text = runs.joinToString("") { it.text }
        val charWidth = font.size * 0.55
        var lines = 0
        var widest = 0.0
        for (paragraph in text.split('\n')) {
            val full = paragraph.length * charWidth
            if (maxWidth == null || full <= maxWidth) { lines++; widest = maxOf(widest, full); continue }
            var line = 0.0
            lines++
            for (word in paragraph.split(' ')) {
                val w = word.length * charWidth
                val add = if (line == 0.0) w else w + charWidth
                if (line > 0 && line + add > maxWidth) { widest = maxOf(widest, line); lines++; line = w } else line += add
            }
            widest = maxOf(widest, line)
        }
        return widest to lines * font.lineHeight
    }
}
