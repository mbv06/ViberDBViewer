package com.mbv.viberdbviewer.model

private val knownJsonStringPatterns =
    mapOf(
        "Text" to jsonStringPattern("Text"),
        "Title" to jsonStringPattern("Title"),
        "URL" to jsonStringPattern("URL"),
        "text" to jsonStringPattern("text"),
    )

internal fun extractJsonString(
    json: String?,
    key: String,
): String? =
    json
        ?.takeIf(String::isNotEmpty)
        ?.let { source ->
            (knownJsonStringPatterns[key] ?: jsonStringPattern(key))
                .find(source)
                ?.groupValues
                ?.get(1)
        }?.let(::decodeJsonString)

private fun jsonStringPattern(key: String): Regex {
    val escapedKey = Regex.escape(key)
    return Regex("\"$escapedKey\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
}

private fun decodeJsonString(value: String): String {
    val result = StringBuilder(value.length)
    var index = 0
    while (index < value.length) {
        val decoded = decodeJsonCharacter(value, index)
        result.append(decoded.value)
        index += decoded.width
    }
    return result.toString()
}

private fun decodeJsonCharacter(
    source: String,
    index: Int,
): DecodedCharacter {
    val value = source[index]
    if (value != '\\' || index + 1 >= source.length) return DecodedCharacter(value, width = 1)

    return when (val escaped = source[index + 1]) {
        '"', '\\', '/' -> DecodedCharacter(escaped)
        'b' -> DecodedCharacter('\b')
        'f' -> DecodedCharacter('\u000C')
        'n' -> DecodedCharacter('\n')
        'r' -> DecodedCharacter('\r')
        't' -> DecodedCharacter('\t')
        'u' -> decodeUnicodeEscape(source, index)
        else -> DecodedCharacter(escaped)
    }
}

private fun decodeUnicodeEscape(
    source: String,
    index: Int,
): DecodedCharacter {
    val end = index + UNICODE_ESCAPE_LENGTH
    val code =
        if (end <= source.length) {
            source.substring(index + ESCAPE_PREFIX_LENGTH, end).toIntOrNull(HEX_RADIX)
        } else {
            null
        }
    return code?.let { DecodedCharacter(it.toChar(), UNICODE_ESCAPE_LENGTH) }
        ?: DecodedCharacter('u')
}

private data class DecodedCharacter(
    val value: Char,
    val width: Int = ESCAPE_PREFIX_LENGTH,
)

private const val ESCAPE_PREFIX_LENGTH = 2
private const val UNICODE_ESCAPE_LENGTH = 6
private const val HEX_RADIX = 16
