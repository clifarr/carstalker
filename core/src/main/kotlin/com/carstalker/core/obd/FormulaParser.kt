package com.carstalker.core.obd

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sqrt

/**
 * Tiny expression evaluator for user-defined PIDs.
 *
 * Supports `A`, `B`, `C`, `D` (response data bytes), numbers, `+ - * / %`, parentheses,
 * unary minus, and the functions `abs`, `min`, `max`, `sqrt`, `round`, `floor`, `ceil`.
 * Example: `(A*256+B)/4`.
 */
class FormulaParser private constructor(
    private val tokens: List<Token>,
    private val source: String,
) {

    private sealed interface Token {
        data class Number(val value: Double) : Token
        data class Variable(val index: Int) : Token
        data class Function(val name: String) : Token
        data class Operator(val symbol: Char) : Token
        data object LParen : Token
        data object RParen : Token
        data object Comma : Token
    }

    private var position = 0
    private val variables = DoubleArray(4)

    /** Number of response bytes the formula reads (1-4). */
    val requiredBytes: Int = (tokens.filterIsInstance<Token.Variable>().maxOfOrNull { it.index } ?: 0) + 1

    fun evaluate(bytes: IntArray): Double {
        variables[0] = bytes.a().toDouble()
        variables[1] = bytes.b().toDouble()
        variables[2] = bytes.c().toDouble()
        variables[3] = bytes.d().toDouble()
        position = 0
        val value = expression()
        if (position != tokens.size) {
            throw FormulaException("Unexpected token at position $position in \"$source\"")
        }
        return value
    }

    private fun expression(): Double {
        var value = term()
        while (true) {
            val op = peek() as? Token.Operator ?: break
            if (op.symbol != '+' && op.symbol != '-') break
            position++
            val rhs = term()
            value = if (op.symbol == '+') value + rhs else value - rhs
        }
        return value
    }

    private fun term(): Double {
        var value = factor()
        while (true) {
            val op = peek() as? Token.Operator ?: break
            if (op.symbol !in charArrayOf('*', '/', '%')) break
            position++
            val rhs = factor()
            value = when (op.symbol) {
                '*' -> value * rhs
                '/' -> value / rhs
                else -> value % rhs
            }
        }
        return value
    }

    private fun factor(): Double {
        val token = next()
        return when (token) {
            is Token.Operator ->
                if (token.symbol == '-') -factor() else throw FormulaException("Unexpected '${token.symbol}'")
            is Token.Number -> token.value
            is Token.Variable -> variables[token.index]
            is Token.LParen -> {
                val value = expression()
                expect(Token.RParen, "')'")
                value
            }
            is Token.Function -> {
                expect(Token.LParen, "'(' after ${token.name}")
                val args = mutableListOf<Double>()
                if (peek() != Token.RParen) {
                    while (true) {
                        args += expression()
                        if (peek() == Token.Comma) {
                            position++
                            continue
                        }
                        break
                    }
                }
                expect(Token.RParen, "')' after ${token.name}")
                applyFunction(token.name, args)
            }
            else -> throw FormulaException("Unexpected token")
        }
    }

    private fun applyFunction(name: String, args: List<Double>): Double {
        fun arity(expected: Int) {
            if (args.size != expected) {
                throw FormulaException("$name expects $expected argument(s), got ${args.size}")
            }
        }
        return when (name) {
            "abs" -> { arity(1); abs(args[0]) }
            "min" -> { arity(2); min(args[0], args[1]) }
            "max" -> { arity(2); max(args[0], args[1]) }
            "sqrt" -> { arity(1); sqrt(args[0]) }
            "round" -> { arity(1); round(args[0]) }
            "floor" -> { arity(1); floor(args[0]) }
            "ceil" -> { arity(1); ceil(args[0]) }
            else -> throw FormulaException("Unknown function $name")
        }
    }

    private fun expect(token: Token, description: String) {
        if (next() != token) throw FormulaException("Expected $description in \"$source\"")
    }

    private fun peek(): Token? = tokens.getOrNull(position)

    private fun next(): Token = tokens.getOrNull(position++)
        ?: throw FormulaException("Unexpected end of formula")

    companion object {
        private val FUNCTIONS = setOf("abs", "min", "max", "sqrt", "round", "floor", "ceil")

        fun compile(formula: String): FormulaParser {
            val tokens = tokenize(formula)
            if (tokens.isEmpty()) throw FormulaException("Formula is empty")
            return FormulaParser(tokens, formula)
        }

        fun isValid(formula: String): Boolean =
            runCatching { compile(formula).evaluate(intArrayOf(1, 2, 3, 4)) }.isSuccess

        private fun tokenize(formula: String): List<Token> {
            val tokens = mutableListOf<Token>()
            var i = 0
            while (i < formula.length) {
                val c = formula[i]
                when {
                    c.isWhitespace() -> i++
                    c.isDigit() || c == '.' -> {
                        val start = i
                        while (i < formula.length && (formula[i].isDigit() || formula[i] == '.')) i++
                        val text = formula.substring(start, i)
                        val value = text.toDoubleOrNull() ?: throw FormulaException("Bad number \"$text\"")
                        tokens += Token.Number(value)
                    }
                    c.isLetter() -> {
                        val start = i
                        while (i < formula.length && formula[i].isLetter()) i++
                        val word = formula.substring(start, i)
                        val lower = word.lowercase()
                        when {
                            word.length == 1 && word[0].uppercaseChar() in 'A'..'D' ->
                                tokens += Token.Variable(word[0].uppercaseChar() - 'A')
                            lower in FUNCTIONS -> tokens += Token.Function(lower)
                            else -> throw FormulaException("Unknown identifier \"$word\"")
                        }
                    }
                    c in "+-*/%" -> { tokens += Token.Operator(c); i++ }
                    c == '(' -> { tokens += Token.LParen; i++ }
                    c == ')' -> { tokens += Token.RParen; i++ }
                    c == ',' -> { tokens += Token.Comma; i++ }
                    else -> throw FormulaException("Unsupported character '$c'")
                }
            }
            return tokens
        }
    }
}

class FormulaException(message: String) : IllegalArgumentException(message)
