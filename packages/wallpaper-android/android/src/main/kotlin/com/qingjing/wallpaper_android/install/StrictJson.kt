package com.qingjing.wallpaper_android.install

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Bounded JSON reader used for signed manifests. Duplicate keys and trailing input are errors. */
internal class StrictJson private constructor(private val text: String) {
    private var position = 0
    private var nodes = 0
    companion object {
        fun parse(bytes: ByteArray): Any? {
            require(bytes.size <= 65536)
            val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            val reader = StrictJson(decoder.decode(ByteBuffer.wrap(bytes)).toString())
            val result = reader.value(0)
            reader.space()
            require(reader.position == reader.text.length)
            return result
        }
    }
    private fun space() { while (position < text.length && text[position] in " \n\r\t") position++ }
    private fun take(c: Char): Boolean {
        space()
        if (position < text.length && text[position] == c) { position++; return true }
        return false
    }
    private fun value(depth: Int): Any? {
        require(depth <= 16 && ++nodes <= 4096)
        space(); require(position < text.length)
        return when (text[position]) {
            '{' -> {
                position++
                val fields = linkedMapOf<String, Any?>()
                if (!take('}')) {
                    do {
                        space(); val key = string(); require(!fields.containsKey(key) && take(':'))
                        fields[key] = value(depth + 1)
                    } while (take(','))
                    require(take('}'))
                }
                fields
            }
            '[' -> {
                position++; val values = mutableListOf<Any?>()
                if (!take(']')) {
                    do { values.add(value(depth + 1)) } while (take(','))
                    require(take(']'))
                }
                values
            }
            '"' -> string()
            't' -> { literal("true"); true }
            'f' -> { literal("false"); false }
            'n' -> { literal("null"); null }
            else -> {
                val start = position
                while (position < text.length && text[position] in "0123456789-eE+.") position++
                val number = text.substring(start, position)
                require(number.length <= 64 && number.matches(Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")))
                if (number.contains('.') || number.contains('e', true)) number.toDouble().also { require(it.isFinite()) }
                else number.toLong()
            }
        }
    }
    private fun literal(value: String) { require(text.startsWith(value, position)); position += value.length }
    private fun string(): String {
        require(position < text.length && text[position++] == '"')
        val result = StringBuilder()
        while (position < text.length) {
            val c = text[position++]
            if (c == '"') {
                val value = result.toString()
                var i = 0
                while (i < value.length) {
                    val ch = value[i++]
                    if (ch.isHighSurrogate()) require(i < value.length && value[i++].isLowSurrogate())
                    else require(!ch.isLowSurrogate())
                }
                return value
            }
            require(c.code >= 32)
            if (c != '\\') result.append(c)
            else {
                require(position < text.length)
                when (val escape = text[position++]) {
                    '"', '\\', '/' -> result.append(escape)
                    'b' -> result.append('\b')
                    'f' -> result.append('\u000c')
                    'n' -> result.append('\n')
                    'r' -> result.append('\r')
                    't' -> result.append('\t')
                    'u' -> {
                        require(position + 4 <= text.length)
                        val hex = text.substring(position, position + 4)
                        require(hex.matches(Regex("[0-9a-fA-F]{4}")))
                        result.append(hex.toInt(16).toChar()); position += 4
                    }
                    else -> error("Invalid JSON escape")
                }
            }
            require(result.length <= 8192)
        }
        error("Unterminated JSON string")
    }
}
