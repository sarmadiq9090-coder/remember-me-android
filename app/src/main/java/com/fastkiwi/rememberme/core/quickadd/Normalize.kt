package com.fastkiwi.rememberme.core.quickadd

/**
 * JavaScript-compatible text helpers for the quick-add port. The Windows parser is written with
 * JavaScript regular expressions, whose \s, \d, \b and $ differ from Java's and Android's (ICU):
 * JavaScript's \s takes every Unicode space, its \d and \b are ASCII only, and its $ never matches
 * before a final line break. [jsRegex] rewrites a JavaScript source so it means the same here.
 */
internal object Js {
    /** JavaScript's \s (WhiteSpace and LineTerminator), as the inside of a character class. */
    const val WS = "\\t\\n\\u000B\\f\\r \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF"
    private const val WORD = "A-Za-z0-9_"
    private const val BOUNDARY = "(?:(?<=[$WORD])(?![$WORD])|(?<![$WORD])(?=[$WORD]))"

    fun isSpace(c: Char): Boolean = when (c) {
        '\t', '\n', '\u000B', '\u000C', '\r', ' ', ' ', ' ', ' ', ' ', ' ', ' ', '　', '﻿' -> true
        else -> c in ' '..' '
    }

    /** String.prototype.trim(). */
    fun trim(text: String): String {
        var s = 0
        var e = text.length
        while (s < e && isSpace(text[s])) s++
        while (e > s && isSpace(text[e - 1])) e--
        return text.substring(s, e)
    }

    /** text.split(/\s+/).filter(Boolean). */
    fun words(text: String): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < text.length) {
            while (i < text.length && isSpace(text[i])) i++
            val s = i
            while (i < text.length && !isSpace(text[i])) i++
            if (i > s) out += text.substring(s, i)
        }
        return out
    }

    /** text.replace(/\s+/g, ' '). */
    fun collapse(text: String): String {
        val sb = StringBuilder()
        var inSpace = false
        for (c in text) {
            if (isSpace(c)) { if (!inSpace) sb.append(' '); inSpace = true } else { sb.append(c); inSpace = false }
        }
        return sb.toString()
    }

    /** Rewrites a JavaScript regular-expression source (\s, \d, \w, \W, \b, $) for java.util.regex and ICU. */
    fun convert(src: String): String {
        val sb = StringBuilder()
        var inClass = false
        var i = 0
        while (i < src.length) {
            val c = src[i]
            if (c == '\\' && i + 1 < src.length) {
                val n = src[i + 1]
                sb.append(
                    when (n) {
                        's' -> if (inClass) WS else "[$WS]"
                        'd' -> if (inClass) "0-9" else "[0-9]"
                        'w' -> if (inClass) WORD else "[$WORD]"
                        'W' -> { check(!inClass) { "\\W in a class" }; "[^$WORD]" }
                        'b' -> { check(!inClass) { "\\b in a class" }; BOUNDARY }
                        else -> "\\$n"
                    },
                )
                i += 2
                continue
            }
            when {
                c == '[' && !inClass -> inClass = true
                c == ']' && inClass -> inClass = false
            }
            if (c == '$' && !inClass) sb.append("\\z") else sb.append(c)
            i++
        }
        return sb.toString()
    }

    /** A JavaScript regular expression: /src/ (no flags), /src/i ([ignoreCase], ASCII), /src/iu ([unicodeCase]). */
    fun regex(src: String, ignoreCase: Boolean = false, unicodeCase: Boolean = false): Regex {
        var flags = 0
        if (ignoreCase) flags = flags or java.util.regex.Pattern.CASE_INSENSITIVE
        if (unicodeCase) flags = flags or java.util.regex.Pattern.CASE_INSENSITIVE or java.util.regex.Pattern.UNICODE_CASE
        return java.util.regex.Pattern.compile(convert(src), flags).toRegex()
    }
}

/** Typed text after [normalize]: [text] is compared, [map]`[i]` is the typed index of normalized character i. */
internal class Normalized(val text: String, val map: IntArray)

/**
 * The Windows normalize() (natural-dates-ar.js): drops diacritics and tatweel, unifies أ/إ/آ/ٱ → ا,
 * ى/ی → ي, ة → ه, چ → ج, گ/ک → ك, ڤ → ف, ٫ → :, ، → , and turns Arabic-Indic and Persian digits into
 * 0-9; everything else is lower-cased one UTF-16 unit at a time, as JavaScript does.
 */
internal object ArabicText {
    private val LETTERS = mapOf(
        'أ' to "ا", 'إ' to "ا", 'آ' to "ا", 'ٱ' to "ا", 'ى' to "ي", 'ی' to "ي", 'ة' to "ه",
        'چ' to "ج", 'گ' to "ك", 'ک' to "ك", 'ڤ' to "ف", '٫' to ":", '،' to ",",
    )

    /** Diacritics (U+064B-U+065F, U+0670) and tatweel (U+0640). */
    fun isMark(c: Char): Boolean = c in 'ً'..'ٟ' || c == 'ٰ' || c == 'ـ'

    fun normalize(text: String): Normalized {
        val out = StringBuilder()
        val map = ArrayList<Int>(text.length + 1)
        for (i in text.indices) {
            val c = text[i]
            if (isMark(c)) continue
            val n = when (c) {
                in '٠'..'٩' -> (c - '٠').toString()
                in '۰'..'۹' -> (c - '۰').toString()
                else -> LETTERS[c] ?: c.toString().lowercase()
            }
            out.append(n)
            map += i
        }
        map += text.length
        return Normalized(out.toString(), map.toIntArray())
    }
}
