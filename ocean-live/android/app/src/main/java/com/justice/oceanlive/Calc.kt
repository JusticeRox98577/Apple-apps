package com.justice.oceanlive

/** Tiny formula evaluator for + - * / parentheses, numbers, A to H and S T U. */
class Calc(s: String, private val vars: Map<Char, Double>) {
    private val chars = s.filter { !it.isWhitespace() }
    private var i = 0

    fun parse(): Double? {
        val v = expr() ?: return null
        return if (i == chars.length) v else null
    }

    private fun expr(): Double? {
        var v = term() ?: return null
        while (i < chars.length && (chars[i] == '+' || chars[i] == '-')) {
            val op = chars[i]
            i++
            val r = term() ?: return null
            v = if (op == '+') v + r else v - r
        }
        return v
    }

    private fun term(): Double? {
        var v = factor() ?: return null
        while (i < chars.length && (chars[i] == '*' || chars[i] == '/')) {
            val op = chars[i]
            i++
            val r = factor() ?: return null
            v = if (op == '*') v * r else (if (r == 0.0) 0.0 else v / r)
        }
        return v
    }

    private fun factor(): Double? {
        if (i >= chars.length) return null
        val c = chars[i]
        if (c == '-') {
            i++
            val f = factor() ?: return null
            return -f
        }
        if (c == '(') {
            i++
            val v = expr() ?: return null
            if (i < chars.length && chars[i] == ')') {
                i++
                return v
            }
            return null
        }
        val named = vars[c]
        if (named != null) {
            i++
            return named
        }
        val start = i
        while (i < chars.length && (chars[i].isDigit() || chars[i] == '.')) i++
        return chars.substring(start, i).toDoubleOrNull()
    }
}
