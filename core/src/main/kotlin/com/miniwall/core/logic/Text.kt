package com.miniwall.core.logic

/**
 * Text helpers ported from the web renderer. Each function matches its web source exactly;
 * the shared conformance suite holds the cases both must agree on.
 */
object TextLogic {
    // the web renderer
    // JavaScript's \s (Unicode spaces included) spelled out: Java's own (?U) flag may not exist in Android's regex engine.
    private const val WS = """[\s\u00a0\u1680\u2000-\u200a\u2028\u2029\u202f\u205f\u3000\ufeff]"""
    private val TEMPLATE = Regex("""\{\{$WS*([${'$'}a-zA-Z0-9._-]+)(?:$WS*\|$WS*([^}]*))?$WS*\}\}""")
    private val CUSTOM_PREFIX = Regex("""^\${'$'}?custom\.""")
    private val LETTERS = Regex("""\p{L}+""")

    /** `{{ name }}` / `{{ name | uppercase }}` substitution. Unknown names become empty; unknown modifiers are ignored. */
    fun replaceVariables(template: String?, variables: Map<String, String>): String {
        if (template.isNullOrEmpty()) return ""
        return TEMPLATE.replace(template) { match ->
            val name = match.groupValues[1].trim().replace(CUSTOM_PREFIX, "")
            val value = variables[name] ?: return@replace ""
            val modifier = match.groups[2]?.value?.trim() ?: return@replace value
            when (modifier) {
                "uppercase" -> value.uppercase()
                "lowercase" -> value.lowercase()
                "capitalize" -> LETTERS.replace(value) { w -> w.value.take(1).uppercase() + w.value.drop(1).lowercase() }
                else -> value
            }
        }
    }

    // the web renderer
    private fun escapeHtml(text: String) = buildString {
        for (c in text) append(
            when (c) {
                '&' -> "&amp;"
                '<' -> "&lt;"
                '>' -> "&gt;"
                '"' -> "&quot;"
                '\'' -> "&#39;"
                else -> c.toString()
            },
        )
    }

    private val SAFE_SCHEME = Regex("^(https?:|mailto:|tel:)")
    private val RELATIVE = Regex("^[/#?]")
    private val ANY_SCHEME = Regex("^[a-z][a-z0-9+.-]*:")

    /** Link targets a text may open (escaped form). Anything else renders as plain text. */
    internal fun isSafeHref(href: String): Boolean {
        val plain = href.replace("&amp;", "&").filter { it.code > 32 && it.code != 127 }.lowercase()
        return SAFE_SCHEME.containsMatchIn(plain) || RELATIVE.containsMatchIn(plain) || !ANY_SCHEME.containsMatchIn(plain)
    }

    private val NEW_LINE = Regex("""\\\n""")
    private val BOLD = Regex("""\*\*([^\n\r\u2028\u2029]*?)\*\*""")
    private val ITALIC = Regex("""\*([^\n\r\u2028\u2029]*?)\*""")
    private val STRIKE = Regex("""~([^\n\r\u2028\u2029]*?)~""")
    private val CODE = Regex("""`([^\n\r\u2028\u2029]*?)`""")
    private val LINK = Regex("""\[([^\n\r\u2028\u2029]*?)\]\(([^\n\r\u2028\u2029]*?)\)""")
    internal const val CODE_OPEN = "<span style='font-family: monospace'>"

    /** The small markdown subset as HTML, after escaping (same output as the web, tag for tag). */
    fun htmlFromMarkdown(text: String?): String {
        if (text.isNullOrEmpty()) return ""
        var s = escapeHtml(text)
        s = NEW_LINE.replace(s, "<br/>")
        s = BOLD.replace(s) { "<b>${it.groupValues[1]}</b>" }
        s = ITALIC.replace(s) { "<i>${it.groupValues[1]}</i>" }
        s = STRIKE.replace(s) { "<s>${it.groupValues[1]}</s>" }
        s = CODE.replace(s) { "$CODE_OPEN${it.groupValues[1]}</span>" }
        s = LINK.replace(s) { m ->
            val label = m.groupValues[1]
            val href = m.groupValues[2]
            if (isSafeHref(href)) {
                "<a href=\"$href\" target='_blank' rel='noopener noreferrer' style='color:inherit;text-decoration:none;'>$label</a>"
            } else {
                label
            }
        }
        return s
    }

    /** One styled piece of a text, ready for AttributedString / AnnotatedString. */
    data class Run(
        val text: String,
        val bold: Boolean = false,
        val italic: Boolean = false,
        val strikethrough: Boolean = false,
        val code: Boolean = false,
        /** Unescaped link target, when the run is inside a link. */
        val link: String? = null,
    )

    private val HREF = Regex("""^<a href="([^"]*)"""")

    /**
     * The runs of [htmlFromMarkdown]'s output, with HTML whitespace rules applied (runs of
     * spaces and newlines collapse to one space, as `white-space: normal` does) and `<br/>`
     * as a line break.
     */
    fun runs(markdown: String?): List<Run> {
        val html = htmlFromMarkdown(markdown)
        val out = mutableListOf<Run>()
        var bold = 0
        var italic = 0
        var strike = 0
        var code = 0
        var link: String? = null
        val text = StringBuilder()
        fun flush() {
            if (text.isEmpty()) return
            out += Run(decodeEntities(text.toString()), bold > 0, italic > 0, strike > 0, code > 0, link)
            text.clear()
        }
        var i = 0
        while (i < html.length) {
            if (html[i] != '<') {
                text.append(html[i]); i++; continue
            }
            val end = html.indexOf('>', i)
            if (end < 0) { text.append(html.substring(i)); break }
            val tag = html.substring(i, end + 1)
            flush()
            when {
                tag == "<br/>" -> out += Run("\n", bold > 0, italic > 0, strike > 0, code > 0, link)
                tag == "<b>" -> bold++
                tag == "</b>" -> bold = maxOf(0, bold - 1)
                tag == "<i>" -> italic++
                tag == "</i>" -> italic = maxOf(0, italic - 1)
                tag == "<s>" -> strike++
                tag == "</s>" -> strike = maxOf(0, strike - 1)
                tag == CODE_OPEN -> code++
                tag == "</span>" -> code = maxOf(0, code - 1)
                tag.startsWith("<a ") -> link = HREF.find(tag)?.groupValues?.get(1)?.let(::decodeEntities)
                tag == "</a>" -> link = null
            }
            i = end + 1
        }
        flush()
        return collapseWhitespace(out)
    }

    private fun decodeEntities(s: String) =
        s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&")

    private val WHITESPACE = Regex("[ \\t\\n\\r\\u000C]+")

    /** `white-space: normal`: collapse runs of whitespace, drop it at line starts and ends. */
    private fun collapseWhitespace(runs: List<Run>): List<Run> {
        val out = mutableListOf<Run>()
        var atLineStart = true
        for (run in runs) {
            if (run.text == "\n") {
                if (out.isNotEmpty()) out[out.size - 1] = out.last().let { it.copy(text = it.text.trimEnd(' ')) }
                out += run
                atLineStart = true
                continue
            }
            var t = WHITESPACE.replace(run.text, " ")
            if (atLineStart || out.lastOrNull()?.text?.endsWith(" ") == true) t = t.trimStart(' ')
            if (t.isEmpty()) continue
            out += run.copy(text = t)
            atLineStart = false
        }
        if (out.isNotEmpty()) out[out.size - 1] = out.last().let { it.copy(text = it.text.trimEnd(' ')) }
        return out.filter { it.text.isNotEmpty() }
    }

    // Web SDK src/url.ts
    private val OPENABLE = Regex("^(https?:|mailto:|tel:)")
    private val ROOT_PATH = Regex("^/(?!/)")

    /** Links a paywall may open: http(s), mailto, tel and site-relative paths. Never `javascript:` or `data:`. */
    fun isOpenableUrl(url: String): Boolean {
        val plain = url.filter { it.code > 32 && it.code != 127 }.lowercase()
        return OPENABLE.containsMatchIn(plain) || ROOT_PATH.containsMatchIn(plain)
    }

    // Web SDK src/fonts.ts
    private val FONT_NAME = Regex("""^(?:[A-Z]+:)?(.+)@(\d{3})$""")

    /** `Fraunces@700` -> ("Fraunces", 700); anything else -> null. */
    fun parseFontName(name: String): Pair<String, Int>? =
        FONT_NAME.find(name)?.let { it.groupValues[1] to it.groupValues[2].toInt() }
}
