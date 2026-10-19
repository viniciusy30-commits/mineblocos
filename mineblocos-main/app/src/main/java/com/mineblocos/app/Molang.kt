package com.mineblocos.app

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Mini interpretador de Molang (a linguagem de expressões das animações do Bedrock).
 * Cobre o que as animações do jogador usam: números, + - * /, comparações, && || !,
 * ternário (`a ? b : c` e `a ? b`), parênteses, `math.*`, `q.*`/`v.*`/`t.*` e `this`.
 * Ângulos de sin/cos/asin/acos/atan2 são em GRAUS, como no Minecraft.
 */
class MolangEnv {
    val q = HashMap<String, Float>()
    val v = HashMap<String, Float>()
    val t = HashMap<String, Float>()
    var self = 0f

    fun get(ns: String, name: String): Float = when (ns) {
        "q", "query" -> q[name] ?: 0f
        "v", "variable" -> v[name] ?: 0f
        "t", "temp" -> t[name] ?: 0f
        else -> 0f
    }
}

abstract class MNode {
    abstract fun eval(e: MolangEnv): Float
}

private class MConst(val c: Float) : MNode() {
    override fun eval(e: MolangEnv) = c
}

private class MVar(val ns: String, val name: String) : MNode() {
    override fun eval(e: MolangEnv) = e.get(ns, name)
}

private class MThis : MNode() {
    override fun eval(e: MolangEnv) = e.self
}

private class MUnary(val op: Char, val a: MNode) : MNode() {
    override fun eval(e: MolangEnv): Float {
        val x = a.eval(e)
        return if (op == '-') -x else if (x == 0f) 1f else 0f
    }
}

private class MBin(val op: String, val a: MNode, val b: MNode) : MNode() {
    override fun eval(e: MolangEnv): Float {
        when (op) {
            "&&" -> return if (a.eval(e) != 0f && b.eval(e) != 0f) 1f else 0f
            "||" -> return if (a.eval(e) != 0f || b.eval(e) != 0f) 1f else 0f
        }
        val x = a.eval(e)
        val y = b.eval(e)
        return when (op) {
            "+" -> x + y
            "-" -> x - y
            "*" -> x * y
            "/" -> if (y == 0f) 0f else x / y
            "<" -> if (x < y) 1f else 0f
            "<=" -> if (x <= y) 1f else 0f
            ">" -> if (x > y) 1f else 0f
            ">=" -> if (x >= y) 1f else 0f
            "==" -> if (x == y) 1f else 0f
            "!=" -> if (x != y) 1f else 0f
            else -> 0f
        }
    }
}

private class MCond(val c: MNode, val a: MNode, val b: MNode?) : MNode() {
    override fun eval(e: MolangEnv): Float =
        if (c.eval(e) != 0f) a.eval(e) else (b?.eval(e) ?: 0f)
}

private class MCall(val fn: String, val args: List<MNode>) : MNode() {
    private val rad = (Math.PI / 180.0).toFloat()
    private val deg = (180.0 / Math.PI).toFloat()
    override fun eval(e: MolangEnv): Float {
        val a = if (args.isNotEmpty()) args[0].eval(e) else 0f
        val b = if (args.size > 1) args[1].eval(e) else 0f
        return when (fn) {
            "sin" -> sin(a * rad)
            "cos" -> cos(a * rad)
            "asin" -> asin(a.coerceIn(-1f, 1f)) * deg
            "acos" -> acos(a.coerceIn(-1f, 1f)) * deg
            "atan2" -> atan2(a, b) * deg
            "abs" -> abs(a)
            "sqrt" -> sqrt(max(0f, a))
            "pow" -> a.pow(b)
            "exp" -> exp(a)
            "ln" -> ln(a)
            "floor" -> floor(a)
            "ceil" -> ceil(a)
            "round" -> Math.round(a).toFloat()
            "trunc" -> a.toInt().toFloat()
            "min" -> min(a, b)
            "max" -> max(a, b)
            "mod" -> if (b == 0f) 0f else a.rem(b)
            "clamp" -> a.coerceIn(min(b, args[2].eval(e)), max(b, args[2].eval(e)))
            "lerp" -> a + (b - a) * args[2].eval(e)
            "hermite_blend" -> { val t = a.coerceIn(0f, 1f); 3f * t * t - 2f * t * t * t }
            "random" -> a + (b - a) * 0.5f
            else -> 0f
        }
    }
}

object Molang {
    private val cache = HashMap<String, MNode>()

    fun parse(src: String): MNode = cache.getOrPut(src) { Parser(src).parseAll() }

    private class Parser(val s: String) {
        var i = 0

        fun parseAll(): MNode {
            // se houver `a; b; return c;` usamos só a última expressão (as animações de
            // movimento do jogador não dependem dos efeitos colaterais)
            val parts = s.split(';').map { it.trim() }.filter { it.isNotEmpty() }
            val last = parts.lastOrNull() ?: return MConst(0f)
            val body = if (last.startsWith("return ")) last.substring(7) else last
            val p = Parser(body)
            return p.expr()
        }

        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }

        fun peek(str: String): Boolean { ws(); return s.startsWith(str, i) }

        fun eat(str: String): Boolean {
            if (peek(str)) { i += str.length; return true }
            return false
        }

        fun expr(): MNode = cond()

        fun cond(): MNode {
            val c = or()
            if (eat("?")) {
                val a = cond()
                val b = if (eat(":")) cond() else null
                return MCond(c, a, b)
            }
            return c
        }

        fun or(): MNode {
            var l = and()
            while (eat("||")) l = MBin("||", l, and())
            return l
        }

        fun and(): MNode {
            var l = eq()
            while (eat("&&")) l = MBin("&&", l, eq())
            return l
        }

        fun eq(): MNode {
            var l = rel()
            while (true) {
                l = if (eat("==")) MBin("==", l, rel()) else if (eat("!=")) MBin("!=", l, rel()) else return l
            }
        }

        fun rel(): MNode {
            var l = add()
            while (true) {
                l = if (eat("<=")) MBin("<=", l, add())
                else if (eat(">=")) MBin(">=", l, add())
                else if (eat("<")) MBin("<", l, add())
                else if (eat(">")) MBin(">", l, add())
                else return l
            }
        }

        fun add(): MNode {
            var l = mul()
            while (true) {
                l = if (eat("+")) MBin("+", l, mul()) else if (eat("-")) MBin("-", l, mul()) else return l
            }
        }

        fun mul(): MNode {
            var l = unary()
            while (true) {
                l = if (eat("*")) MBin("*", l, unary()) else if (eat("/")) MBin("/", l, unary()) else return l
            }
        }

        fun unary(): MNode {
            if (eat("-")) return MUnary('-', unary())
            if (peek("!") && !peek("!=")) { eat("!"); return MUnary('!', unary()) }
            return primary()
        }

        fun primary(): MNode {
            ws()
            if (eat("(")) {
                val e = expr()
                eat(")")
                return e
            }
            if (i < s.length && (s[i].isDigit() || s[i] == '.')) {
                val st = i
                while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
                if (i < s.length && (s[i] == 'e' || s[i] == 'E') && i + 1 < s.length && (s[i + 1].isDigit() || s[i + 1] == '-')) {
                    i += 2
                    while (i < s.length && s[i].isDigit()) i++
                }
                if (i < s.length && s[i] == 'f') { val v = s.substring(st, i).toFloat(); i++; return MConst(v) }
                return MConst(s.substring(st, i).toFloat())
            }
            if (i < s.length && s[i] == '\'') {
                i++
                while (i < s.length && s[i] != '\'') i++
                i++
                return MConst(0f)
            }
            val st = i
            while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_' || s[i] == '.')) i++
            val id = s.substring(st, i).lowercase()
            if (id.isEmpty()) { i++; return MConst(0f) }
            if (id == "this") return MThis()
            if (id == "true") return MConst(1f)
            if (id == "false") return MConst(0f)
            if (eat("(")) {
                val args = ArrayList<MNode>()
                ws()
                if (!eat(")")) {
                    do { args.add(expr()) } while (eat(","))
                    eat(")")
                }
                val fn = if (id.startsWith("math.")) id.substring(5) else id
                return MCall(fn, args)
            }
            val dot = id.indexOf('.')
            if (dot < 0) return MConst(0f)
            return MVar(id.substring(0, dot), id.substring(dot + 1))
        }
    }
}
