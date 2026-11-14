package com.fastkiwi.rememberme.torture.blackbox

/** One line of a session's script.txt: a verb and its arguments ("…" or «…» quote text with spaces). */
class Line(val no: Int, val raw: String, val verb: String, val args: List<String>) {
    fun arg(i: Int): String = args.getOrNull(i) ?: throw ActionError("this line needs more words: $raw")
    fun intArg(i: Int): Int = arg(i).toIntOrNull() ?: throw ActionError("«${arg(i)}» is not a whole number")
    fun floatArg(i: Int): Float = arg(i).toFloatOrNull() ?: throw ActionError("«${arg(i)}» is not a number")
    /** A short ASCII name for the output files. */
    val slug: String get() = (listOf(verb) + args).joinToString("-").replace(Regex("[^A-Za-z0-9._]+"), "-").trim('-', '.').take(40).ifEmpty { "step" }
}

/** An action that a person could not do; the message is shown to the tester as is. */
class ActionError(message: String) : Exception(message)

object Script {
    fun parse(text: String): Pair<List<Line>, List<String>> {
        val lines = mutableListOf<Line>()
        val problems = mutableListOf<String>()
        text.removePrefix("﻿").lines().forEachIndexed { i, raw0 ->
            val raw = raw0.trim()
            if (raw.isEmpty() || raw.startsWith("#")) return@forEachIndexed
            try {
                val t = tokens(raw)
                lines += Line(i + 1, raw, t.first().lowercase(), t.drop(1))
            } catch (e: ActionError) {
                problems += "line ${i + 1}: ${e.message}"
            }
        }
        return lines to problems
    }

    fun tokens(s: String): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c.isWhitespace() -> i++
                c == '"' || c == '«' || c == '“' -> {
                    val close = when (c) { '«' -> '»'; '“' -> '”'; else -> '"' }
                    val sb = StringBuilder()
                    i++
                    var closed = false
                    while (i < s.length) {
                        val d = s[i]
                        if (d == '\\' && i + 1 < s.length && (s[i + 1] == '"' || s[i + 1] == '\\')) { sb.append(s[i + 1]); i += 2; continue }
                        if (d == close || (close == '”' && d == '"')) { closed = true; i++; break }
                        sb.append(d); i++
                    }
                    if (!closed) throw ActionError("a quote is opened but never closed")
                    out += sb.toString()
                }
                else -> {
                    val start = i
                    while (i < s.length && !s[i].isWhitespace()) i++
                    out += s.substring(start, i)
                }
            }
        }
        return out
    }
}
