package com.jack.micbridge.server

import java.time.Instant

object Json {
    fun objectOf(vararg fields: Pair<String, Any?>): String = buildString {
        append('{')
        fields.forEachIndexed { index, (key, value) ->
            if (index > 0) append(',')
            append(string(key)).append(':').append(value(value))
        }
        append('}')
    }

    fun string(value: String): String = buildString {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code < 0x20) {
                    append("\\u").append(character.code.toString(16).padStart(4, '0'))
                } else append(character)
            }
        }
        append('"')
    }

    private fun value(value: Any?): String = when (value) {
        null -> "null"
        is String -> string(value)
        is Boolean, is Number -> value.toString()
        is RawJson -> value.value
        is Iterable<*> -> value.joinToString(prefix = "[", postfix = "]") { this.value(it) }
        else -> string(value.toString())
    }

    fun error(code: String?, message: String?): RawJson? =
        if (code == null && message == null) null
        else RawJson(objectOf("code" to code, "message" to message))

    fun instant(epochMs: Long?): String? = epochMs?.let { Instant.ofEpochMilli(it).toString() }

    @JvmInline
    value class RawJson(val value: String)
}

